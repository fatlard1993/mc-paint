package justfatlard.mc_paint;

import justfatlard.pandorical.api.PixelCanvas;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * Paintings as items. An unfinished one carries its cells, to be put back on an easel and carried
 * on with. A signed one carries its title, its painter, and the maps its blocks were drawn onto
 * when it was signed: the maps are the picture from then on, the way they are for any map art,
 * so the item stays small however large the painting.
 */
public final class Artworks {
	private Artworks() {}

	private static final String ROOT = Main.MOD_ID;

	public record Unfinished(CanvasSize size, byte[] pixels) {}

	/**
	 * @param maps in reading order: left to right along the top row, then down
	 * @param copy which copy of the painting this is, from 1; 0 for the painting itself
	 */
	public record Signed(CanvasSize size, String title, String author, int[] maps, int copy) {
		public Signed copied(int number) {
			return new Signed(size, title, author, maps, number);
		}
	}

	public static ItemStack unfinished(CanvasSize size, byte[] pixels) {
		ItemStack stack = new ItemStack(Main.UNFINISHED_PAINTING);
		CompoundTag tag = new CompoundTag();
		tag.putInt("width", size.width);
		tag.putInt("height", size.height);
		tag.putString("cells", PixelCanvas.encode(pixels));
		write(stack, tag);
		stack.set(DataComponents.LORE, new ItemLore(List.of(sizeLine(size))));
		return stack;
	}

	public static Unfinished readUnfinished(ItemStack stack) {
		if (!stack.is(Main.UNFINISHED_PAINTING)) return null;
		CompoundTag tag = read(stack);
		CanvasSize size = CanvasSize.of(tag.getIntOr("width", 0), tag.getIntOr("height", 0));
		if (size == null) return null;
		// "pixels" is how cells were saved before colours were map colours
		byte[] cells = tag.contains("cells") ? PixelCanvas.decode(tag.getStringOr("cells", ""), size.cells())
			: Paint.legacyCells(tag.getStringOr("pixels", ""), size.cells());
		return new Unfinished(size, cells);
	}

	public static ItemStack signed(Signed painting) {
		ItemStack stack = new ItemStack(Main.PAINTING);
		write(stack, toTag(painting));
		stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("mc-paint.painting.named",
			"\"%s\" by %s", painting.title(), painting.author()));
		List<Component> lore = painting.copy() > 0
			? List.of(sizeLine(painting.size()), grey(Component.translatableWithFallback("mc-paint.painting.copy", "Copy #%s", painting.copy())))
			: List.of(sizeLine(painting.size()));
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	public static Signed readSigned(ItemStack stack) {
		if (!stack.is(Main.PAINTING)) return null;
		return fromTag(read(stack));
	}

	static Signed fromTag(CompoundTag tag) {
		CanvasSize size = CanvasSize.of(tag.getIntOr("width", 0), tag.getIntOr("height", 0));
		int[] maps = tag.getIntArray("maps").orElse(new int[0]);
		if (size == null || maps.length != size.width * size.height) return null;
		return new Signed(size, tag.getStringOr("title", ""), tag.getStringOr("author", ""), maps, tag.getIntOr("copy", 0));
	}

	static CompoundTag toTag(Signed painting) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("width", painting.size().width);
		tag.putInt("height", painting.size().height);
		tag.putString("title", painting.title());
		tag.putString("author", painting.author());
		tag.putIntArray("maps", painting.maps());
		if (painting.copy() > 0) tag.putInt("copy", painting.copy());
		return tag;
	}

	private static Component sizeLine(CanvasSize size) {
		return grey(Component.literal(size.label()));
	}

	private static Component grey(Component line) {
		return line.copy().withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY));
	}

	private static void write(ItemStack stack, CompoundTag tag) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(ROOT, tag));
	}

	private static CompoundTag read(ItemStack stack) {
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty(ROOT);
	}
}
