package dev.otectus.mcakingdoms.api.townstead;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A settlement's aggregate welfare from Townstead: live readings for loaded residents, last-known ones for the rest. It is
 * not an offline simulation. {@code known} can exceed {@code residents} when Townstead still files a resident under a
 * village it left; {@link #inconsistentCounts} then says so and {@link #coverageFraction} is capped, never proof that every
 * current resident was measured.
 */
public record TownsteadVillageLifeView(UUID settlementId, ResourceLocation dimension, List<Integer> villageIds,
                                       int residents, int known, int loaded, int collapsed, long worldDay, long oldestSampleWorldDay,
                                       Map<String, NeedSummary> needs, Coverage coverage, boolean inconsistentCounts) {
    /** How much of the roll the numbers describe, judged against the configured minimum coverage and sample age. */
    public enum Coverage { NONE, INSUFFICIENT, PARTIAL, GOOD }

    public record NeedSummary(String needId, Simulation simulation, int sampled, double mean, int min, int max, int inCrisis,
                              Optional<String> band) {
        /** {@code NOT_SIMULATED}: residents are known but none has the need switched on; {@code NO_SAMPLES}: nobody known. */
        public enum Simulation { SIMULATED, NOT_SIMULATED, NO_SAMPLES }

        public NeedSummary {
            Objects.requireNonNull(needId, "needId");
            Objects.requireNonNull(simulation, "simulation");
            band = Objects.requireNonNull(band, "band");
        }
    }

    public TownsteadVillageLifeView {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(dimension, "dimension");
        villageIds = List.copyOf(Objects.requireNonNull(villageIds, "villageIds"));
        needs = Map.copyOf(Objects.requireNonNull(needs, "needs"));
        Objects.requireNonNull(coverage, "coverage");
    }

    public Optional<NeedSummary> need(String needId) {
        return Optional.ofNullable(needs.get(needId));
    }

    /** Known residents on the current roll as a share of it, at most 1. */
    public double coverageFraction() {
        return residents <= 0 ? 0.0 : Math.min(known, residents) / (double) residents;
    }

    public long oldestSampleAgeDays() {
        return Math.max(0L, worldDay - oldestSampleWorldDay);
    }
}
