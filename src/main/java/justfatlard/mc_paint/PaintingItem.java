package justfatlard.mc_paint;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** A signed painting, hung on the wall it is used against. */
public class PaintingItem extends Item {
	public PaintingItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Artworks.Signed painting = Artworks.readSigned(context.getItemInHand());
		if (painting == null) return InteractionResult.PASS;
		return Paintings.hang(context, painting);
	}
}
