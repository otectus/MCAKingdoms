package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaCommunityRef;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.townstead.BoundCivicBuilding;
import dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryPolicy;
import dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryResult;
import dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryStatus;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingAvailability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingState;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCalendarView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCareerView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadGeneView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadOriginView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadService;
import dev.otectus.mcakingdoms.api.townstead.TownsteadSpiritView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerNeedsView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The published {@link TownsteadService} over the selected backends. Townstead villages are mapped to settlements only by
 * their exact dimension-qualified MCA reference (spec 6.1), including references kept from merged records, and each
 * building keeps its original village id.
 */
final class TownsteadServiceImpl implements TownsteadService {
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadBackends.Selection selection;
    private final TownsteadBackend backend;
    private final TownsteadBackend buildingBackend;
    private final TownsteadCapabilities capabilities;
    private final TownsteadVillageLife life = new TownsteadVillageLife();
    private final AtomicLong revision = new AtomicLong();
    /** The last consistent building reading per settlement, for {@link TownsteadBuildingAvailability#STALE}. */
    private final Map<UUID, List<TownsteadBuildingState>> lastGood = new ConcurrentHashMap<>();

    TownsteadServiceImpl(MinecraftServer server, KingdomsService kingdoms, TownsteadBackends.Selection selection) {
        this.server = Objects.requireNonNull(server, "server");
        this.kingdoms = Objects.requireNonNull(kingdoms, "kingdoms");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.backend = selection.backend();
        this.buildingBackend = selection.buildings();
        this.capabilities = new TownsteadCapabilities(selection);
    }

    TownsteadBackend backendImpl() {
        return backend;
    }

    TownsteadBackend buildingBackend() {
        return buildingBackend;
    }

    TownsteadCapabilities states() {
        return capabilities;
    }

    TownsteadVillageLife lifeCache() {
        return life;
    }

    KingdomsService kingdoms() {
        return kingdoms;
    }

    MinecraftServer server() {
        return server;
    }

    @Override public int apiVersion() { return 1; }
    @Override public String backend() { return backend.id(); }

    @Override
    public Set<TownsteadCapability> capabilities() {
        Set<TownsteadCapability> out = new java.util.HashSet<>(backend.capabilities());
        out.removeAll(TownsteadCapabilities.BUILDING);
        for (TownsteadCapability capability : TownsteadCapabilities.BUILDING) {
            if (buildingBackend.capabilities().contains(capability)) out.add(capability);
        }
        // MCA's list answers a point query too when no provider has one (unchanged from 0.2.x).
        if (out.contains(TownsteadCapability.READ_BUILDINGS)) out.add(TownsteadCapability.READ_BUILDING);
        return Set.copyOf(out);
    }

    @Override
    public Map<TownsteadCapability, String> diagnostics() {
        Map<TownsteadCapability, String> out = new LinkedHashMap<>(backend.diagnostics());
        for (TownsteadCapability capability : TownsteadCapabilities.BUILDING) out.put(capability, buildingBackend.diagnostics().getOrDefault(capability, "unavailable"));
        return Map.copyOf(out);
    }

    @Override
    public Map<TownsteadCapability, TownsteadCapabilityState> capabilityStates() {
        return capabilities.states();
    }

    @Override
    public Optional<TownsteadVillagerView> villager(Entity entity) {
        if (entity.getServer() != server) return Optional.empty();
        return backend.villager(entity);
    }

    @Override
    public Optional<TownsteadVillagerNeedsView> villagerNeeds(Entity entity) {
        if (entity.getServer() != server) return Optional.empty();
        return backend.needs(entity);
    }

    @Override
    public Optional<TownsteadCareerView> career(Entity entity) {
        if (entity.getServer() != server) return Optional.empty();
        return backend.career(entity);
    }

    @Override
    public Optional<Boolean> systemEnabled(String system) {
        return backend.systemEnabled(system);
    }

    @Override
    public Optional<TownsteadCalendarView> calendar() {
        return backend.calendar(server);
    }

    // ---- identity ----

    /** Every MCA village the settlement holds in this level, its own reference first, then those kept from merges. */
    List<Integer> villageIds(SettlementView settlement, ResourceLocation dimension) {
        List<Integer> out = new ArrayList<>();
        Optional.ofNullable(settlement.externalRefs().get(McaCommunityRef.EXTERNAL_REF_NAMESPACE)).flatMap(McaCommunityRef::parse)
                .filter(ref -> ref.dimension().equals(dimension)).ifPresent(ref -> out.add(ref.villageId()));
        for (String value : kingdoms.getSettlementExternalRefs(settlement.id()).getOrDefault(McaCommunityRef.EXTERNAL_REF_NAMESPACE, Set.of())) {
            McaCommunityRef.parse(value).filter(ref -> ref.dimension().equals(dimension)).map(McaCommunityRef::villageId)
                    .filter(id -> !out.contains(id)).ifPresent(out::add);
        }
        List<Integer> merged = new ArrayList<>(out.subList(Math.min(1, out.size()), out.size()));
        merged.sort(Comparator.naturalOrder());
        List<Integer> ordered = new ArrayList<>(out.isEmpty() ? List.of() : List.of(out.get(0)));
        ordered.addAll(merged);
        return List.copyOf(ordered);
    }

    private Optional<SettlementView> settlementIn(ServerLevel level, UUID settlementId) {
        if (level.getServer() != server) return Optional.empty();
        return kingdoms.getSettlement(settlementId).filter(settlement -> settlement.dimension().equals(level.dimension()));
    }

    // ---- buildings ----

    /** One settlement's buildings with how consistently they were read. */
    record SettlementBuildings(List<TownsteadBuildingEvidence> buildings, boolean consistent, boolean villages, String detail) {
    }

    SettlementBuildings read(ServerLevel level, UUID settlementId) {
        Optional<SettlementView> settlement = settlementIn(level, settlementId);
        if (settlement.isEmpty()) return new SettlementBuildings(List.of(), false, false, "settlement not in this level");
        List<Integer> villages = villageIds(settlement.get(), level.dimension().location());
        if (villages.isEmpty()) return new SettlementBuildings(List.of(), true, false, "no MCA village reference");
        List<TownsteadBuildingEvidence> out = new ArrayList<>();
        boolean consistent = true;
        List<String> details = new ArrayList<>();
        for (int villageId : villages) {
            TownsteadBackend.BuildingRead read = buildingBackend.buildings(level, villageId);
            if (read.status() != TownsteadBackend.BuildingRead.Status.OK) {
                consistent = false;
                details.add(read.status().name().toLowerCase(java.util.Locale.ROOT) + ": " + read.detail());
            }
            for (TownsteadBackend.RawBuilding raw : read.buildings()) {
                view(level, settlement.get(), raw).ifPresent(out::add);
            }
        }
        if (consistent) capabilities.recovered(TownsteadCapability.READ_BUILDINGS);
        else capabilities.degraded(TownsteadCapability.READ_BUILDINGS, String.join("; ", details));
        out.sort(Comparator.comparingInt((TownsteadBuildingEvidence e) -> e.building().villageId()).thenComparingInt(e -> e.building().buildingId()));
        return new SettlementBuildings(List.copyOf(out), consistent, true, String.join("; ", details));
    }

    private Optional<TownsteadBuildingEvidence> view(ServerLevel level, SettlementView settlement, TownsteadBackend.RawBuilding raw) {
        try {
            UUID bindingId = TownsteadBuildingView.bindingId(level.dimension().location(), settlement.id(), raw.villageId(), raw.id());
            TownsteadBuildingView view = new TownsteadBuildingView(level.dimension().location(), settlement.id(), bindingId,
                    raw.villageId(), raw.id(), raw.type(), raw.family(), Math.max(1, raw.tier()), raw.size(),
                    raw.center().getX(), raw.center().getY(), raw.center().getZ(),
                    raw.min().getX(), raw.min().getY(), raw.min().getZ(), raw.max().getX(), raw.max().getY(), raw.max().getZ());
            return Optional.of(new TownsteadBuildingEvidence(view, raw.completion(), raw.source()));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public List<TownsteadBuildingView> buildings(ServerLevel level, UUID settlementId) {
        return read(level, settlementId).buildings().stream().map(TownsteadBuildingEvidence::building).toList();
    }

    @Override
    public List<TownsteadBuildingEvidence> buildingEvidence(ServerLevel level, UUID settlementId) {
        return read(level, settlementId).buildings();
    }

    @Override
    public Optional<TownsteadBuildingView> buildingAt(ServerLevel level, BlockPos pos) {
        return buildingEvidenceAt(level, pos).map(TownsteadBuildingEvidence::building);
    }

    /**
     * Townstead's own point query when it has one (exact footprints for pens, wharves and open-air buildings); otherwise
     * MCA's list, the smallest box containing the position winning, as in 0.2.x.
     */
    @Override
    public Optional<TownsteadBuildingEvidence> buildingEvidenceAt(ServerLevel level, BlockPos pos) {
        if (level.getServer() != server) return Optional.empty();
        if (buildingBackend.capabilities().contains(TownsteadCapability.READ_BUILDING)
                && !(buildingBackend instanceof TownsteadLegacyBackend legacy && legacy.buildingFallback())) {
            return buildingBackend.buildingAt(level, pos).flatMap(raw -> kingdoms.getSettlementForMcaVillage(level.dimension().location(), raw.villageId())
                    .filter(settlement -> settlement.dimension().equals(level.dimension()))
                    .flatMap(settlement -> view(level, settlement, raw)));
        }
        return kingdoms.getSettlementAt(level, pos).stream()
                .flatMap(settlement -> buildingEvidence(level, settlement.id()).stream())
                .filter(evidence -> evidence.building().contains(pos))
                .min(Comparator.comparingLong((TownsteadBuildingEvidence e) -> volume(e.building()))
                        .thenComparingInt(e -> e.building().buildingId()));
    }

    private static long volume(TownsteadBuildingView building) {
        return (long) (building.maxX() - building.minX() + 1) * (building.maxY() - building.minY() + 1)
                * (building.maxZ() - building.minZ() + 1);
    }

    void rememberGood(UUID settlement, List<TownsteadBuildingState> states) {
        lastGood.put(settlement, List.copyOf(states));
    }

    void forget(UUID settlement) {
        lastGood.remove(settlement);
    }

    @Override
    public TownsteadBuildingAvailability availability(ServerLevel level, BoundCivicBuilding binding) {
        if (level.getServer() != server || !level.dimension().location().equals(binding.dimension())) return TownsteadBuildingAvailability.UNLOADED;
        if (!buildingBackend.capabilities().contains(TownsteadCapability.READ_BUILDINGS)) return TownsteadBuildingAvailability.PROVIDER_UNAVAILABLE;
        Optional<SettlementView> settlement = kingdoms.getSettlement(binding.settlementId());
        if (settlement.isEmpty()) return TownsteadBuildingAvailability.UNKNOWN;
        BlockPos anchor = settlement.get().anchor();
        if (!level.hasChunkAt(anchor)) return TownsteadBuildingAvailability.UNLOADED;
        SettlementBuildings read = read(level, settlement.get().id());
        boolean present = read.buildings().stream().anyMatch(e -> e.building().villageId() == binding.villageId()
                && e.building().buildingId() == binding.buildingId());
        if (present) return read.consistent() ? TownsteadBuildingAvailability.CURRENT : TownsteadBuildingAvailability.STALE;
        if (!read.villages()) return TownsteadBuildingAvailability.UNKNOWN;
        if (!read.consistent()) {
            boolean seen = lastGood.getOrDefault(settlement.get().id(), List.of()).stream()
                    .anyMatch(s -> s.villageId() == binding.villageId() && s.buildingId() == binding.buildingId());
            return seen ? TownsteadBuildingAvailability.STALE : TownsteadBuildingAvailability.UNKNOWN;
        }
        return TownsteadBuildingAvailability.CONFIRMED_REMOVED;
    }

    @Override
    public List<TownsteadBuildingEvidence> replacementCandidates(ServerLevel level, BoundCivicBuilding binding, int minimumTier) {
        if (level.getServer() != server) return List.of();
        UUID settlement = kingdoms.resolveSettlementId(binding.settlementId());
        SettlementBuildings read = read(level, settlement);
        if (!read.consistent()) return List.of();
        return read.buildings().stream()
                .filter(e -> e.building().family().equals(binding.family()))
                .filter(e -> !(e.building().villageId() == binding.villageId() && e.building().buildingId() == binding.buildingId()))
                .filter(TownsteadBuildingEvidence::verifiedComplete)
                .filter(e -> e.building().level() >= Math.max(1, minimumTier))
                .sorted(Comparator.comparingInt((TownsteadBuildingEvidence e) -> e.building().villageId()).thenComparingInt(e -> e.building().buildingId()))
                .toList();
    }

    @Override
    public Optional<TownsteadOriginView> origin(ResourceLocation id) {
        return backend.origin(id);
    }

    @Override
    public Optional<TownsteadGeneView> gene(ResourceLocation id) {
        return backend.gene(id);
    }

    @Override
    public Optional<TownsteadSpiritView> spirit(ServerLevel level, UUID settlementId) {
        return settlementIn(level, settlementId).flatMap(settlement -> villageIds(settlement, level.dimension().location()).stream().findFirst())
                .flatMap(villageId -> backend.spirit(level, villageId));
    }

    @Override
    public List<TownsteadVillageView> villages(UUID settlementId) {
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return List.of();
        ServerLevel level = server.getLevel(settlement.get().dimension());
        if (level == null) return List.of();
        return villageIds(settlement.get(), level.dimension().location()).stream()
                .map(villageId -> backend.village(level, villageId)).flatMap(Optional::stream).toList();
    }

    @Override
    public Optional<TownsteadVillageLifeView> villageLife(UUID settlementId) {
        if (!server.isSameThread()) return Optional.empty();
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty() || !capabilities.usable(TownsteadCapability.READ_NEEDS_SUMMARY)) return Optional.empty();
        long now = server.getTickCount();
        UUID id = settlement.get().id();
        Optional<TownsteadVillageLifeView> cached = life.cached(id, now, TownsteadIntegrationConfig.VILLAGE_LIFE_REFRESH_TICKS.get());
        if (cached.isPresent()) return cached;
        ResourceLocation dimension = settlement.get().dimension().location();
        Map<Integer, TownsteadBackend.NeedsSummary> summaries = new LinkedHashMap<>();
        for (int villageId : villageIds(settlement.get(), dimension)) {
            backend.needsSummary(server, dimension, villageId).ifPresent(summary -> summaries.put(villageId, summary));
        }
        if (summaries.isEmpty()) return Optional.empty();
        TownsteadVillageLifeView view = TownsteadVillageLife.interpret(id, dimension, summaries, new TownsteadVillageLife.Limits(
                TownsteadIntegrationConfig.WELFARE_MINIMUM_COVERAGE.get(), TownsteadIntegrationConfig.WELFARE_MAXIMUM_SAMPLE_AGE_DAYS.get()));
        life.put(id, view, now);
        return Optional.of(view);
    }

    @Override
    public BuildingRecoveryResult recover(ServerLevel level, BoundCivicBuilding bound,
                                           BuildingRecoveryPolicy policy) {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(policy, "policy");
        if (level.getServer() != server || !level.dimension().location().equals(bound.dimension())) {
            return result(BuildingRecoveryStatus.WAITING, bound, null, "bound dimension is not loaded here");
        }
        Optional<SettlementView> settlement = kingdoms.getSettlement(bound.settlementId());
        if (settlement.isEmpty()) {
            return result(BuildingRecoveryStatus.FAILED, bound, null, "settlement no longer exists");
        }
        SettlementBuildings read = read(level, settlement.get().id());
        List<TownsteadBuildingView> buildings = read.buildings().stream().map(TownsteadBuildingEvidence::building).toList();
        Optional<TownsteadBuildingView> exact = buildings.stream()
                .filter(building -> building.villageId() == bound.villageId() && building.buildingId() == bound.buildingId())
                .filter(building -> building.family().equals(bound.family()))
                .findFirst();
        if (exact.isPresent()) {
            // A binding made before its settlement merged still finds the building; it comes back with the survivor's id.
            return result(BuildingRecoveryStatus.FOUND, bound.forSettlement(settlement.get().id()), exact.get(),
                    "building resolved");
        }
        if (!buildingBackend.capabilities().contains(TownsteadCapability.READ_BUILDINGS)) {
            return result(missingBuildingStatus(policy, false), bound, null,
                    "loaded-village building capability is unavailable");
        }
        // An unreadable provider or a missing village record is not evidence that the building is gone (spec 7.3).
        if (!read.consistent() || !read.villages()) {
            return result(BuildingRecoveryStatus.WAITING, bound, null, "building records could not be read consistently; waiting");
        }
        return switch (policy) {
            case WAIT -> result(missingBuildingStatus(policy, true), bound, null, "bound building is absent");
            case FAIL_WITH_REASON -> result(missingBuildingStatus(policy, true), bound, null,
                    "bound building is absent or changed family");
            case REBIND_SAME_FAMILY -> sameFamilyCandidate(buildings, bound.family())
                    .map(building -> result(BuildingRecoveryStatus.REBOUND,
                            BoundCivicBuilding.from(building), building,
                            "rebound to lowest building id in the same family"))
                    .orElseGet(() -> result(BuildingRecoveryStatus.WAITING, bound, null,
                            "no loaded building in the required family"));
        };
    }

    static BuildingRecoveryStatus missingBuildingStatus(BuildingRecoveryPolicy policy, boolean readable) {
        return readable && policy == BuildingRecoveryPolicy.FAIL_WITH_REASON
                ? BuildingRecoveryStatus.FAILED : BuildingRecoveryStatus.WAITING;
    }

    @Override
    public boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> playerCause,
                                ResourceLocation reactionId, Set<String> contextTags) {
        if (!TownsteadIntegrationConfig.ENABLE_REACTIONS.get()
                || level.getServer() != server || villager.level() != level) return false;
        return backend.fireReaction(level, villager, playerCause, reactionId, contextTags);
    }

    @Override public long revision() { return revision.get(); }

    void reconciledChange() { revision.incrementAndGet(); }

    private static BuildingRecoveryResult result(BuildingRecoveryStatus status, BoundCivicBuilding binding,
                                                 TownsteadBuildingView building, String reason) {
        return new BuildingRecoveryResult(status, Optional.ofNullable(binding), Optional.ofNullable(building), reason);
    }

    static Optional<TownsteadBuildingView> sameFamilyCandidate(List<TownsteadBuildingView> buildings,
                                                                String family) {
        Objects.requireNonNull(buildings, "buildings");
        Objects.requireNonNull(family, "family");
        return buildings.stream()
                .filter(building -> building.family().equals(family))
                .min(Comparator.comparingInt(TownsteadBuildingView::buildingId));
    }
}
