package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A kingdom banner, heraldic shield or court noticeboard (B1, B3). Placing one binds it to a kingdom: the kingdom an
 * earlier placement gave the item, otherwise the kingdom of the recognized settlement it stands in, otherwise the
 * placer's own civic residence. Owning or placing one creates no claim, office or control. Using a banner or shield names
 * its kingdom and motto; using a noticeboard reads the kingdom's latest public records.
 */
public final class HeraldicBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public enum Kind { BANNER, SHIELD, NOTICEBOARD }

    private static final VoxelShape POLE = Block.box(6, 0, 6, 10, 16, 10);
    private final Kind kind;

    public HeraldicBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() { return kind; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getAxis().isHorizontal() && kind != Kind.BANNER ? context.getClickedFace() : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (kind == Kind.BANNER) return POLE;
        // A panel two pixels thick against the block behind it.
        return switch (state.getValue(FACING)) {
            case SOUTH -> Block.box(1, 1, 0, 15, 15, 2);
            case WEST -> Block.box(14, 1, 1, 16, 15, 15);
            case EAST -> Block.box(0, 1, 1, 2, 15, 15);
            default -> Block.box(1, 1, 14, 15, 15, 16);
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return level.isClientSide() || type != HeraldicBlocks.HERALDIC.get() ? null
                : (tickLevel, pos, tickState, entity) -> HeraldicBlockEntity.serverTick(tickLevel, pos, tickState, (HeraldicBlockEntity) entity);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HeraldicBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HeraldicBlockEntity heraldic)) return;
        var server = level.getServer();
        if (!HeraldryConfig.enabled()) { heraldic.bind(""); return; }
        // A banner carried from one kingdom to another still stands for the kingdom it was made for.
        String kingdom = heraldic.kingdom();
        if (kingdom.isEmpty()) kingdom = McaKingdomsApi.get(server).getSettlementAt((net.minecraft.server.level.ServerLevel) level, pos)
                .map(s -> s.kingdomId().toString()).orElse("");
        if (kingdom.isEmpty() && placer instanceof ServerPlayer player)
            kingdom = McaKingdomsApi.get(server).getResidence(player).map(s -> s.kingdomId().toString()).orElse("");
        heraldic.bind(kingdom);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof HeraldicBlockEntity heraldic)) return InteractionResult.PASS;
        var server = serverPlayer.getServer();
        if (!heraldic.bound()) {
            player.sendSystemMessage(ActionRegistry.tr("heraldry.mcakingdoms.unbound", "This %s stands for no kingdom: it was placed outside a recognized settlement by someone without a civic residence.", getName()));
            return InteractionResult.CONSUME;
        }
        Component kingdom = Names.kingdomName(server, heraldic.kingdom());
        if (kind == Kind.NOTICEBOARD) {
            HeraldryService.readNoticeboard(serverPlayer, heraldic.kingdom()).forEach(serverPlayer::sendSystemMessage);
            return InteractionResult.CONSUME;
        }
        if (heraldic.historical()) {
            player.sendSystemMessage(ActionRegistry.tr("heraldry.mcakingdoms.historical", "A historical %s of %s: that kingdom is no longer defined on this server.", getName(), heraldic.kingdom()));
            return InteractionResult.CONSUME;
        }
        player.sendSystemMessage(ActionRegistry.tr("heraldry.mcakingdoms.of", "The %s of %s.", getName(), kingdom));
        Heraldry.of(server, heraldic.kingdom()).filter(v -> !v.mottoKey().isEmpty())
                .ifPresent(v -> player.sendSystemMessage(ActionRegistry.tr("heraldry.mcakingdoms.motto", "Motto: %s", Component.translatable(v.mottoKey()))));
        return InteractionResult.CONSUME;
    }
}
