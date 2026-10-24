package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.settlement.VillageFootprints;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Reconciliation against an in-memory settlement store, using the split village of appendix A (world 21). */
class SettlementReconcilerTest {
    private static final ResourceKey<Level> OVERWORLD = TestKeys.overworld();
    private static final ResourceLocation PLAINS_VILLAGE = new ResourceLocation("minecraft", "village_plains");
    private static final int MARGIN = 48;
    private static final int MAX_SPAN = 512;

    /** Stands in for the service: refuses the listed pairs as the politics preflight would, merges the rest. */
    private static final class Service implements SettlementReconciler.Mutations {
        final SettlementSavedData data = new SettlementSavedData();
        final Set<String> refused = new HashSet<>();
        final List<String> attempts = new ArrayList<>();

        @Override
        public void merge(SettlementRecord source, SettlementRecord target) {
            attempts.add(source.id + ">" + target.id);
            if (refused.contains(source.id + ">" + target.id)) {
                throw new IllegalArgumentException("Both settlements have holders of the same local office; remove one appointment before merging");
            }
            data.validateMergeExternalRefs(source, target);
            SettlementReconciler.combine(data, source, target, SettlementReconciler.MergePlan.of(source, target, true), MAX_SPAN);
        }

        @Override
        public void adopt(SettlementRecord record, VillageFootprint village) {
            SettlementReconciler.attach(data, record, village, MAX_SPAN);
        }
    }

    private static VillageFootprint village(int startX, int startZ, int minX, int minZ, int maxX, int maxZ) {
        return new VillageFootprint(OVERWORLD, PLAINS_VILLAGE, new ChunkPos(startX, startZ),
                new BoundingBox(minX, 100, minZ, maxX, 156, maxZ));
    }

    private static VillageFootprint dawnfieldVillage() {
        return village(-124, 82, -2052, 1268, -1903, 1380);
    }

    private static SettlementRecord record(String name, DetectionSource source, String sourceKey, BlockPos anchor,
                                           SettlementBounds bounds, long created) {
        SettlementRecord record = new SettlementRecord();
        record.id = UUID.randomUUID();
        record.dimension = OVERWORLD;
        record.anchor = anchor;
        record.bounds = bounds;
        record.radius = Math.max(bounds.maxX() - bounds.minX(), bounds.maxZ() - bounds.minZ()) / 2;
        record.kingdomId = new ResourceLocation("mcakingdoms", "serenum");
        record.displayName = name;
        record.slug = new ResourceLocation("mcakingdoms", name.toLowerCase(Locale.ROOT).replace(' ', '-'));
        record.biomeAtCreation = new ResourceLocation("minecraft", "plains");
        record.assignmentSource = AssignmentSource.EXACT_BIOME;
        record.detectionSource = source;
        record.sourceId = new ResourceLocation("mcakingdoms", source == DetectionSource.STRUCTURE ? "village_structure" : "poi_cluster");
        record.sourceKey = sourceKey;
        record.createdGameTime = created;
        record.lastObservedGameTime = created;
        record.addDetectorIdentity(record.sourceId, record.sourceKey);
        if (source == DetectionSource.STRUCTURE) {
            record.addStrongStructureIdentity(record.sourceId, record.sourceKey);
            record.addExternalRef(VillageFootprints.STRUCTURE_REF, sourceKey);
        }
        return record;
    }

    private static SettlementRecord dawnfield() {
        return record("Dawnfield", DetectionSource.STRUCTURE, dawnfieldVillage().key(), new BlockPos(-1977, 128, 1324),
                new SettlementBounds(-2052, 1268, -1903, 1380), 422);
    }

    private static SettlementRecord peacecross() {
        return record("Peacecross", DetectionSource.POI, "minecraft:overworld:poi:-127:82", new BlockPos(-2029, 110, 1324),
                new SettlementBounds(-2093, 1260, -1965, 1388), 440);
    }

    @Test
    void theSplitVillageOfWorld21MergesIntoItsStructureRecord() {
        Service service = new Service();
        SettlementRecord dawnfield = dawnfield(), peacecross = peacecross();
        service.data.add(dawnfield);
        service.data.add(peacecross);
        SettlementReconciler reconciler = new SettlementReconciler(service.data, service);

        SettlementReconciler.Report report = reconciler.reconcile(dawnfieldVillage(), MARGIN, record -> false, (a, b) -> false);

        assertEquals(1, report.merged());
        assertEquals(1, report.villages());
        assertTrue(report.changed());
        assertEquals(1, service.data.records().size());
        assertEquals(dawnfield.id, service.data.resolveId(peacecross.id), "the retired id redirects to the survivor");
        assertEquals(dawnfield.id, service.data.get(peacecross.id).orElseThrow().id);
        assertEquals("Dawnfield", dawnfield.displayName);
        assertTrue(dawnfield.aliases.contains("Peacecross"), "the retired name stays searchable");
        assertTrue(dawnfield.retiredSlugs.contains(peacecross.slug));
        assertTrue(dawnfield.hasDetectorIdentity(peacecross.sourceId, peacecross.sourceKey));
        assertEquals(new SettlementBounds(-2093, 1260, -1903, 1388), dawnfield.bounds, "the bounds become their union");
        assertTrue(dawnfield.bounds.contains(peacecross.anchor));
        assertFalse(reconciler.reconcile(dawnfieldVillage(), MARGIN, record -> false, (a, b) -> false).changed(),
                "a second pass finds nothing left to merge");
    }

    @Test
    void survivorIsTheKeyCarrierThenTheHighestRankThenTheOldest() {
        String key = dawnfieldVillage().key();
        SettlementRecord carrier = record("Carrier", DetectionSource.POI, "poi-a", BlockPos.ZERO, SettlementBounds.around(BlockPos.ZERO, 32), 500);
        carrier.addExternalRef(VillageFootprints.STRUCTURE_REF, key);
        SettlementRecord external = record("Native", DetectionSource.EXTERNAL, "mca-1", BlockPos.ZERO, SettlementBounds.around(BlockPos.ZERO, 32), 100);
        SettlementRecord manual = record("Manual", DetectionSource.MANUAL, "manual-1", BlockPos.ZERO, SettlementBounds.around(BlockPos.ZERO, 32), 50);
        SettlementRecord olderPoi = record("Older", DetectionSource.POI, "poi-b", BlockPos.ZERO, SettlementBounds.around(BlockPos.ZERO, 32), 10);
        SettlementRecord newerPoi = record("Newer", DetectionSource.POI, "poi-c", BlockPos.ZERO, SettlementBounds.around(BlockPos.ZERO, 32), 20);

        var order = SettlementReconciler.survivorOrder(key);
        assertEquals(carrier, List.of(manual, external, olderPoi, carrier).stream().min(order).orElseThrow());
        assertEquals(external, List.of(manual, olderPoi, external).stream().min(order).orElseThrow());
        assertEquals(olderPoi, List.of(newerPoi, olderPoi).stream().min(order).orElseThrow());
    }

    @Test
    void withoutACanonicalRecordTheBestFragmentReceivesTheVillageKey() {
        Service service = new Service();
        VillageFootprint village = dawnfieldVillage();
        SettlementRecord older = record("Older", DetectionSource.POI, "poi-older", new BlockPos(-2000, 120, 1300),
                new SettlementBounds(-2032, 1268, -1968, 1332), 10);
        SettlementRecord newer = record("Newer", DetectionSource.POI, "poi-newer", new BlockPos(-1950, 120, 1350),
                new SettlementBounds(-1982, 1318, -1918, 1382), 20);
        service.data.add(newer);
        service.data.add(older);

        var report = new SettlementReconciler(service.data, service).reconcile(village, MARGIN, record -> false, (a, b) -> false);

        assertEquals(1, report.merged());
        assertEquals(1, service.data.records().size());
        assertTrue(SettlementReconciler.carriesKey(older, village.key()));
        assertEquals(older, SettlementReconciler.canonical(service.data, OVERWORLD, village.key()).orElseThrow());
        assertTrue(older.hasStrongStructureIdentities(), "the village key is a strong identity from now on");
        assertEquals(PLAINS_VILLAGE, older.styleId);
        assertTrue(older.bounds.contains(-2052, 1268) && older.bounds.contains(-1903, 1380), "the survivor covers the village box");
    }

    @Test
    void lockedNamesAndKingdomsCarryOverOnlyToAnUnlockedSurvivor() {
        Service service = new Service();
        SettlementRecord survivor = dawnfield(), retired = peacecross();
        retired.nameLocked = true;
        retired.kingdomLocked = true;
        retired.kingdomId = new ResourceLocation("mcakingdoms", "yew");
        service.data.add(survivor);
        service.data.add(retired);

        SettlementReconciler.MergePlan plan = SettlementReconciler.MergePlan.of(retired, survivor, true);
        assertTrue(plan.carryName());
        assertTrue(plan.carryKingdom());
        SettlementReconciler.combine(service.data, retired, survivor, plan, MAX_SPAN);
        assertEquals("Peacecross", survivor.displayName);
        assertTrue(survivor.nameLocked);
        assertTrue(survivor.aliases.contains("Dawnfield"), "the survivor's own name becomes an alias");
        assertFalse(survivor.aliases.contains("Peacecross"));
        assertEquals(new ResourceLocation("mcakingdoms", "yew"), survivor.kingdomId);
        assertTrue(survivor.kingdomLocked);

        SettlementRecord lockedSurvivor = dawnfield(), lockedRetired = peacecross();
        lockedSurvivor.nameLocked = true;
        lockedRetired.nameLocked = true;
        lockedRetired.kingdomLocked = true;
        lockedRetired.kingdomId = new ResourceLocation("mcakingdoms", "yew");
        lockedSurvivor.kingdomLocked = true;
        SettlementReconciler.MergePlan kept = SettlementReconciler.MergePlan.of(lockedRetired, lockedSurvivor, true);
        assertFalse(kept.carryName());
        assertFalse(kept.carryKingdom());
        assertEquals(lockedSurvivor.kingdomId, kept.kingdom());

        SettlementReconciler.MergePlan operator = SettlementReconciler.MergePlan.of(peacecrossLocked(), dawnfield(), false);
        assertFalse(operator.carryName() || operator.carryKingdom(), "an operator merge keeps the chosen target's identity");
    }

    private static SettlementRecord peacecrossLocked() {
        SettlementRecord record = peacecross();
        record.nameLocked = true;
        record.kingdomLocked = true;
        record.kingdomId = new ResourceLocation("mcakingdoms", "yew");
        return record;
    }

    @Test
    void boundsBecomeTheirUnionUnlessThatExceedsTheCap() {
        Service service = new Service();
        SettlementRecord target = dawnfield(), source = peacecross();
        service.data.add(target);
        service.data.add(source);
        SettlementReconciler.combine(service.data, source, target, SettlementReconciler.MergePlan.of(source, target, false), 100);
        assertEquals(new SettlementBounds(-2052, 1268, -1903, 1380), target.bounds, "a 190-block union exceeds a 100-block cap");

        SettlementRecord grown = dawnfield();
        assertTrue(SettlementReconciler.grow(grown, new SettlementBounds(-2093, 1260, -1965, 1388), MAX_SPAN));
        assertEquals(new SettlementBounds(-2093, 1260, -1903, 1388), grown.bounds);
        assertEquals(95, grown.radius);
        assertFalse(SettlementReconciler.grow(grown, new SettlementBounds(-2000, 1300, -1990, 1310), MAX_SPAN),
                "bounds already holding the extra area do not change");
    }

    @Test
    void recordsWithAStructureIdentityOfTheirOwnAreNeverClaimed() {
        Service service = new Service();
        SettlementRecord dawnfield = dawnfield();
        VillageFootprint neighbour = village(-128, 82, -2120, 1270, -2040, 1370);
        SettlementRecord other = record("Neighbour", DetectionSource.STRUCTURE, neighbour.key(), new BlockPos(-2080, 110, 1320),
                new SettlementBounds(-2120, 1270, -2040, 1370), 300);
        service.data.add(dawnfield);
        service.data.add(other);
        var report = new SettlementReconciler(service.data, service).reconcile(dawnfieldVillage(), MARGIN, record -> false, (a, b) -> false);
        assertEquals(0, report.merged());
        assertEquals(2, service.data.records().size(), "adjacent villages stay distinct by key");
    }

    @Test
    void retiredMcaVillagesInsideTheVillageJoinItButLiveOnesDoNot() {
        Service service = new Service();
        SettlementRecord dawnfield = dawnfield();
        dawnfield.bounds = new SettlementBounds(-2300, 1100, -1700, 1600); // grown by observed MCA buildings
        SettlementRecord retiredMca = record("Old Native", DetectionSource.EXTERNAL, "minecraft:overworld#7", new BlockPos(-1750, 70, 1550),
                SettlementBounds.around(new BlockPos(-1750, 70, 1550), 24), 600);
        retiredMca.addExternalRef("mca", "minecraft:overworld#7");
        SettlementRecord liveMca = record("Live Native", DetectionSource.EXTERNAL, "minecraft:overworld#8", new BlockPos(-1760, 70, 1150),
                SettlementBounds.around(new BlockPos(-1760, 70, 1150), 24), 610);
        liveMca.addExternalRef("mca", "minecraft:overworld#8");
        service.data.add(dawnfield);
        service.data.add(retiredMca);
        service.data.add(liveMca);
        int[] listings = {0};
        var rule = SettlementReconciler.retiredReferences("mca", () -> {
            listings[0]++;
            return Optional.of(Set.of("minecraft:overworld#8"));
        });

        var report = new SettlementReconciler(service.data, service).reconcile(dawnfieldVillage(), MARGIN, record -> false, rule);

        assertEquals(1, report.merged());
        assertEquals(dawnfield.id, service.data.resolveId(retiredMca.id));
        assertEquals(liveMca.id, service.data.resolveId(liveMca.id), "a village MCA still lists keeps its own record");
        assertTrue(dawnfield.hasExternalRef("mca", "minecraft:overworld#7"));
        assertEquals(1, listings[0], "MCA's village list is read once per pass");
        assertFalse(SettlementReconciler.retiredReferences("mca", Optional::empty).test(dawnfield, liveMca),
                "an unreadable village list claims nothing");
    }

    @Test
    void aRefusedPairIsReportedEachPassButAttemptedAndLoggedOnce() {
        Service service = new Service();
        SettlementRecord dawnfield = dawnfield(), peacecross = peacecross();
        service.data.add(dawnfield);
        service.data.add(peacecross);
        service.refused.add(peacecross.id + ">" + dawnfield.id);
        SettlementReconciler reconciler = new SettlementReconciler(service.data, service);

        var first = reconciler.reconcile(dawnfieldVillage(), MARGIN, record -> false, (a, b) -> false);
        var second = reconciler.reconcile(dawnfieldVillage(), MARGIN, record -> false, (a, b) -> false);

        assertEquals(1, service.attempts.size(), "a refused pair is not retried this session");
        assertEquals(0, first.merged());
        assertTrue(first.changed(), "the first refusal is news");
        assertFalse(second.changed(), "a remembered refusal is not");
        assertEquals(1, second.refused().size());
        SettlementReconciler.Refusal refusal = second.refused().get(0);
        assertEquals("/kingdoms village merge " + peacecross.id + " " + dawnfield.id, refusal.command());
        assertTrue(refusal.reason().contains("same local office"));
        assertEquals(List.of(refusal), reconciler.refusals(List.of(peacecross.id)));
        assertEquals(2, service.data.records().size(), "both records stay");
    }

    @Test
    void startupPassSpreadsChunkReadsOverTicksAndStopsAtTheBudget() {
        Service service = new Service();
        VillageFootprint first = village(0, 0, -40, -40, 40, 40);
        VillageFootprint second = village(40, 40, 600, 600, 680, 680);
        SettlementRecord a = record("Alder", DetectionSource.POI, "poi-a", new BlockPos(0, 120, 0), SettlementBounds.around(BlockPos.ZERO, 32), 10);
        SettlementRecord b = record("Birch", DetectionSource.POI, "poi-b", new BlockPos(640, 120, 640),
                SettlementBounds.around(new BlockPos(640, 120, 640), 32), 20);
        SettlementRecord structure = dawnfield();
        service.data.add(a);
        service.data.add(b);
        service.data.add(structure);
        SettlementReconciler reconciler = new SettlementReconciler(service.data, service);
        SettlementReconciler.Lookup readsOnly = new SettlementReconciler.Lookup() {
            @Override
            public Optional<VillageFootprint> loaded(SettlementRecord record) {
                return Optional.empty();
            }

            @Override
            public Optional<VillageFootprint> read(SettlementRecord record, VillageFootprints.Budget budget) {
                if (!budget.take()) return Optional.empty();
                return Optional.of(record == a ? first : second);
            }
        };

        var spread = reconciler.startup();
        assertFalse(spread.step(readsOnly, MARGIN, 2, 1), "one read per tick leaves the second record for the next tick");
        assertTrue(spread.step(readsOnly, MARGIN, 2, 1));
        assertEquals(2, spread.report().examined(), "the structure record needs no reconciliation");
        assertEquals(0, spread.report().unresolved());
        assertEquals(2, spread.report().chunkReads());
        assertTrue(SettlementReconciler.carriesKey(a, first.key()));
        assertTrue(SettlementReconciler.carriesKey(b, second.key()));

        Service limited = new Service();
        SettlementRecord c = record("Cedar", DetectionSource.POI, "poi-c", new BlockPos(0, 120, 0), SettlementBounds.around(BlockPos.ZERO, 32), 10);
        SettlementRecord d = record("Dogwood", DetectionSource.POI, "poi-d", new BlockPos(640, 120, 640),
                SettlementBounds.around(new BlockPos(640, 120, 640), 32), 20);
        limited.data.add(c);
        limited.data.add(d);
        var budgeted = new SettlementReconciler(limited.data, limited).startup();
        SettlementReconciler.Lookup either = new SettlementReconciler.Lookup() {
            @Override
            public Optional<VillageFootprint> loaded(SettlementRecord record) {
                return Optional.empty();
            }

            @Override
            public Optional<VillageFootprint> read(SettlementRecord record, VillageFootprints.Budget budget) {
                if (!budget.take()) return Optional.empty();
                return Optional.of(record == c ? first : second);
            }
        };
        assertTrue(budgeted.step(either, MARGIN, 1, 4), "an exhausted budget ends the pass");
        assertEquals(1, budgeted.report().unresolved(), "the rest reconcile lazily as their chunks load");
        assertEquals(1, budgeted.report().chunkReads());
    }
}
