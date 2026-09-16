package justfatlard.mc_paint;

import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The size a canvas is stretched to as it goes on the easel: every shape at once, each drawn to
 * scale, one press to choose. The canvas is only taken once a size is chosen, so backing out
 * costs nothing.
 */
public final class SizeScreen {
	private SizeScreen() {}

	public static final String TYPE = "mc-paint:size";

	private static final int PAD = 8;
	private static final int COLUMNS = 4;
	private static final int BUTTON_WIDTH = 56;
	private static final int BUTTON_HEIGHT = 52;
	private static final int GAP = 6;
	private static final int PICTURE_PIXELS_PER_BLOCK = 8;
	private static final int TOP = 20;
	private static final int WIDTH = PAD * 2 + COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * GAP;
	private static final int ROWS = (CanvasSize.values().length + COLUMNS - 1) / COLUMNS;
	private static final int HEIGHT = TOP + ROWS * BUTTON_HEIGHT + (ROWS - 1) * GAP + PAD;
	private static final double REACH = 8.0;

	private record Pending(ServerLevel level, BlockPos easel, InteractionHand hand, String screenId) {}

	private static final Map<UUID, Pending> pending = new HashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		for (CanvasSize size : CanvasSize.values()) {
			screens.onAction(TYPE, size.id(), (player, data) -> choose(player, size));
		}
		screens.onClose(TYPE, player -> pending.remove(player.getUUID()));
	}

	public static void forget(UUID player) {
		pending.remove(player);
	}

	public static void open(ServerPlayer player, ServerLevel level, BlockPos foot, InteractionHand hand) {
		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, HEIGHT).title("Canvas");
		screen.panel("frame", 0, 0, WIDTH, HEIGHT, Map.of());
		screen.text("heading", PAD, PAD, Map.of(
			ComponentType.PROP_TEXT, Component.translatableWithFallback("mc-paint.size.heading",
				"Stretch the canvas to a size, in blocks").getString(),
			ComponentType.PROP_COLOR, "#404040"));

		CanvasSize[] sizes = CanvasSize.values();
		for (int i = 0; i < sizes.length; i++) {
			CanvasSize size = sizes[i];
			int x = PAD + (i % COLUMNS) * (BUTTON_WIDTH + GAP);
			int y = TOP + (i / COLUMNS) * (BUTTON_HEIGHT + GAP);
			int pictureWidth = size.width * PICTURE_PIXELS_PER_BLOCK;
			int pictureHeight = size.height * PICTURE_PIXELS_PER_BLOCK;
			int pictureArea = 4 * PICTURE_PIXELS_PER_BLOCK;
			screen.component(new ComponentBuilder(size.id(), ComponentType.BUTTON).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
				.prop(ComponentType.PROP_TOOLTIP, Component.translatableWithFallback("mc-paint.size.tip",
					"%s blocks wide, %s tall", size.width, size.height).getString())
				.child(new ComponentBuilder(size.id() + "_picture", ComponentType.PANEL)
					.bounds((BUTTON_WIDTH - pictureWidth) / 2, 4 + (pictureArea - pictureHeight) / 2, pictureWidth, pictureHeight)
					.prop(ComponentType.PROP_BACKGROUND, String.format("#%08X", Paint.argb(Paint.BARE)))
					.prop(ComponentType.PROP_BORDER, "flat")
					.prop(ComponentType.PROP_BORDER_COLOR, "#FF6B4F2A"))
				.child(new ComponentBuilder(size.id() + "_label", ComponentType.TEXT)
					.bounds(0, BUTTON_HEIGHT - 13, BUTTON_WIDTH, 10)
					.prop(ComponentType.PROP_TEXT, size.label())
					.prop(ComponentType.PROP_ALIGN, "center")
					.prop(ComponentType.PROP_SHADOW, "true")));
		}

		pending.put(player.getUUID(), new Pending(level, foot.immutable(), hand, screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	private static void choose(ServerPlayer player, CanvasSize size) {
		Pending asked = pending.remove(player.getUUID());
		if (asked == null) return;
		PandoricalApi.screens().close(player, asked.screenId());
		if (player.level() != asked.level() || player.distanceToSqr(Vec3.atCenterOf(asked.easel())) > REACH * REACH) return;
		if (!(asked.level().getBlockEntity(asked.easel()) instanceof EaselBlockEntity easel)) return;
		if (easel.hasCanvas()) {
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.easel.occupied",
				"This easel already holds a canvas."));
			return;
		}

		ItemStack canvas = canvasIn(player, asked.hand());
		if (canvas == null) {
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.size.no_canvas",
				"You need a canvas in hand to put on the easel."));
			return;
		}
		canvas.consume(1, player);
		easel.loadCanvas(size, Paint.blank(size));
		asked.level().playSound(null, asked.easel().above(), SoundEvents.PAINTING_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
		player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.size.loaded",
			"Canvas on the easel. Right-click it with an empty hand to paint."));
	}

	/** The canvas the player clicked with, or any other they are still holding. */
	private static ItemStack canvasIn(ServerPlayer player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (held.is(Main.CANVAS)) return held;
		for (InteractionHand other : InteractionHand.values()) {
			if (player.getItemInHand(other).is(Main.CANVAS)) return player.getItemInHand(other);
		}
		return null;
	}
}
