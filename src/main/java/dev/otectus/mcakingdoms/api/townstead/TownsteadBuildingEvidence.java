package dev.otectus.mcakingdoms.api.townstead;

import java.util.Objects;

/**
 * A building with the evidence behind it: whether MCA records it as complete, and which backend read it. A
 * {@link Completion#UNKNOWN} building comes from a provider that cannot say (Townstead 0.7.x without MCA's own list);
 * callers accept it the way they did before 0.3.0 but must not call it verified.
 */
public record TownsteadBuildingEvidence(TownsteadBuildingView building, Completion completion, Source source) {
    public enum Completion { COMPLETE, INCOMPLETE, UNKNOWN }

    /** {@code TOWNSTEAD_V1}: Townstead's effective types and unified list; the others read MCA's building map. */
    public enum Source { TOWNSTEAD_V1, TOWNSTEAD_LEGACY, MCA_DIRECT }

    public TownsteadBuildingEvidence {
        Objects.requireNonNull(building, "building");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(source, "source");
    }

    /** MCA itself records the building as complete. */
    public boolean verifiedComplete() {
        return completion == Completion.COMPLETE;
    }

    /** A new institution, recognition or construction step may use it: complete, or of unknown completion. */
    public boolean acceptsNewUse() {
        return completion != Completion.INCOMPLETE;
    }
}
