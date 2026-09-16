package justfatlard.mc_paint;

import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;


/**
 * A two-block studio easel, facing the painter: any of eight ways, as an armor stand stands, so a
 * {@code facing} and a {@code diagonal} a quarter-turn's half clockwise of it. Everything it
 * holds lives in the lower half's block entity, and either half answers a click.
 *
 * <ul>
 *   <li>Dye: onto the palette, one per click; held, one after another.</li>
 *   <li>Canvas: onto an empty easel, at a size chosen from a screen.</li>
 *   <li>Unfinished painting: onto an empty easel, to carry on with.</li>
 *   <li>Empty hand: paint.</li>
 * </ul>
 */
public class EaselBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
	public static final BooleanProperty DIAGONAL = BooleanProperty.create("diagonal");

	/** The easel's footprint is square and centred so one shape serves all eight ways it can face. */
	private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 16, 14);

	public EaselBlock(Properties properties) {
		super(properties);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(DIAGONAL, false)
			.setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, DIAGONAL, HALF);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	/** The way the easel's front faces, as an entity's yaw: 0 south, 90 west, in eighths of a turn. */
	public static float yaw(BlockState state) {
		return state.getValue(FACING).toYRot() + (state.getValue(DIAGONAL) ? 45 : 0);
	}

	// --- Two halves, placed and broken together, the way a door is ---

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		Level level = context.getLevel();
		if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) return null;
		// Toward the player, to the nearest eighth of a turn
		int eighth = Math.floorMod(Math.round((context.getRotation() + 180) / 45), 8);
		return defaultBlockState()
			.setValue(FACING, Direction.fromYRot(eighth / 2 * 90))
			.setValue(DIAGONAL, eighth % 2 == 1);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
		level.setBlockAndUpdate(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER));
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) return level.getBlockState(pos.below()).is(this);
		BlockPos below = pos.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
			Direction toward, BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
		DoubleBlockHalf half = state.getValue(HALF);
		boolean partner = toward == (half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
		if (partner && !(neighbour.is(this) && neighbour.getValue(HALF) != half)) return Blocks.AIR.defaultBlockState();
		if (half == DoubleBlockHalf.LOWER && toward == Direction.DOWN && !state.canSurvive(level, pos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, ticks, pos, toward, neighbourPos, neighbour, random);
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		// Broken from the top by a player who drops nothing, the bottom must not drop the easel either
		if (!level.isClientSide() && state.getValue(HALF) == DoubleBlockHalf.UPPER && player.preventsBlockDrops()) {
			BlockPos foot = pos.below();
			BlockState footState = level.getBlockState(foot);
			if (footState.is(this)) {
				level.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
				level.levelEvent(player, 2001, foot, Block.getId(footState));
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new EaselBlockEntity(pos, state) : null;
	}

	public static BlockPos footOf(BlockPos pos, BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
	}

	// --- Use ---

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		boolean handled = Paint.dyeOf(stack) != null || stack.is(Main.CANVAS)
			|| stack.is(Main.UNFINISHED_PAINTING) || stack.is(Main.PAINTING);
		if (!handled) return InteractionResult.TRY_WITH_EMPTY_HAND;
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer painter)) return InteractionResult.SUCCESS;
		BlockPos foot = footOf(pos, state);
		if (!(server.getBlockEntity(foot) instanceof EaselBlockEntity easel)) return InteractionResult.PASS;

		DyeColor dye = Paint.dyeOf(stack);
		if (dye != null) {
			int color = Paint.color(dye);
			EaselBlockEntity.Added added = easel.addDye(dye);
			if (added != EaselBlockEntity.Added.ADDED) {
				painter.sendOverlayMessage(added == EaselBlockEntity.Added.FULL
					? Component.translatableWithFallback("mc-paint.palette.full",
						"No room for more %s: the palette holds %s dyes of each colour.", Paint.name(color), Paint.MAX_DYES)
					: Component.translatableWithFallback("mc-paint.palette.no_room",
						"The palette holds %s colours: paint one out before adding another.", Paint.MAX_COLORS));
				return InteractionResult.CONSUME;
			}
			stack.consume(1, player);
			server.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
			painter.sendOverlayMessage(Component.translatableWithFallback("mc-paint.palette.added", "%s on the palette: %s of %s dyes",
				Paint.name(color), Paint.dyeCount(easel.paint(color)), Paint.MAX_DYES));
			PaintScreen.paletteChanged(server, foot);
			return InteractionResult.SUCCESS_SERVER;
		}

		if (stack.is(Main.PAINTING)) {
			copy(painter, server, easel, Artworks.readSigned(stack));
			return InteractionResult.CONSUME;
		}
		if (easel.hasCanvas()) {
			painter.sendOverlayMessage(Component.translatableWithFallback("mc-paint.easel.occupied",
				"This easel already holds a canvas."));
			return InteractionResult.CONSUME;
		}

		Artworks.Unfinished unfinished = Artworks.readUnfinished(stack);
		if (unfinished != null) {
			easel.loadCanvas(unfinished.size(), unfinished.pixels());
			stack.consume(1, player);
			server.playSound(null, pos, SoundEvents.PAINTING_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
			return InteractionResult.SUCCESS_SERVER;
		}
		if (stack.is(Main.CANVAS)) {
			if (!needsPandorical(painter)) SizeScreen.open(painter, server, foot, hand);
			return InteractionResult.SUCCESS_SERVER;
		}
		return InteractionResult.PASS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer painter)) return InteractionResult.SUCCESS;
		BlockPos foot = footOf(pos, state);
		if (!(server.getBlockEntity(foot) instanceof EaselBlockEntity easel)) return InteractionResult.PASS;
		if (!easel.hasCanvas()) {
			painter.sendOverlayMessage(Component.translatableWithFallback("mc-paint.easel.empty",
				"Put a canvas on the easel first, and some dye on its palette."));
			return InteractionResult.SUCCESS_SERVER;
		}
		if (!needsPandorical(painter)) PaintScreen.open(painter, server, foot, easel);
		return InteractionResult.SUCCESS_SERVER;
	}

	/**
	 * A signed painting held to a blank canvas is copied onto it: the canvas is spent, and the copy
	 * comes off the easel at the painting's size, sharing its maps and numbered among its copies.
	 * The painting held is kept.
	 */
	private static void copy(ServerPlayer player, ServerLevel level, EaselBlockEntity easel, Artworks.Signed original) {
		if (original == null) return;
		if (!easel.hasCanvas()) {
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.copy.no_canvas",
				"Put a blank canvas on the easel to copy this painting onto."));
		} else if (!easel.isBlank()) {
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.copy.not_blank",
				"A copy needs a blank canvas."));
		} else {
			easel.clearCanvas();
			int number = CopyCounts.next(level.getServer(), original);
			easel.popOff(level, Artworks.signed(original.copied(number)));
			player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.copy.made",
				"Copy #%s of \"%s\".", number, original.title()));
		}
	}

	/** Tell a player without the Pandorical client why nothing opened; true when they lack it. */
	private static boolean needsPandorical(ServerPlayer player) {
		if (PandoricalApi.hasCapability(player, "screens")) return false;
		player.sendOverlayMessage(Component.translatableWithFallback("mc-paint.needs_pandorical",
			"Painting needs the Pandorical mod on your client."));
		return true;
	}
}
