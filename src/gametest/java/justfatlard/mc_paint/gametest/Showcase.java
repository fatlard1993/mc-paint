package justfatlard.mc_paint.gametest;

import justfatlard.mc_paint.CanvasSize;
import justfatlard.mc_paint.EaselBlockEntity;
import justfatlard.mc_paint.Paint;
import justfatlard.mc_paint.PaintScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The pictures for the readme and the mod page: a canvas standing painted on its easel, and the
 * screen it was painted on.
 *
 * <p>The picture on the canvas is set cell by cell rather than clicked in by hand - a painting made
 * through the screen would take an afternoon of input to script - but it is the same canvas the
 * easel stands and the same cells the screen paints. Run it under xvfb-run; the frames land in
 * build/run/clientGameTest/screenshots.
 */
public final class Showcase implements FabricClientGameTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;

	/** Two blocks square: big enough to read as a picture, small enough to sit on the easel. */
	private static final CanvasSize SIZE = CanvasSize.TWO_BY_TWO;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("gamerule doWeatherCycle false");
			server.runCommand("time set noon");
			server.runCommand("gamemode creative @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX();
			int y = origin.getY();
			int z = origin.getZ();
			BlockPos easelFoot = new BlockPos(x, y, z - 4);

			// A stone floor, a stone wall behind, and the easel facing the camera.
			server.runCommand("fill %d %d %d %d %d %d minecraft:smooth_stone"
				.formatted(x - 6, y - 1, z - 8, x + 6, y - 1, z + 4));
			server.runCommand("fill %d %d %d %d %d %d minecraft:stone_bricks"
				.formatted(x - 6, y, z - 8, x + 6, y + 4, z - 8));
			server.runCommand("setblock %d %d %d mc-paint:easel[facing=south]"
				.formatted(easelFoot.getX(), easelFoot.getY(), easelFoot.getZ()));

			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockEntity be = level.getBlockEntity(easelFoot);
				if (!(be instanceof EaselBlockEntity easel)) return;
				// Paint on the palette, so the screen has colours in it.
				for (DyeColor dye : new DyeColor[] {DyeColor.LIGHT_BLUE, DyeColor.YELLOW, DyeColor.GREEN,
						DyeColor.BLUE, DyeColor.WHITE, DyeColor.BROWN, DyeColor.RED, DyeColor.BLACK}) {
					easel.addDye(dye);
				}
				easel.loadCanvas(SIZE, painting());
			});
			context.waitTicks(40);

			// The canvas on the easel, with the HUD out of the way.
			context.getInput().pressKey(options -> options.keyToggleGui);
			look(server, x + 0.5, y, z + 0.6, easelFoot.getX() + 0.5, y + 1.6, easelFoot.getZ() + 0.5);
			context.waitTicks(20);
			shoot(context, "easel");

			// And the screen it is painted on: palette down one side, canvas in the middle.
			context.getInput().pressKey(options -> options.keyToggleGui);
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				if (level.getBlockEntity(easelFoot) instanceof EaselBlockEntity easel) {
					PaintScreen.open(connection.getServerPlayer(), level, easelFoot, easel);
				}
			});
			context.waitTicks(40);
			shoot(context, "painting-screen");
		}
	}

	/**
	 * A picture to stand on the easel: sky over hills over water, with a sun in it. Drawn here
	 * rather than clicked in, but drawn in the cells the screen would have filled.
	 */
	private static byte[] painting() {
		int columns = SIZE.columns();
		int rows = SIZE.rows();
		byte[] cells = new byte[columns * rows];
		byte sky = (byte) Paint.color(DyeColor.LIGHT_BLUE);
		byte sun = (byte) Paint.color(DyeColor.YELLOW);
		byte hill = (byte) Paint.color(DyeColor.GREEN);
		byte water = (byte) Paint.color(DyeColor.BLUE);
		byte foam = (byte) Paint.color(DyeColor.WHITE);
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				int horizon = rows * 5 / 8;
				byte cell;
				if (row >= horizon) {
					cell = (row % 5 == 0 && (column + row) % 7 < 2) ? foam : water;
				} else {
					// Two hills, each a shallow arc rising to meet the horizon.
					int firstHill = horizon - (int) (rows * 0.18 * Math.sin(Math.PI * column / columns));
					int secondHill = horizon - (int) (rows * 0.12
						* Math.sin(Math.PI * ((column + columns * 0.4) % columns) / columns));
					cell = (row >= firstHill || row >= secondHill) ? hill : sky;
				}
				int sunRow = rows / 5;
				int sunColumn = columns * 3 / 4;
				if (Math.hypot(column - sunColumn, row - sunRow) < rows * 0.09) cell = sun;
				cells[row * columns + column] = cell;
			}
		}
		return cells;
	}

	/**
	 * Stand the camera at one place and point it at another. The camera's y is the feet, so it
	 * looks from 1.62 above where it stands.
	 */
	private void look(TestServerContext server, double x, double y, double z,
			double atX, double atY, double atZ) {
		double dx = atX - x;
		double dy = atY - (y + 1.62);
		double dz = atZ - z;
		double yaw = -Math.toDegrees(Math.atan2(dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		server.runCommand("tp @a %.2f %.2f %.2f %.1f %.1f".formatted(x, y, z, yaw, pitch));
	}

	private void shoot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name)
			.withSize(WIDTH, HEIGHT)
			.disableCounterPrefix());
	}
}
