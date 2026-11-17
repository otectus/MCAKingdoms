package dev.otectus.mcakingdoms.api.townstead;

import java.util.Objects;

/**
 * One capability's state with its reason. {@code source} names the backend that answers it ({@code v1}, {@code legacy},
 * {@code mca} or {@code none}). {@code reason} is operator text, never shown in a village menu as is.
 */
public record TownsteadCapabilityState(Status status, String source, String reason) {
    public enum Status {
        /** Townstead (or MCA, for the MCA-only capabilities) is not installed, or the integration is switched off. */
        ABSENT,
        /** Bound, but the world switched the Townstead system behind it off. */
        DISABLED,
        /** The installed provider does not offer it. */
        UNSUPPORTED,
        READY,
        /** Bound, but a read failed or returned inconsistent data; evidence from it is weaker until it reads again. */
        DEGRADED,
        /** Bound, but its cached data is older than the configured freshness limit. */
        STALE,
        /** Bound, but recorded state disagrees with the provider (for example a restored backup); an operator decides. */
        RECOVERY_REQUIRED;

        public boolean usable() {
            return this == READY || this == DEGRADED || this == STALE;
        }
    }

    public TownsteadCapabilityState {
        Objects.requireNonNull(status, "status");
        source = source == null ? "none" : source;
        reason = reason == null ? "" : reason;
    }
}
