package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingFingerprint;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingState;
import dev.otectus.mcakingdoms.api.townstead.event.TownsteadBuildingsChangedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Building reconciliation (spec 5.4, 7.2, 12.1). Townstead's building and spirit events mark a settlement dirty and it is
 * re-read within a few ticks; the slow poll (every {@code buildingReconcileIntervalTicks}) still covers settlements around
 * online players, round robin, so no ordering of the player list starves one, and catches changes no event reports. The
 * first reading of a settlement is its baseline, never a change; a reading that is not consistent changes nothing; a
 * settlement that leaves the active set keeps its fingerprint, so a change made while nobody watched is still noticed.
 */
final class TownsteadBuildingReconciler {
    private static final int RETAINED = 1_024;
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private final Map<UUID, List<TownsteadBuildingState>> fingerprints = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, List<TownsteadBuildingState>> eldest) { return size() > RETAINED; }
    };
    /** Server tick of each settlement's last consistent reading, for diagnostics. */
    private final Map<UUID, Long> readAt = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) { return size() > RETAINED; }
    };
    private final Deque<UUID> rotation = new ArrayDeque<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private long reads;
    private long inconsistent;
    private long changes;

    TownsteadBuildingReconciler(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    void markDirty(UUID settlement) {
        if (settlement != null && dirty.size() < TownsteadIntegrationConfig.EVENT_QUEUE_CAPACITY.get()) dirty.add(settlement);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() != server) return;
        int budget = TownsteadIntegrationConfig.EVENT_BUDGET_PER_TICK.get();
        var pending = dirty.iterator();
        while (budget-- > 0 && pending.hasNext()) {
            UUID settlement = pending.next();
            pending.remove();
            reconcile(settlement);
        }
        if (server.getTickCount() % TownsteadIntegrationConfig.BUILDING_RECONCILE_INTERVAL.get() != 0) return;
        Set<UUID> active = new LinkedHashSet<>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level)) continue;
            kingdoms.getSettlementAt(level, player.blockPosition()).ifPresent(settlement -> active.add(settlement.id()));
        }
        rotation.removeIf(id -> !active.contains(id));
        for (UUID id : active) if (!rotation.contains(id)) rotation.addLast(id);
        int limit = Math.min(TownsteadIntegrationConfig.MAX_ACTIVE_SETTLEMENTS.get(), rotation.size());
        for (int i = 0; i < limit; i++) {
            UUID next = rotation.pollFirst();
            if (next == null) break;
            rotation.addLast(next);
            reconcile(next);
        }
    }

    /** One bounded re-read; returns whether the settlement's buildings changed. */
    boolean reconcile(UUID settlementId) {
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return false;
        ServerLevel level = server.getLevel(settlement.get().dimension());
        if (level == null) return false;
        reads++;
        TownsteadServiceImpl.SettlementBuildings read = townstead.read(level, settlement.get().id());
        if (!read.consistent() || !read.villages()) {
            if (!read.consistent()) inconsistent++;
            return false;
        }
        List<TownsteadBuildingState> current = read.buildings().stream().map(TownsteadBuildingState::of)
                .sorted(TownsteadBuildingState.ORDER).toList();
        UUID id = settlement.get().id();
        List<TownsteadBuildingState> previous = fingerprints.put(id, current);
        readAt.put(id, (long) server.getTickCount());
        townstead.rememberGood(id, current);
        // First consistent sight with Townstead: a baseline for restored-backup checks, never an award (spec 6.3).
        if (!"mca".equals(townstead.backend()) && !"none".equals(townstead.backend())) TownsteadAuthority.get(server).ifPresent(authority -> authority.observe(id));
        if (previous == null || previous.equals(current)) return false;
        changes++;
        townstead.reconciledChange();
        townstead.lifeCache().invalidate(id);
        List<TownsteadBuildingFingerprint> before = previous.stream().map(TownsteadBuildingState::legacy).toList();
        List<TownsteadBuildingFingerprint> after = current.stream().map(TownsteadBuildingState::legacy).toList();
        MinecraftForge.EVENT_BUS.post(new TownsteadBuildingsChangedEvent(id, level.dimension().location(), before, after, previous, current));
        return true;
    }

    Optional<List<TownsteadBuildingState>> fingerprint(UUID settlement) {
        return Optional.ofNullable(fingerprints.get(settlement));
    }

    /** Server ticks since the settlement's last consistent reading this session. */
    java.util.OptionalLong readAge(UUID settlement) {
        Long at = readAt.get(settlement);
        return at == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(Math.max(0L, server.getTickCount() - at));
    }

    record Stats(int tracked, int rotation, int dirty, long reads, long inconsistent, long changes) {
    }

    Stats stats() {
        return new Stats(fingerprints.size(), rotation.size(), dirty.size(), reads, inconsistent, changes);
    }

    void clear() {
        fingerprints.clear();
        readAt.clear();
        rotation.clear();
        dirty.clear();
    }
}
