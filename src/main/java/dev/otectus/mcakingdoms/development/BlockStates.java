package dev.otectus.mcakingdoms.development;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/**
 * Whether the block in the world is the block a journal recorded (AUD-05). The whole state is compared: a player who
 * rotated, waterlogged, lit, opened or otherwise set a block changed it. The only properties ignored are the few a
 * block derives from its neighbours when anything next to it changes, never from a player's choice:
 * <ul>
 *   <li>fence, pane and iron-bar connections ({@code north/east/south/west} of {@link CrossCollisionBlock});</li>
 *   <li>wall sides and its post ({@link WallBlock});</li>
 *   <li>stair corner shape ({@link StairBlock#SHAPE});</li>
 *   <li>a fence gate set into a wall ({@link FenceGateBlock#IN_WALL});</li>
 *   <li>redstone dust connections and power ({@link RedStoneWireBlock});</li>
 *   <li>grass, podzol and mycelium under snow ({@link SnowyDirtBlock#SNOWY});</li>
 *   <li>leaf and scaffolding distance ({@link LeavesBlock#DISTANCE}, {@link ScaffoldingBlock}).</li>
 * </ul>
 */
public final class BlockStates {
    private BlockStates() {
    }

    private static final Set<Property<?>> CROSS = Set.of(CrossCollisionBlock.NORTH, CrossCollisionBlock.EAST, CrossCollisionBlock.SOUTH, CrossCollisionBlock.WEST);
    private static final Set<Property<?>> WALL = Set.of(WallBlock.NORTH_WALL, WallBlock.EAST_WALL, WallBlock.SOUTH_WALL, WallBlock.WEST_WALL, WallBlock.UP);
    private static final Set<Property<?>> WIRE = Set.of(RedStoneWireBlock.NORTH, RedStoneWireBlock.EAST, RedStoneWireBlock.SOUTH, RedStoneWireBlock.WEST, RedStoneWireBlock.POWER);

    /** Whether {@code property} of {@code block} follows its neighbours rather than a placement choice. */
    static boolean neighbourShaped(Block block, Property<?> property) {
        if (block instanceof CrossCollisionBlock) return CROSS.contains(property);
        if (block instanceof WallBlock) return WALL.contains(property);
        if (block instanceof StairBlock) return property == StairBlock.SHAPE;
        if (block instanceof FenceGateBlock) return property == FenceGateBlock.IN_WALL;
        if (block instanceof RedStoneWireBlock) return WIRE.contains(property);
        if (block instanceof SnowyDirtBlock) return property == SnowyDirtBlock.SNOWY;
        if (block instanceof LeavesBlock) return property == LeavesBlock.DISTANCE;
        if (block instanceof ScaffoldingBlock) return property == BlockStateProperties.STABILITY_DISTANCE || property == BlockStateProperties.BOTTOM;
        return false;
    }

    /** The same block with the same value for every property that is not neighbour-shaped. */
    public static boolean same(BlockState actual, BlockState recorded) {
        if (actual.getBlock() != recorded.getBlock()) return false;
        for (Property<?> property : recorded.getProperties()) {
            if (neighbourShaped(recorded.getBlock(), property)) continue;
            if (!actual.hasProperty(property) || !actual.getValue(property).equals(recorded.getValue(property))) return false;
        }
        return true;
    }
}
