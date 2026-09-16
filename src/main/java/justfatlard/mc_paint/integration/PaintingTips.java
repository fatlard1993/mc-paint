package justfatlard.mc_paint.integration;

import justfatlard.block_tip.api.BlockTipApi;
import justfatlard.mc_paint.Artworks;
import justfatlard.mc_paint.Paintings;

/**
 * A hung painting introduced on the block tip by its title and its painter, rather than as the
 * map in an item frame it is underneath. Only loaded when block-tip is here: this class imports
 * its API, and a class that mentions a missing one cannot be loaded.
 */
public final class PaintingTips {
	private PaintingTips() {}

	public static void register() {
		BlockTipApi.nameEntity((entity, player) -> {
			Artworks.Signed painting = Paintings.signedOn(entity);
			return painting == null || painting.title().isBlank() ? null : "\"" + painting.title() + "\"";
		});
		BlockTipApi.describeEntity((entity, player) -> {
			Artworks.Signed painting = Paintings.signedOn(entity);
			if (painting == null || painting.author().isBlank()) return null;
			String by = "by " + painting.author();
			return painting.copy() > 0 ? by + " · copy #" + painting.copy() : by;
		});
	}
}
