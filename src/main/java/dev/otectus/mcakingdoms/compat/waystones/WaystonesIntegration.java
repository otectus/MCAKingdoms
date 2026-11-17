package dev.otectus.mcakingdoms.compat.waystones;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.event.SettlementCreatedEvent;
import dev.otectus.mcakingdoms.compat.waystones.WaystonesNamingData.Entry;
import dev.otectus.mcakingdoms.compat.waystones.WaystonesNamingData.Stage;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.*;

/**
 * Settlement names for waystones (WAY-01). When a normal waystone inside a recognized settlement first needs a name, it
 * gets the settlement's name: a generated stone at its first activation (through Waystones' own name event, after
 * Waystones resolved its duplicates), a newly placed unnamed stone during its placement, before the settings screen
 * opens. A stone outside every settlement, a stone that already has a name, a sharestone, warp plate or portstone keeps
 * Waystones' own behaviour. Names that collide get " (2)", " (3)" and so on, kept across restarts.
 *
 * <p>Naming is one-time: a later settlement rename or merge never renames a stone. A player's own name always wins: the
 * native edit is observed (only on the tested Waystones build) and the stone is never touched again unless someone with
 * Waystones' own edit rights explicitly asks for the settlement name. Stone id, owner, global flag, activation, costs
 * and cooldowns are never changed, nobody is given a destination, and no chunk is loaded.
 *
 * <p>Tested with Waystones 14.1.21 and Balm 7.3.44; any other version reports itself unsupported and changes nothing.
 */
public final class WaystonesIntegration {
    private static final Logger LOG = LogUtils.getLogger();
    static final String TESTED_WAYSTONES = "14.1.21", TESTED_BALM = "7.3.44";
    /** Pending stones looked at again per pass, and the pass period in ticks. */
    static final int PENDING_BUDGET = 16, PENDING_PERIOD = 200;
    /** How close a stone must be for "Name this waystone after the settlement". */
    static final double NEAR = 8.0;
    /** The most registered waystones read in one pass; a larger registry is read up to here. */
    static final int REGISTRY_LIMIT = 65_536;

    public enum Readiness { ABSENT, READY, UNSUPPORTED, DISABLED }

    /** Whether the integration works here, the installed versions and the reason when it does not. */
    public record Status(Readiness readiness, String waystones, String balm, String reason) {
        public boolean ready() { return readiness == Readiness.READY; }
    }

    private static volatile WaystonesAccess access;
    private static volatile Status status = new Status(Readiness.ABSENT, "", "", "not checked yet");
    private static volatile boolean editObserved;

    private WaystonesIntegration() {
    }

    private static String version(String mod) {
        return ModList.get().getModContainerById(mod).map(c -> c.getModInfo().getVersion().toString()).orElse("");
    }

    /**
     * Binds Waystones and registers the two Balm listeners, once per game (common setup, when every mod is constructed).
     * The settings switch is read per event, so turning it off stops naming at once.
     */
    public static synchronized void setup() {
        String waystones = version("waystones"), balm = version("balm");
        if (waystones.isEmpty()) { status = new Status(Readiness.ABSENT, "", balm, "Waystones is not installed"); return; }
        if (!waystones.equals(TESTED_WAYSTONES) || !balm.equals(TESTED_BALM)) {
            status = new Status(Readiness.UNSUPPORTED, waystones, balm, "the integration is tested with Waystones " + TESTED_WAYSTONES + " and Balm " + TESTED_BALM);
            LOG.warn("[MCA: Kingdoms] Waystones {} with Balm {} is not the tested pair ({} with {}); waystones keep their own names", waystones, balm, TESTED_WAYSTONES, TESTED_BALM);
            return;
        }
        var bound = WaystonesAccess.bind();
        if (bound.access().isEmpty()) {
            status = new Status(Readiness.UNSUPPORTED, waystones, balm, "a Waystones member is missing: " + bound.reason());
            LOG.warn("[MCA: Kingdoms] The Waystones integration is off: {}", bound.reason());
            return;
        }
        var bind = bound.access().get();
        bind.listen(bind.nameEvent, WaystonesIntegration::nameEvent);
        bind.listen(bind.activatedEvent, WaystonesIntegration::activated);
        access = bind;
        status = new Status(Readiness.READY, waystones, balm, "");
        LOG.info("[MCA: Kingdoms] Waystones {} with Balm {}: new waystones in settlements take the settlement's name", waystones, balm);
    }

    /** The integration's state now, including the settings switch. */
    public static Status status() {
        var now = status;
        if (now.ready() && !enabled()) return new Status(Readiness.DISABLED, now.waystones(), now.balm(), "turned off by waystones.settlementNames");
        return now;
    }

    /** Whether Waystones' own edits are observed, so a player's later rename is respected (the tested build only). */
    public static boolean editsObserved() {
        return editObserved || "applied".equals(System.getProperty("mcakingdoms.waystones.gate"));
    }

    static boolean enabled() {
        return !McaKingdomsConfig.SPEC.isLoaded() || McaKingdomsConfig.WAYSTONE_SETTLEMENT_NAMES.get();
    }

    private static long pendingTicks() {
        return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.WAYSTONE_PENDING_TICKS.get() : 6000;
    }

    private static Optional<MinecraftServer> server() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread() ? Optional.of(server) : Optional.empty();
    }

    // ---- naming ----

    /** A proposed name: the settlement, its base name and the collision-safe assignment. */
    record Proposal(SettlementView settlement, String base, WaystoneNames.Assignment assignment) { }

    /** Why a stone keeps its name, or the name it would get now. */
    record Decision(Optional<Proposal> proposal, WaystoneNames.Reason reason) { }

    private static String dimension(Object stone) { return access.dimension(stone).location().toString(); }

    /** The settlement name {@code stone} would get now, or why it keeps its own. Reads only; loads no chunk. */
    static Decision decide(MinecraftServer server, Object stone) {
        return decide(server, stone, null);
    }

    /**
     * {@code used}: whether a name (as {@link WaystoneNames#key} compares it) is taken by another stone or reservation,
     * when a caller decides many stones from one index; null reads the registry for this stone alone.
     */
    private static Decision decide(MinecraftServer server, Object stone, java.util.function.Predicate<String> used) {
        var bind = access;
        if (bind == null) return new Decision(Optional.empty(), WaystoneNames.Reason.PROVIDER_UNSUPPORTED);
        if (!bind.normal(stone)) return new Decision(Optional.empty(), WaystoneNames.Reason.NOT_A_WAYSTONE);
        ServerLevel level = server.getLevel(bind.dimension(stone));
        if (level == null) return new Decision(Optional.empty(), WaystoneNames.Reason.OUTSIDE_SETTLEMENT);
        var settlement = McaKingdomsApi.get(server).getSettlementAt(level, bind.pos(stone));
        if (settlement.isEmpty()) return new Decision(Optional.empty(), WaystoneNames.Reason.OUTSIDE_SETTLEMENT);
        var base = WaystoneNames.base(settlement.get().displayName());
        if (base.isEmpty()) return new Decision(Optional.empty(), WaystoneNames.Reason.UNUSABLE_NAME);
        if (used == null) {
            UUID self = bind.id(stone);
            var taken = new HashSet<String>();
            bind.all(server).limit(REGISTRY_LIMIT).forEach(other -> { if (!bind.id(other).equals(self)) taken.add(WaystoneNames.key(bind.name(other))); });
            for (var e : WaystonesNamingData.get(server).entries()) if (e.stage().reserves() && !e.stone().equals(self)) taken.add(WaystoneNames.key(e.name()));
            used = taken::contains;
        }
        var assignment = WaystoneNames.assign(base.get(), used);
        if (assignment.isEmpty()) return new Decision(Optional.empty(), WaystoneNames.Reason.NO_FREE_NAME);
        return new Decision(Optional.of(new Proposal(settlement.get(), base.get(), assignment.get())), WaystoneNames.Reason.NAMED);
    }

    private static Entry fresh(Object stone, long now) {
        return new Entry(access.id(stone), dimension(stone), access.pos(stone).immutable(), null, "", "", 0, Stage.PENDING, now, now, "");
    }

    private static boolean detached(WaystonesNamingData data, UUID stone) {
        return data.entry(stone).map(e -> e.stage() == Stage.DETACHED || e.stage() == Stage.UNRESOLVED).orElse(false);
    }

    /**
     * Waystones asks for a generated stone's first name; Waystones already resolved its own duplicates, so the settlement
     * name is made collision-safe here. The record of the assignment is written before the name is handed back.
     */
    static void nameEvent(Object event) {
        var server = server();
        if (server.isEmpty() || access == null || !enabled()) return;
        try {
            Object stone = access.nameEventWaystone(event);
            var data = WaystonesNamingData.get(server.get());
            if (!data.writable() || detached(data, access.id(stone))) return;
            var decision = decide(server.get(), stone);
            if (decision.proposal().isEmpty()) return;
            var p = decision.proposal().get();
            long now = server.get().overworld().getGameTime();
            var entry = data.entry(access.id(stone)).orElseGet(() -> fresh(stone, now))
                    .named(p.settlement().id(), p.base(), p.assignment().name(), p.assignment().number(), Stage.ASSIGNED, now);
            if (!data.commit(server.get(), List.of(entry))) return;
            access.nameEventSetName(event, p.assignment().name());
        } catch (RuntimeException failure) {
            LOG.warn("[MCA: Kingdoms] A generated waystone keeps Waystones' own name: {}", failure.toString());
        }
    }

    /**
     * A stone was activated (placement activates it before its settings screen opens). A normal stone still without a
     * name, inside a settlement, gets the settlement's name now; one in a place that is about to be recognized waits.
     */
    static void activated(Object event) {
        var server = server();
        if (server.isEmpty() || access == null || !enabled()) return;
        try {
            if (!(access.activatedPlayer(event) instanceof ServerPlayer)) return;
            Object stone = access.activatedWaystone(event);
            if (!access.normal(stone) || access.hasName(stone)) return;
            var data = WaystonesNamingData.get(server.get());
            if (!data.writable() || detached(data, access.id(stone))) return;
            long now = server.get().overworld().getGameTime();
            var decision = decide(server.get(), stone);
            if (decision.proposal().isPresent()) {
                var p = decision.proposal().get();
                var entry = data.entry(access.id(stone)).orElseGet(() -> fresh(stone, now))
                        .named(p.settlement().id(), p.base(), p.assignment().name(), p.assignment().number(), Stage.ASSIGNED, now);
                if (data.commit(server.get(), List.of(entry))) access.nameDuringActivation(server.get(), stone, p.assignment().name());
                return;
            }
            if (decision.reason() == WaystoneNames.Reason.OUTSIDE_SETTLEMENT && soon(server.get(), stone))
                data.commit(server.get(), List.of(fresh(stone, now).at(Stage.PENDING, now, "waiting for the settlement here to be recognized")));
        } catch (RuntimeException failure) {
            LOG.warn("[MCA: Kingdoms] A placed waystone keeps its own name: {}", failure.toString());
        }
    }

    /**
     * Whether the place around a stone may be recognized as a settlement soon: a scan is already queued there, or village
     * beds, bells or workstations stand nearby. Then a scan is asked for (loaded chunks only) and the stone waits.
     */
    private static boolean soon(MinecraftServer server, Object stone) {
        ServerLevel level = server.getLevel(access.dimension(stone));
        if (level == null || !(McaKingdomsApi.get(server) instanceof dev.otectus.mcakingdoms.core.KingdomsServiceImpl impl)) return false;
        BlockPos pos = access.pos(stone);
        if (impl.scanPending(level, pos)) return true;
        int radius = McaKingdomsConfig.POI_SEARCH_RADIUS.get();
        boolean pois = level.getPoiManager().getInSquare(h -> h.is(net.minecraft.tags.PoiTypeTags.VILLAGE), pos, radius,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).anyMatch(r -> level.hasChunk(r.getPos().getX() >> 4, r.getPos().getZ() >> 4));
        if (pois) impl.pointOfInterestChanged(level, pos);
        return pois;
    }

    // ---- pending stones, verification and restart ----

    /** A settlement was recognized: stones waiting inside it are named now, if they are still unnamed and nobody edited them. */
    @SubscribeEvent
    public static void settlementCreated(SettlementCreatedEvent event) {
        var server = server();
        if (server.isEmpty() || access == null || !enabled()) return;
        var data = WaystonesNamingData.get(server.get());
        if (!data.writable()) return;
        var settlement = event.settlement();
        var waiting = data.entries().stream().filter(e -> e.stage() == Stage.PENDING && e.dimension().equals(settlement.dimension().location().toString())
                && settlement.bounds().contains(e.pos())).limit(PENDING_BUDGET).toList();
        for (var e : waiting) retry(server.get(), data, e);
    }

    /** Every few seconds: pending stones whose time ran out keep their own name; a few others are looked at again. */
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || access == null || event.getServer().getTickCount() % PENDING_PERIOD != 7) return;
        MinecraftServer server = event.getServer();
        var data = WaystonesNamingData.get(server);
        if (!data.writable() || data.count(Stage.PENDING) == 0) return;
        long now = server.overworld().getGameTime();
        var expired = new ArrayList<Entry>();
        int retried = 0;
        for (var e : List.copyOf(data.entries())) {
            if (e.stage() != Stage.PENDING) continue;
            if (now - e.createdAt() > pendingTicks()) expired.add(e.at(Stage.EXPIRED, now, "no settlement was recognized here in time; Waystones' own name is kept"));
            else if (enabled() && retried++ < PENDING_BUDGET) retry(server, data, e);
        }
        if (!expired.isEmpty()) data.commit(server, expired);
    }

    /** Names a waiting stone if it is still eligible: it exists, it is still unnamed and nobody edited it. Never overwrites a name. */
    private static void retry(MinecraftServer server, WaystonesNamingData data, Entry e) {
        try {
            var stone = access.stone(server, e.stone());
            long now = server.overworld().getGameTime();
            if (stone.isEmpty()) { data.commit(server, List.of(e.at(Stage.EXPIRED, now, "the waystone is gone"))); return; }
            if (access.hasName(stone.get())) { data.commit(server, List.of(e.at(Stage.DETACHED, now, "it was named meanwhile; that name is kept"))); return; }
            var decision = decide(server, stone.get());
            if (decision.proposal().isEmpty()) return;
            var p = decision.proposal().get();
            var entry = e.named(p.settlement().id(), p.base(), p.assignment().name(), p.assignment().number(), Stage.ASSIGNED, now);
            if (data.commit(server, List.of(entry))) access.rename(server, stone.get(), p.assignment().name());
        } catch (RuntimeException failure) {
            LOG.warn("[MCA: Kingdoms] A waiting waystone could not be named: {}", failure.toString());
        }
    }

    /**
     * A world save wrote Waystones' records (they are saved with the Overworld's data, before this event): assignments
     * whose stone carries the given name are confirmed. A stone that now carries another name was renamed by something
     * this integration did not see, and is never touched again. A named stone Waystones no longer has (broken, not
     * silk-touched) frees its name for the next stone.
     */
    @SubscribeEvent
    public static void saved(LevelEvent.Save event) {
        if (access == null || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        MinecraftServer server = level.getServer();
        var data = WaystonesNamingData.get(server);
        if (!data.writable() || data.count(Stage.ASSIGNED) + data.count(Stage.MANAGED) == 0) return;
        long now = server.overworld().getGameTime();
        var changes = new ArrayList<Entry>();
        for (var e : data.entries()) {
            if (!e.stage().reserves()) continue;
            try {
                var stone = access.stone(server, e.stone());
                if (stone.isEmpty()) { changes.add(e.at(Stage.EXPIRED, now, "the waystone is gone; its name is free again")); continue; }
                if (e.stage() != Stage.ASSIGNED) continue;
                String current = access.name(stone.get());
                if (current.equals(e.name())) changes.add(e.at(Stage.MANAGED, now, ""));
                else if (access.hasName(stone.get())) changes.add(e.at(Stage.DETACHED, now, "renamed outside this integration; that name is kept"));
            } catch (RuntimeException unreadable) {
                // Checked again at the next save.
            }
        }
        if (!changes.isEmpty()) data.commit(server, changes);
    }

    /**
     * After a restart, assignments the previous run made but no save confirmed are settled from the stones as Waystones
     * loaded them: the given name means it was saved (managed); no name means it was not, so it is given again if the
     * stone is still eligible; any other name cannot be attributed, so it is left as it is and reported.
     */
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (access == null) return;
        MinecraftServer server = event.getServer();
        var data = WaystonesNamingData.get(server);
        if (!data.writable() || data.count(Stage.ASSIGNED) == 0) return;
        long now = server.overworld().getGameTime();
        var changes = new ArrayList<Entry>();
        var again = new ArrayList<Entry>();
        for (var e : data.entries()) {
            if (e.stage() != Stage.ASSIGNED) continue;
            try {
                var stone = access.stone(server, e.stone());
                if (stone.isEmpty()) { changes.add(e.at(Stage.EXPIRED, now, "the waystone was not in Waystones' records after the restart; its name is free again")); continue; }
                String current = access.name(stone.get());
                if (current.equals(e.name())) changes.add(e.at(Stage.MANAGED, now, ""));
                else if (!access.hasName(stone.get())) again.add(e);
                else changes.add(e.at(Stage.UNRESOLVED, now, "its name after a restart was not the one recorded; it is left as it is"));
            } catch (RuntimeException unreadable) {
                changes.add(e.at(Stage.UNRESOLVED, now, "it could not be read after a restart"));
            }
        }
        if (!changes.isEmpty()) data.commit(server, changes);
        for (var e : again) {
            var stone = access.stone(server, e.stone());
            if (stone.isPresent() && enabled()) {
                try { access.rename(server, stone.get(), e.name()); }
                catch (RuntimeException failure) { data.commit(server, List.of(e.at(Stage.UNRESOLVED, now, "it could not be named again: " + failure.getMessage()))); }
            }
        }
    }

    // ---- player edits (observed on the tested Waystones build only) ----

    /** Called by the edit observer after Waystones accepted a player's edit of a stone: that stone is the player's to name. */
    public static void manualEdit(ServerPlayer player, Object message) {
        editObserved = true;
        if (access == null) return;
        try {
            UUID stoneId = (UUID) uidField(message).get(message);
            MinecraftServer server = player.getServer();
            if (server == null || stoneId == null) return;
            var data = WaystonesNamingData.get(server);
            if (!data.writable()) return;
            var stone = access.stone(server, stoneId);
            if (stone.isEmpty() || !access.normal(stone.get())) return;
            long now = server.overworld().getGameTime();
            var entry = data.entry(stoneId).orElseGet(() -> fresh(stone.get(), now));
            if (entry.stage() == Stage.DETACHED) return;
            data.commit(server, List.of(entry.at(Stage.DETACHED, now, "named by " + player.getGameProfile().getName())));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.warn("[MCA: Kingdoms] A waystone edit could not be recorded; that stone's name is still never changed automatically once it has one: {}", failure.toString());
        }
    }

    private static volatile java.lang.reflect.Field uid;

    private static java.lang.reflect.Field uidField(Object message) throws NoSuchFieldException {
        var field = uid;
        if (field == null || field.getDeclaringClass() != message.getClass()) {
            field = message.getClass().getDeclaredField("waystoneUid");
            field.setAccessible(true);
            uid = field;
        }
        return field;
    }

    // ---- explicit requests ----

    /** What an explicit request or a backfill did with one stone. */
    public record Result(UUID stone, String name, WaystoneNames.Reason reason) { }

    /** The normal waystone nearest {@code player} within {@link #NEAR} blocks, in their dimension. */
    static Optional<Object> nearest(ServerPlayer player) {
        if (access == null) return Optional.empty();
        var dim = player.serverLevel().dimension();
        return access.all(player.getServer()).filter(s -> access.dimension(s).equals(dim) && access.normal(s))
                .filter(s -> access.pos(s).distToCenterSqr(player.position()) <= NEAR * NEAR)
                .min(Comparator.comparingDouble(s -> access.pos(s).distToCenterSqr(player.position())));
    }

    /** The reason the nearest stone has the name it has, for players. */
    public static Optional<Result> explainNearest(ServerPlayer player) {
        var stone = nearest(player);
        if (stone.isEmpty()) return Optional.empty();
        UUID id = access.id(stone.get());
        String current = access.name(stone.get());
        var data = WaystonesNamingData.get(player.getServer());
        var entry = data.entry(id);
        if (entry.map(e -> e.stage() == Stage.DETACHED || e.stage() == Stage.UNRESOLVED).orElse(false))
            return Optional.of(new Result(id, current, WaystoneNames.Reason.PLAYER_NAME_KEPT));
        if (entry.map(e -> e.stage() == Stage.PENDING).orElse(false)) return Optional.of(new Result(id, current, WaystoneNames.Reason.WAITING_FOR_DISCOVERY));
        if (entry.map(e -> e.stage().reserves() && e.name().equals(current)).orElse(false)) return Optional.of(new Result(id, current, WaystoneNames.Reason.NAMED));
        var decision = decide(player.getServer(), stone.get());
        if (decision.reason() == WaystoneNames.Reason.NAMED && access.hasName(stone.get())) return Optional.of(new Result(id, current, WaystoneNames.Reason.PLAYER_NAME_KEPT));
        return Optional.of(new Result(id, current, decision.reason()));
    }

    /**
     * "Name this waystone after the settlement": the nearest stone, which the player may edit under Waystones' own rules,
     * takes the settlement's collision-safe name, replacing its current name because they asked. Its id, owner, global
     * flag and activations are unchanged.
     */
    public static Result useSettlementName(ServerPlayer player) {
        var server = player.getServer();
        dev.otectus.mcakingdoms.interaction.Keyed.require(status().ready(), "waystones.mcakingdoms.refusal.unavailable", "Settlement names for waystones are not available on this server.");
        var stone = nearest(player).orElseThrow(() -> dev.otectus.mcakingdoms.interaction.Keyed.refuse("waystones.mcakingdoms.refusal.none_near",
                "Stand within %s blocks of a waystone.", (int) NEAR));
        dev.otectus.mcakingdoms.interaction.Keyed.require(access.mayEdit(player, player.level(), stone), "waystones.mcakingdoms.refusal.not_yours",
                "Waystones does not let you rename that waystone (it is someone else's, or global).");
        var data = WaystonesNamingData.get(server);
        dev.otectus.mcakingdoms.interaction.Keyed.require(data.writable(), "waystones.mcakingdoms.refusal.read_only", "The waystone naming records are kept read-only on this server.");
        var decision = decide(server, stone);
        var p = decision.proposal().orElseThrow(() -> dev.otectus.mcakingdoms.interaction.Keyed.refuse("waystones.mcakingdoms.refusal." + decision.reason().name().toLowerCase(Locale.ROOT),
                reasonText(decision.reason())));
        long now = server.overworld().getGameTime();
        var entry = data.entry(access.id(stone)).orElseGet(() -> fresh(stone, now))
                .named(p.settlement().id(), p.base(), p.assignment().name(), p.assignment().number(), Stage.ASSIGNED, now);
        dev.otectus.mcakingdoms.interaction.Keyed.require(data.commit(server, List.of(entry)), "waystones.mcakingdoms.refusal.save_failed",
                "The waystone naming records could not be saved, so nothing changed.");
        access.rename(server, stone, p.assignment().name());
        return new Result(access.id(stone), p.assignment().name(), WaystoneNames.Reason.NAMED);
    }

    /** English for a reason; the bundled translations replace it. */
    public static String reasonText(WaystoneNames.Reason reason) {
        return switch (reason) {
            case NAMED -> "It carries its settlement's name.";
            case OUTSIDE_SETTLEMENT -> "It is not inside a recognized settlement, so it keeps Waystones' own name.";
            case WAITING_FOR_DISCOVERY -> "It waits a short while for the settlement around it to be recognized.";
            case PLAYER_NAME_KEPT -> "It has a name a player chose or kept; that name is never changed automatically.";
            case NOT_A_WAYSTONE -> "Sharestones, warp plates and portstones keep their own names.";
            case UNUSABLE_NAME -> "The settlement's name cannot be used for a waystone.";
            case NO_FREE_NAME -> "Every numbered form of the settlement's name is already used by another waystone.";
            case PROVIDER_UNSUPPORTED -> "The installed Waystones is not the version this integration is tested with.";
            case RECORDS_READ_ONLY -> "The waystone naming records are kept read-only on this server.";
            case PENDING_FULL -> "Too many new waystones are waiting for their settlement; this one keeps Waystones' own name.";
            case UNRESOLVED -> "Its name after a restart could not be attributed, so it is left as it is.";
        };
    }

    /**
     * {@code /kingdoms waystones backfill}: every registered normal stone that is still unnamed and inside a settlement is
     * eligible; named stones are never eligible (an unknown historical name is a player's until shown otherwise). A dry
     * run counts reasons only; applying names at most {@code limit} stones, so a large world is done in steps.
     */
    public static Map<WaystoneNames.Reason, Integer> backfill(MinecraftServer server, boolean apply, int limit) {
        var counts = new EnumMap<WaystoneNames.Reason, Integer>(WaystoneNames.Reason.class);
        if (access == null) { counts.put(WaystoneNames.Reason.PROVIDER_UNSUPPORTED, 1); return counts; }
        var data = WaystonesNamingData.get(server);
        if (!data.writable()) { counts.put(WaystoneNames.Reason.RECORDS_READ_ONLY, 1); return counts; }
        int applied = 0;
        var stones = access.all(server).limit(REGISTRY_LIMIT).toList();
        // One index of every name in use for the whole pass (VAL-01: no full registry read per stone), counted so that a
        // stone's own name and reservation can be left out of its own check; each proposal joins it, as apply would.
        var used = new HashMap<String, Integer>();
        for (Object s : stones) used.merge(WaystoneNames.key(access.name(s)), 1, Integer::sum);
        for (var e : data.entries()) if (e.stage().reserves()) used.merge(WaystoneNames.key(e.name()), 1, Integer::sum);
        for (Object stone : stones) {
            WaystoneNames.Reason reason;
            if (!access.normal(stone)) reason = WaystoneNames.Reason.NOT_A_WAYSTONE;
            else if (access.hasName(stone) || detached(data, access.id(stone))) reason = WaystoneNames.Reason.PLAYER_NAME_KEPT;
            else {
                var own = new HashMap<String, Integer>();
                own.merge(WaystoneNames.key(access.name(stone)), 1, Integer::sum);
                data.entry(access.id(stone)).filter(e -> e.stage().reserves()).ifPresent(e -> own.merge(WaystoneNames.key(e.name()), 1, Integer::sum));
                var decision = decide(server, stone, key -> used.getOrDefault(key, 0) - own.getOrDefault(key, 0) > 0);
                reason = decision.reason();
                if (decision.proposal().isPresent()) {
                    var p = decision.proposal().get();
                    used.merge(WaystoneNames.key(p.assignment().name()), 1, Integer::sum);
                    if (apply && applied < limit) {
                        long now = server.overworld().getGameTime();
                        var entry = data.entry(access.id(stone)).orElseGet(() -> fresh(stone, now))
                                .named(p.settlement().id(), p.base(), p.assignment().name(), p.assignment().number(), Stage.ASSIGNED, now);
                        if (data.commit(server, List.of(entry))) { access.rename(server, stone, p.assignment().name()); applied++; }
                    }
                }
            }
            counts.merge(reason, 1, Integer::sum);
        }
        return counts;
    }

    /** Test seam: whether the binding is in place. */
    public static boolean bound() {
        return access != null;
    }
}
