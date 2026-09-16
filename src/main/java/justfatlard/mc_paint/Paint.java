package justfatlard.mc_paint;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * Colours, which are map colours: a packed map colour, 0 to 255, is at once a cell on a canvas,
 * a colour on the palette and a pixel on the map a signed painting is drawn onto. A dye paints its
 * own map colour at full brightness; a mix of two paints the map colour nearest their blend. So
 * whatever is painted can be hung exactly as it was painted.
 */
public final class Paint {
	private Paint() {}

	/** Cells one dye covers: half of a single-block canvas. */
	public static final int UNITS_PER_DYE = 128;
	/** Dyes' worth of one colour the palette holds at once. */
	public static final int MAX_DYES = 3;
	public static final int MAX_UNITS = MAX_DYES * UNITS_PER_DYE;
	/** Colours the palette holds at once. */
	public static final int MAX_COLORS = 19;

	/** Paper-white, a warm white just off the white dye's own, so a canvas reads as paper and not as paint. */
	public static final int BARE = color(MapColor.QUARTZ);

	public static int color(MapColor color) {
		return color.getPackedId(MapColor.Brightness.HIGH) & 0xFF;
	}

	public static int color(DyeColor dye) {
		return color(dye.getMapColor());
	}

	/** The dye a colour is, or null for a mixed one. */
	public static DyeColor dyeOf(int color) {
		for (DyeColor dye : DyeColor.values()) {
			if (color(dye) == color) return dye;
		}
		return null;
	}

	/** The dye a stack is, or null for anything that is not a dye. */
	public static DyeColor dyeOf(ItemStack stack) {
		return stack.getItem() instanceof DyeItem ? stack.get(DataComponents.DYE) : null;
	}

	public static ItemStack dyeStack(DyeColor dye, int count) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(dye.getName() + "_dye")), count);
	}

	public static int argb(int color) {
		return MapColor.getColorFromPackedId(color);
	}

	/** Every colour's ARGB, by colour: the palette a canvas of map colours is drawn with. */
	public static int[] palette() {
		int[] palette = new int[256];
		for (int i = 0; i < palette.length; i++) palette[i] = argb(i);
		return palette;
	}

	public static byte[] blank(CanvasSize size) {
		byte[] cells = new byte[size.cells()];
		Arrays.fill(cells, (byte) BARE);
		return cells;
	}

	/**
	 * The colour of two paints mixed in these amounts: their blend, as the map colour nearest it.
	 * Never bare canvas, which is not a colour anything can be painted.
	 */
	public static int mix(int a, int aUnits, int b, int bUnits) {
		float t = (float) bUnits / (aUnits + bUnits);
		int ca = argb(a);
		int cb = argb(b);
		int red = Math.round(channel(ca, 16) * (1 - t) + channel(cb, 16) * t);
		int green = Math.round(channel(ca, 8) * (1 - t) + channel(cb, 8) * t);
		int blue = Math.round(channel(ca, 0) * (1 - t) + channel(cb, 0) * t);
		int best = a;
		long bestDistance = Long.MAX_VALUE;
		for (int id = 1; id < 64; id++) {
			if (MapColor.byId(id) == MapColor.NONE) continue;
			for (int brightness = 0; brightness < 4; brightness++) {
				int candidate = id << 2 | brightness;
				if (candidate == BARE) continue;
				int c = argb(candidate);
				long dr = channel(c, 16) - red;
				long dg = channel(c, 8) - green;
				long db = channel(c, 0) - blue;
				// Weighted toward how strongly the eye tells each channel apart
				long distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
				if (distance < bestDistance) {
					bestDistance = distance;
					best = candidate;
				}
			}
		}
		return best;
	}

	private static int channel(int argb, int shift) {
		return argb >> shift & 0xFF;
	}

	/** Paint as the dyes it would take, to a tenth: "1", "2.5". */
	public static String dyeCount(int units) {
		float dyes = Math.round(10.0F * units / UNITS_PER_DYE) / 10.0F;
		return dyes == Math.floor(dyes) ? String.valueOf((int) dyes) : String.valueOf(dyes);
	}

	public static Component name(int color) {
		DyeColor dye = dyeOf(color);
		return dye != null ? Component.translatable("color.minecraft." + dye.getName())
			: Component.translatableWithFallback("mc-paint.color.mixed", "Mixed colour");
	}

	// --- Before colours were map colours ---

	private static final String LEGACY_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

	/** A colour as a palette index was, when bare canvas was 0 and each dye its id plus one. */
	public static int fromLegacyIndex(int index) {
		return index >= 1 && index <= DyeColor.values().length ? color(DyeColor.byId(index - 1)) : BARE;
	}

	/** Cells as they were saved before, one character per palette index. */
	public static byte[] legacyCells(String encoded, int size) {
		byte[] cells = new byte[size];
		for (int i = 0; i < size; i++) {
			int index = i < encoded.length() ? Math.max(0, LEGACY_ALPHABET.indexOf(encoded.charAt(i))) : 0;
			cells[i] = (byte) fromLegacyIndex(index);
		}
		return cells;
	}
}
