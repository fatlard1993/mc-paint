package justfatlard.mc_paint;

/**
 * The shapes a canvas can be stretched to, in blocks, the way vanilla's paintings come. A block
 * of canvas is sixteen cells a side, the resolution of every other texture in the game.
 */
public enum CanvasSize {
	ONE_BY_ONE(1, 1),
	TWO_BY_ONE(2, 1),
	ONE_BY_TWO(1, 2),
	TWO_BY_TWO(2, 2),
	THREE_BY_THREE(3, 3),
	FOUR_BY_TWO(4, 2),
	FOUR_BY_THREE(4, 3),
	FOUR_BY_FOUR(4, 4);

	public static final int CELLS_PER_BLOCK = 16;

	public final int width;
	public final int height;

	CanvasSize(int width, int height) {
		this.width = width;
		this.height = height;
	}

	public int columns() {
		return width * CELLS_PER_BLOCK;
	}

	public int rows() {
		return height * CELLS_PER_BLOCK;
	}

	public int cells() {
		return columns() * rows();
	}

	public String label() {
		return width + " × " + height;
	}

	public String id() {
		return width + "x" + height;
	}

	public static CanvasSize of(int width, int height) {
		for (CanvasSize size : values()) {
			if (size.width == width && size.height == height) return size;
		}
		return null;
	}
}
