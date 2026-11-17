package dev.otectus.mcakingdoms.development;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Blocker;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Cause;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Home;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Placement;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Stage;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
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
 *
 * <p>Blockers are structured (AUD-05): a cause, a position and evidence, worded only when shown. A survey of the rest of
 * a plot exempts exactly the cells this work provably placed (its journal says so and the block is still exactly its
 * block); a matching block anywhere else is someone else's and stays a blocker.
 */
public final class PlotSurvey {
    private PlotSurvey() {
    }

    /** What the server knows about recognized buildings near a plot. Unreadable is not empty: construction waits (AUD-04). */
    public enum BuildingEvidence { ABSENT, READ, UNREADABLE }
    public record Buildings(BuildingEvidence evidence, List<int[]> boxes) { }

    public record Result(List<Blocker> blockers, List<Placement> plan) {
        public Result { blockers = List.copyOf(blockers); plan = List.copyOf(plan); }
        public boolean clear() { return blockers.isEmpty(); }
    }

    /** A first survey: the plan for every block of the template at {@code origin}, and what stands in its way. */
    public static Result survey(ServerLevel level, DevelopmentTemplate template, BlockPos origin, SettlementView settlement, Collection<Home> homes, UUID builder) {
        var blockers = new LinkedHashSet<Blocker>();
        var plan = new ArrayList<Placement>();
        place(level, settlement, blockers);
        var buildings = buildings(level, settlement);
        if (buildings.evidence() == BuildingEvidence.UNREADABLE) blockers.add(new Blocker(Cause.BUILDINGS_UNREADABLE, null, ""));
        Set<BlockPos> cells = new HashSet<>();
        for (var block : template.blocks()) cells.add(origin.offset(block.offset()));
        for (var block : template.blocks()) {
            BlockPos pos = origin.offset(block.offset());
            BlockState now = level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
            cell(level, template, pos, now, block.state(), cells, settlement, homes, buildings, blockers);
            plan.add(new Placement(pos, BlockStateParser.serialize(now), BlockStateParser.serialize(block.state()), Stage.PLANNED, ""));
        }
        for (var placement : plan) margin(level, placement.pos(), Set.of(), blockers);
        if (blockers.isEmpty() && builder != null) protection(level, plan, builder, blockers);
        return new Result(new ArrayList<>(blockers), plan);
    }

    /**
     * The rest of a plot again (resume and repair, AUD-04/05): every cell not provably this work's is surveyed as it is
     * now against its original state; this work's own cells are exempt and are never re-planned over. A cell someone
     * changed, including one now holding a block that only looks like this work's, is a blocker, never adopted.
     */
    public static Result remaining(ServerLevel level, DevelopmentTemplate template, DevelopmentSavedData.Project project, SettlementView settlement,
                                   Collection<Home> homes, UUID builder) {
        var blockers = new LinkedHashSet<Blocker>();
        place(level, settlement, blockers);
        var buildings = buildings(level, settlement);
        if (buildings.evidence() == BuildingEvidence.UNREADABLE) blockers.add(new Blocker(Cause.BUILDINGS_UNREADABLE, null, ""));
        Set<BlockPos> cells = new HashSet<>(); project.journal().forEach(x -> cells.add(x.pos()));
        Set<BlockPos> owned = new HashSet<>();
        var open = new ArrayList<Placement>();
        for (var x : project.journal()) {
            if (owned(level, x)) { owned.add(x.pos()); continue; }
            if (x.stage() == Stage.RESTORED) continue;
            if (level.isOutsideBuildHeight(x.pos()) || !level.hasChunkAt(x.pos())) { blockers.add(new Blocker(Cause.UNLOADED, x.pos(), "")); continue; }
            BlockState now = level.getBlockState(x.pos());
            var before = state(level, x.before());
            var after = state(level, x.after());
            if (after.isEmpty()) { blockers.add(new Blocker(Cause.TEMPLATE_MISSING, x.pos(), x.after())); continue; }
            if (before.isEmpty() || !BlockStates.same(now, before.get())) {
                // Exactly this work's block, but the journal holds no proof this work placed it: someone else did.
                blockers.add(BlockStates.same(now, after.get()) ? new Blocker(Cause.FOREIGN_MATCH, x.pos(), BlockStateParser.serialize(now))
                        : new Blocker(Cause.CHANGED, x.pos(), BlockStateParser.serialize(now)));
                continue;
            }
            cell(level, template, x.pos(), now, after.get(), cells, settlement, homes, buildings, blockers);
            open.add(x);
        }
        for (var x : open) margin(level, x.pos(), owned, blockers);
        if (blockers.isEmpty() && builder != null) protection(level, open, builder, blockers);
        return new Result(new ArrayList<>(blockers), project.journal());
    }

    /**
     * Whether the cell provably holds this work's block: its journal says the work placed it, or announced it as in flight
     * (AUD-03), and it is still exactly that block. A matching block anywhere else is not this work's.
     */
    static boolean owned(ServerLevel level, Placement placement) {
        if (!(placement.placed() || placement.stage() == Stage.INTENT) || level.isOutsideBuildHeight(placement.pos()) || !level.hasChunkAt(placement.pos())) return false;
        var after = state(level, placement.after());
        return after.isPresent() && BlockStates.same(level.getBlockState(placement.pos()), after.get());
    }

    /** Whether the block now at a journaled position is still exactly the block the survey saw. */
    static boolean unchanged(ServerLevel level, Placement placement) {
        var before = state(level, placement.before());
        return before.isPresent() && BlockStates.same(level.getBlockState(placement.pos()), before.get());
    }

    /** Whether the block now at a journaled position is exactly the block this work places there. */
    static boolean matchesAfter(ServerLevel level, Placement placement) {
        var after = state(level, placement.after());
        return after.isPresent() && BlockStates.same(level.getBlockState(placement.pos()), after.get());
    }

    private static void place(ServerLevel level, SettlementView settlement, Set<Blocker> blockers) {
        String dimension = level.dimension().location().toString();
        if (!DevelopmentMode.dimensions().contains(dimension)) blockers.add(new Blocker(Cause.DIMENSION, null, dimension));
        if (!settlement.dimension().equals(level.dimension())) blockers.add(new Blocker(Cause.OTHER_DIMENSION, null, dimension));
    }

    /** One cell's own conditions; also re-checked for every block just before it is placed (AUD-04). */
    static void cell(ServerLevel level, DevelopmentTemplate template, BlockPos pos, BlockState now, BlockState after, Set<BlockPos> cells, SettlementView settlement,
                     Collection<Home> homes, Buildings buildings, Set<Blocker> blockers) {
        String dimension = level.dimension().location().toString();
        if (!level.getWorldBorder().isWithinBounds(pos) || level.isOutsideBuildHeight(pos)) { blockers.add(new Blocker(Cause.BORDER, pos, "")); return; }
        if (!level.hasChunkAt(pos)) { blockers.add(new Blocker(Cause.UNLOADED, pos, "")); return; }
        if (!settlement.bounds().contains(pos)) blockers.add(new Blocker(Cause.OUTSIDE, pos, settlement.displayName()));
        for (var home : homes)
            if (home.dimension().equals(dimension) && home.pos().closerThan(pos, home.radius() + 0.5)) { blockers.add(new Blocker(Cause.HOME, pos, "")); break; }
        for (var b : buildings.boxes())
            if (pos.getX() >= b[0] - 1 && pos.getX() <= b[3] + 1 && pos.getY() >= b[1] - 1 && pos.getY() <= b[4] + 1 && pos.getZ() >= b[2] - 1 && pos.getZ() <= b[5] + 1) {
                blockers.add(new Blocker(Cause.BUILDING, pos, "")); break;
            }
        if (template.placement() == DevelopmentTemplate.Placement.GROUND) {
            if (!now.is(BlockTags.DIRT) || !level.getBlockState(pos.above()).isAir()) blockers.add(new Blocker(Cause.GROUND, pos, BlockStateParser.serialize(now)));
        } else if (!now.isAir() && !(now.canBeReplaced() && now.getFluidState().isEmpty() && !now.hasBlockEntity())) {
            blockers.add(new Blocker(Cause.OCCUPIED, pos, BlockStateParser.serialize(now)));
        } else if (!after.canSurvive(level, pos) && !cells.contains(pos.below())) {
            blockers.add(new Blocker(Cause.SUPPORT, pos, ""));
        }
    }

    /** The plot block and its one-block margin: storage, homes, fields, doors, redstone and points of interest stay untouched. */
    static void margin(ServerLevel level, BlockPos pos, Set<BlockPos> exempt, Set<Blocker> blockers) {
        // Nothing here may load a chunk: a margin or point-of-interest range reaching into an unloaded chunk refuses the plot instead.
        if (!level.hasChunksAt(pos.offset(-2, 0, -2), pos.offset(2, 0, 2))) { blockers.add(new Blocker(Cause.UNLOADED, pos, "")); return; }
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            if (exempt.contains(near)) continue;
            BlockState state = level.getBlockState(near);
            if (state.hasBlockEntity()) blockers.add(new Blocker(Cause.BLOCK_ENTITY, pos, BlockStateParser.serialize(state)));
            else if (state.is(BlockTags.BEDS)) blockers.add(new Blocker(Cause.BED, pos, ""));
            else if (state.getBlock() instanceof FarmBlock) blockers.add(new Blocker(Cause.FARMLAND, pos, ""));
            else if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) blockers.add(new Blocker(Cause.DOOR, pos, ""));
            else if (state.isSignalSource() || state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.REPEATER) || state.is(Blocks.COMPARATOR) || state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                blockers.add(new Blocker(Cause.REDSTONE, pos, ""));
        }
        if (level.getPoiManager().getInRange(type -> true, pos, 2, PoiManager.Occupancy.ANY).findAny().isPresent())
            blockers.add(new Blocker(Cause.POI, pos, ""));
    }

    /** Each placement offered to every protection and claim mod as the approving official's block placement. */
    private static void protection(ServerLevel level, List<Placement> cells, UUID builder, Set<Blocker> blockers) {
        var official = FakePlayerFactory.get(level, new GameProfile(builder, "[MCA: Kingdoms]"));
        for (var placement : cells) {
            if (protectedFrom(level, placement.pos(), official)) { blockers.add(new Blocker(Cause.PROTECTED, placement.pos(), "")); break; }
        }
    }

    static boolean protectedFrom(ServerLevel level, BlockPos pos, net.minecraft.world.entity.Entity official) {
        var event = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, pos), level.getBlockState(pos.below()), official);
        return MinecraftForge.EVENT_BUS.post(event);
    }

    /**
     * Recognized building boxes of the settlement (min x, y, z, max x, y, z). {@code ABSENT} when no installed mod reports
     * buildings (nothing to keep clear of); {@code UNREADABLE} when one is installed but its answer failed, which is not
     * proof of an empty plot (AUD-04).
     */
    static Buildings buildings(ServerLevel level, SettlementView settlement) {
        try {
            var townstead = KingdomsTownsteadApi.get(level.getServer());
            if (!townstead.capabilities().contains(TownsteadCapability.READ_BUILDING)) return new Buildings(BuildingEvidence.ABSENT, List.of());
            return new Buildings(BuildingEvidence.READ, townstead.buildings(level, settlement.id()).stream().limit(256)
                    .map(b -> new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}).toList());
        } catch (RuntimeException | LinkageError unavailable) {
            return new Buildings(BuildingEvidence.UNREADABLE, List.of());
        }
    }

    static Optional<BlockState> state(ServerLevel level, String text) {
        try { return Optional.of(BlockStateParser.parseForBlock(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), text, false).blockState()); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { return Optional.empty(); }
    }
}
