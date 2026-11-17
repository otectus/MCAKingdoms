package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns the Townstead subscription for one server session and turns its notices into bounded, coalesced invalidation work
 * (spec 5.4): one entry per village (or villager) and kind, handled a few per tick on the server thread, each handler
 * re-reading committed provider state. A notice is a hint: it never pays, promotes or records anything by itself. On
 * detach the subscription is closed and the queue is emptied, so nothing survives into the next world.
 */
final class TownsteadEventBridge {
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl service;
    private final LinkedHashMap<Key, TownsteadSignal> queue = new LinkedHashMap<>();
    private final List<Consumer<Handled>> listeners = new ArrayList<>();
    private dev.otectus.mcakingdoms.api.Registration subscription = () -> { };
    private boolean open;
    private long received;
    private long coalesced;
    private long dropped;
    private long handled;
    private long failures;
    private long lastFailureLog;
    private long session;
    private int maxDepth;

    /** One signal resolved to the settlement it concerns (null when it concerns none of ours). */
    record Handled(TownsteadSignal signal, UUID settlement) {
    }

    private record Key(TownsteadSignal.Kind kind, String dimension, int village, UUID subject) {
    }

    TownsteadEventBridge(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl service) {
        this.server = Objects.requireNonNull(server, "server");
        this.kingdoms = Objects.requireNonNull(kingdoms, "kingdoms");
        this.service = Objects.requireNonNull(service, "service");
    }

    void listen(Consumer<Handled> listener) {
        listeners.add(listener);
    }

    void open(long session) {
        this.session = session;
        open = true;
        subscription = service.backendImpl().subscribe(server, this::accept, this::residence);
    }

    void close() {
        open = false;
        try {
            subscription.close();
        } finally {
            subscription = () -> { };
            queue.clear();
        }
    }

    long session() {
        return session;
    }

    private UUID residence(Entity entity) {
        try {
            return kingdoms.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Called synchronously by Townstead after a change committed. Never reads the world here; it only queues. */
    void accept(TownsteadSignal signal) {
        if (!open || !server.isSameThread()) return;
        received++;
        Key key = new Key(signal.kind(), signal.dimension() == null ? "" : signal.dimension().toString(), signal.villageId(),
                signal.kind() == TownsteadSignal.Kind.LIFE || signal.kind() == TownsteadSignal.Kind.RESIDENT_HOME ? signal.subject() : null);
        if (queue.containsKey(key)) {
            coalesced++;
            queue.put(key, signal);
            return;
        }
        if (queue.size() >= TownsteadIntegrationConfig.EVENT_QUEUE_CAPACITY.get()) {
            dropped++;
            return;
        }
        queue.put(key, signal);
        maxDepth = Math.max(maxDepth, queue.size());
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() != server || !open || queue.isEmpty()) return;
        int budget = TownsteadIntegrationConfig.EVENT_BUDGET_PER_TICK.get();
        Iterator<TownsteadSignal> pending = queue.values().iterator();
        List<TownsteadSignal> batch = new ArrayList<>();
        while (budget-- > 0 && pending.hasNext()) {
            batch.add(pending.next());
            pending.remove();
        }
        for (TownsteadSignal signal : batch) {
            handled++;
            UUID settlement = settlementOf(signal).orElse(null);
            Handled done = new Handled(signal, settlement);
            for (Consumer<Handled> listener : listeners) {
                try {
                    listener.accept(done);
                } catch (RuntimeException failure) {
                    failures++;
                    // At most one line a minute, with the running count, so a broken handler cannot flood the log.
                    if (server.getTickCount() - lastFailureLog >= 1_200 || failures == 1) {
                        lastFailureLog = server.getTickCount();
                        com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] Townstead {} notice handler failed ({} failure(s) this session): {}",
                                signal.kind(), failures, failure.toString());
                    }
                }
            }
        }
    }

    /** The settlement a signal names: by exact MCA village reference, or the villager's Kingdoms residence. */
    Optional<UUID> settlementOf(TownsteadSignal signal) {
        if (signal.settlement() != null) return kingdoms.getSettlement(signal.settlement()).map(SettlementView::id);
        if (!signal.village()) return Optional.empty();
        return kingdoms.getSettlementForMcaVillage(signal.dimension(), signal.villageId()).map(SettlementView::id);
    }

    /** Asks Kingdoms' own detectors to look where Townstead founded a village; recognition follows the normal rules. */
    void discoverFounding(TownsteadSignal signal) {
        if (!signal.village()) return;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, signal.dimension()));
        if (level == null) return;
        service.backendImpl().village(level, signal.villageId()).map(v -> v.center()).filter(level::hasChunkAt)
                .ifPresent(center -> kingdoms.discover(level, new BlockPos(center), 0));
    }

    record Stats(boolean open, int depth, int maxDepth, long received, long coalesced, long dropped, long handled, long failures) {
    }

    Stats stats() {
        return new Stats(open, queue.size(), maxDepth, received, coalesced, dropped, handled, failures);
    }

    Map<String, Integer> depthByKind() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (TownsteadSignal signal : queue.values()) out.merge(signal.kind().name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
        return out;
    }
}
