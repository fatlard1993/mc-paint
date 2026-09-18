package justfatlard.mc_paint;

import justfatlard.mc_paint.mixin.ItemFrameAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Signed paintings on walls. A hung painting is one invisible, fixed item frame per block, each
 * holding the map that block was drawn onto at signing, all sharing a group id. Fixed frames
 * cannot be turned, emptied, pushed or knocked off, so the only ways a painting comes down are
 * the ones here: a player hitting any part of it, or the wall behind any part going.
 */
public final class Paintings {
	private Paintings() {}

	public static final String EASEL_TAG = "mc-paint.easel";
	public static final String PAINTING_TAG = "mc-paint.painting";

	private static final int MAP_SIZE = 128;
	private static final int MAP_PIXELS_PER_CELL = MAP_SIZE / CanvasSize.CELLS_PER_BLOCK;

	/** Draw each block of the canvas onto a fresh locked map, in reading order. */
	public static int[] drawTiles(ServerLevel level, CanvasSize size, byte[] pixels) {
		int[] maps = new int[size.width * size.height];
		for (int row = 0; row < size.height; row++) {
			for (int column = 0; column < size.width; column++) {
				MapId id = level.getFreeMapId();
				MapItemSavedData data = MapItemSavedData.createForClient((byte) 0, true, level.dimension());
				for (int y = 0; y < MAP_SIZE; y++) {
					for (int x = 0; x < MAP_SIZE; x++) {
						int cx = column * CanvasSize.CELLS_PER_BLOCK + x / MAP_PIXELS_PER_CELL;
						int cy = row * CanvasSize.CELLS_PER_BLOCK + y / MAP_PIXELS_PER_CELL;
						data.setColor(x, y, pixels[cx + cy * size.columns()]);
					}
				}
				level.setMapData(id, data);
				maps[column + row * size.width] = id.id();
			}
		}
		return maps;
	}

	// --- Hanging ---

	public static InteractionResult hang(UseOnContext context, Artworks.Signed painting) {
		if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
		Player player = context.getPlayer();
		Direction facing = context.getClickedFace();
		if (!facing.getAxis().isHorizontal()) {
			tell(player, Component.translatableWithFallback("mc-paint.hang.walls_only", "Paintings hang on walls."));
			return InteractionResult.FAIL;
		}

		List<BlockPos> tiles = tilePositions(context.getClickedPos().relative(facing), facing, painting.size());
		for (BlockPos tile : tiles) {
			if (player != null && !player.mayUseItemAt(tile, facing, context.getItemInHand())) return InteractionResult.FAIL;
			if (!fits(level, tile, facing)) {
				tell(player, Component.translatableWithFallback("mc-paint.hang.no_room",
					"Won't fit: this painting needs a clear, flat wall %s blocks.", painting.size().label()));
				return InteractionResult.FAIL;
			}
		}

		String group = UUID.randomUUID().toString();
		for (int i = 0; i < tiles.size(); i++) {
			ItemFrame frame = new ItemFrame(level, tiles.get(i), facing);
			frame.setInvisible(true);
			frame.setSilent(true);
			((ItemFrameAccessor) frame).setPaintingFixed(true);
			frame.setItem(tileStack(painting, group, i), false);
			frame.addTag(PAINTING_TAG);
			level.addFreshEntity(frame);
		}
		level.playSound(null, tiles.get(0), SoundEvents.PAINTING_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
		Awards.hung(player, painting.size());
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS_SERVER;
	}

	/**
	 * Where each block of a painting goes, in reading order, for one hung against the wall behind
	 * {@code clicked}: centred on the clicked block, the odd block going right and up, the way
	 * vanilla hangs its own.
	 */
	private static List<BlockPos> tilePositions(BlockPos clicked, Direction facing, CanvasSize size) {
		Direction right = facing.getCounterClockWise();
		int left = (size.width - 1) / 2;
		int below = (size.height - 1) / 2;
		List<BlockPos> tiles = new ArrayList<>();
		for (int row = 0; row < size.height; row++) {
			for (int column = 0; column < size.width; column++) {
				tiles.add(clicked.relative(right, column - left).above(size.height - 1 - row - below));
			}
		}
		return tiles;
	}

	private static boolean fits(ServerLevel level, BlockPos tile, Direction facing) {
		if (!level.getBlockState(tile.relative(facing.getOpposite())).isSolid()) return false;
		if (!level.getBlockState(tile).getCollisionShape(level, tile).isEmpty()) return false;
		return level.getEntitiesOfClass(HangingEntity.class, new AABB(tile)).isEmpty();
	}

	private static ItemStack tileStack(Artworks.Signed painting, String group, int tile) {
		ItemStack map = new ItemStack(Items.FILLED_MAP);
		map.set(DataComponents.MAP_ID, new MapId(painting.maps()[tile]));
		CompoundTag tag = Artworks.toTag(painting);
		tag.putString("group", group);
		tag.putInt("tile", tile);
		CustomData.update(DataComponents.CUSTOM_DATA, map, root -> root.put(Main.MOD_ID, tag));
		return map;
	}

	private static CompoundTag tileData(ItemFrame frame) {
		return frame.getItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty(Main.MOD_ID);
	}

	// --- Taking down ---

	public static boolean isPainting(Entity entity) {
		return entity instanceof ItemFrame && entity.entityTags().contains(PAINTING_TAG);
	}

	/** The painting this entity is a part of, or null when it is not part of one. */
	public static Artworks.Signed signedOn(Entity entity) {
		return isPainting(entity) ? Artworks.fromTag(tileData((ItemFrame) entity)) : null;
	}

	/** An easel's canvas anchor, or an item frame an easel used to show its canvas in before it had one. */
	public static boolean isEaselBoard(Entity entity) {
		return entity.entityTags().contains(EASEL_TAG);
	}

	/**
	 * Take down the whole painting a frame is part of, and give it back as the painting it was, at
	 * the part that was hit. Nothing is given back to a creative player, as vanilla's paintings do.
	 */
	public static void takeDown(ServerLevel level, ItemFrame hit, Player by) {
		CompoundTag data = tileData(hit);
		String group = data.getStringOr("group", "");
		int reach = CanvasSize.FOUR_BY_FOUR.width;
		List<ItemFrame> parts = group.isEmpty() ? List.of(hit) : level.getEntitiesOfClass(ItemFrame.class,
			hit.getBoundingBox().inflate(reach), frame -> isPainting(frame) && group.equals(tileData(frame).getStringOr("group", "")));
		Artworks.Signed painting = Artworks.fromTag(data);
		if (painting != null && (by == null || !by.hasInfiniteMaterials())) {
			hit.spawnAtLocation(level, Artworks.signed(painting));
		}
		level.playSound(null, hit.getPos(), SoundEvents.PAINTING_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
		for (ItemFrame part : parts) part.discard();
	}

	/** A painting whose wall has gone comes down. */
	static void check(ServerLevel level, ItemFrame frame) {
		if (frame.isRemoved() || !isPainting(frame)) return;
		BlockPos wall = frame.getPos().relative(frame.getDirection().getOpposite());
		if (level.isLoaded(wall) && !level.getBlockState(wall).isSolid()) takeDown(level, frame, null);
	}

	/**
	 * An easel's anchor has loaded: stand its canvas on it again, or let it go if the easel has.
	 * An item frame here is an easel from before anchors, showing its canvas as a map; the easel is
	 * given an anchor in its place.
	 */
	static void easelLoaded(ServerLevel level, Entity board) {
		if (board.isRemoved()) return;
		BlockPos foot = board instanceof ItemFrame frame ? frame.getPos().below() : board.blockPosition();
		if (!level.isLoaded(foot)) return;
		boolean easel = level.getBlockEntity(foot) instanceof EaselBlockEntity found && found.hasCanvas();
		if (board instanceof ItemFrame || !easel) board.discard();
		if (easel) ((EaselBlockEntity) level.getBlockEntity(foot)).stand(level);
	}

	private static void tell(Player player, Component message) {
		if (player != null) player.sendOverlayMessage(message);
	}
}
