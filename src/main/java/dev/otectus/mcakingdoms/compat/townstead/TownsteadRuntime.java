package dev.otectus.mcakingdoms.compat.townstead;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.compat.townstead.network.TownsteadCivicNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Attaches the Townstead integration to one server and detaches it (spec 5.4): backends are selected once, Townstead's
 * events are subscribed after MCA: Kingdoms' services are attached, and on detach every subscription is closed and every
 * cache, queue and screen state is cleared, so an integrated world A followed by world B in the same JVM shares nothing.
 */
public final class TownsteadRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSIONS = new AtomicLong();
    private static final Map<MinecraftServer, Session> SESSIONS_BY_SERVER = new WeakHashMap<>();

    private TownsteadRuntime() {
    }

    /** Everything one server session holds; internal to the integration, commands and tasks. */
    public static final class Session {
        final long id;
        final TownsteadServiceImpl service;
        final TownsteadBuildingReconciler reconciler;
        final TownsteadEventBridge bridge;
        final TownsteadPoliticalReactions reactions;
        final TownsteadAuthority authority;
        final TownsteadChronicleBridge chronicles;
        final TownsteadWork work;
        final TownsteadSuggestions suggestions = new TownsteadSuggestions();

        Session(long id, TownsteadServiceImpl service, TownsteadBuildingReconciler reconciler, TownsteadEventBridge bridge,
                TownsteadPoliticalReactions reactions, TownsteadAuthority authority, TownsteadChronicleBridge chronicles, TownsteadWork work) {
            this.id = id;
            this.service = service;
            this.reconciler = reconciler;
            this.bridge = bridge;
            this.reactions = reactions;
            this.authority = authority;
            this.chronicles = chronicles;
            this.work = work;
        }

        public long id() { return id; }
        /** {@code v1}, {@code legacy}, {@code mca} or {@code none}. */
        public String backend() { return service.backend(); }
        TownsteadServiceImpl service() { return service; }
        TownsteadBuildingReconciler reconciler() { return reconciler; }
        TownsteadEventBridge bridge() { return bridge; }
        TownsteadAuthority authority() { return authority; }
        TownsteadChronicleBridge chronicles() { return chronicles; }
        TownsteadWork work() { return work; }
        TownsteadSuggestions suggestions() { return suggestions; }
        TownsteadPoliticalReactions reactions() { return reactions; }
    }

    public static void registerGlobalHooks() {
        TownsteadIntegrationConfig.register();
        TownsteadCivicNetwork.init();
        MinecraftForge.EVENT_BUS.register(TownsteadMappings.class);
    }

    public static Optional<Session> session(MinecraftServer server) {
        synchronized (SESSIONS_BY_SERVER) {
            return Optional.ofNullable(SESSIONS_BY_SERVER.get(server));
        }
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(kingdoms, "kingdoms");
        boolean installed = ModList.get().isLoaded("townstead");
        boolean enabled = TownsteadIntegrationConfig.ENABLED.get();
        String version = ModList.get().getModContainerById("townstead")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("absent");
        TownsteadBackends.Selection selection = TownsteadBackends.select(installed, enabled, TownsteadIntegrationConfig.USE_PUBLIC_API.get(),
                TownsteadIntegrationConfig.ALLOW_INTERNAL_FALLBACK.get(), version);
        TownsteadServiceImpl service = new TownsteadServiceImpl(server, kingdoms, selection);
        KingdomsTownsteadApi.attach(server, service);
        long sessionId = SESSIONS.incrementAndGet();
        TownsteadBuildingReconciler reconciler = new TownsteadBuildingReconciler(server, kingdoms, service);
        TownsteadEventBridge bridge = new TownsteadEventBridge(server, kingdoms, service);
        TownsteadPoliticalReactions reactions = new TownsteadPoliticalReactions(server, kingdoms, service);
        TownsteadAuthority authority = new TownsteadAuthority(server, kingdoms, service);
        TownsteadChronicleBridge chronicles = new TownsteadChronicleBridge(server, kingdoms, service);
        TownsteadWork work = new TownsteadWork(server, kingdoms, service);
        Session session = new Session(sessionId, service, reconciler, bridge, reactions, authority, chronicles, work);
        bridge.listen(handled -> route(session, handled));
        boolean townsteadActive = installed && enabled;
        // Building changes come from MCA's village data, so they are watched whenever it is readable.
        boolean reconcile = service.capabilities().contains(TownsteadCapability.READ_BUILDINGS);
        if (reconcile) MinecraftForge.EVENT_BUS.register(reconciler);
        if (townsteadActive) {
            MinecraftForge.EVENT_BUS.register(reactions);
            MinecraftForge.EVENT_BUS.register(chronicles);
            MinecraftForge.EVENT_BUS.register(TownsteadHeraldry.listener(server, kingdoms, service));
        }
        MinecraftForge.EVENT_BUS.register(bridge);
        // Culture and founding-profile hints for new, unassigned settlements; explicit and locked assignments come first.
        Registration hints = townsteadActive ? kingdoms.registerKingdomResolver(new net.minecraft.resources.ResourceLocation("mcakingdoms", "townstead_culture_hint"),
                (level, candidate) -> cultureHint(service, kingdoms, level, candidate)) : () -> { };
        synchronized (SESSIONS_BY_SERVER) {
            SESSIONS_BY_SERVER.put(server, session);
        }
        bridge.open(sessionId);
        var info = selection.backend().info();
        LOGGER.info("[MCA: Kingdoms] Townstead {} backend {} ({}); buildings from {}; capabilities: {}", version, selection.backend().id(),
                info.detail(), selection.buildings().id(), service.capabilities());
        String validated = dev.otectus.mcakingdoms.politics.PoliticalProviderVersions.TOWNSTEAD;
        if (!"absent".equals(version) && !java.util.Arrays.asList(validated.split(",")).contains(version)) {
            LOGGER.warn("[MCA: Kingdoms] Townstead {} has not been validated with this release (validated: {}). "
                    + "Each binding is probed member by member and fails closed where it cannot read, so the "
                    + "capabilities above are the ones that work.", version, validated);
        }
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            try {
                hints.close();
                bridge.close();
            } finally {
                MinecraftForge.EVENT_BUS.unregister(bridge);
                if (townsteadActive) {
                    MinecraftForge.EVENT_BUS.unregister(reactions);
                    MinecraftForge.EVENT_BUS.unregister(chronicles);
                    TownsteadHeraldry.unregister(server);
                }
                if (reconcile) MinecraftForge.EVENT_BUS.unregister(reconciler);
                reconciler.clear();
                service.lifeCache().clear();
                work.clear();
                authority.clear();
                chronicles.clear();
                session.suggestions.clear();
                dev.otectus.mcakingdoms.citizen.NpcAdulthood.clear();
                TownsteadCivicNetwork.clearServerState();
                synchronized (SESSIONS_BY_SERVER) {
                    SESSIONS_BY_SERVER.remove(server, session);
                }
                KingdomsTownsteadApi.detach(server, service);
            }
        };
    }

    /** A data-pack hint mapping the new settlement's Townstead culture or founding profile to a kingdom (spec 6.4). */
    static Optional<dev.otectus.mcakingdoms.api.KingdomResolution> cultureHint(TownsteadServiceImpl service, KingdomsService kingdoms,
                                                                               net.minecraft.server.level.ServerLevel level, dev.otectus.mcakingdoms.api.SettlementCandidate candidate) {
        if (TownsteadMappings.snapshot().cultureHints().isEmpty()) return Optional.empty();
        var reference = Optional.ofNullable(candidate.externalRefs().get(dev.otectus.mcakingdoms.api.McaCommunityRef.EXTERNAL_REF_NAMESPACE))
                .flatMap(dev.otectus.mcakingdoms.api.McaCommunityRef::parse).filter(ref -> ref.dimension().equals(level.dimension().location()));
        if (reference.isEmpty()) return Optional.empty();
        var culture = service.backendImpl().village(level, reference.get().villageId()).flatMap(dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView::culture);
        var profile = service.backendImpl().foundingProfile(level.getServer(), reference.get().dimension(), reference.get().villageId());
        return TownsteadMappings.cultureHint(culture, profile)
                .filter(hint -> kingdoms.getKingdom(hint.kingdom()).filter(dev.otectus.mcakingdoms.api.KingdomView::defined).isPresent())
                .map(hint -> new dev.otectus.mcakingdoms.api.KingdomResolution(hint.kingdom(), dev.otectus.mcakingdoms.api.AssignmentSource.ADDON,
                        Optional.empty(), 1_000, 0.75, List.of("Townstead hint " + hint.id() + ": culture " + culture.map(Object::toString).orElse("none")
                        + ", founding profile " + profile.map(Object::toString).orElse("none"))));
    }

    /** Where each Townstead notice goes; each handler re-reads committed provider state itself. */
    private static void route(Session session, TownsteadEventBridge.Handled handled) {
        TownsteadSignal signal = handled.signal();
        java.util.UUID settlement = handled.settlement();
        switch (signal.kind()) {
            case BUILDINGS -> {
                session.reconciler.markDirty(settlement);
                session.work.invalidate(settlement);
            }
            case SPIRIT, NEEDS, RESIDENT_HOME -> {
                if (settlement != null) session.service.lifeCache().invalidate(settlement);
            }
            case SETTINGS -> {
                session.service.lifeCache().clear();
                session.work.clear();
                session.authority.clear();
            }
            case WORKSITES -> session.work.invalidate(settlement);
            case WORK_DONE -> session.work.workDone(settlement);
            case FOUNDED -> {
                if (settlement == null) session.bridge.discoverFounding(signal);
                else session.reconciler.markDirty(settlement);
            }
            case POLITICS -> session.authority.invalidate(settlement);
            case LIFE -> {
                if (signal.subject() != null) dev.otectus.mcakingdoms.citizen.NpcAdulthood.invalidate(signal.subject());
                if (settlement != null) session.service.lifeCache().invalidate(settlement);
            }
            case CHRONICLE -> session.chronicles.digestChanged(settlement);
        }
    }
}
