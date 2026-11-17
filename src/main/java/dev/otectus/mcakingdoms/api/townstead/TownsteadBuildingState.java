package dev.otectus.mcakingdoms.api.townstead;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * The detailed reconciliation fingerprint: type, family, tier, completion and geometry, so a change that keeps the type
 * and size (a moved wall, a building that lost a required block) is still a change. {@link TownsteadBuildingFingerprint}
 * stays the 0.2.x shape for existing consumers.
 */
public record TownsteadBuildingState(
        UUID bindingId, int villageId, int buildingId, String type, String family, int tier, int size,
        TownsteadBuildingEvidence.Completion completion,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ
) {
    /** Village, then building id: the order of the snapshot never depends on the provider's collection order. */
    public static final Comparator<TownsteadBuildingState> ORDER =
            Comparator.comparingInt(TownsteadBuildingState::villageId).thenComparingInt(TownsteadBuildingState::buildingId);

    public TownsteadBuildingState {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(completion, "completion");
    }

    public static TownsteadBuildingState of(TownsteadBuildingEvidence evidence) {
        TownsteadBuildingView b = evidence.building();
        return new TownsteadBuildingState(b.bindingId(), b.villageId(), b.buildingId(), b.type(), b.family(), b.level(), b.size(),
                evidence.completion(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    public TownsteadBuildingFingerprint legacy() {
        return new TownsteadBuildingFingerprint(bindingId, buildingId, type, size);
    }
}
