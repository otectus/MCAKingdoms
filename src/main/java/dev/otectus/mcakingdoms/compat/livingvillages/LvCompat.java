package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.util.*;

/**
 * What the Living Villages integration can do on this server, capability by capability, with the concrete reason for each
 * state. The same snapshot answers Kingdom Tasks availability, the Book, operator status and every service's own gate, so
 * they cannot disagree. A read-only adapter is never reported as full integration.
 */
public final class LvCompat {
    public static final String MOD_ID = "recruitsvillages";
    public static final String AUDITED_VERSION = "1.1.0";
    public static final String RECRUITS_VERSION = "1.15.2";

    public enum Readiness { READY, PARTIAL, UNSUPPORTED, UNAVAILABLE, RECOVERY_REQUIRED }

    public enum Capability {
        METADATA_READ, VILLAGE_ENUMERATION, STABLE_BINDING, REALM_MEMBERSHIP, DIPLOMACY_REQUEST, WAR_PREFLIGHT,
        ABSTRACT_COMBAT, CONQUEST_OBSERVATION, RESIDENT_PRESERVATION, WORKER_SUPPORT, ECONOMY_RECEIPTS, UI_ACCESS
    }

    public record Status(Capability capability, Readiness readiness, String reason) {
        public boolean ready() { return readiness == Readiness.READY; }
        public Component label() {
            return Component.translatableWithFallback("warfare.mcakingdoms.villages.capability." + capability.name().toLowerCase(Locale.ROOT),
                    capability.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        public Component state() {
            return Component.translatableWithFallback("warfare.mcakingdoms.villages.readiness." + readiness.name().toLowerCase(Locale.ROOT), readiness.name());
        }
        public Component why() {
            return reason.isEmpty() ? Component.empty() : Component.translatableWithFallback("warfare.mcakingdoms.villages.reason." + reason, reason.replace('_', ' '));
        }
    }

    public record Snapshot(String tuple, Readiness overall, List<Status> capabilities, String gate, String shape) {
        public Snapshot { capabilities = List.copyOf(capabilities); }
        public Status of(Capability capability) {
            return capabilities.stream().filter(s -> s.capability() == capability).findFirst()
                    .orElse(new Status(capability, Readiness.UNAVAILABLE, "unknown"));
        }
        public boolean ready(Capability capability) { return of(capability).ready(); }
    }

    /** Handler names each capability needs merged into the provider's classes (mixin handlers keep their suffix). */
    private static final Map<Capability, Map<String, List<String>>> HOOKS = Map.of(
            Capability.WAR_PREFLIGHT, Map.of("war.WarManager", List.of("lvDeclare", "lvDeclared", "lvAbstractStart", "lvAttacker", "lvTarget", "lvBattle")),
            Capability.ABSTRACT_COMBAT, Map.of("war.WarManager", List.of("lvAbstractTick", "lvAbstractTicked", "lvResolve", "lvResolved", "lvSettle")),
            Capability.CONQUEST_OBSERVATION, Map.of("war.WarManager", List.of("lvOccupy", "lvOccupied", "lvOccupyAbstract", "lvOccupiedAbstract", "lvRepel")),
            Capability.DIPLOMACY_REQUEST, Map.of("sim.Relations", List.of("lvArbitrate")),
            Capability.RESIDENT_PRESERVATION, Map.of("materialize.CivilianMaterializer", List.of("lvCapture", "lvSpawn"),
                    "materialize.MaterializationManager", List.of("lvGuardTrim", "lvGuardStray", "lvGuardBodies", "lvGuardCleanup", "lvGuardWorkers", "lvGuardDematerialize", "lvKeepBodies")),
            Capability.WORKER_SUPPORT, Map.of("materialize.MaterializationManager", List.of("lvPromote", "lvJobless", "lvConsume")));

    private static volatile Map<Capability, String> hookFailures;

    private LvCompat() {
    }

    public static boolean installed() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    public static String version() {
        return ModList.get().getModContainerById(MOD_ID).map(m -> m.getModInfo().getVersion().toString()).orElse("");
    }

    private static String recruitsVersion() {
        return ModList.get().getModContainerById("recruits").map(m -> m.getModInfo().getVersion().toString()).orElse("");
    }

    /** The mixin plugin's verdict on the installed Living Villages jar: {@code applied} or {@code skipped:<why>}. */
    public static String gate() {
        return System.getProperty("mcakingdoms.livingvillages.gate", installed() ? "skipped:not_evaluated" : "skipped:absent");
    }

    /** Whether new bindings and provider-touching operations may run now. */
    public static boolean active(MinecraftServer server) {
        return installed() && LvConfig.enabled() && gate().equals("applied") && LvAccess.shape().isEmpty()
                && LvBridgeSavedData.get(server).writable();
    }

    private static synchronized Map<Capability, String> hookFailures() {
        if (hookFailures != null) return hookFailures;
        var failures = new EnumMap<Capability, String>(Capability.class);
        String residents = System.getProperty("mcakingdoms.livingvillages.residents", "");
        if (!"applied".equals(residents))
            failures.put(Capability.RESIDENT_PRESERVATION, residents.startsWith("skipped:development") ? "development_runtime" : "hook_inactive");
        for (var entry : HOOKS.entrySet()) {
            for (var target : entry.getValue().entrySet()) {
                Set<String> names = new HashSet<>();
                try {
                    for (var method : Class.forName(LvAccess.ROOT + target.getKey()).getDeclaredMethods()) names.add(method.getName());
                } catch (ClassNotFoundException | LinkageError missing) {
                    failures.put(entry.getKey(), "hook_target_missing");
                    continue;
                }
                for (String handler : target.getValue())
                    if (names.stream().noneMatch(n -> n.endsWith("mcakingdoms$" + handler))) failures.putIfAbsent(entry.getKey(), "hook_inactive");
            }
        }
        hookFailures = Map.copyOf(failures);
        return hookFailures;
    }

    public static Snapshot snapshot(MinecraftServer server) {
        String tuple = "Recruits " + (recruitsVersion().isEmpty() ? "absent" : recruitsVersion()) + " · Living Villages "
                + (installed() ? version() : "absent");
        var out = new ArrayList<Status>();
        String common = !installed() ? "absent" : !recruitsVersion().equals(RECRUITS_VERSION) ? "recruits_version"
                : !version().equals(AUDITED_VERSION) ? "unaudited_version" : !gate().equals("applied") ? gateReason()
                : !LvAccess.shape().isEmpty() ? "shape_mismatch" : !LvConfig.enabled() ? "disabled" : "";
        Readiness commonState = common.isEmpty() ? Readiness.READY
                : common.equals("absent") || common.equals("disabled") ? Readiness.UNAVAILABLE : Readiness.UNSUPPORTED;
        boolean writable = LvBridgeSavedData.get(server).writable();
        var data = LvBridgeSavedData.get(server).view();
        boolean regressed = data.provenance.regressedAt > 0;
        var failures = common.isEmpty() ? hookFailures() : Map.<Capability, String>of();
        for (Capability capability : Capability.values()) {
            if (!common.isEmpty()) { out.add(new Status(capability, commonState, common)); continue; }
            boolean readOnly = capability == Capability.METADATA_READ || capability == Capability.VILLAGE_ENUMERATION || capability == Capability.UI_ACCESS;
            if (!writable && !readOnly) { out.add(new Status(capability, Readiness.RECOVERY_REQUIRED, "store_read_only")); continue; }
            if (regressed && (capability == Capability.CONQUEST_OBSERVATION || capability == Capability.STABLE_BINDING)) {
                out.add(new Status(capability, Readiness.RECOVERY_REQUIRED, "provider_regressed")); continue;
            }
            String failure = failures.get(capability);
            if (failure != null) { out.add(new Status(capability, Readiness.UNAVAILABLE, failure)); continue; }
            if ((capability == Capability.WAR_PREFLIGHT || capability == Capability.ABSTRACT_COMBAT || capability == Capability.CONQUEST_OBSERVATION)
                    && !dev.otectus.mcakingdoms.warfare.WarfareConfig.enabled()) {
                out.add(new Status(capability, Readiness.PARTIAL, "warfare_off")); continue;
            }
            out.add(new Status(capability, Readiness.READY, ""));
        }
        Readiness overall = out.stream().allMatch(Status::ready) ? Readiness.READY
                : out.stream().anyMatch(Status::ready) ? out.stream().anyMatch(s -> s.readiness() == Readiness.RECOVERY_REQUIRED)
                ? Readiness.RECOVERY_REQUIRED : Readiness.PARTIAL : commonState == Readiness.READY ? Readiness.UNAVAILABLE : commonState;
        return new Snapshot(tuple, overall, out, gate(), LvAccess.shape());
    }

    private static String gateReason() {
        String gate = gate();
        if (gate.startsWith("skipped:unaudited_build")) return "unaudited_build";
        if (gate.startsWith("skipped:version")) return "unaudited_version";
        if (gate.startsWith("skipped:recruits")) return "recruits_version";
        return "hooks_not_applied";
    }

    /** For a world restart in the same JVM; hook presence cannot change, but tests reset it. */
    static void resetForTests() {
        hookFailures = null;
    }
}
