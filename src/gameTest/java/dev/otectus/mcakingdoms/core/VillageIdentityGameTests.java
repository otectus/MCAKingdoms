package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.api.event.SettlementMutationPreflightEvent;
import dev.otectus.mcakingdoms.api.townstead.BoundCivicBuilding;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBindingPersistence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingView;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.settlement.SpatialSettlementIndex;
import dev.otectus.mcakingdoms.settlement.VillageFootprints;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One village, one settlement. Real generated starts cannot be placed in a GameTest world ({@code /place structure}
 * registers none), so each test registers a footprint source with a synthetic village box; that SPI is the seam.
 * Every test has its own batch and a far, per-run origin, so no footprint or start-up pass reaches another test's records.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class VillageIdentityGameTests {
    private static final ResourceLocation PLAINS = new ResourceLocation("minecraft", "village_plains");
    private static final ResourceLocation STRUCTURE_DETECTOR = new ResourceLocation("mcakingdoms", "village_structure");
    private static final ResourceLocation POI_DETECTOR = new ResourceLocation("mcakingdoms", "poi_cluster");
    private static final ResourceLocation MCA_DETECTOR = new ResourceLocation("mcakingdoms", "mca");
    /** A chunk-aligned origin of this run, far from the GameTest grid and from every earlier run's records. */
    private static final int RUN_X = 16 * ThreadLocalRandom.current().nextInt(8_000, 40_000);
    private static final int RUN_Z = 16 * ThreadLocalRandom.current().nextInt(8_000, 40_000);
    private static final String RUN = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));

    private static void check(boolean ok, String message) {
        if (!ok) throw new GameTestAssertException(message);
    }

    private static KingdomsServiceImpl service(GameTestHelper h) {
        return (KingdomsServiceImpl) McaKingdomsApi.get(h.getLevel().getServer());
    }

    private static BlockPos origin(int slot) {
        return new BlockPos(RUN_X + slot * 4_096, 70, RUN_Z);
    }

    private static VillageFootprint village(Level level, BlockPos center, int halfWidth) {
        return new VillageFootprint(level.dimension(), PLAINS, new ChunkPos(center),
                new BoundingBox(center.getX() - halfWidth, 60, center.getZ() - halfWidth,
                        center.getX() + halfWidth, 90, center.getZ() + halfWidth));
    }

    private static Registration footprints(GameTestHelper h, String name, VillageFootprint... villages) {
        List<VillageFootprint> known = List.of(villages);
        return service(h).registerVillageFootprintSource(new ResourceLocation("mcakingdoms", "test_" + name + "_" + RUN),
                (level, pos, margin) -> known.stream()
                        .filter(village -> village.dimension().equals(level.dimension()) && village.contains(pos, margin))
                        .toList());
    }

    /** What the built-in structure detector emits for a village start. */
    private static SettlementCandidate structureCandidate(VillageFootprint village) {
        BoundingBox box = village.box();
        return new SettlementCandidate(village.dimension(), box.getCenter(),
                Math.max(16, Math.max(box.getXSpan(), box.getZSpan()) / 2), village.bounds(), STRUCTURE_DETECTOR, village.key(),
                DetectionSource.STRUCTURE, Optional.of(PLAINS), Optional.empty(),
                Map.of(VillageFootprints.STRUCTURE_REF, village.key()), Optional.empty());
    }

    /** A POI cluster seen by one scan, before it was resolved to any village. */
    private static SettlementCandidate poiCandidate(Level level, BlockPos anchor, SettlementBounds bounds) {
        ChunkPos chunk = new ChunkPos(anchor);
        return new SettlementCandidate(level.dimension(), anchor, Math.max(32, (bounds.maxX() - bounds.minX()) / 2), bounds,
                POI_DETECTOR, level.dimension().location() + ":poi:" + chunk.x + ":" + chunk.z, DetectionSource.POI,
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty());
    }

    /** The shape of {@code McaAccess.candidate}: an MCA village id as source key and external reference. */
    private static SettlementCandidate mcaCandidate(Level level, int villageId, BlockPos center, int halfWidth) {
        String reference = level.dimension().location() + "#" + villageId;
        return new SettlementCandidate(level.dimension(), center, halfWidth, SettlementBounds.around(center, halfWidth),
                MCA_DETECTOR, reference, DetectionSource.EXTERNAL, Optional.empty(), Optional.empty(),
                Map.of("mca", reference), Optional.empty());
    }

    private static Set<String> refs(KingdomsServiceImpl api, UUID settlement, String namespace) {
        return api.getSettlementExternalRefs(settlement).getOrDefault(namespace, Set.of());
    }

    /** A lookup that fails the test instead of the server: inside succeedWhen any other exception stops the tick loop. */
    private static Optional<UUID> find(KingdomsServiceImpl api, String name) {
        try {
            return api.findSettlement(name).map(SettlementView::id);
        } catch (IllegalArgumentException failure) {
            throw new GameTestAssertException("Lookup of " + name + " failed: " + failure.getMessage());
        }
    }

    private static boolean covers(SettlementBounds outer, SettlementBounds inner) {
        return outer.minX() <= inner.minX() && outer.minZ() <= inner.minZ() && outer.maxX() >= inner.maxX() && outer.maxZ() >= inner.maxZ();
    }

    // ---- T1: saved data ----------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_saved_data")
    public static void settlementsFileKeepsSchemaOneAndTheReconciledMarker(GameTestHelper h) throws Exception {
        SettlementRecord peacecross = world21Records(BlockPos.ZERO, "saved").get(1);
        SettlementSavedData data = new SettlementSavedData();
        data.add(peacecross);
        data.markReconciled();
        CompoundTag saved = data.save(new CompoundTag());
        check(saved.getInt("Schema") == 1, "The settlements file left schema 1");
        SettlementSavedData reloaded = load(saved);
        check(reloaded.reconciled() == SettlementSavedData.RECONCILIATION_VERSION, "Reconciled did not survive save and load");
        check(reloaded.get(peacecross.id).orElseThrow().snapshot().equals(peacecross.snapshot()), "The record changed on reload");
        saved.remove("Reconciled");
        SettlementSavedData older = load(saved);
        check(older.reconciled() == 0, "A file written before the marker must read as not reconciled");
        check(older.get(peacecross.id).isPresent(), "A file written before the marker lost its record");
        h.succeed();
    }

    // ---- (a) POI and structure candidates -----------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_a")
    public static void poiAndStructureCandidatesFormOneVillageInEitherOrder(GameTestHelper h) {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        VillageFootprint forward = village(level, origin(1), 40);
        VillageFootprint reverse = village(level, origin(1).offset(1_024, 0, 0), 40);
        try (Registration ignored = footprints(h, "a", forward, reverse)) {
            BlockPos center = forward.box().getCenter();
            SettlementBounds clusterBounds = new SettlementBounds(center.getX() + 10, center.getZ() - 20, center.getX() + 60, center.getZ() + 20);
            SettlementView first = api.registerCandidate(level, poiCandidate(level, center.offset(30, 0, 0), clusterBounds));
            check(refs(api, first.id(), VillageFootprints.STRUCTURE_REF).contains(forward.key()), "A POI cluster in a village did not resolve to it");
            SettlementView second = api.registerCandidate(level, structureCandidate(forward));
            check(first.id().equals(second.id()), "The structure start registered a second settlement");
            check(second.detectionSource() == DetectionSource.STRUCTURE, "The record was not promoted to the structure");
            check(second.anchor().equals(center), "The promoted anchor is not the box centre");
            check(covers(second.bounds(), forward.bounds()) && covers(second.bounds(), clusterBounds),
                    "The bounds do not cover the structure box and the POI extent: " + second.bounds());

            BlockPos otherCenter = reverse.box().getCenter();
            SettlementBounds otherCluster = new SettlementBounds(otherCenter.getX() + 10, otherCenter.getZ() - 20,
                    otherCenter.getX() + 60, otherCenter.getZ() + 20);
            SettlementView structureFirst = api.registerCandidate(level, structureCandidate(reverse));
            SettlementView poiSecond = api.registerCandidate(level, poiCandidate(level, otherCenter.offset(30, 0, 0), otherCluster));
            check(structureFirst.id().equals(poiSecond.id()), "The reverse order split the village");
            SettlementView reversed = api.getSettlement(structureFirst.id()).orElseThrow();
            check(reversed.detectionSource() == DetectionSource.STRUCTURE && reversed.anchor().equals(otherCenter),
                    "The reverse order changed the village's identity");
            check(reversed.bounds().equals(new SettlementBounds(second.bounds().minX() + 1_024, second.bounds().minZ(),
                    second.bounds().maxX() + 1_024, second.bounds().maxZ())), "Either order must give the same bounds");
        }
        h.succeed();
    }

    // ---- (b) MCA candidates -------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_b")
    public static void mcaVillagesInsideTheFootprintJoinIt(GameTestHelper h) {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        VillageFootprint village = village(level, origin(2), 60);
        int firstId = ThreadLocalRandom.current().nextInt(1_000_000, 1_500_000), secondId = firstId + 1;
        try (Registration ignored = footprints(h, "b", village)) {
            SettlementView structure = api.registerCandidate(level, structureCandidate(village));
            BlockPos center = village.box().getCenter();
            SettlementView first = api.registerCandidate(level, mcaCandidate(level, firstId, center.offset(-20, 0, 10), 30));
            SettlementView second = api.registerCandidate(level, mcaCandidate(level, secondId, center.offset(25, 0, -15), 20));
            check(first.id().equals(structure.id()) && second.id().equals(structure.id()), "An MCA village split the generated village");
            Set<String> mca = refs(api, structure.id(), "mca");
            check(mca.contains(level.dimension().location() + "#" + firstId) && mca.contains(level.dimension().location() + "#" + secondId),
                    "Both MCA references must be kept: " + mca);
            check(api.getSettlementForMcaVillage(level.dimension().location(), secondId).orElseThrow().id().equals(structure.id()),
                    "The second MCA village does not resolve to the village");
            check(refs(api, structure.id(), VillageFootprints.STRUCTURE_REF).contains(village.key()), "MCA promotion lost the structure key");
        }
        h.succeed();
    }

    // ---- (c) distinct villages ------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_c")
    public static void separateVillagesAndUnclaimedClustersStaySeparate(GameTestHelper h) {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        VillageFootprint west = village(level, origin(3), 40);
        VillageFootprint east = village(level, origin(3).offset(128, 0, 0), 40);
        try (Registration ignored = footprints(h, "c", west, east)) {
            BlockPos westCenter = west.box().getCenter(), eastCenter = east.box().getCenter();
            SettlementView inWest = api.registerCandidate(level, poiCandidate(level, westCenter, SettlementBounds.around(westCenter, 32)));
            SettlementView inEast = api.registerCandidate(level, poiCandidate(level, eastCenter, SettlementBounds.around(eastCenter, 32)));
            check(!inWest.id().equals(inEast.id()), "Villages 128 blocks apart merged");
            check(refs(api, inWest.id(), VillageFootprints.STRUCTURE_REF).contains(west.key()), "The western cluster lost its village");
            check(refs(api, inEast.id(), VillageFootprints.STRUCTURE_REF).contains(east.key()), "The eastern cluster lost its village");
            BlockPos away = westCenter.offset(328, 0, 0);
            SettlementView alone = api.registerCandidate(level, poiCandidate(level, away, SettlementBounds.around(away, 32)));
            check(!alone.id().equals(inWest.id()) && !alone.id().equals(inEast.id()), "A cluster outside every village joined one");
            check(alone.detectionSource() == DetectionSource.POI && refs(api, alone.id(), VillageFootprints.STRUCTURE_REF).isEmpty(),
                    "A cluster outside every village must stay a POI-only settlement");
        }
        h.succeed();
    }

    // ---- (d) the split village of appendix A, world 21 --------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_d", timeoutTicks = 400)
    public static void preSplitRecordsReconcileWhenTheirChunkIsScanned(GameTestHelper h) throws Exception {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        BlockPos shift = origin(4);
        List<SettlementRecord> records = world21Records(shift, "d");
        adopt(api, records);
        SettlementRecord dawnfield = records.get(0), peacecross = records.get(1);
        VillageFootprint village = world21Village(level, shift);
        check(village.key().equals(dawnfield.sourceKey), "Fixture key drifted from the structure detector's format");
        check(!api.getSettlement(peacecross.id).orElseThrow().id().equals(dawnfield.id), "The fixture should start split");

        var knowledge = SettlementKnowledge.get(level.getServer());
        UUID visitedBoth = UUID.randomUUID(), visitedFragment = UUID.randomUUID(), visitedNeither = UUID.randomUUID();
        knowledge.discover(visitedBoth, dawnfield.id);
        knowledge.discover(visitedBoth, peacecross.id);
        knowledge.recordVisit(visitedBoth, dawnfield.id);
        knowledge.recordVisit(visitedBoth, peacecross.id);
        knowledge.recordVisit(visitedFragment, peacecross.id);

        Registration source = footprints(h, "d", village);
        ChunkPos fragmentChunk = new ChunkPos(peacecross.anchor);
        level.getChunk(fragmentChunk.x, fragmentChunk.z);
        level.setChunkForced(fragmentChunk.x, fragmentChunk.z, true);
        api.enqueueChunk(level, fragmentChunk);
        h.succeedWhen(() -> {
            check(api.getSettlement(peacecross.id).orElseThrow().id().equals(dawnfield.id), "Peacecross did not redirect to Dawnfield");
            check(find(api, peacecross.displayName).equals(Optional.of(dawnfield.id)), "The retired name is not searchable");
            check(api.getSettlementAt(level, dawnfield.anchor).orElseThrow().id().equals(dawnfield.id), "Dawnfield's anchor is not Dawnfield");
            check(api.getSettlementAt(level, peacecross.anchor).orElseThrow().id().equals(dawnfield.id), "Peacecross's anchor is not Dawnfield");
            check(api.getSettlement(dawnfield.id).orElseThrow().displayName().equals(dawnfield.displayName), "The survivor changed its name");
            check(!knowledge.recordVisit(visitedBoth, dawnfield.id), "A player who entered both fragments got a first visit again");
            check(!knowledge.recordVisit(visitedFragment, dawnfield.id), "A player who entered the fragment got a first visit again");
            check(knowledge.recordVisit(visitedNeither, dawnfield.id) && !knowledge.recordVisit(visitedNeither, dawnfield.id),
                    "A new visitor must get exactly one first visit");
            check(visits(knowledge, visitedBoth).equals(Set.of(dawnfield.id)), "Visits for both ids did not collapse to one");
            source.close();
            level.setChunkForced(fragmentChunk.x, fragmentChunk.z, false);
        });
    }

    // ---- (e) stores keyed by settlement ---------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_e")
    public static void storesFollowAMergeToTheSurvivor(GameTestHelper h) {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        var server = level.getServer();
        BlockPos base = origin(5);
        SettlementView retired = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), base, 32,
                new ResourceLocation("mcakingdoms", "merge_fixture"), "retired-" + RUN, "Merge Fixture Retired " + RUN));
        SettlementView survivor = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), base.offset(512, 0, 0), 32,
                new ResourceLocation("mcakingdoms", "merge_fixture"), "survivor-" + RUN, "Merge Fixture Survivor " + RUN));
        UUID campaign = dev.otectus.mcakingdoms.warfare.MergeStoreFixtures.seedCampaign(server, retired.id());
        UUID contract = dev.otectus.mcakingdoms.warfare.contracts.MergeStoreFixtures.seedContract(server, retired.id());
        UUID scenario = dev.otectus.mcakingdoms.evolution.MergeStoreFixtures.seedScenario(server, retired.id());
        UUID appointment = dev.otectus.mcakingdoms.civic.MergeStoreFixtures.seedAppointment(server, retired.id());
        ResourceLocation dimension = level.dimension().location();
        BoundCivicBuilding binding = new BoundCivicBuilding(TownsteadBuildingView.bindingId(dimension, retired.id(), 12, 7),
                retired.id(), dimension, 12, 7, "townstead:kitchen", "townstead:kitchen_l1");
        CompoundTag savedBinding = TownsteadBindingPersistence.save(binding);
        try {
            api.merge(retired.id(), survivor.id());
            check(dev.otectus.mcakingdoms.warfare.MergeStoreFixtures.campaignSettlement(server, campaign).equals(survivor.id()),
                    "The campaign stayed on the retired id");
            check(dev.otectus.mcakingdoms.warfare.contracts.MergeStoreFixtures.contractSettlement(server, contract).equals(survivor.id()),
                    "The civilian contract stayed on the retired id");
            check(dev.otectus.mcakingdoms.evolution.MergeStoreFixtures.scenarioSettlement(server, scenario).equals(survivor.id()),
                    "The evolution scenario stayed on the retired id");
            check(dev.otectus.mcakingdoms.evolution.MergeStoreFixtures.eligible(server, survivor.id())
                    && !dev.otectus.mcakingdoms.evolution.MergeStoreFixtures.eligible(server, retired.id()), "The evolution region did not move");
            check(dev.otectus.mcakingdoms.civic.MergeStoreFixtures.appointmentSettlement(server, appointment).equals(survivor.id()),
                    "The civic seed stayed on the retired id");
            BoundCivicBuilding moved = TownsteadBindingPersistence.load(savedBinding, api::resolveSettlementId).orElseThrow();
            check(moved.settlementId().equals(survivor.id()) && moved.bindingId().equals(
                    TownsteadBuildingView.bindingId(dimension, survivor.id(), 12, 7)), "The Townstead binding was not recomputed for the survivor");
            check(api.resolveSettlementId(retired.id()).equals(survivor.id()), "The retired id does not resolve to the survivor");
        } finally {
            dev.otectus.mcakingdoms.warfare.MergeStoreFixtures.removeCampaign(server, campaign);
            dev.otectus.mcakingdoms.evolution.MergeStoreFixtures.removeScenario(server, scenario, survivor.id());
            dev.otectus.mcakingdoms.civic.MergeStoreFixtures.removeAppointment(server, appointment);
        }
        h.succeed();
    }

    // ---- (f) politics preflight veto ----------------------------------------------------------------------------------

    public static final class PreflightProbe {
        final Set<UUID> watched;
        int merges;

        PreflightProbe(Set<UUID> watched) {
            this.watched = watched;
        }

        /** Sees refused attempts too: a rejection cancels the event. */
        @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST, receiveCanceled = true)
        public void preflight(SettlementMutationPreflightEvent event) {
            if (event.mergeTarget().isPresent() && watched.contains(event.source().id())) merges++;
        }
    }

    @GameTest(template = "empty", batch = "village_identity_f", timeoutTicks = 200)
    public static void aPreflightVetoLeavesBothRecordsAndIsReportedOnce(GameTestHelper h) throws Exception {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        var server = level.getServer();
        VillageFootprint village = village(level, origin(6), 50);
        BlockPos center = village.box().getCenter();
        SettlementView canonical = api.registerCandidate(level, structureCandidate(village));
        SettlementView fragment = api.registerCandidate(level, poiCandidate(level, center.offset(70, 0, 0),
                SettlementBounds.around(center.offset(70, 0, 0), 32)));
        check(!canonical.id().equals(fragment.id()), "The fixture fragment should start separate");
        ChunkPos fragmentChunk = new ChunkPos(fragment.anchor());
        level.getChunk(fragmentChunk.x, fragmentChunk.z);
        level.setChunkForced(fragmentChunk.x, fragmentChunk.z, true);
        api.setKingdom(canonical.id(), new ResourceLocation("mcakingdoms:serenum"));
        api.setKingdom(fragment.id(), new ResourceLocation("mcakingdoms:serenum"));

        var politics = dev.otectus.mcakingdoms.politics.MergeVetoFixtures.stewardsInBoth(level, api, canonical.id(), fragment.id());
        var probe = new PreflightProbe(Set.of(fragment.id()));
        MinecraftForge.EVENT_BUS.register(politics);
        MinecraftForge.EVENT_BUS.register(probe);
        try (Registration ignored = footprints(h, "f", village)) {
            var first = api.reconcileAround(level, center, 8);
            var second = api.reconcileAround(level, center, 8);
            check(first.merged() == 0 && first.refused().size() == 1, "The vetoed pair was merged or not reported");
            check(first.refused().get(0).reason().contains("local office"), "Wrong refusal: " + first.refused().get(0).reason());
            check(second.refused().size() == 1, "A later pass stopped reporting the refused pair");
            check(probe.merges == 1, "A refused pair was retried in the same session: " + probe.merges);
            check(api.getSettlement(fragment.id()).orElseThrow().id().equals(fragment.id())
                    && api.getSettlement(canonical.id()).orElseThrow().id().equals(canonical.id()), "A refused merge changed a record");

            List<String> said = new ArrayList<>();
            CommandSourceStack operator = captured(server, level, center, said);
            int merged = server.getCommands().getDispatcher().execute("kingdoms village reconcile 8", operator);
            check(merged == 0, "The command merged a refused pair");
            check(said.stream().anyMatch(line -> line.startsWith("command.mcakingdoms.village.reconcile_refused")
                    && line.contains(fragment.displayName()) && line.contains(canonical.displayName())
                    && line.contains("/kingdoms village merge " + fragment.id() + " " + canonical.id())),
                    "The reconcile command did not report the refused pair: " + said);
            check(probe.merges == 1, "The command retried a refused pair");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(probe);
            MinecraftForge.EVENT_BUS.unregister(politics);
            level.setChunkForced(fragmentChunk.x, fragmentChunk.z, false);
        }
        h.succeed();
    }

    private static CommandSourceStack captured(net.minecraft.server.MinecraftServer server, ServerLevel level, BlockPos at, List<String> said) {
        CommandSource sink = new CommandSource() {
            /** The key and arguments, so the check does not depend on the server having the mod's translations. */
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        ? text.getKey() + " " + java.util.Arrays.toString(text.getArgs()) : message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        return new CommandSourceStack(sink, Vec3.atCenterOf(at), Vec2.ZERO, level, 4, "VillageIdentityTest",
                Component.literal("VillageIdentityTest"), server, null);
    }

    // ---- (g) start-up pass ----------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_g", timeoutTicks = 400)
    public static void startupPassReconcilesOnceAndRecordsIt(GameTestHelper h) throws Exception {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        BlockPos shift = origin(7);
        List<SettlementRecord> records = world21Records(shift, "g");
        adopt(api, records);
        SettlementRecord dawnfield = records.get(0), peacecross = records.get(1);
        ChunkPos anchorChunk = new ChunkPos(peacecross.anchor);
        level.getChunk(anchorChunk.x, anchorChunk.z);
        level.setChunkForced(anchorChunk.x, anchorChunk.z, true);
        Registration source = footprints(h, "g", world21Village(level, shift));
        SettlementSavedData data = liveData(api);
        Field marker = SettlementSavedData.class.getDeclaredField("reconciled");
        marker.setAccessible(true);
        marker.setInt(data, 0);
        check(api.beginStartupReconciliation(), "The start-up pass did not start on an unreconciled world");
        h.succeedWhen(() -> {
            check(api.getSettlement(peacecross.id).orElseThrow().id().equals(dawnfield.id), "The start-up pass left the village split");
            check(data.reconciled() == SettlementSavedData.RECONCILIATION_VERSION, "Reconciled was not recorded");
            level.getDataStorage().save();
            try {
                CompoundTag file = net.minecraft.nbt.NbtIo.readCompressed(level.getServer()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/mcakingdoms_settlements.dat").toFile());
                check(file.getCompound("data").getInt("Reconciled") == SettlementSavedData.RECONCILIATION_VERSION
                        && file.getCompound("data").getInt("Schema") == 1, "Reconciled was not written to the settlements file");
            } catch (java.io.IOException failure) {
                throw new GameTestAssertException("Settlements file unreadable: " + failure);
            }
            check(!api.beginStartupReconciliation(), "A second start ran the start-up pass again");
            source.close();
            level.setChunkForced(anchorChunk.x, anchorChunk.z, false);
        });
    }

    // ---- (h) D5: manual registration ---------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = "village_identity_h")
    public static void manualRegistrationInsideAVillageJoinsIt(GameTestHelper h) throws Exception {
        KingdomsServiceImpl api = service(h);
        ServerLevel level = h.getLevel();
        VillageFootprint village = village(level, origin(8), 40);
        BlockPos center = village.box().getCenter();
        try (Registration ignored = footprints(h, "h", village)) {
            SettlementView structure = api.registerCandidate(level, structureCandidate(village));
            SettlementView inside = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), center.offset(20, 0, 20), 64,
                    new ResourceLocation("mcakingdoms", "manual_test"), "inside-" + RUN, "Manual Inside " + RUN));
            check(inside.id().equals(structure.id()), "A manual registration inside a village created a second settlement");
            check(api.findSettlement("Manual Inside " + RUN).isEmpty(), "The joined village took the proposed name");
            BlockPos outside = center.offset(300, 0, 0);
            SettlementView separate = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), outside, 64,
                    new ResourceLocation("mcakingdoms", "manual_test"), "outside-" + RUN, "Manual Outside " + RUN));
            check(!separate.id().equals(structure.id()) && separate.displayName().equals("Manual Outside " + RUN),
                    "A manual registration outside every village did not create its own settlement");

            List<String> said = new ArrayList<>();
            int result = level.getServer().getCommands().getDispatcher().execute("kingdoms village create 32 Command Inside " + RUN,
                    captured(level.getServer(), level, center.offset(-10, 0, 5), said));
            check(result == 1 && said.stream().anyMatch(line -> line.startsWith("command.mcakingdoms.village.joined")
                            && line.contains(structure.displayName())),
                    "The create command did not say the place joined the village: " + said);
        }
        h.succeed();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------------

    /** Appendix A, world 21, shifted by a chunk-aligned offset: Dawnfield (structure) and Peacecross (POI fragment). */
    private static List<SettlementRecord> world21Records(BlockPos shift, String test) throws Exception {
        int dx = shift.getX(), dz = shift.getZ(), cx = dx >> 4, cz = dz >> 4;
        String key = "minecraft:overworld:minecraft:village_plains:" + (-124 + cx) + ":" + (82 + cz);
        SettlementRecord dawnfield = record("Dawnfield " + RUN + " " + test, DetectionSource.STRUCTURE, STRUCTURE_DETECTOR, key,
                new BlockPos(-1977 + dx, 128, 1324 + dz), 75, new SettlementBounds(-2052 + dx, 1268 + dz, -1903 + dx, 1380 + dz),
                "regions_unexplored:highland_fields", 422);
        dawnfield.styleId = PLAINS;
        dawnfield.addStrongStructureIdentity(STRUCTURE_DETECTOR, key);
        dawnfield.addExternalRef(VillageFootprints.STRUCTURE_REF, key);
        SettlementRecord peacecross = record("Peacecross " + RUN + " " + test, DetectionSource.POI, POI_DETECTOR,
                "minecraft:overworld:poi:" + (-127 + cx) + ":" + (82 + cz), new BlockPos(-2029 + dx, 110, 1324 + dz), 64,
                new SettlementBounds(-2093 + dx, 1260 + dz, -1965 + dx, 1388 + dz), "regions_unexplored:orchard", 440);
        // Through the real loader, from the root as 0.1.1 wrote it: schema 1, no Reconciled key.
        CompoundTag root = new CompoundTag();
        root.putInt("Schema", 1);
        root.putLong("Revision", 10);
        ListTag settlements = new ListTag();
        settlements.add(dawnfield.save());
        settlements.add(peacecross.save());
        root.put("Settlements", settlements);
        root.put("Redirects", new ListTag());
        SettlementSavedData loaded = load(root);
        return List.of(loaded.get(dawnfield.id).orElseThrow(), loaded.get(peacecross.id).orElseThrow());
    }

    private static VillageFootprint world21Village(Level level, BlockPos shift) {
        int dx = shift.getX(), dz = shift.getZ();
        return new VillageFootprint(level.dimension(), PLAINS, new ChunkPos(-124 + (dx >> 4), 82 + (dz >> 4)),
                new BoundingBox(-2052 + dx, 100, 1268 + dz, -1903 + dx, 156, 1380 + dz));
    }

    private static SettlementRecord record(String name, DetectionSource detection, ResourceLocation sourceId, String sourceKey,
                                           BlockPos anchor, int radius, SettlementBounds bounds, String biome, long created) {
        SettlementRecord record = new SettlementRecord();
        record.id = UUID.randomUUID();
        record.dimension = Level.OVERWORLD;
        record.anchor = anchor;
        record.radius = radius;
        record.bounds = bounds;
        record.kingdomId = new ResourceLocation("mcakingdoms", "serenum");
        record.displayName = name;
        record.slug = new ResourceLocation("mcakingdoms", name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        record.biomeAtCreation = new ResourceLocation(biome);
        record.assignmentSource = AssignmentSource.EXACT_BIOME;
        record.detectionSource = detection;
        record.sourceId = sourceId;
        record.sourceKey = sourceKey;
        record.createdGameTime = created;
        record.lastObservedGameTime = created;
        record.addDetectorIdentity(sourceId, sourceKey);
        return record;
    }

    private static SettlementSavedData load(CompoundTag tag) throws Exception {
        Method load = SettlementSavedData.class.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        return (SettlementSavedData) load.invoke(null, tag);
    }

    private static SettlementSavedData liveData(KingdomsServiceImpl api) throws Exception {
        Field field = KingdomsServiceImpl.class.getDeclaredField("data");
        field.setAccessible(true);
        return (SettlementSavedData) field.get(api);
    }

    /** Puts loaded records into the running service, as a world saved by an earlier build would hold them. */
    private static void adopt(KingdomsServiceImpl api, List<SettlementRecord> records) throws Exception {
        Field indexField = KingdomsServiceImpl.class.getDeclaredField("spatialIndex");
        indexField.setAccessible(true);
        SettlementSavedData live = liveData(api);
        SpatialSettlementIndex index = (SpatialSettlementIndex) indexField.get(api);
        for (SettlementRecord record : records) {
            live.add(record);
            index.add(record.snapshot());
        }
    }

    private static Set<UUID> visits(SettlementKnowledge knowledge, UUID player) {
        Set<UUID> seen = new HashSet<>();
        for (Tag entry : knowledge.save(new CompoundTag()).getList("Visits", Tag.TAG_COMPOUND)) {
            CompoundTag visitor = (CompoundTag) entry;
            if (!visitor.getUUID("Player").equals(player)) continue;
            for (Tag id : visitor.getList("Settlements", Tag.TAG_COMPOUND)) seen.add(((CompoundTag) id).getUUID("Id"));
        }
        return seen;
    }
}
