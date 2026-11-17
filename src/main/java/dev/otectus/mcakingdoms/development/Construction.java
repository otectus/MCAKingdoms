package dev.otectus.mcakingdoms.development;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.persistence.FaultPoints;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;

import java.util.*;

/**
 * Construction on a surveyed plot as a journal the world can be checked against (AUD-03/04/05).
 *
 * <p>Each pass first places the cells it announced in the previous pass, then announces the next ones. An announcement
 * ({@code INTENT}) is on disk before the world is touched; a placement is read back and counts ({@code APPLIED}) only
 * when the world holds exactly the planned block; two world saves that show it (the second comes after the chunk writer
 * finished the first), or a later server run that loads it from disk, make it {@code VERIFIED}. A work completes once, when every cell is verified. After a crash, a cell the previous
 * run announced or placed is settled from what the world loaded: the planned block means it was placed, the original
 * block means it was not, anything else is someone's change and stops the work. A verified cell is never placed again,
 * even when its block is gone: a player may have removed it on purpose.
 *
 * <p>Before every placement the work's current policy is checked (mode, settlement consent, the approver's authority in
 * the settlement's current kingdom, the dimension) and the cell itself (bounds, protected homes, recognized buildings,
 * the block there, native protection), so an opt-out, a protected home or a lowered mode stops the next block, not a
 * later one.
 */
final class Construction {
    private static final String P = DevelopmentService.P;
    /** Cells settled from a previous run in one pass, server-wide. */
    static final int RECOVERY_BUDGET = 64;

    private final DevelopmentService service;

    Construction(DevelopmentService service) {
        this.service = service;
    }

    private static Project stop(Project p, State state, Blocker blocker, List<Placement> journal, long now, long revision) {
        return p.journal(journal).blocked(state, DevelopmentText.blocker(blocker), List.of(blocker), now, revision);
    }

    /** The first reason this work may not place a block now, independent of any one cell (AUD-04); empty when it may. */
    Optional<Blocker> policy(Project p, Records next, DevelopmentMode mode) {
        if (!mode.atLeast(DevelopmentMode.MANAGED_GROWTH)) return Optional.of(new Blocker(Cause.MODE, null, mode.name()));
        var settlement = service.settlement(p.settlement());
        if (settlement.isEmpty()) return Optional.of(new Blocker(Cause.AUTHORITY, null, p.kingdom()));
        if (!next.economy(p.settlement()).optedIn()) return Optional.of(new Blocker(Cause.CONSENT, null, ""));
        if (!service.approverValid(p)) return Optional.of(new Blocker(Cause.AUTHORITY, null, dev.otectus.mcakingdoms.interaction.Names.kingdomName(service.server(), service.authority(p)).getString()));
        if (!DevelopmentMode.dimensions().contains(p.dimension())) return Optional.of(new Blocker(Cause.DIMENSION, null, p.dimension()));
        return Optional.empty();
    }

    /**
     * Settles cells a previous server run announced, placed or restored but did not see reach disk, from the world as it
     * was loaded (AUD-03), in loaded chunks only. Exactly this work's block means it was placed (verified now); the original
     * block means it was not (planned again); anything else is someone's change and stops the work. A restore is done
     * when the original block is there, and to do again when this work's block came back. At most {@code budget[0]}
     * cells are looked at; the budget is reduced by those examined.
     */
    Project recover(Project p, ServerLevel level, long now, Records next, int[] budget) {
        String session = DevelopmentService.session();
        if (p.journal().stream().noneMatch(x -> !x.session().equals(session) && x.stage().unsettled())) return p;
        var journal = new ArrayList<>(p.journal());
        Blocker changed = null;
        for (int i = 0; i < journal.size() && budget[0] > 0; i++) {
            var x = journal.get(i);
            if (x.session().equals(session) || !x.stage().unsettled() || level.isOutsideBuildHeight(x.pos()) || !level.hasChunkAt(x.pos())) continue;
            budget[0]--;
            if (x.stage().restoring()) {
                journal.set(i, x.at(PlotSurvey.matchesAfter(level, x) ? Stage.VERIFIED : Stage.REVERTED, session));
                continue;
            }
            if (PlotSurvey.matchesAfter(level, x)) journal.set(i, x.at(Stage.VERIFIED, session));
            else if (PlotSurvey.unchanged(level, x)) journal.set(i, x.at(Stage.PLANNED, session));
            else {
                journal.set(i, x.at(Stage.PLANNED, session));
                if (changed == null) changed = new Blocker(Cause.CHANGED, x.pos(), net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(x.pos())));
            }
        }
        if (changed != null && p.state() != State.ROLLING_BACK && p.state().open()) return stop(p, State.REPAIR_NEEDED, changed, journal, now, next.revision);
        return p.journal(journal);
    }

    /** One pass of a READY or BUILDING work (AUD-03/04): place the announced cells, announce the next. */
    Project build(Project p, DevelopmentTemplate template, long now, Records next, int budget, List<Runnable> notices, int[] placedOut) {
        var level = service.level(p).orElse(null);
        if (level == null) return stop(p, State.BLOCKED, new Blocker(Cause.LEVEL_MISSING, null, p.dimension()), p.journal(), now, next.revision);
        SettlementView settlement = service.settlement(p.settlement()).orElse(null);
        if (settlement == null) return stop(p, State.PAUSED, new Blocker(Cause.AUTHORITY, null, p.kingdom()), p.journal(), now, next.revision);
        var buildings = PlotSurvey.buildings(level, settlement);
        if (buildings.evidence() == PlotSurvey.BuildingEvidence.UNREADABLE)
            return stop(p, State.PAUSED, new Blocker(Cause.BUILDINGS_UNREADABLE, null, ""), p.journal(), now, next.revision);
        String session = DevelopmentService.session();
        var journal = new ArrayList<>(p.journal());
        var builder = FakePlayerFactory.get(level, new GameProfile(p.approver() == null ? p.proposer() : p.approver(), "[MCA: Kingdoms]"));
        Set<BlockPos> cells = new HashSet<>(); journal.forEach(x -> cells.add(x.pos()));
        Set<BlockPos> owned = new HashSet<>(); journal.stream().filter(Placement::placed).forEach(x -> owned.add(x.pos()));
        int placed = 0;
        // 1. Place what the previous pass announced, each cell checked again as it is now.
        for (int i = 0; i < journal.size() && placed < budget; i++) {
            var x = journal.get(i);
            if (x.stage() != Stage.INTENT) continue;
            // In-flight evidence (AUD-03/05): this work announced the cell and the world holds exactly its block, so an
            // earlier pass placed it and its record did not reach disk. It is this work's, and counts once.
            if (!level.isOutsideBuildHeight(x.pos()) && level.hasChunkAt(x.pos()) && PlotSurvey.matchesAfter(level, x)) {
                journal.set(i, x.at(Stage.APPLIED, session));
                owned.add(x.pos());
                continue;
            }
            var problem = check(level, template, x, cells, owned, settlement, next, buildings, builder);
            if (problem.isPresent()) {
                journal.set(i, x.at(Stage.PLANNED, session));
                if (problem.get().cause() == Cause.UNLOADED) return p.journal(journal, State.BUILDING, P + "reason.waiting_chunk", now, next.revision);
                if (problem.get().cause() == Cause.OCCUPIED && problem.get().evidence().equals("entity"))
                    return p.journal(journal, State.BUILDING, P + "reason.occupied", now, next.revision);
                return stop(p, severity(problem.get()), problem.get(), journal, now, next.revision);
            }
            var after = PlotSurvey.state(level, x.after()).orElse(null);
            if (after == null) return stop(p, State.BLOCKED, new Blocker(Cause.TEMPLATE_MISSING, x.pos(), x.after()), journal, now, next.revision);
            BlockState shaped = Block.updateFromNeighbourShapes(after, level, x.pos());
            if (shaped.isAir() || !shaped.canSurvive(level, x.pos())) {
                journal.set(i, x.at(Stage.PLANNED, session));
                return stop(p, State.REPAIR_NEEDED, new Blocker(Cause.UNSUPPORTED, x.pos(), ""), journal, now, next.revision);
            }
            FaultPoints.reach("development.place");
            level.setBlock(x.pos(), shaped, Block.UPDATE_ALL);
            // The world's answer, not setBlock's: only exactly the planned block counts as built (AUD-03).
            if (!PlotSurvey.matchesAfter(level, x)) {
                boolean untouched = PlotSurvey.unchanged(level, x);
                journal.set(i, x.at(Stage.PLANNED, session));
                return stop(p, State.REPAIR_NEEDED, new Blocker(untouched ? Cause.REFUSED : Cause.CHANGED, x.pos(), ""), journal, now, next.revision);
            }
            journal.set(i, x.at(Stage.APPLIED, session));
            owned.add(x.pos());
            placed++;
            placedOut[0]++;
            DevelopmentMetrics.placed();
        }
        // 2. Announce the next cells; they are placed next pass, after this record is on disk.
        int announce = DevelopmentMode.blocksPerPass();
        for (int i = 0; i < journal.size() && announce > 0; i++) {
            var x = journal.get(i);
            if (x.stage() == Stage.INTENT) { announce--; continue; }
            if (x.stage() != Stage.PLANNED) continue;
            var problem = check(level, template, x, cells, owned, settlement, next, buildings, builder);
            if (problem.isPresent()) {
                if (problem.get().cause() == Cause.UNLOADED) return p.journal(journal, State.BUILDING, P + "reason.waiting_chunk", now, next.revision);
                if (problem.get().cause() == Cause.OCCUPIED && problem.get().evidence().equals("entity"))
                    return p.journal(journal, State.BUILDING, P + "reason.occupied", now, next.revision);
                return stop(p, severity(problem.get()), problem.get(), journal, now, next.revision);
            }
            journal.set(i, x.at(Stage.INTENT, session));
            announce--;
        }
        if (journal.stream().allMatch(x -> x.stage() == Stage.VERIFIED)) return service.complete(p.journal(journal), template, now, next, notices);
        if (journal.stream().allMatch(Placement::placed))
            return p.journal(journal, State.BUILDING, P + "reason.awaiting_save", now, next.revision);
        return p.journal(journal, State.BUILDING, P + "reason.building", now, next.revision);
    }

    /** A cell change that needs an official's repair, versus a plot condition that blocks until it clears. */
    private static State severity(Blocker blocker) {
        return switch (blocker.cause()) {
            case CHANGED, FOREIGN_MATCH, PROTECTED, UNSUPPORTED, REFUSED -> State.REPAIR_NEEDED;
            case HOME, BUILDING, OUTSIDE, BORDER, DIMENSION, OTHER_DIMENSION -> State.BLOCKED;
            default -> State.BLOCKED;
        };
    }

    /** One cell as it is now, just before it is announced or placed: the world there, the plot's conditions and native protection. */
    private Optional<Blocker> check(ServerLevel level, DevelopmentTemplate template, Placement x, Set<BlockPos> cells, Set<BlockPos> owned, SettlementView settlement,
                                    Records next, PlotSurvey.Buildings buildings, net.minecraft.world.entity.Entity builder) {
        // The block, and every neighbour its shape and updates read, must already be loaded: construction never loads a chunk.
        if (level.isOutsideBuildHeight(x.pos()) || !level.hasChunksAt(x.pos().offset(-2, 0, -2), x.pos().offset(2, 0, 2))) return Optional.of(new Blocker(Cause.UNLOADED, x.pos(), ""));
        // Never build into someone standing there: wait for them to step away.
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(x.pos())).isEmpty()) return Optional.of(new Blocker(Cause.OCCUPIED, x.pos(), "entity"));
        if (!PlotSurvey.unchanged(level, x)) {
            String now = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(x.pos()));
            return Optional.of(PlotSurvey.matchesAfter(level, x) ? new Blocker(Cause.FOREIGN_MATCH, x.pos(), now) : new Blocker(Cause.CHANGED, x.pos(), now));
        }
        var after = PlotSurvey.state(level, x.after());
        if (after.isEmpty()) return Optional.of(new Blocker(Cause.TEMPLATE_MISSING, x.pos(), x.after()));
        var found = new LinkedHashSet<Blocker>();
        PlotSurvey.cell(level, template, x.pos(), level.getBlockState(x.pos()), after.get(), cells, settlement, next.homes.values(), buildings, found);
        PlotSurvey.margin(level, x.pos(), owned, found);
        if (!found.isEmpty()) return Optional.of(found.iterator().next());
        if (PlotSurvey.protectedFrom(level, x.pos(), builder)) return Optional.of(new Blocker(Cause.PROTECTED, x.pos(), ""));
        return Optional.empty();
    }

    /**
     * A world save of {@code level} was handed to the chunk writer (AUD-03). Cells this run placed or restored there move
     * one step toward verified: the first save that shows them makes them saved, the next verified, since the writer has
     * long finished the earlier save by then. A placed cell that no longer holds this work's block was changed by someone
     * after it was placed: the work stops for an official instead of placing it again. Cells in unloaded chunks wait.
     */
    Optional<Project> saved(Project p, ServerLevel level, long now, long revision) {
        if (!p.dimension().equals(level.dimension().location().toString())) return Optional.empty();
        String session = DevelopmentService.session();
        if (p.journal().stream().noneMatch(x -> x.session().equals(session) && (x.stage() == Stage.APPLIED || x.stage() == Stage.SAVED || x.stage().restoring()))) return Optional.empty();
        var journal = new ArrayList<>(p.journal());
        Blocker changed = null;
        for (int i = 0; i < journal.size(); i++) {
            var x = journal.get(i);
            if (!x.session().equals(session) || !level.hasChunkAt(x.pos())) continue;
            switch (x.stage()) {
                case APPLIED, SAVED -> {
                    if (PlotSurvey.matchesAfter(level, x)) journal.set(i, x.at(x.stage() == Stage.APPLIED ? Stage.SAVED : Stage.VERIFIED, session));
                    else if (p.state().open() && p.state() != State.ROLLING_BACK) {
                        journal.set(i, x.at(Stage.PLANNED, session));
                        if (changed == null) changed = new Blocker(Cause.CHANGED, x.pos(), net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(x.pos())));
                    }
                }
                case RESTORED -> journal.set(i, x.at(PlotSurvey.unchanged(level, x) ? Stage.RESTORE_SAVED : Stage.REVERTED, session));
                case RESTORE_SAVED -> journal.set(i, x.at(Stage.REVERTED, session));
                default -> { }
            }
        }
        if (changed != null) return Optional.of(stop(p, State.REPAIR_NEEDED, changed, journal, now, revision));
        return Optional.of(p.journal(journal));
    }

    /** Positions a rollback leaves in place for good: changed by someone, protected, refused or without a readable original. */
    static Set<BlockPos> finallyKept(Project p) {
        var out = new HashSet<BlockPos>();
        for (var b : p.blockers()) if (b.pos() != null && b.cause() != Cause.UNLOADED) out.add(b.pos());
        return out;
    }

    /** A rollback is done when every restore is on disk and nothing of the work is left except what it keeps for good. */
    static boolean rollbackDone(Project p) {
        var kept = finallyKept(p);
        return p.journal().stream().noneMatch(x -> x.stage().restoring() || x.placed() && !kept.contains(x.pos()));
    }

    /** Whether a rollback has a cell it could restore now: placed, not kept for good, in a loaded chunk. */
    static boolean rollbackCanAdvance(Project p, ServerLevel level) {
        var kept = finallyKept(p);
        return p.journal().stream().anyMatch(x -> x.placed() && !kept.contains(x.pos()) && !level.isOutsideBuildHeight(x.pos()) && level.hasChunkAt(x.pos()));
    }

    /** What one rollback pass did: cells restored, cells kept with why (changed, protected, invalid original), and cells still to do. */
    record Rollback(Project project, int restored, List<Blocker> kept, int pending) { }

    /**
     * Restores the original block of every cell provably this work's (AUD-05), in loaded chunks only: the cell must still
     * hold exactly this work's block, the official must be allowed to change it under every protection mod, and the
     * restore must read back. Anything else is kept and reported; an unloaded cell waits and keeps the rollback open.
     */
    Rollback rollback(Project p, ServerLevel level, UUID official, long now, long revision, int budget) {
        String session = DevelopmentService.session();
        var journal = new ArrayList<>(p.journal());
        var kept = new ArrayList<Blocker>();
        int restored = 0, pending = 0;
        var actor = FakePlayerFactory.get(level, new GameProfile(official, "[MCA: Kingdoms]"));
        for (int i = 0; i < journal.size(); i++) {
            var x = journal.get(i);
            if (x.stage() == Stage.INTENT) { journal.set(i, x.at(Stage.PLANNED, session)); continue; }
            if (!x.placed()) continue;
            if (level.isOutsideBuildHeight(x.pos()) || !level.hasChunkAt(x.pos())) { pending++; kept.add(new Blocker(Cause.UNLOADED, x.pos(), "")); continue; }
            if (restored >= budget) { pending++; continue; }
            if (!PlotSurvey.matchesAfter(level, x)) { kept.add(new Blocker(Cause.CHANGED, x.pos(), net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(x.pos())))); continue; }
            var before = PlotSurvey.state(level, x.before());
            if (before.isEmpty()) { kept.add(new Blocker(Cause.TEMPLATE_MISSING, x.pos(), x.before())); continue; }
            var event = new BlockEvent.BreakEvent(level, x.pos(), level.getBlockState(x.pos()), actor);
            if (MinecraftForge.EVENT_BUS.post(event)) { kept.add(new Blocker(Cause.PROTECTED, x.pos(), "")); continue; }
            FaultPoints.reach("development.restore");
            level.setBlock(x.pos(), before.get(), Block.UPDATE_ALL);
            if (!BlockStates.same(level.getBlockState(x.pos()), before.get())) { kept.add(new Blocker(Cause.REFUSED, x.pos(), "")); continue; }
            journal.set(i, x.at(Stage.RESTORED, session));
            restored++;
        }
        Project next = p.journal(journal).blocked(State.ROLLING_BACK, P + "reason.rolling_back", kept, now, revision);
        return new Rollback(next, restored, kept, pending);
    }
}
