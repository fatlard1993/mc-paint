package justfatlard.mc_paint;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.Picture;
import justfatlard.pandorical.api.PixelCanvas;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an easel holds: a palette of dyes worked into paint, and the canvas on it.
 *
 * <p>Paint is counted in cells, by colour. A dye put on the palette adds {@link Paint#UNITS_PER_DYE}
 * of its colour, up to {@link Paint#MAX_DYES} dyes' worth; two colours mixed make a third from both.
 * A colour painted down to nothing leaves the palette. When the easel is broken, every whole dye
 * still unused comes back; the part-used one, and anything mixed, is gone with the paint.
 *
 * <p>The canvas stands on the easel as a Pandorical picture, anchored to an invisible item display
 * in the lower half: the easel's own cells, sent whole when anyone comes near and patched as they
 * are painted. The easel keeps the cells; the picture is only how they are seen, and is shown
 * again whenever its anchor loads.
 */
public class EaselBlockEntity extends BlockEntity {
	private static final float BLOCK_OF_CANVAS = 0.8F;
	private static final float LONGEST_SIDE = 1.4F;
	/** The top of the tray's lip, from the easel's foot, in blocks. */
	private static final float TRAY_TOP = 17 / 16F;
	/** How far in front of the easel's middle the canvas's foot stands, in blocks. */
	private static final float FORWARD = 2.5F / 16F;
	/** Leaning back against the mast, in degrees. */
	private static final float TILT = 6;
	private static final float THICKNESS = 1 / 16F;
	/** The back of the paper, a shade under its front. */
	private static final int BACK = 0xFFE6E2D8;

	/** Paint by colour, in the order the colours came onto the palette. */
	private final Map<Integer, Integer> palette = new LinkedHashMap<>();
	private CanvasSize size;
	private byte[] pixels;

	public EaselBlockEntity(BlockPos pos, BlockState state) {
		super(Main.EASEL_BLOCK_ENTITY, pos, state);
	}

	// --- Palette ---

	/** The colours on the palette, in the order they came. */
	public List<Integer> colors() {
		return List.copyOf(palette.keySet());
	}

	public int paint(int color) {
		return palette.getOrDefault(color, 0);
	}

	public boolean onPalette(int color) {
		return paint(color) > 0;
	}

	private void set(int color, int units) {
		if (units <= 0) palette.remove(color);
		else palette.put(color, units);
	}

	public enum Added { ADDED, FULL, NO_ROOM }

	/** Put a dye's paint on the palette: a whole dye's worth, if it fits and there is room for the colour. */
	public Added addDye(DyeColor dye) {
		int color = Paint.color(dye);
		if (paint(color) > Paint.MAX_UNITS - Paint.UNITS_PER_DYE) return Added.FULL;
		if (!onPalette(color) && palette.size() >= Paint.MAX_COLORS) return Added.NO_ROOM;
		set(color, paint(color) + Paint.UNITS_PER_DYE);
		setChanged();
		return Added.ADDED;
	}

	/**
	 * What mixing these amounts of two colours would make, and whether the palette can take it.
	 *
	 * @param before how much of the result the palette holds now
	 * @param after  how much it will hold once mixed; the two paints are spent in making it
	 */
	public record Mix(int result, int before, int after, Component problem) {
		public boolean ok() {
			return problem == null;
		}
	}

	public Mix plan(int a, int aUnits, int b, int bUnits) {
		if (!onPalette(a) || !onPalette(b)) return refused("mc-paint.mix.choose", "Choose two colours to mix.");
		if (a == b) return refused("mc-paint.mix.same", "Choose two different colours.");
		if (aUnits <= 0 || bUnits <= 0 || aUnits > paint(a) || bUnits > paint(b)) {
			return refused("mc-paint.mix.amounts", "Choose how much of each to use.");
		}
		int result = Paint.mix(a, aUnits, b, bUnits);
		int before = paint(result);
		int after = before - (result == a ? aUnits : 0) - (result == b ? bUnits : 0) + aUnits + bUnits;
		if (after > Paint.MAX_UNITS) {
			return new Mix(result, before, after, Component.translatableWithFallback("mc-paint.mix.too_much",
				"More than the %s dyes of one colour the palette holds.", Paint.MAX_DYES));
		}
		int colors = palette.size() + (before == 0 ? 1 : 0)
			- (paint(a) == aUnits && result != a ? 1 : 0) - (paint(b) == bUnits && result != b ? 1 : 0);
		if (colors > Paint.MAX_COLORS) return new Mix(result, before, after, Component.translatableWithFallback(
			"mc-paint.mix.full", "The palette has no room for another colour."));
		return new Mix(result, before, after, null);
	}

	private static Mix refused(String key, String fallback) {
		return new Mix(-1, 0, 0, Component.translatableWithFallback(key, fallback));
	}

	/** Mix two colours on the palette, spending both; the colour made, or -1 if the plan was refused. */
	public int mix(int a, int aUnits, int b, int bUnits) {
		Mix plan = plan(a, aUnits, b, bUnits);
		if (!plan.ok()) return -1;
		set(a, paint(a) - aUnits);
		set(b, paint(b) - bUnits);
		set(plan.result(), plan.after());
		setChanged();
		return plan.result();
	}

	/** The palette as a canvas supply, by colour. */
	public int[] supply() {
		int[] supply = new int[PixelCanvas.MAX_PALETTE];
		palette.forEach((color, units) -> supply[color] = units);
		return supply;
	}

	/** Take the palette's paint to what a stroke left of it; a colour spent to nothing is off the palette. */
	public void spend(int[] supply) {
		for (int color : colors()) set(color, supply[color]);
		setChanged();
	}

	/** Every whole dye still unused; mixed paint cannot be unmixed, and goes with the easel. */
	private List<ItemStack> unusedDyes() {
		List<ItemStack> dyes = new ArrayList<>();
		palette.forEach((color, units) -> {
			DyeColor dye = Paint.dyeOf(color);
			for (int whole = dye == null ? 0 : units / Paint.UNITS_PER_DYE; whole > 0; whole -= 64) {
				dyes.add(Paint.dyeStack(dye, Math.min(whole, 64)));
			}
		});
		return dyes;
	}

	// --- Canvas ---

	public boolean hasCanvas() {
		return size != null;
	}

	public CanvasSize size() {
		return size;
	}

	/** The canvas's own cells, painted in place. */
	public byte[] pixels() {
		return pixels;
	}

	public boolean isBlank() {
		for (byte cell : pixels) {
			if ((cell & 0xFF) != Paint.BARE) return false;
		}
		return true;
	}

	public void loadCanvas(CanvasSize size, byte[] pixels) {
		this.size = size;
		this.pixels = Arrays.copyOf(pixels, size.cells());
		setChanged();
		if (level instanceof ServerLevel server) stand(server);
	}

	/**
	 * Take the canvas off: as an unfinished painting if anything is on it, or back to the canvas it
	 * was if nothing is, since nothing has been spent on it yet.
	 */
	public ItemStack takeCanvas() {
		if (!hasCanvas()) return ItemStack.EMPTY;
		ItemStack taken = isBlank() ? new ItemStack(Main.CANVAS) : Artworks.unfinished(size, pixels);
		clearCanvas();
		return taken;
	}

	/** Take the canvas off and give it nothing back: it has been signed and carried away already. */
	public void clearCanvas() {
		size = null;
		pixels = null;
		setChanged();
		if (level instanceof ServerLevel server) unstand(server);
	}

	/** A finished painting comes off the easel toward whoever is in front of it. */
	public void popOff(ServerLevel level, ItemStack painting) {
		BlockPos board = worldPosition.above();
		Block.popResourceFromFace(level, board, getBlockState().getValue(EaselBlock.FACING), painting);
		level.playSound(null, board, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
	}

	/** Show these cells, just painted, to everyone watching. */
	public void painted(ServerLevel level, int[] cells, int count) {
		Display anchor = anchor(level);
		if (anchor == null) return;
		int[] indices = Arrays.copyOf(cells, count);
		byte[] values = new byte[count];
		for (int n = 0; n < count; n++) values[n] = pixels[indices[n]];
		PandoricalApi.pictures().paint(anchor, indices, values);
		setChanged();
	}

	// --- The canvas standing on the easel ---

	/** Where the anchor stands: the middle of the lower half, inside the block so a box around it finds it. */
	private Vec3 anchorAt() {
		return Vec3.atCenterOf(worldPosition);
	}

	private Display anchor(ServerLevel level) {
		List<Display.ItemDisplay> found = level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(worldPosition),
			display -> display.entityTags().contains(Paintings.EASEL_TAG));
		return found.isEmpty() ? null : found.get(0);
	}

	/** Stand the canvas on the easel, anchoring it first if nothing does yet. */
	public void stand(ServerLevel level) {
		if (!hasCanvas()) return;
		Display anchor = anchor(level);
		if (anchor == null) {
			anchor = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
			anchor.setPos(anchorAt());
			anchor.addTag(Paintings.EASEL_TAG);
			level.addFreshEntity(anchor);
		}
		PandoricalApi.pictures().show(anchor, picture());
	}

	private void unstand(ServerLevel level) {
		Display anchor = anchor(level);
		if (anchor == null) return;
		PandoricalApi.pictures().clear(anchor);
		anchor.discard();
	}

	/**
	 * The canvas as it stands: on the tray, leaning back against the mast, a block of canvas drawn
	 * at {@link #BLOCK_OF_CANVAS} of a block until its longest side reaches {@link #LONGEST_SIDE},
	 * so a small canvas looks small on the easel and a large one overhangs it the way a real one does.
	 */
	Picture picture() {
		float scale = Math.min(BLOCK_OF_CANVAS, LONGEST_SIDE / Math.max(size.width, size.height));
		float yaw = EaselBlock.yaw(getBlockState());
		double facing = Math.toRadians(yaw);
		float dx = (float) (-Math.sin(facing) * FORWARD);
		float dz = (float) (Math.cos(facing) * FORWARD);
		Picture.Pose pose = new Picture.Pose(dx, TRAY_TOP - 0.5F, dz, yaw, TILT, size.width * scale, size.height * scale);
		return new Picture(size.columns(), size.rows(), Paint.palette(), pixels, pose, BACK, THICKNESS);
	}

	// --- Breaking ---

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		if (!(level instanceof ServerLevel server)) return;
		PaintScreen.easelGone(server, pos);
		for (ItemStack dye : unusedDyes()) Block.popResource(server, pos, dye);
		ItemStack canvas = takeCanvas();
		if (!canvas.isEmpty()) Block.popResource(server, pos.above(), canvas);
	}

	// --- Saving ---

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		int[] pairs = new int[palette.size() * 2];
		int i = 0;
		for (Map.Entry<Integer, Integer> entry : palette.entrySet()) {
			pairs[i++] = entry.getKey();
			pairs[i++] = entry.getValue();
		}
		output.putIntArray("palette", pairs);
		if (size != null) {
			output.putInt("width", size.width);
			output.putInt("height", size.height);
			output.putString("cells", PixelCanvas.encode(pixels));
		}
	}

	/** Also reads easels saved before colours were map colours: "paint" by palette index, "pixels" one character a cell. */
	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		palette.clear();
		input.getIntArray("palette").ifPresentOrElse(pairs -> {
			for (int i = 0; i + 1 < pairs.length; i += 2) set(pairs[i], pairs[i + 1]);
		}, () -> input.getIntArray("paint").ifPresent(legacy -> {
			for (int i = 1; i < legacy.length; i++) set(Paint.fromLegacyIndex(i), legacy[i]);
		}));
		size = CanvasSize.of(input.getIntOr("width", 0), input.getIntOr("height", 0));
		if (size == null) {
			pixels = null;
		} else if (input.getString("cells").isPresent()) {
			pixels = PixelCanvas.decode(input.getStringOr("cells", ""), size.cells());
		} else {
			pixels = Paint.legacyCells(input.getStringOr("pixels", ""), size.cells());
		}
	}
}
