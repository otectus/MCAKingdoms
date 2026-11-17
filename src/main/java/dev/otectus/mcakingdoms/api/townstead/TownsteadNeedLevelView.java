package dev.otectus.mcakingdoms.api.townstead;

import java.util.Objects;

/**
 * One need on its own scale, as Townstead's api.v1 publishes it. Energy is energy (higher is better), not the fatigue of
 * {@link TownsteadNeedsView}. Temperature is two-sided: heat and cold both harm, so read {@link #crisis} and {@link #band}
 * rather than {@link #fraction}, which only says how warm.
 */
public record TownsteadNeedLevelView(String needId, int value, int min, int max, int neutral, String band,
                                     boolean crisis, boolean enabled) {
    public static final String HUNGER = "townstead:hunger";
    public static final String THIRST = "townstead:thirst";
    public static final String ENERGY = "townstead:energy";
    public static final String TEMPERATURE = "townstead:temperature";

    public TownsteadNeedLevelView {
        Objects.requireNonNull(needId, "needId");
        band = band == null ? "" : band;
    }

    public boolean thermal() {
        return TEMPERATURE.equals(needId);
    }

    /** The reading as a fraction of its scale; a ranking of warmth for temperature, not of comfort. */
    public double fraction() {
        int range = max - min;
        return range <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (value - min) / (double) range));
    }

    /**
     * How far the reading is from comfortable, as a fraction of the scale: below neutral for the one-sided needs, either
     * side of neutral for temperature. Zero for a disabled need.
     */
    public double distress() {
        int range = max - min;
        if (!enabled || range <= 0) return 0.0;
        double below = Math.max(0.0, (neutral - value) / (double) range);
        return thermal() ? Math.abs(value - neutral) / (double) range : below;
    }
}
