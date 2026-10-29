package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where charters are sealed (B2). The press is the place, not the authority: sealing happens through the "Seal a
 * charter" task, which an official runs within a few blocks of a press with a blank charter in hand, and the server
 * checks their office and the record before anything is written.
 */
public final class SealPressBlock extends HorizontalDirectionalBlock {
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 14, 14);

    public SealPressBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) {
            Component keybind = Component.keybind("key.mcakingdoms.actions");
            serverPlayer.sendSystemMessage(ActionRegistry.tr("heraldry.mcakingdoms.press", "This seal press is where charters are sealed. Hold a blank charter, press %s and open “Seal a charter”; your office and the record are checked before anything is sealed.", keybind));
        }
        return InteractionResult.CONSUME;
    }

    /** True when a seal press stands within {@code radius} blocks of {@code pos}. */
    public static boolean near(Level level, BlockPos pos, int radius) {
        for (BlockPos at : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius), pos.offset(radius, radius, radius)))
            if (level.isLoaded(at) && level.getBlockState(at).getBlock() instanceof SealPressBlock) return true;
        return false;
    }
}
