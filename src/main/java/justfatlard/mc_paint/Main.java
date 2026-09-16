package justfatlard.mc_paint;

import justfatlard.pandorical.api.BlockRegistration;
import justfatlard.pandorical.api.ItemRegistration;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class Main implements ModInitializer {
	public static final String MOD_ID = "mc-paint";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Identifier EASEL_ID = Identifier.fromNamespaceAndPath(MOD_ID, "easel");
	public static final Identifier CANVAS_ID = Identifier.fromNamespaceAndPath(MOD_ID, "canvas");
	public static final Identifier UNFINISHED_PAINTING_ID = Identifier.fromNamespaceAndPath(MOD_ID, "unfinished_painting");
	public static final Identifier PAINTING_ID = Identifier.fromNamespaceAndPath(MOD_ID, "painting");

	/** Planks' own, because the client's stand-in is built from planks and a dig must take as long on both sides. */
	private static final float EASEL_STRENGTH = 2.0F;

	public static final EaselBlock EASEL = new EaselBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.WOOD)
		.strength(EASEL_STRENGTH)
		.sound(SoundType.WOOD)
		.noOcclusion()
		.ignitedByLava()
		.pushReaction(PushReaction.POPPED)
		.setId(ResourceKey.create(Registries.BLOCK, EASEL_ID)));

	public static final BlockEntityType<EaselBlockEntity> EASEL_BLOCK_ENTITY =
		new BlockEntityType<>(EaselBlockEntity::new, Set.of(EASEL));

	public static final Item EASEL_ITEM = new DoubleHighBlockItem(EASEL, new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, EASEL_ID))
		.useBlockDescriptionPrefix());

	public static final Item CANVAS = new Item(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, CANVAS_ID))
		.stacksTo(16));

	public static final Item UNFINISHED_PAINTING = new Item(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, UNFINISHED_PAINTING_ID))
		.stacksTo(1));

	public static final Item PAINTING = new PaintingItem(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, PAINTING_ID))
		.stacksTo(1));

	/** Every painting loaded, so their walls can be checked without searching. */
	private static final Set<ItemFrame> FRAMES = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final int CHECK_EVERY_TICKS = 20;

	@Override
	public void onInitialize() {
		PandoricalApi.content().registerBlock(EASEL_ID.toString(), new BlockRegistration()
			.baseBlock("minecraft:oak_planks")
			.strength(EASEL_STRENGTH)
			.requiresCorrectTool(false)
			.interactive()
			.model(MOD_ID + ":block/easel_lower"));
		PandoricalApi.content().registerItem(EASEL_ID.toString(), new ItemRegistration().model(MOD_ID + ":item/easel"));
		PandoricalApi.content().registerItem(CANVAS_ID.toString(), new ItemRegistration().model(MOD_ID + ":item/canvas")
			.maxStackSize(16));
		PandoricalApi.content().registerItem(UNFINISHED_PAINTING_ID.toString(), new ItemRegistration()
			.model(MOD_ID + ":item/unfinished_painting").maxStackSize(1));
		PandoricalApi.content().registerItem(PAINTING_ID.toString(), new ItemRegistration()
			.model(MOD_ID + ":item/painting").maxStackSize(1));
		PandoricalApi.content().registerModAssets(MOD_ID);

		Registry.register(BuiltInRegistries.BLOCK, EASEL_ID, EASEL);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, EASEL_ID, EASEL_BLOCK_ENTITY);
		Registry.register(BuiltInRegistries.ITEM, EASEL_ID, EASEL_ITEM);
		Registry.register(BuiltInRegistries.ITEM, CANVAS_ID, CANVAS);
		Registry.register(BuiltInRegistries.ITEM, UNFINISHED_PAINTING_ID, UNFINISHED_PAINTING);
		Registry.register(BuiltInRegistries.ITEM, PAINTING_ID, PAINTING);

		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(Registries.CREATIVE_MODE_TAB, EASEL_ID),
			FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.mc-paint.mc-paint"))
				.icon(() -> new ItemStack(EASEL_ITEM))
				.displayItems((context, entries) -> {
					entries.accept(new ItemStack(EASEL_ITEM));
					entries.accept(new ItemStack(CANVAS));
				})
				.build());

		PaintScreen.register();
		SizeScreen.register();

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof ItemFrame frame)) return InteractionResult.PASS;
			if (Paintings.isPainting(frame)) {
				if (!player.mayBuild() || !level.mayInteract(player, frame.getPos())) return InteractionResult.FAIL;
				if (level instanceof ServerLevel server) Paintings.takeDown(server, frame, player);
				return InteractionResult.SUCCESS;
			}
			return Paintings.isEaselBoard(frame) ? InteractionResult.FAIL : InteractionResult.PASS;
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (Paintings.isPainting(entity)) FRAMES.add((ItemFrame) entity);
			else if (Paintings.isEaselBoard(entity)) level.getServer().execute(() -> Paintings.easelLoaded(level, entity));
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof ItemFrame frame) FRAMES.remove(frame);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % CHECK_EVERY_TICKS != 0) return;
			for (ItemFrame frame : new ArrayList<>(FRAMES)) {
				if (frame.level() instanceof ServerLevel level) Paintings.check(level, frame);
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PaintScreen.forget(handler.getPlayer().getUUID());
			SizeScreen.forget(handler.getPlayer().getUUID());
		});

		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("block-tip")) {
			justfatlard.mc_paint.integration.PaintingTips.register();
		}

		LOGGER.info("Loaded mc-paint (server-side with Pandorical)");
	}
}
