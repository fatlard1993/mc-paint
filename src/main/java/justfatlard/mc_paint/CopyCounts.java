package justfatlard.mc_paint;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * How many copies have been made of each painting, so every copy knows which it is. Counted by the
 * painting's first map, which every copy shares with its original, so a copy of a copy is numbered
 * with the rest. Kept on the overworld: one count, whichever dimension a copy is made in.
 */
public final class CopyCounts extends SavedData {
	private static final Codec<CopyCounts> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT)
		.xmap(CopyCounts::new, counts -> counts.counts);
	private static final SavedDataType<CopyCounts> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath(Main.MOD_ID, "copies"), CopyCounts::new, CODEC, DataFixTypes.LEVEL);

	private final Map<String, Integer> counts;

	private CopyCounts() {
		this(Map.of());
	}

	private CopyCounts(Map<String, Integer> saved) {
		counts = new HashMap<>(saved);
	}

	/** Count one more copy of this painting, and say which it is: the first copy is 1. */
	public static int next(MinecraftServer server, Artworks.Signed painting) {
		CopyCounts copies = server.overworld().getDataStorage().computeIfAbsent(TYPE);
		int number = copies.counts.merge(String.valueOf(painting.maps()[0]), 1, Integer::sum);
		copies.setDirty();
		return number;
	}
}
