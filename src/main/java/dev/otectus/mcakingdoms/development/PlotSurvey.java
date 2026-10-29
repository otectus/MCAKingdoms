package dev.otectus.mcakingdoms.development;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Home;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Placement;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;

import java.util.*;

/**
 * Whether a work may be built on a plot (D3), and the exact plan if it may. Every block must be in a loaded chunk of an
 * allowed dimension, inside the world border and the settlement, away from protected homes and recognized buildings,
 * on air or a replaceable plant (or, for ground works, natural ground with air above), and buildable by the approving
 * official under every protection or claim mod (each placement is offered to them as that official's block placement).
 * The plot and a one-block margin may hold no block entity, bed, farmland, door, redstone component or point of
 * interest. A screenshot or a matching blueprint is not consent: only this survey is.
 */
public final class PlotSurvey {
    private static final String B = "development.mcakingdoms.blocker.";

    private PlotSurvey() {
    }

    public record Result(List<String> blockers, List<Placement> plan) {
        public Result { blockers = List.copyOf(blockers); plan = List.copyOf(plan); }
        public boolean clear() { return blockers.isEmpty(); }
    }

    public static Result survey(ServerLevel level, DevelopmentTemplate template, BlockPos origin, SettlementView settlement, Collection<Home> homes, UUID builder) {
        var blockers = new LinkedHashSet<String>();
        var plan = new ArrayList<Placement>();
        String dimension = level.dimension().location().toString();
        if (!DevelopmentMode.dimensions().contains(dimension)) blockers.add(Keyed.text(B + "dimension", "Construction is not allowed in %s on this server.", dimension));
        if (!settlement.dimension().equals(level.dimension())) blockers.add(Keyed.text(B + "other_dimension", "The plot is not in the settlement's dimension."));
        var buildings = buildings(level, settlement);
        for (var block : template.blocks()) {
            BlockPos pos = origin.offset(block.offset());
            if (!level.getWorldBorder().isWithinBounds(pos) || level.isOutsideBuildHeight(pos)) { blockers.add(Keyed.text(B + "border", "Part of the plot is outside the world.")); continue; }
            if (!level.hasChunkAt(pos)) { blockers.add(Keyed.text(B + "unloaded", "Part of the plot is not loaded; stand near it and survey again.")); continue; }
            if (!settlement.bounds().contains(pos)) blockers.add(Keyed.text(B + "outside", "Part of the plot is outside %s.", settlement.displayName()));
            for (var home : homes)
                if (home.dimension().equals(dimension) && home.pos().closerThan(pos, home.radius() + 0.5))
                    blockers.add(Keyed.text(B + "home", "The plot is within a player's protected home."));
            for (var b : buildings)
                if (pos.getX() >= b[0] - 1 && pos.getX() <= b[3] + 1 && pos.getY() >= b[1] - 1 && pos.getY() <= b[4] + 1 && pos.getZ() >= b[2] - 1 && pos.getZ() <= b[5] + 1)
                    blockers.add(Keyed.text(B + "building", "The plot overlaps a recognized village building."));
            BlockState now = level.getBlockState(pos);
            // The template's own state: its shape (fence connections, support) settles when it is placed among its neighbours.
            BlockState after = block.state();
            if (template.placement() == DevelopmentTemplate.Placement.GROUND) {
                if (!now.is(BlockTags.DIRT) || !level.getBlockState(pos.above()).isAir()) blockers.add(Keyed.text(B + "ground", "A ground work needs natural ground with air above it at %s.", pos.toShortString()));
            } else if (!now.isAir() && !(now.canBeReplaced() && now.getFluidState().isEmpty() && !now.hasBlockEntity())) {
                blockers.add(Keyed.text(B + "occupied", "%s is occupied.", pos.toShortString()));
            } else if (!after.canSurvive(level, pos) && template.blocks().stream().noneMatch(other -> origin.offset(other.offset()).equals(pos.below()))) {
                blockers.add(Keyed.text(B + "support", "%s has nothing to stand on.", pos.toShortString()));
            }
            plan.add(new Placement(pos, BlockStateParser.serialize(now), BlockStateParser.serialize(after), false));
        }
        for (var placement : plan) margin(level, placement.pos(), blockers);
        if (blockers.isEmpty() && builder != null) {
            var official = FakePlayerFactory.get(level, new GameProfile(builder, "[MCA: Kingdoms]"));
            for (var placement : plan) {
                var event = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, placement.pos()), level.getBlockState(placement.pos().below()), official);
                if (MinecraftForge.EVENT_BUS.post(event)) { blockers.add(Keyed.text(B + "protected", "The land at %s is protected from the approving official's building.", placement.pos().toShortString())); break; }
            }
        }
        return new Result(new ArrayList<>(blockers), plan);
    }

    /** The plot block and its one-block margin: storage, homes, fields, doors, redstone and points of interest stay untouched. */
    private static void margin(ServerLevel level, BlockPos pos, Set<String> blockers) {
        // Nothing here may load a chunk: a margin or point-of-interest range reaching into an unloaded chunk refuses the plot instead.
        if (!level.hasChunksAt(pos.offset(-2, 0, -2), pos.offset(2, 0, 2))) {
            blockers.add(Keyed.text(B + "unloaded", "Part of the plot is not loaded; stand near it and survey again."));
            return;
        }
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            BlockState state = level.getBlockState(near);
            if (state.hasBlockEntity()) blockers.add(Keyed.text(B + "block_entity", "Storage or machinery is next to %s.", pos.toShortString()));
            else if (state.is(BlockTags.BEDS)) blockers.add(Keyed.text(B + "bed", "A bed is next to %s.", pos.toShortString()));
            else if (state.getBlock() instanceof FarmBlock) blockers.add(Keyed.text(B + "farmland", "Farmland is next to %s.", pos.toShortString()));
            else if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) blockers.add(Keyed.text(B + "door", "A door or gate is next to %s.", pos.toShortString()));
            else if (state.isSignalSource() || state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.REPEATER) || state.is(Blocks.COMPARATOR) || state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                blockers.add(Keyed.text(B + "redstone", "A redstone component is next to %s.", pos.toShortString()));
        }
        if (level.getPoiManager().getInRange(type -> true, pos, 2, PoiManager.Occupancy.ANY).findAny().isPresent())
            blockers.add(Keyed.text(B + "poi", "A workstation, bed or bell is within 2 blocks of %s.", pos.toShortString()));
    }

    /** Recognized building boxes of the settlement (min x, y, z, max x, y, z), when buildings can be read; none otherwise. */
    private static List<int[]> buildings(ServerLevel level, SettlementView settlement) {
        try {
            var townstead = KingdomsTownsteadApi.get(level.getServer());
            if (!townstead.capabilities().contains(TownsteadCapability.READ_BUILDING)) return List.of();
            return townstead.buildings(level, settlement.id()).stream().limit(256)
                    .map(b -> new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}).toList();
        } catch (RuntimeException unavailable) { return List.of(); }
    }

    /** Whether the block now at a journaled position is still the block the survey saw (so placing there replaces nothing new). */
    static boolean unchanged(ServerLevel level, Placement placement) {
        return sameBlock(level.getBlockState(placement.pos()), placement.before());
    }

    /** Whether the block now at a journaled position is the block this work places there (its shape may have joined its neighbours). */
    static boolean ours(ServerLevel level, Placement placement) {
        return sameBlock(level.getBlockState(placement.pos()), placement.after());
    }

    private static boolean sameBlock(BlockState state, String text) {
        int properties = text.indexOf('[');
        String id = properties < 0 ? text : text.substring(0, properties);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(id);
    }

    static Optional<BlockState> state(ServerLevel level, String text) {
        try { return Optional.of(BlockStateParser.parseForBlock(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), text, false).blockState()); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { return Optional.empty(); }
    }
}
