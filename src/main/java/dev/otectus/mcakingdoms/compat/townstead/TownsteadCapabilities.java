package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState.Status;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Independent binding, configuration and runtime health for each operation (spec 5.2, 12.2). Binding readiness and a
 * world-disabled Townstead system are separate states: a capability whose system the world switched off reads
 * {@code DISABLED} while it stays bound, and comes back without a restart when the system is switched on again.
 */
final class TownsteadCapabilities {
    /** The Townstead Switchboard system each capability depends on; ids Townstead does not list are never assumed. */
    static final Map<TownsteadCapability, String> SYSTEMS = Map.ofEntries(
            Map.entry(TownsteadCapability.READ_CAREERS, "careers"),
            Map.entry(TownsteadCapability.READ_WORK_ORDERS, "work"),
            Map.entry(TownsteadCapability.READ_STORAGE, "work"),
            Map.entry(TownsteadCapability.READ_CHRONICLES, "chronicles"),
            Map.entry(TownsteadCapability.EMIT_CHRONICLE, "chronicles"),
            Map.entry(TownsteadCapability.READ_POLITICS, "politics"),
            Map.entry(TownsteadCapability.READ_SPIRIT, "spirit"),
            Map.entry(TownsteadCapability.READ_CALENDAR, "calendar"),
            Map.entry(TownsteadCapability.DISPATCH_REACTION, "reactions"),
            Map.entry(TownsteadCapability.READ_SCHEDULE, "shifts"),
            Map.entry(TownsteadCapability.READ_HANGOUTS, "hangouts"),
            Map.entry(TownsteadCapability.READ_ORIGIN, "roots"));
    static final Set<TownsteadCapability> BUILDING = Set.of(TownsteadCapability.READ_BUILDING, TownsteadCapability.READ_BUILDINGS,
            TownsteadCapability.READ_BUILDING_COMPLETION);

    private final TownsteadBackends.Selection selection;
    private final Map<TownsteadCapability, String> degraded = new ConcurrentHashMap<>();
    private final Map<TownsteadCapability, String> recovery = new ConcurrentHashMap<>();

    TownsteadCapabilities(TownsteadBackends.Selection selection) {
        this.selection = selection;
    }

    TownsteadBackend owner(TownsteadCapability capability) {
        return BUILDING.contains(capability) ? selection.buildings() : selection.backend();
    }

    void degraded(TownsteadCapability capability, String reason) {
        degraded.put(capability, reason == null ? "" : reason);
    }

    void recovered(TownsteadCapability capability) {
        degraded.remove(capability);
    }

    void recoveryRequired(TownsteadCapability capability, String reason) {
        recovery.put(capability, reason == null ? "" : reason);
    }

    void clearRecovery(TownsteadCapability capability) {
        recovery.remove(capability);
    }

    /** Bound, and the world runs the system behind it (or it has none). */
    boolean usable(TownsteadCapability capability) {
        return state(capability).status().usable();
    }

    Optional<Boolean> system(TownsteadCapability capability) {
        String system = SYSTEMS.get(capability);
        return system == null ? Optional.empty() : selection.backend().systemEnabled(system);
    }

    TownsteadCapabilityState state(TownsteadCapability capability) {
        TownsteadBackend owner = owner(capability);
        String reason = owner.diagnostics().getOrDefault(capability, "");
        if (capability == TownsteadCapability.POLITICAL_DELEGATION) {
            return new TownsteadCapabilityState(selection.installed() ? Status.UNSUPPORTED : Status.ABSENT, owner.id(), selection.installed()
                    ? "Townstead 0.8.0 publishes no contract for an external government; political delegation is unavailable"
                    : "Townstead is not installed");
        }
        if (!owner.capabilities().contains(capability)) {
            boolean mcaOnly = BUILDING.contains(capability);
            Status status = selection.installed() || (mcaOnly && !"none".equals(owner.id())) ? Status.UNSUPPORTED : Status.ABSENT;
            return new TownsteadCapabilityState(status, owner.id(), reason);
        }
        if (system(capability).filter(on -> !on).isPresent()) {
            return new TownsteadCapabilityState(Status.DISABLED, owner.id(), "the world switched Townstead's " + SYSTEMS.get(capability) + " system off");
        }
        String needed = recovery.get(capability);
        if (needed != null) return new TownsteadCapabilityState(Status.RECOVERY_REQUIRED, owner.id(), needed);
        String weak = degraded.get(capability);
        if (weak != null) return new TownsteadCapabilityState(Status.DEGRADED, owner.id(), weak);
        if (capability == TownsteadCapability.DISPATCH_REACTION && !owner.reactionsPlayable()) {
            return new TownsteadCapabilityState(Status.DEGRADED, owner.id(), "no animation backend is registered, so reactions are silent");
        }
        return new TownsteadCapabilityState(Status.READY, owner.id(), reason);
    }

    Map<TownsteadCapability, TownsteadCapabilityState> states() {
        Map<TownsteadCapability, TownsteadCapabilityState> out = new EnumMap<>(TownsteadCapability.class);
        for (TownsteadCapability capability : TownsteadCapability.values()) out.put(capability, state(capability));
        // In declaration order, so diagnostics list capabilities the same way every time.
        return Collections.unmodifiableMap(out);
    }
}
