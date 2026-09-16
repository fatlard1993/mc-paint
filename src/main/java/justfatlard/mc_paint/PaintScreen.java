package justfatlard.mc_paint;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.ComponentUpdateBuilder;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.PixelCanvas;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Painting at an easel: the canvas on the left, the palette, brush, and the way out on the right.
 *
 * <p>The canvas is a {@link ComponentType#PIXEL_CANVAS}: the client paints ahead and reports each
 * stroke, and this applies the same stroke to the easel's own cells and palette and says so. A
 * stroke that could not have been honest - an ink not on the palette, a brush bigger than any
 * offered - is refused, and the client handed the easel's cells back to replace what it guessed.
 *
 * <p>Leaving keeps the canvas on the easel to come back to. Signing asks for a title, draws the
 * canvas onto a map per block, and pops it off the easel as the finished painting.
 */
public final class PaintScreen {
	private PaintScreen() {}

	public static final String TYPE = "mc-paint:paint";

	private static final String CANVAS = "canvas";
	private static final String TOOLS = "tools";
	private static final String FORM = "form";
	private static final String MIXER = "mixer";
	private static final String MIX = "mix";
	private static final String TITLE = "title";
	private static final String ERROR = "error";
	private static final String LEAVE = "leave";
	private static final String SIGN = "sign";
	private static final String BACK = "back";
	private static final String CONFIRM = "confirm";
	private static final String MIX_BACK = "mix_back";
	private static final String MIX_APPLY = "mix_apply";
	private static final String MIX_RESULT = "mix_result";
	private static final String MIX_TEXT = "mix_text";
	private static final String MIX_ERROR = "mix_error";

	private static final int MAX_BRUSH = 4;
	private static final int MAX_TITLE = 32;
	private static final double REACH = 8.0;
	/** How much of a colour a mix is measured out in: a quarter of a dye. */
	private static final int MIX_STEP = Paint.UNITS_PER_DYE / 4;
	private static final int MIX_START = Paint.UNITS_PER_DYE / 2;

	private static final int PAD = 8;
	private static final int CANVAS_BOX = 192;
	private static final int COLUMN_X = PAD + CANVAS_BOX + PAD;
	private static final int COLUMN_WIDTH = 108;
	private static final int WIDTH = COLUMN_X + COLUMN_WIDTH + PAD;
	private static final int HEIGHT = PAD + CANVAS_BOX + PAD;
	private static final int SWATCH = 20;
	private static final int SWATCH_STEP = SWATCH + 2;
	private static final int SWATCHES_PER_ROW = 5;
	private static final int SWATCH_ROW_STEP = SWATCH + 6;
	private static final int SWATCHES_TOP = 12;
	/** Every colour the palette holds, and the mix button after the last of them. */
	private static final int SLOTS = Paint.MAX_COLORS + 1;
	private static final int BAR_WIDTH = SWATCH - 2;
	private static final int BRUSH_TOP = SWATCHES_TOP + (SLOTS / SWATCHES_PER_ROW) * SWATCH_ROW_STEP + 2;
	private static final int BUTTON_WIDTH = 52;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTONS_TOP = CANVAS_BOX - BUTTON_HEIGHT;
	private static final int CHIP = 12;
	private static final int CHIP_STEP = CHIP + 3;
	private static final int CHIPS_PER_ROW = 7;
	private static final int CHIPS_TOP = 62;
	private static final int RESULT_TOP = 112;
	private static final int RESULT_BAR = 60;
	private static final String TEXT_COLOR = "#404040";
	private static final String ERROR_COLOR = "#AA0000";

	private static final class Session {
		final ServerLevel level;
		final BlockPos easel;
		final String screenId;
		int ink;
		int brush = 1;
		String title = "";
		boolean mixing;
		/** The two wells being mixed from: a colour each, or -1 for none, and how much of it. */
		final int[] well = {-1, -1};
		final int[] units = new int[2];
		/** The well a colour chosen from the chips goes into. */
		int filling;

		Session(ServerLevel level, BlockPos easel, String screenId, int ink) {
			this.level = level;
			this.easel = easel;
			this.screenId = screenId;
			this.ink = ink;
		}
	}

	private static final Map<UUID, Session> sessions = new HashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		screens.onAction(TYPE, CANVAS, PaintScreen::onCanvas);
		for (int k = 0; k < SLOTS; k++) {
			int slot = k;
			screens.onAction(TYPE, slotId(slot), (player, data) -> chooseSlot(player, slot));
			screens.onAction(TYPE, chipId(slot), (player, data) -> chip(player, slot));
		}
		for (int b = 1; b <= MAX_BRUSH; b++) {
			int brush = b;
			screens.onAction(TYPE, brushId(brush), (player, data) -> brush(player, brush));
		}
		for (int w = 0; w < 2; w++) {
			int well = w;
			screens.onAction(TYPE, wellId(well), (player, data) -> fill(player, well));
			screens.onAction(TYPE, wellId(well) + "_less", (player, data) -> measure(player, well, -MIX_STEP));
			screens.onAction(TYPE, wellId(well) + "_more", (player, data) -> measure(player, well, MIX_STEP));
		}
		screens.onAction(TYPE, MIX, (player, data) -> showMixer(player, true));
		screens.onAction(TYPE, MIX_BACK, (player, data) -> showMixer(player, false));
		screens.onAction(TYPE, MIX_APPLY, (player, data) -> mix(player));
		screens.onAction(TYPE, LEAVE, (player, data) -> close(player));
		screens.onAction(TYPE, SIGN, (player, data) -> showForm(player, true));
		screens.onAction(TYPE, BACK, (player, data) -> showForm(player, false));
		screens.onAction(TYPE, TITLE, PaintScreen::onTitle);
		screens.onAction(TYPE, CONFIRM, (player, data) -> sign(player));
		screens.onClose(TYPE, player -> sessions.remove(player.getUUID()));
	}

	public static void forget(UUID player) {
		sessions.remove(player);
	}

	// --- Opening ---

	public static void open(ServerPlayer player, ServerLevel level, BlockPos foot, EaselBlockEntity easel) {
		ServerPlayer painter = painterAt(level, foot);
		if (painter != null && painter != player) {
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.easel.busy",
				"%s is painting at this easel.", painter.getDisplayName()));
			return;
		}

		easel.stand(level);
		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, HEIGHT).title("Easel");
		Session session = new Session(level, foot.immutable(), screen.screenId(), firstInk(easel));
		screen.panel("frame", 0, 0, WIDTH, HEIGHT, Map.of());
		screen.component(canvas(easel, session));
		screen.component(tools(easel, session));
		screen.component(mixer(easel, session));
		screen.component(form(player));

		sessions.put(player.getUUID(), session);
		PandoricalApi.screens().open(player, screen.build());
		if (session.ink < 0) {
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.palette.empty",
				"No paint on the palette yet: right-click the easel with dye."));
		}
	}

	private static int firstInk(EaselBlockEntity easel) {
		List<Integer> colors = easel.colors();
		return colors.isEmpty() ? -1 : colors.get(0);
	}

	private static ComponentBuilder canvas(EaselBlockEntity easel, Session session) {
		StringBuilder palette = new StringBuilder();
		for (int argb : Paint.palette()) {
			if (!palette.isEmpty()) palette.append(',');
			palette.append(hex(argb));
		}
		return new ComponentBuilder(CANVAS, ComponentType.PIXEL_CANVAS)
			.bounds(PAD, PAD, CANVAS_BOX, CANVAS_BOX)
			.prop(ComponentType.PROP_CANVAS_COLUMNS, String.valueOf(easel.size().columns()))
			.prop(ComponentType.PROP_CANVAS_ROWS, String.valueOf(easel.size().rows()))
			.prop(ComponentType.PROP_CANVAS_PALETTE, palette.toString())
			.prop(ComponentType.PROP_CANVAS_PIXELS, PixelCanvas.encode(easel.pixels()))
			.prop(ComponentType.PROP_CANVAS_SUPPLY, PixelCanvas.encodeSupply(easel.supply()))
			.prop(ComponentType.PROP_CANVAS_INK, String.valueOf(session.ink))
			.prop(ComponentType.PROP_CANVAS_BRUSH, String.valueOf(session.brush))
			.prop(ComponentType.PROP_CANVAS_ACK, "0");
	}

	/**
	 * The palette, brushes and the ways out. The palette shows only the colours on it, in the order
	 * they came, with the mix button moving to stand after the last; every slot is built at open and
	 * shown or hidden, since a screen's components are fixed once it is open.
	 */
	private static ComponentBuilder tools(EaselBlockEntity easel, Session session) {
		ComponentBuilder tools = group(TOOLS, true);
		tools.child(text("palette_label", 0, 0, Component.translatableWithFallback("mc-paint.screen.palette", "Palette")));
		for (int k = 0; k < SLOTS; k++) {
			int x = slotX(k);
			int y = slotY(k);
			tools.child(new ComponentBuilder(slotId(k), ComponentType.BUTTON).bounds(x, y, SWATCH, SWATCH)
				.props(slotProps(easel, session, k))
				.child(new ComponentBuilder(slotId(k) + "_fill", ComponentType.PANEL).bounds(4, 4, SWATCH - 8, SWATCH - 8)
					.props(fillProps(colorAt(easel, k)))));
			tools.child(new ComponentBuilder(slotId(k) + "_track", ComponentType.PANEL).bounds(x + 1, y + SWATCH + 1, BAR_WIDTH, 3)
				.props(Map.of(ComponentType.PROP_BACKGROUND, "#FF000000", ComponentType.PROP_BORDER, "none",
					ComponentType.PROP_VISIBLE, String.valueOf(colorAt(easel, k) >= 0)))
				.child(new ComponentBuilder(slotId(k) + "_bar", ComponentType.PANEL).bounds(0, 0, barWidth(easel, k), 2)
					.props(Map.of(ComponentType.PROP_BACKGROUND, barColor(easel.paint(colorAt(easel, k))), ComponentType.PROP_BORDER, "none"))));
		}
		int after = easel.colors().size();
		tools.child(new ComponentBuilder(MIX, ComponentType.BUTTON).bounds(slotX(after), slotY(after), SWATCH, SWATCH)
			.props(mixProps(easel)));
		tools.child(text("brush_label", 0, BRUSH_TOP, Component.translatableWithFallback("mc-paint.screen.brush", "Brush")));
		for (int b = 1; b <= MAX_BRUSH; b++) {
			tools.child(new ComponentBuilder(brushId(b), ComponentType.BUTTON)
				.bounds((b - 1) * SWATCH_STEP, BRUSH_TOP + 10, SWATCH, 18)
				.props(brushProps(session, b)));
		}
		tools.child(button(LEAVE, 0, Component.translatableWithFallback("mc-paint.screen.leave", "Leave"),
			Component.translatableWithFallback("mc-paint.screen.leave.tip", "Leave the canvas on the easel to come back to")));
		tools.child(button(SIGN, BUTTON_WIDTH + 4, Component.translatableWithFallback("mc-paint.screen.sign", "Sign"),
			Component.translatableWithFallback("mc-paint.screen.sign.tip", "Name it, sign it, and take it off the easel")));
		return tools;
	}

	/**
	 * Mixing two colours: a well for each, with how much of it to use; the palette's colours to fill
	 * them from; and what they would make, with how full of it the palette would be.
	 */
	private static ComponentBuilder mixer(EaselBlockEntity easel, Session session) {
		ComponentBuilder mixer = group(MIXER, false);
		mixer.child(text("mix_label", 0, 0, Component.translatableWithFallback("mc-paint.mix.title", "Mix two colours")));
		for (int w = 0; w < 2; w++) {
			int y = 12 + w * 24;
			mixer.child(new ComponentBuilder(wellId(w), ComponentType.BUTTON).bounds(0, y, SWATCH, SWATCH)
				.child(new ComponentBuilder(wellId(w) + "_fill", ComponentType.PANEL).bounds(4, 4, SWATCH - 8, SWATCH - 8)
					.props(fillProps(-1))));
			mixer.child(new ComponentBuilder(wellId(w) + "_less", ComponentType.BUTTON).bounds(24, y + 3, 14, 14)
				.prop(ComponentType.PROP_LABEL, "-"));
			mixer.child(new ComponentBuilder(wellId(w) + "_amount", ComponentType.TEXT).bounds(40, y + 6, 30, 10)
				.prop(ComponentType.PROP_TEXT, "")
				.prop(ComponentType.PROP_ALIGN, "center")
				.prop(ComponentType.PROP_COLOR, TEXT_COLOR));
			mixer.child(new ComponentBuilder(wellId(w) + "_more", ComponentType.BUTTON).bounds(72, y + 3, 14, 14)
				.prop(ComponentType.PROP_LABEL, "+"));
		}
		for (int k = 0; k < Paint.MAX_COLORS; k++) {
			mixer.child(new ComponentBuilder(chipId(k), ComponentType.BUTTON)
				.bounds((k % CHIPS_PER_ROW) * CHIP_STEP, CHIPS_TOP + (k / CHIPS_PER_ROW) * CHIP_STEP, CHIP, CHIP)
				.child(new ComponentBuilder(chipId(k) + "_fill", ComponentType.PANEL).bounds(2, 2, CHIP - 4, CHIP - 4)
					.props(fillProps(-1))));
		}
		mixer.child(new ComponentBuilder(MIX_RESULT, ComponentType.PANEL).bounds(0, RESULT_TOP, SWATCH, SWATCH).props(fillProps(-1)));
		mixer.child(new ComponentBuilder(MIX_TEXT, ComponentType.TEXT).pos(24, RESULT_TOP + 1)
			.prop(ComponentType.PROP_TEXT, "").prop(ComponentType.PROP_COLOR, TEXT_COLOR));
		mixer.child(new ComponentBuilder(MIX_RESULT + "_track", ComponentType.PANEL).bounds(24, RESULT_TOP + 13, RESULT_BAR, 3)
			.props(Map.of(ComponentType.PROP_BACKGROUND, "#FF000000", ComponentType.PROP_BORDER, "none"))
			.child(new ComponentBuilder(MIX_RESULT + "_after", ComponentType.PANEL).bounds(0, 0, 0, 2)
				.props(Map.of(ComponentType.PROP_BORDER, "none")))
			.child(new ComponentBuilder(MIX_RESULT + "_before", ComponentType.PANEL).bounds(0, 0, 0, 2)
				.props(Map.of(ComponentType.PROP_BORDER, "none"))));
		mixer.child(new ComponentBuilder(MIX_ERROR, ComponentType.TEXT).pos(0, RESULT_TOP + 26)
			.prop(ComponentType.PROP_TEXT, "")
			.prop(ComponentType.PROP_COLOR, ERROR_COLOR)
			.prop(ComponentType.PROP_WRAP_WIDTH, String.valueOf(COLUMN_WIDTH)));
		mixer.child(button(MIX_BACK, 0, Component.translatableWithFallback("mc-paint.screen.back", "Back"), null));
		mixer.child(button(MIX_APPLY, BUTTON_WIDTH + 4, Component.translatableWithFallback("mc-paint.mix.apply", "Mix"), null));
		return mixer;
	}

	private static ComponentBuilder form(ServerPlayer player) {
		ComponentBuilder form = group(FORM, false);
		form.child(text("title_label", 0, 0, Component.translatableWithFallback("mc-paint.screen.title", "Title")));
		form.child(new ComponentBuilder(TITLE, ComponentType.TEXT_INPUT).bounds(0, 12, COLUMN_WIDTH, 18)
			.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(MAX_TITLE))
			.prop(ComponentType.PROP_PLACEHOLDER, Component.translatableWithFallback("mc-paint.screen.title.placeholder",
				"Name your painting").getString()));
		form.child(text("signature", 0, 38, Component.translatableWithFallback("mc-paint.screen.signature",
			"Signed by %s", player.getName().getString())));
		form.child(new ComponentBuilder(ERROR, ComponentType.TEXT).pos(0, 56)
			.prop(ComponentType.PROP_TEXT, "")
			.prop(ComponentType.PROP_COLOR, ERROR_COLOR)
			.prop(ComponentType.PROP_WRAP_WIDTH, String.valueOf(COLUMN_WIDTH)));
		form.child(button(BACK, 0, Component.translatableWithFallback("mc-paint.screen.back", "Back"), null));
		form.child(button(CONFIRM, BUTTON_WIDTH + 4, Component.translatableWithFallback("mc-paint.screen.sign", "Sign"), null));
		return form;
	}

	private static ComponentBuilder group(String id, boolean visible) {
		return new ComponentBuilder(id, ComponentType.PANEL).bounds(COLUMN_X, PAD, COLUMN_WIDTH, CANVAS_BOX)
			.prop(ComponentType.PROP_BACKGROUND, "#00000000")
			.prop(ComponentType.PROP_BORDER, "none")
			.prop(ComponentType.PROP_VISIBLE, String.valueOf(visible));
	}

	private static ComponentBuilder text(String id, int x, int y, Component text) {
		return new ComponentBuilder(id, ComponentType.TEXT).pos(x, y)
			.prop(ComponentType.PROP_TEXT, text.getString())
			.prop(ComponentType.PROP_COLOR, TEXT_COLOR);
	}

	private static ComponentBuilder button(String id, int x, Component label, Component tooltip) {
		ComponentBuilder button = new ComponentBuilder(id, ComponentType.BUTTON).bounds(x, BUTTONS_TOP, BUTTON_WIDTH, BUTTON_HEIGHT)
			.prop(ComponentType.PROP_LABEL, label.getString());
		if (tooltip != null) button.prop(ComponentType.PROP_TOOLTIP, tooltip.getString());
		return button;
	}

	// --- The palette's look ---

	private static String slotId(int slot) {
		return "slot_" + slot;
	}

	private static String chipId(int slot) {
		return "chip_" + slot;
	}

	private static String wellId(int well) {
		return "well_" + well;
	}

	private static String brushId(int brush) {
		return "brush_" + brush;
	}

	private static int slotX(int slot) {
		return (slot % SWATCHES_PER_ROW) * SWATCH_STEP;
	}

	private static int slotY(int slot) {
		return SWATCHES_TOP + (slot / SWATCHES_PER_ROW) * SWATCH_ROW_STEP;
	}

	/** The colour in a palette slot, or -1 past the last. */
	private static int colorAt(EaselBlockEntity easel, int slot) {
		List<Integer> colors = easel.colors();
		return slot < colors.size() ? colors.get(slot) : -1;
	}

	private static String hex(int argb) {
		return String.format("#%08X", argb);
	}

	private static Map<String, String> slotProps(EaselBlockEntity easel, Session session, int slot) {
		int color = colorAt(easel, slot);
		if (color < 0) return Map.of(ComponentType.PROP_VISIBLE, "false");
		Component tip = Component.translatableWithFallback("mc-paint.screen.swatch", "%s: %s of %s dyes",
			Paint.name(color), Paint.dyeCount(easel.paint(color)), Paint.MAX_DYES);
		return Map.of(
			ComponentType.PROP_VISIBLE, "true",
			ComponentType.PROP_STYLE, session.ink == color ? "pressed" : "default",
			ComponentType.PROP_TOOLTIP, tip.getString());
	}

	/** The mix button, which mixes only once there are two colours to mix. */
	private static Map<String, String> mixProps(EaselBlockEntity easel) {
		int colors = easel.colors().size();
		return Map.of(
			ComponentType.PROP_LABEL, "+",
			ComponentType.PROP_ENABLED, String.valueOf(colors >= 2),
			ComponentType.PROP_TOOLTIP, (colors >= 2
				? Component.translatableWithFallback("mc-paint.mix.tip", "Mix two colours into a new one")
				: Component.translatableWithFallback("mc-paint.mix.need_two", "Put two colours on the palette to mix them")).getString());
	}

	/** A colour's square, or an empty well's. */
	private static Map<String, String> fillProps(int color) {
		return Map.of(
			ComponentType.PROP_BACKGROUND, color < 0 ? "#00000000" : hex(Paint.argb(color)),
			ComponentType.PROP_BORDER, "flat",
			ComponentType.PROP_BORDER_COLOR, "#FF373737");
	}

	/** Measured against every dye the palette holds, so one dye fills a third and the rest is room for more. */
	private static int barWidth(EaselBlockEntity easel, int slot) {
		return width(BAR_WIDTH, easel.paint(colorAt(easel, slot)));
	}

	private static int width(int full, int units) {
		if (units <= 0) return 0;
		return Math.max(1, Math.round((float) full * Math.min(units, Paint.MAX_UNITS) / Paint.MAX_UNITS));
	}

	/** Green when full through yellow to red when nearly spent, as an item's durability bar goes. */
	private static String barColor(int units) {
		float left = Math.min(1.0F, (float) units / Paint.MAX_UNITS);
		int red = Math.round(255 * Math.min(1.0F, 2.0F - 2.0F * left));
		int green = Math.round(255 * Math.min(1.0F, 2.0F * left));
		return String.format("#FF%02X%02X00", red, green);
	}

	private static Map<String, String> brushProps(Session session, int brush) {
		return Map.of(
			ComponentType.PROP_LABEL, String.valueOf(brush),
			ComponentType.PROP_STYLE, session.brush == brush ? "pressed" : "default",
			ComponentType.PROP_TOOLTIP, Component.translatableWithFallback("mc-paint.screen.brush.size", "%s × %s brush",
				brush, brush).getString());
	}

	private static void slotUpdates(List<ComponentUpdate> updates, EaselBlockEntity easel, Session session, int slot) {
		int color = colorAt(easel, slot);
		updates.add(new ComponentUpdateBuilder(slotId(slot)).props(slotProps(easel, session, slot)).build());
		updates.add(new ComponentUpdateBuilder(slotId(slot) + "_fill").props(fillProps(color)).build());
		updates.add(new ComponentUpdateBuilder(slotId(slot) + "_track")
			.prop(ComponentType.PROP_VISIBLE, String.valueOf(color >= 0)).build());
		updates.add(new ComponentUpdateBuilder(slotId(slot) + "_bar").size(barWidth(easel, slot), 2)
			.prop(ComponentType.PROP_BACKGROUND, barColor(easel.paint(color))).build());
	}

	/** Every slot, and the mix button to after the last: for when the palette's colours came or went. */
	private static void paletteUpdates(List<ComponentUpdate> updates, EaselBlockEntity easel, Session session) {
		for (int k = 0; k < SLOTS; k++) slotUpdates(updates, easel, session, k);
		int after = easel.colors().size();
		updates.add(new ComponentUpdateBuilder(MIX).pos(slotX(after), slotY(after)).props(mixProps(easel)).build());
	}

	/** The mixer as the session has it: the wells, the chips to fill them from, and what they make. */
	private static void mixerUpdates(List<ComponentUpdate> updates, EaselBlockEntity easel, Session session) {
		List<Integer> colors = easel.colors();
		for (int w = 0; w < 2; w++) {
			if (session.well[w] >= 0 && !easel.onPalette(session.well[w])) session.well[w] = -1;
			int color = session.well[w];
			if (color >= 0) session.units[w] = Math.clamp(session.units[w], Math.min(MIX_STEP, easel.paint(color)), easel.paint(color));
			updates.add(new ComponentUpdateBuilder(wellId(w))
				.prop(ComponentType.PROP_STYLE, session.filling == w ? "pressed" : "default")
				.prop(ComponentType.PROP_TOOLTIP, (color < 0
					? Component.translatableWithFallback("mc-paint.mix.empty_well", "Choose a colour below for this")
					: Paint.name(color)).getString()).build());
			updates.add(new ComponentUpdateBuilder(wellId(w) + "_fill").props(fillProps(color)).build());
			updates.add(new ComponentUpdateBuilder(wellId(w) + "_amount")
				.prop(ComponentType.PROP_TEXT, color < 0 ? "" : Paint.dyeCount(session.units[w])).build());
			updates.add(new ComponentUpdateBuilder(wellId(w) + "_less").prop(ComponentType.PROP_ENABLED, String.valueOf(color >= 0)).build());
			updates.add(new ComponentUpdateBuilder(wellId(w) + "_more").prop(ComponentType.PROP_ENABLED, String.valueOf(color >= 0)).build());
		}
		for (int k = 0; k < Paint.MAX_COLORS; k++) {
			int color = k < colors.size() ? colors.get(k) : -1;
			updates.add(new ComponentUpdateBuilder(chipId(k))
				.prop(ComponentType.PROP_VISIBLE, String.valueOf(color >= 0))
				.prop(ComponentType.PROP_TOOLTIP, color < 0 ? "" : Paint.name(color).getString()).build());
			updates.add(new ComponentUpdateBuilder(chipId(k) + "_fill").props(fillProps(color)).build());
		}

		EaselBlockEntity.Mix plan = easel.plan(session.well[0], session.units[0], session.well[1], session.units[1]);
		boolean made = plan.result() >= 0;
		updates.add(new ComponentUpdateBuilder(MIX_RESULT).props(fillProps(plan.result())).build());
		updates.add(new ComponentUpdateBuilder(MIX_TEXT).prop(ComponentType.PROP_TEXT, !made ? "" : Component.translatableWithFallback(
			"mc-paint.mix.makes", "%s of %s dyes", Paint.dyeCount(plan.after()), Paint.MAX_DYES).getString()).build());
		updates.add(new ComponentUpdateBuilder(MIX_RESULT + "_after").size(made ? width(RESULT_BAR, plan.after()) : 0, 2)
			.prop(ComponentType.PROP_BACKGROUND, plan.after() > Paint.MAX_UNITS ? "#FFFF3030" : barColor(plan.after())).build());
		// What the palette holds of it already, drawn darker over the front of what it will hold
		updates.add(new ComponentUpdateBuilder(MIX_RESULT + "_before").size(made ? width(RESULT_BAR, plan.before()) : 0, 2)
			.prop(ComponentType.PROP_BACKGROUND, "#FF5A6A5A").build());
		updates.add(new ComponentUpdateBuilder(MIX_ERROR)
			.prop(ComponentType.PROP_TEXT, plan.ok() || !made ? "" : plan.problem().getString()).build());
		updates.add(new ComponentUpdateBuilder(MIX_APPLY).prop(ComponentType.PROP_ENABLED, String.valueOf(plan.ok())).build());
	}

	// --- Answering ---

	/** The player's session, while its easel is still there with a canvas on it and within reach; closed otherwise. */
	private static Session session(ServerPlayer player) {
		Session session = sessions.get(player.getUUID());
		if (session == null) return null;
		if (player.level() == session.level && easel(session) != null
			&& player.distanceToSqr(Vec3.atCenterOf(session.easel)) <= REACH * REACH) {
			return session;
		}
		close(player);
		return null;
	}

	private static EaselBlockEntity easel(Session session) {
		return session.level.getBlockEntity(session.easel) instanceof EaselBlockEntity easel && easel.hasCanvas() ? easel : null;
	}

	private static void onCanvas(ServerPlayer player, Map<String, String> data) {
		Session session = session(player);
		if (session == null) return;
		EaselBlockEntity easel = easel(session);

		String pick = data.get(PixelCanvas.ACTION_PICK);
		if (pick != null) {
			try {
				choose(player, Integer.parseInt(pick));
			} catch (NumberFormatException ignored) {
			}
			return;
		}

		PixelCanvas.Stroke stroke = PixelCanvas.Stroke.fromAction(data);
		if (stroke == null) return;
		boolean honest = stroke.ink() >= 0 && stroke.ink() < PixelCanvas.MAX_PALETTE
			&& stroke.brush() >= 1 && stroke.brush() <= MAX_BRUSH;
		int colorsBefore = easel.colors().size();
		int[] supply = easel.supply();
		IntArrayList changed = new IntArrayList();
		if (honest) {
			CanvasSize size = easel.size();
			PixelCanvas.apply(easel.pixels(), size.columns(), size.rows(), supply, stroke, changed::add);
			easel.spend(supply);
			easel.painted(session.level, changed.elements(), changed.size());
		}

		ComponentUpdateBuilder canvas = new ComponentUpdateBuilder(CANVAS)
			.prop(ComponentType.PROP_CANVAS_ACK, String.valueOf(stroke.seq()))
			.prop(ComponentType.PROP_CANVAS_SUPPLY, PixelCanvas.encodeSupply(supply));
		if (!honest) canvas.prop(ComponentType.PROP_CANVAS_PIXELS, PixelCanvas.encode(easel.pixels()));
		List<ComponentUpdate> updates = new ArrayList<>();
		updates.add(canvas.build());
		if (honest && !changed.isEmpty()) {
			// A colour painted out leaves the palette, and every slot after it moves up one
			if (easel.colors().size() != colorsBefore) paletteUpdates(updates, easel, session);
			else slotUpdates(updates, easel, session, easel.colors().indexOf(stroke.ink()));
			if (session.mixing) mixerUpdates(updates, easel, session);
		}
		PandoricalApi.screens().update(player, session.screenId, updates);
	}

	private static void chooseSlot(ServerPlayer player, int slot) {
		Session session = session(player);
		if (session != null) choose(player, colorAt(easel(session), slot));
	}

	private static void choose(ServerPlayer player, int color) {
		Session session = session(player);
		if (session == null) return;
		EaselBlockEntity easel = easel(session);
		if (!easel.onPalette(color)) return;
		int previous = easel.colors().indexOf(session.ink);
		session.ink = color;
		List<ComponentUpdate> updates = new ArrayList<>();
		updates.add(new ComponentUpdateBuilder(CANVAS).prop(ComponentType.PROP_CANVAS_INK, String.valueOf(color)).build());
		if (previous >= 0) slotUpdates(updates, easel, session, previous);
		slotUpdates(updates, easel, session, easel.colors().indexOf(color));
		PandoricalApi.screens().update(player, session.screenId, updates);
	}

	private static void brush(ServerPlayer player, int brush) {
		Session session = session(player);
		if (session == null) return;
		session.brush = brush;
		List<ComponentUpdate> updates = new ArrayList<>();
		updates.add(new ComponentUpdateBuilder(CANVAS).prop(ComponentType.PROP_CANVAS_BRUSH, String.valueOf(brush)).build());
		for (int b = 1; b <= MAX_BRUSH; b++) {
			updates.add(new ComponentUpdateBuilder(brushId(b)).props(brushProps(session, b)).build());
		}
		PandoricalApi.screens().update(player, session.screenId, updates);
	}

	private static void showForm(ServerPlayer player, boolean show) {
		Session session = session(player);
		if (session == null) return;
		PandoricalApi.screens().update(player, session.screenId, List.of(
			new ComponentUpdateBuilder(TOOLS).prop(ComponentType.PROP_VISIBLE, String.valueOf(!show)).build(),
			new ComponentUpdateBuilder(FORM).prop(ComponentType.PROP_VISIBLE, String.valueOf(show)).build(),
			new ComponentUpdateBuilder(TITLE).prop(ComponentType.PROP_FOCUSED, String.valueOf(show)).build(),
			new ComponentUpdateBuilder(ERROR).prop(ComponentType.PROP_TEXT, "").build()));
	}

	// --- Mixing ---

	/** Open the mixer with the colour being painted in the first well, ready to choose the second; or close it. */
	private static void showMixer(ServerPlayer player, boolean show) {
		Session session = session(player);
		if (session == null) return;
		EaselBlockEntity easel = easel(session);
		if (show && easel.colors().size() < 2) return;
		session.mixing = show;
		List<ComponentUpdate> updates = new ArrayList<>();
		if (show) {
			session.well[0] = easel.onPalette(session.ink) ? session.ink : -1;
			session.well[1] = -1;
			session.units[0] = session.well[0] < 0 ? 0 : Math.min(MIX_START, easel.paint(session.well[0]));
			session.filling = session.well[0] < 0 ? 0 : 1;
			mixerUpdates(updates, easel, session);
		}
		updates.add(new ComponentUpdateBuilder(TOOLS).prop(ComponentType.PROP_VISIBLE, String.valueOf(!show)).build());
		updates.add(new ComponentUpdateBuilder(MIXER).prop(ComponentType.PROP_VISIBLE, String.valueOf(show)).build());
		PandoricalApi.screens().update(player, session.screenId, updates);
	}

	/** Choose which well the next colour chosen goes into. */
	private static void fill(ServerPlayer player, int well) {
		Session session = session(player);
		if (session == null || !session.mixing) return;
		session.filling = well;
		mixerRefresh(player, session);
	}

	/** A colour chosen for the well being filled; the other well is filled next if it is still empty. */
	private static void chip(ServerPlayer player, int slot) {
		Session session = session(player);
		if (session == null || !session.mixing) return;
		EaselBlockEntity easel = easel(session);
		int color = colorAt(easel, slot);
		if (color < 0) return;
		int w = session.filling;
		session.well[w] = color;
		session.units[w] = Math.min(MIX_START, easel.paint(color));
		if (session.well[1 - w] < 0) session.filling = 1 - w;
		mixerRefresh(player, session);
	}

	private static void measure(ServerPlayer player, int well, int change) {
		Session session = session(player);
		if (session == null || !session.mixing || session.well[well] < 0) return;
		session.units[well] += change;
		mixerRefresh(player, session);
	}

	/** Mix, and paint with what was made. */
	private static void mix(ServerPlayer player) {
		Session session = session(player);
		if (session == null || !session.mixing) return;
		EaselBlockEntity easel = easel(session);
		int made = easel.mix(session.well[0], session.units[0], session.well[1], session.units[1]);
		if (made < 0) {
			mixerRefresh(player, session);
			return;
		}
		session.level.playSound(null, session.easel, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 0.8F);
		showMixer(player, false);
		afterPaletteChange(player, session, easel);
		choose(player, made);
	}

	private static void mixerRefresh(ServerPlayer player, Session session) {
		List<ComponentUpdate> updates = new ArrayList<>();
		mixerUpdates(updates, easel(session), session);
		PandoricalApi.screens().update(player, session.screenId, updates);
	}

	private static void onTitle(ServerPlayer player, Map<String, String> data) {
		Session session = sessions.get(player.getUUID());
		if (session == null) return;
		String text = data.getOrDefault("text", "").strip();
		session.title = text.length() > MAX_TITLE ? text.substring(0, MAX_TITLE) : text;
	}

	private static void sign(ServerPlayer player) {
		Session session = session(player);
		if (session == null) return;
		EaselBlockEntity easel = easel(session);
		if (session.title.isEmpty()) {
			formError(player, session, Component.translatableWithFallback("mc-paint.sign.untitled", "Give your painting a title first."));
			return;
		}
		if (easel.isBlank()) {
			formError(player, session, Component.translatableWithFallback("mc-paint.sign.blank", "Paint something before you sign it."));
			return;
		}

		CanvasSize size = easel.size();
		int[] maps = Paintings.drawTiles(session.level, size, easel.pixels());
		Artworks.Signed painting = new Artworks.Signed(size, session.title, player.getName().getString(), maps, 0);
		easel.clearCanvas();

		easel.popOff(session.level, Artworks.signed(painting));
		close(player);
	}

	private static void formError(ServerPlayer player, Session session, Component message) {
		PandoricalApi.screens().update(player, session.screenId, List.of(
			new ComponentUpdateBuilder(ERROR).prop(ComponentType.PROP_TEXT, message.getString()).build()));
	}

	private static void close(ServerPlayer player) {
		Session session = sessions.remove(player.getUUID());
		if (session != null) PandoricalApi.screens().close(player, session.screenId);
	}

	// --- The easel, from outside ---

	private static ServerPlayer painterAt(ServerLevel level, BlockPos foot) {
		for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
			Session session = entry.getValue();
			if (session.level == level && session.easel.equals(foot)) {
				ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
				if (player != null) return player;
			}
		}
		return null;
	}

	/** Dye went onto the palette: show whoever is painting here. */
	public static void paletteChanged(ServerLevel level, BlockPos foot) {
		ServerPlayer player = painterAt(level, foot);
		if (player == null) return;
		Session session = session(player);
		if (session == null) return;
		afterPaletteChange(player, session, easel(session));
	}

	/** The palette's colours or paint changed from outside a stroke: the canvas's supply, every slot, and the mixer. */
	private static void afterPaletteChange(ServerPlayer player, Session session, EaselBlockEntity easel) {
		List<ComponentUpdate> updates = new ArrayList<>();
		if (!easel.onPalette(session.ink)) {
			session.ink = firstInk(easel);
			updates.add(new ComponentUpdateBuilder(CANVAS).prop(ComponentType.PROP_CANVAS_INK, String.valueOf(session.ink)).build());
		}
		updates.add(new ComponentUpdateBuilder(CANVAS)
			.prop(ComponentType.PROP_CANVAS_SUPPLY, PixelCanvas.encodeSupply(easel.supply())).build());
		paletteUpdates(updates, easel, session);
		if (session.mixing) mixerUpdates(updates, easel, session);
		PandoricalApi.screens().update(player, session.screenId, updates);
	}

	/** The easel is being broken: whoever is painting at it stops. */
	public static void easelGone(ServerLevel level, BlockPos foot) {
		ServerPlayer player = painterAt(level, foot);
		if (player != null) close(player);
	}
}
