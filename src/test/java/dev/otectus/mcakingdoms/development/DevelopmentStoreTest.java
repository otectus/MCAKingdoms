package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.3.1 unit tests of the development store's pure rules: retention keeps productive assets and anything still owed
 * (AUD-07); receipt eviction raises the replay watermark (AUD-07); merges carry every record once with conservative consent
 * and kept overflow (AUD-06); a kingdom change pauses construction (AUD-06); a schema 1 file migrates without losing a
 * placed block or a completed work (AUD-03/07).
 */
class DevelopmentStoreTest {
    private static final ResourceLocation LAMP = new ResourceLocation("mcakingdoms", "lamp_post");
    private static final ResourceLocation HALL = new ResourceLocation("mcakingdoms", "civic_hall");

    @AfterEach void templates() { DevelopmentTemplate.installForTests(Map.of()); }

    private static Project project(UUID settlement, State state, WorkKind kind, long updated) {
        UUID id = UUID.randomUUID();
        return new Project(id, settlement, "mcakingdoms:madera", LAMP, state, "", UUID.randomUUID(), null, false, kind == WorkKind.CONSTRUCTION ? "minecraft:overworld" : "",
                kind == WorkKind.CONSTRUCTION ? new BlockPos(0, 64, 0) : null, List.of(), List.of(), List.of(), null, 0, updated, 0, kind, List.of(), null);
    }

    @Test void retentionKeepsProductiveWorksAndAnythingStillOwed() {
        var r = new Records();
        UUID settlement = UUID.randomUUID();
        var owedWork = project(settlement, State.CANCELLED, WorkKind.MILESTONE, 0);
        r.projects.put(owedWork.id(), owedWork);
        r.refunds.add(new Refund(UUID.randomUUID(), "minecraft:oak_fence", 2, owedWork.id()));
        var unproductive = project(settlement, State.COMPLETED, WorkKind.MILESTONE, 1); // completed before 0.3.1 kept assets
        r.projects.put(unproductive.id(), unproductive);
        for (int i = 0; i < 510; i++) {
            var p = project(settlement, State.COMPLETED, WorkKind.MILESTONE, 10 + i);
            r.projects.put(p.id(), p);
            r.assets.put(p.id(), new Asset(p.id(), settlement, LAMP, "lighting", 1, Map.of("goods", 1), 10 + i));
        }
        for (int i = 0; i < 4; i++) { var open = project(settlement, State.APPROVED, WorkKind.MILESTONE, 5); r.projects.put(open.id(), open); }
        assertEquals(516, r.projects.size());
        DevelopmentSavedData.retain(r);
        assertTrue(r.projects.size() <= DevelopmentSavedData.PROJECTS, "history was not trimmed: " + r.projects.size());
        assertTrue(r.projects.containsKey(owedWork.id()), "a work with an owed refund was dropped");
        assertTrue(r.projects.containsKey(unproductive.id()), "a completed work without a recorded asset was dropped");
        assertEquals(4, r.projects.values().stream().filter(p -> p.state().open()).count(), "an open work was dropped");
        assertEquals(510, r.assets.size(), "a productive asset was dropped with its display record");
        assertEquals(510, r.assets.values().stream().mapToInt(Asset::points).sum(), "earned points were lost");
    }

    @Test void retentionRefusesRatherThanDropLiveWork() {
        var r = new Records();
        UUID settlement = UUID.randomUUID();
        for (int i = 0; i < DevelopmentSavedData.PROJECTS + 1; i++) { var p = project(settlement, State.APPROVED, WorkKind.MILESTONE, i); r.projects.put(p.id(), p); }
        DevelopmentSavedData.retain(r);
        assertEquals(DevelopmentSavedData.PROJECTS + 1, r.projects.size());
        assertThrows(IllegalArgumentException.class, r::validate, "a store over its bound with only live work must refuse the change");
    }

    @Test void evictedReceiptsRaiseTheReplayWatermark() {
        var r = new Records();
        for (int i = 0; i <= DevelopmentSavedData.RECEIPTS; i++) r.receipts.put(UUID.randomUUID(), new Receipt(UUID.randomUUID(), "f", "r", 1_000 + i));
        DevelopmentSavedData.retain(r);
        assertEquals(DevelopmentSavedData.RECEIPTS, r.receipts.size());
        assertEquals(1_000, r.receiptWatermark, "the dropped receipt's issue time must refuse its replay");
    }

    @Test void requestIdsCarryTheirIssueTime() {
        UUID id = dev.otectus.mcakingdoms.persistence.RequestIds.issue(123_456L);
        assertTrue(dev.otectus.mcakingdoms.persistence.RequestIds.issued(id));
        assertEquals(123_456L, dev.otectus.mcakingdoms.persistence.RequestIds.issuedAt(id));
        assertFalse(dev.otectus.mcakingdoms.persistence.RequestIds.issued(UUID.randomUUID()), "a random id has no issue time");
        assertNotEquals(id, dev.otectus.mcakingdoms.persistence.RequestIds.issue(123_456L), "two ids issued at once must differ");
    }

    @Test void aMergeCarriesEverythingOnceWithConservativeConsent() {
        var r = new Records();
        UUID survivor = UUID.randomUUID(), retired = UUID.randomUUID();
        r.economies.put(survivor, new Economy(survivor, 3, Map.of("lighting", 1), true, Map.of("goods", 9_000L), 50, Map.of()));
        r.economies.put(retired, new Economy(retired, 2, Map.of("lighting", 1, "market", 1), true, Map.of("goods", 3_000L, "tools", 4L), 70, Map.of("goods", 5L)));
        var building = project(retired, State.BUILDING, WorkKind.CONSTRUCTION, 0);
        var milestone = project(retired, State.APPROVED, WorkKind.MILESTONE, 0);
        var done = project(retired, State.COMPLETED, WorkKind.MILESTONE, 0);
        for (var p : List.of(building, milestone, done)) r.projects.put(p.id(), p);
        r.assets.put(done.id(), new Asset(done.id(), retired, HALL, "civic_hall", 2, Map.of("goods", 1), 0));
        r.trades.add(new Trade(retired, survivor, "goods", 4, 0));
        assertTrue(DevelopmentMigration.merge(r, retired, survivor, "Oakfield", 100));
        var e = r.economy(survivor);
        assertEquals(5, e.points());
        assertEquals(Map.of("lighting", 2, "market", 1), e.roles());
        assertEquals(Development.ABSTRACT_CAP, e.abstractGoods().get("goods"));
        assertEquals(4L, e.abstractGoods().get("tools"));
        assertEquals(2_005L, e.overflow().get("goods"), "goods above the cap must be kept, not truncated");
        assertTrue(e.optedIn(), "two settlements that both allowed construction still allow it");
        assertEquals(70, e.lastProduced());
        assertFalse(r.economies.containsKey(retired));
        assertEquals(State.PAUSED, r.projects.get(building.id()).state(), "inherited construction must wait for review");
        assertEquals(Cause.INHERITED, r.projects.get(building.id()).blockers().get(0).cause());
        assertEquals(State.APPROVED, r.projects.get(milestone.id()).state(), "a milestone carries over unchanged");
        assertTrue(r.projects.values().stream().allMatch(p -> p.settlement().equals(survivor)));
        assertEquals(survivor, r.assets.get(done.id()).settlement());
        assertEquals(survivor, r.trades.get(0).from());
        assertFalse(DevelopmentMigration.merge(r, retired, survivor, "Oakfield", 200), "a second run must find nothing to move");
    }

    @Test void consentIsConservativeWhenOnlyOneSettlementAllowedConstruction() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var allowed = new Economy(a, 0, Map.of(), true, Map.of(), 0, Map.of());
        var refused = new Economy(b, 0, Map.of(), false, Map.of(), 0, Map.of());
        assertFalse(DevelopmentMigration.combine(allowed, refused, true).optedIn());
        assertFalse(DevelopmentMigration.combine(refused, allowed, true).optedIn());
        var r = new Records();
        r.economies.put(a, allowed);
        r.projects.put(UUID.randomUUID(), project(b, State.COMPLETED, WorkKind.MILESTONE, 0));
        DevelopmentMigration.merge(r, b, a, "Retired", 0);
        assertFalse(r.economy(a).optedIn(), "land that never agreed to construction was opted in by a merge");
    }

    @Test void aKingdomChangePausesConstructionForReview() {
        var r = new Records();
        UUID settlement = UUID.randomUUID();
        var approved = project(settlement, State.APPROVED, WorkKind.CONSTRUCTION, 0);
        var milestone = project(settlement, State.APPROVED, WorkKind.MILESTONE, 0);
        r.projects.put(approved.id(), approved); r.projects.put(milestone.id(), milestone);
        assertTrue(DevelopmentMigration.reassigned(r, settlement, "Lunari", 0));
        assertEquals(State.PAUSED, r.projects.get(approved.id()).state());
        assertEquals(Cause.KINGDOM_CHANGED, r.projects.get(approved.id()).blockers().get(0).cause());
        assertEquals(State.APPROVED, r.projects.get(milestone.id()).state(), "a milestone builds nothing and needs no review");
        assertFalse(DevelopmentMigration.reassigned(r, settlement, "Lunari", 0), "a second run must change nothing");
    }

    @Test void aSchemaOneFileMigratesWithoutLosingPlacedBlocksOrCompletedWorks() {
        DevelopmentTemplate.installForTests(Map.of(HALL, new DevelopmentTemplate(HALL, DevelopmentTemplate.Role.CIVIC_HALL, "t", Set.of(), DevelopmentMode.PROJECTS,
                List.of(), List.of(), DevelopmentTemplate.Placement.AIR, 3, Map.of("goods", 1))));
        net.minecraft.SharedConstants.tryDetectVersion();
        UUID building = UUID.randomUUID(), completed = UUID.randomUUID(), settlement = UUID.randomUUID();
        var tag = new CompoundTag();
        tag.putInt("Schema", 1);
        var projects = new ListTag();
        var b = base(building, settlement, LAMP, "BUILDING");
        b.putString("Dimension", "minecraft:overworld"); b.putLong("Origin", new BlockPos(1, 64, 1).asLong());
        var journal = new ListTag();
        for (int i = 0; i < 3; i++) {
            var j = new CompoundTag(); j.putLong("Pos", new BlockPos(1, 64 + i, 1).asLong()); j.putString("Before", "minecraft:air"); j.putString("After", "minecraft:oak_fence");
            j.putBoolean("Placed", i == 0); journal.add(j);
        }
        b.put("Journal", journal);
        projects.add(b);
        projects.add(base(completed, settlement, HALL, "COMPLETED"));
        tag.put("Projects", projects);
        var data = DevelopmentSavedData.load(tag);
        assertTrue(data.writable(), "a schema 1 file was refused");
        var p = data.records().project(building).orElseThrow();
        assertEquals(WorkKind.CONSTRUCTION, p.kind(), "a work with a plot must stay construction");
        assertEquals(List.of(Stage.VERIFIED, Stage.PLANNED, Stage.PLANNED), p.journal().stream().map(Placement::stage).toList(), "a placed block was lost or invented");
        var asset = data.records().assets().stream().filter(a -> a.work().equals(completed)).findFirst().orElseThrow(() -> new AssertionError("a completed work left no asset"));
        assertEquals(3, asset.points());
        assertEquals(Map.of("goods", 1), asset.output());
        var resaved = data.save(new CompoundTag());
        assertEquals(DevelopmentSavedData.SCHEMA, resaved.getInt("Schema"));
        assertEquals(data.records().project(building), DevelopmentSavedData.load(resaved).records().project(building), "a migrated work did not survive a second save");
    }

    @Test void aNewerFileIsKeptReadOnlyAndUnchanged() {
        var tag = new CompoundTag();
        tag.putInt("Schema", DevelopmentSavedData.SCHEMA + 1);
        tag.putString("Future", "kept");
        var data = DevelopmentSavedData.load(tag);
        assertFalse(data.writable());
        assertEquals(tag, data.save(new CompoundTag()), "a newer file must be written back exactly as read");
    }

    private static CompoundTag base(UUID id, UUID settlement, ResourceLocation template, String state) {
        var t = new CompoundTag();
        t.putUUID("Id", id); t.putUUID("Settlement", settlement); t.putString("Kingdom", "mcakingdoms:madera"); t.putString("Template", template.toString());
        t.putString("State", state); t.putString("Reason", ""); t.putUUID("Proposer", UUID.randomUUID()); t.putBoolean("ByOperator", false);
        t.putString("Dimension", ""); t.put("Demand", new ListTag()); t.put("Contributions", new ListTag()); t.put("Journal", new ListTag());
        return t;
    }
}
