package justfatlard.mc_paint;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The advancements this mod hands out.
 *
 * <p>The biggest canvas only: sixteen blocks of wall at sixteen cells each is two hundred and
 * fifty-six pixels across, and nobody fills one by accident. Smaller paintings are the mod working;
 * this is somebody deciding to spend an evening on it.
 */
public final class Awards {
	private Awards() {}

	/** Hung a painting on a wall. Marked only at the largest size the easel stretches to. */
	public static void hung(@Nullable Player player, CanvasSize size) {
		if (size != CanvasSize.FOUR_BY_FOUR) return;
		if (player instanceof ServerPlayer painter) award(painter, "hung");
	}

	private static void award(ServerPlayer player, String path) {
		if (player.level().getServer() == null) return;
		AdvancementHolder holder = player.level().getServer().getAdvancements()
			.get(Identifier.fromNamespaceAndPath(Main.MOD_ID, path));
		if (holder == null) return;

		AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
		if (progress.isDone()) return;
		for (String criterion : progress.getRemainingCriteria()) {
			player.getAdvancements().award(holder, criterion);
		}
	}
}
