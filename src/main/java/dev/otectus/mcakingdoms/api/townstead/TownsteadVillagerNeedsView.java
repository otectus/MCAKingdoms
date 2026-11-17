package dev.otectus.mcakingdoms.api.townstead;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A villager's needs from Townstead's api.v1, every registered need with its own scale. Unlike Townstead's own
 * convenience methods, {@link #inCrisis()} and {@link #primaryNeed()} ignore needs that are switched off or not simulated
 * for this villager, and treat heat and cold alike.
 */
public record TownsteadVillagerNeedsView(Map<String, TownsteadNeedLevelView> levels, boolean collapsed, boolean thirstSimulated,
                                         OptionalInt bodyTemperatureTenths, OptionalInt ambientTemperatureTenths) {
    public TownsteadVillagerNeedsView {
        levels = Map.copyOf(Objects.requireNonNull(levels, "levels"));
        bodyTemperatureTenths = Objects.requireNonNull(bodyTemperatureTenths, "bodyTemperatureTenths");
        ambientTemperatureTenths = Objects.requireNonNull(ambientTemperatureTenths, "ambientTemperatureTenths");
    }

    public Optional<TownsteadNeedLevelView> level(String needId) {
        return Optional.ofNullable(levels.get(needId));
    }

    public boolean enabled(String needId) {
        return level(needId).map(TownsteadNeedLevelView::enabled).orElse(false);
    }

    public boolean inCrisis() {
        return levels.values().stream().anyMatch(level -> level.enabled() && level.crisis());
    }

    /** The enabled need furthest from comfortable, if any is away from it. */
    public Optional<String> primaryNeed() {
        return levels.values().stream().filter(TownsteadNeedLevelView::enabled).filter(level -> level.distress() > 0.0)
                .max(java.util.Comparator.comparingDouble(TownsteadNeedLevelView::distress)
                        .thenComparing(TownsteadNeedLevelView::needId, java.util.Comparator.reverseOrder()))
                .map(TownsteadNeedLevelView::needId);
    }
}
