package dev.otectus.mcakingdoms.compat.livingvillages;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.*;

/**
 * One server's Living Villages bridge: its store, its change queue, its bounded batches and its save acknowledgements.
 * Created at server start when the integration is active and discarded at stop, so a second world opened in the same
 * JVM starts from its own records and never sees the first world's queues or caches.
 */
public final class LvRuntime {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, LvRuntime> RUNTIMES = new WeakHashMap<>();

    final MinecraftServer server;
    final LvAccess access;
    final LvBridgeSavedData data;
    /** Villages whose provider record changed since the last batch, in arrival order. */
    final LinkedHashSet<UUID> changed = new LinkedHashSet<>();
    /** Operations applied in memory and waiting for the next overworld save before they count as acknowledged. */
    final Set<UUID> awaitingSave = new LinkedHashSet<>();
    /** Pending resident lifecycle updates, folded into the store once a second. */
    final Map<UUID, LvState.Resident> residentUpdates = new LinkedHashMap<>();
    final Set<String> loggedOnce = new HashSet<>();
    /** Refusals already reported for an attacker and target pair, so one protected capital is logged once per interval. */
    final Map<String, Long> refusalSeen = new HashMap<>();
    UUID reconcileCursor;
    /** Game time of the last overworld save this runtime saw; announcements wait for it. */
    long lastSaved = -1;
    long ticks;
    boolean started;

    private LvRuntime(MinecraftServer server, LvAccess access) {
        this.server = server;
        this.access = access;
        this.data = LvBridgeSavedData.get(server);
    }

    /**
     * The running bridge, or empty when the integration is inactive on this server. Turning {@code livingVillages.enabled}
     * off takes effect at once: every hook then leaves Living Villages to its own behaviour, and the bridge resumes when it
     * is turned back on. A server that started with it off starts the bridge at its next start.
     */
    public static Optional<LvRuntime> get(MinecraftServer server) {
        if (server == null || !LvConfig.enabled()) return Optional.empty();
        synchronized (RUNTIMES) {
            return Optional.ofNullable(RUNTIMES.get(server));
        }
    }

    static Optional<LvRuntime> of(Level level) {
        return level instanceof ServerLevel server ? get(server.getServer()) : Optional.empty();
    }

    /**
     * Starts the bridge for {@code server} when the supported tuple is installed and enabled. A world that already holds
     * integrated residents is refused (the server stops before any provider tick) if their protection hooks are not active,
     * unless an operator acknowledged it; silently letting Living Villages discard those people is not a fallback.
     */
    public static void attach(MinecraftServer server) {
        if (!LvCompat.installed()) return;
        var data = LvBridgeSavedData.get(server);
        var view = data.view();
        boolean integrated = view.bindings.values().stream().anyMatch(b -> b.status() == LvState.BindingStatus.BOUND)
                && view.residents.values().stream().anyMatch(r -> r.lifecycle() != LvState.Lifecycle.DEAD);
        var snapshot = LvCompat.snapshot(server);
        if (integrated && LvConfig.enabled() && LvConfig.persistentResidents() && !snapshot.ready(LvCompat.Capability.RESIDENT_PRESERVATION)
                && !LvConfig.allowUnprotected()) {
            throw new IllegalStateException("[MCA: Kingdoms] This world has residents of Living Villages villages that MCA: Kingdoms protects, "
                    + "but the protection hooks are not active (" + snapshot.of(LvCompat.Capability.RESIDENT_PRESERVATION).reason() + "; installed: "
                    + snapshot.tuple() + "). Living Villages would discard and respawn them. Install Recruits 1.15.2 with the audited "
                    + "Recruits: Living Villages 1.1.0 jar, or set livingVillages.allowUnprotectedResidents = true in "
                    + "config/mcakingdoms-integrations-common.toml to accept that risk. Nothing in the world was changed.");
        }
        if (!LvCompat.active(server)) {
            LOGGER.info("[MCA: Kingdoms] Recruits: Living Villages integration inactive ({}): saved bridge records are kept unchanged",
                    snapshot.of(LvCompat.Capability.STABLE_BINDING).reason());
            return;
        }
        var access = LvAccess.get().orElse(null);
        if (access == null) return;
        var runtime = new LvRuntime(server, access);
        synchronized (RUNTIMES) {
            RUNTIMES.put(server, runtime);
        }
        LvBindings.registerFootprints(runtime);
        LvHooks.registerNativeListeners();
        LOGGER.info("[MCA: Kingdoms] Recruits: Living Villages integration active ({}; {})", snapshot.tuple(), snapshot.overall());
    }

    public static void detach(MinecraftServer server) {
        LvRuntime runtime;
        synchronized (RUNTIMES) {
            runtime = RUNTIMES.remove(server);
        }
        if (runtime != null) {
            runtime.flushResidents();
            runtime.changed.clear();
            runtime.awaitingSave.clear();
            runtime.residentUpdates.clear();
            runtime.refusalSeen.clear();
            LvBindings.closeFootprints(runtime);
        }
        LvHooks.clearTransient();
    }

    KingdomsService kingdoms() {
        return McaKingdomsApi.get(server);
    }

    long now() {
        return server.overworld().getGameTime();
    }

    ServerLevel overworld() {
        return server.overworld();
    }

    LvState view() {
        return data.view();
    }

    /** Commits {@code next}; on failure logs once and returns false, leaving everything as it was. */
    boolean commit(LvState next) {
        flushResidentsInto(next);
        if (data.commit(server, next)) return true;
        once("commit", "Living Villages bridge records could not be saved; the change was not applied");
        return false;
    }

    void once(String key, String message, Object... args) {
        if (loggedOnce.add(key)) LOGGER.warn("[MCA: Kingdoms] " + message, args);
    }

    /** Marks a provider village for the next batch. Cheap; safe to call from any provider hook on the server thread. */
    void touch(UUID village) {
        if (village != null && changed.size() < 4096) changed.add(village);
    }

    void stage(LvState.Resident resident) {
        residentUpdates.put(resident.entity(), resident);
    }

    private void flushResidentsInto(LvState next) {
        if (residentUpdates.isEmpty()) return;
        for (var update : residentUpdates.values()) {
            if (!next.residents.containsKey(update.entity()) && next.residents.size() >= LvState.RESIDENTS - 1) continue;
            next.residents.put(update.entity(), update);
        }
        residentUpdates.clear();
    }

    void flushResidents() {
        if (residentUpdates.isEmpty() || !data.writable()) return;
        var next = data.snapshot();
        flushResidentsInto(next);
        try {
            data.markChanged(next);
        } catch (RuntimeException invalid) {
            once("residents", "Living Villages resident records could not be updated: {}", invalid.toString());
        }
    }

    /** Called from the server tick: bounded batches only, never a whole-world scan. */
    void tick() {
        ticks++;
        if (!started) {
            started = true;
            LvBindings.begin(this);
        }
        if (ticks % 20 == 0) flushResidents();
        if (ticks % LvConfig.batchInterval() == 0) LvBindings.batch(this);
        if (ticks % LvConfig.reconcileInterval() == 0) {
            LvBindings.slice(this);
            LvDiplomacy.maintain(this);
            LvWarBridge.maintain(this);
            if (LvConfig.autoAdopt()) LvAdoption.automatic(this);
        }
        if (ticks % 40 == 0) LvNotices.deliver(this);
    }

    /** The overworld finished writing its saved data and entities: what was applied before now is durable. */
    void saved() {
        lastSaved = now();
        if (awaitingSave.isEmpty()) return;
        if (((net.minecraft.world.level.saveddata.SavedData) access.store(server)).isDirty()) return;
        var next = data.snapshot();
        var acknowledged = new ArrayList<LvState.Operation>();
        for (UUID id : List.copyOf(awaitingSave)) {
            var op = next.journal.get(id);
            awaitingSave.remove(id);
            if (op == null || op.state() != LvState.OperationState.APPLIED) continue;
            var done = op.state(LvState.OperationState.ACKNOWLEDGED, null, now());
            next.journal.put(id, done);
            acknowledged.add(done);
        }
        if (acknowledged.isEmpty() || !commit(next)) return;
        for (var op : acknowledged) LvEconomy.acknowledged(this, op);
        LvNotices.publishAcknowledged(this);
    }

    boolean online(UUID player) {
        return player != null && server.getPlayerList().getPlayer(player) != null;
    }

    Optional<ServerPlayer> player(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(server.getPlayerList().getPlayer(id));
    }
}
