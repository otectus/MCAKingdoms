package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView.Coverage;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView.NeedSummary;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView.NeedSummary.Simulation;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Interprets Townstead's per-village need aggregates for one settlement (spec 8.1): unavailable data is never a healthy
 * village, a need nobody simulates is "not simulated" rather than satisfied, temperature carries no mean-fraction band
 * (Townstead's band ranks warmth, so a hotter village would read as more prosperous), and a known count above the roll
 * is reported as inconsistent rather than as full coverage. A cache shares one reading among every viewer and is dropped
 * by Townstead's change events.
 */
final class TownsteadVillageLife {
    /** The needs Townstead 0.8.0 simulates; any other id it reports is shown too. */
    static final List<String> STANDARD = List.of(TownsteadNeedLevelView.HUNGER, TownsteadNeedLevelView.THIRST,
            TownsteadNeedLevelView.ENERGY, TownsteadNeedLevelView.TEMPERATURE);
    private static final List<String> BAND_ORDER = List.of("thriving", "steady", "strained", "crisis");
    private static final int CACHE = 256;

    private final Map<UUID, Entry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) { return size() > CACHE; }
    };

    private record Entry(TownsteadVillageLifeView view, long computedAt) {
    }

    /** Coverage limits; from config in game, fixed in tests. */
    record Limits(double minimumCoverage, long maximumSampleAgeDays) {
    }

    Optional<TownsteadVillageLifeView> cached(UUID settlement, long now, long ttl) {
        Entry entry = cache.get(settlement);
        return entry != null && now - entry.computedAt() < ttl ? Optional.of(entry.view()) : Optional.empty();
    }

    void put(UUID settlement, TownsteadVillageLifeView view, long now) {
        cache.put(settlement, new Entry(view, now));
    }

    void invalidate(UUID settlement) {
        cache.remove(settlement);
    }

    void clear() {
        cache.clear();
    }

    int size() {
        return cache.size();
    }

    /** Ticks since the oldest shared reading was taken; empty when none is held. */
    java.util.OptionalLong oldestAge(long now) {
        return cache.values().stream().mapToLong(entry -> Math.max(0L, now - entry.computedAt())).max();
    }

    /** One settlement's view from the summaries of each of its MCA villages (usually one; more after a merge). */
    static TownsteadVillageLifeView interpret(UUID settlement, ResourceLocation dimension, Map<Integer, TownsteadBackend.NeedsSummary> villages,
                                              Limits limits) {
        int residents = 0;
        int known = 0;
        int loaded = 0;
        int collapsed = 0;
        long worldDay = 0;
        long oldest = Long.MAX_VALUE;
        Map<String, List<TownsteadBackend.NeedStat>> stats = new LinkedHashMap<>();
        Set<String> needIds = new LinkedHashSet<>(STANDARD);
        boolean inconsistent = false;
        for (TownsteadBackend.NeedsSummary summary : villages.values()) {
            residents += Math.max(0, summary.residents());
            known += Math.max(0, summary.known());
            loaded += Math.max(0, summary.loaded());
            collapsed += Math.max(0, summary.collapsed());
            worldDay = Math.max(worldDay, summary.worldDay());
            if (summary.known() > 0) oldest = Math.min(oldest, summary.oldestSampleWorldDay());
            if (summary.known() > summary.residents() || summary.loaded() > summary.known()) inconsistent = true;
            summary.stats().forEach((need, stat) -> {
                needIds.add(need);
                stats.computeIfAbsent(need, ignored -> new ArrayList<>()).add(stat);
            });
        }
        if (oldest == Long.MAX_VALUE) oldest = worldDay;
        Map<String, NeedSummary> needs = new LinkedHashMap<>();
        for (String need : needIds) needs.put(need, need(need, stats.getOrDefault(need, List.of()), known));
        long age = Math.max(0L, worldDay - oldest);
        double coverage = residents <= 0 ? 0.0 : Math.min(known, residents) / (double) residents;
        Coverage level;
        if (residents <= 0 && known <= 0) level = Coverage.NONE;
        else if (coverage < limits.minimumCoverage() || (loaded == 0 && age > limits.maximumSampleAgeDays())) level = Coverage.INSUFFICIENT;
        else if (coverage >= 0.9 && age <= limits.maximumSampleAgeDays()) level = Coverage.GOOD;
        else level = Coverage.PARTIAL;
        return new TownsteadVillageLifeView(settlement, dimension, List.copyOf(villages.keySet()), residents, known, loaded, collapsed,
                worldDay, oldest, needs, level, inconsistent);
    }

    private static NeedSummary need(String need, List<TownsteadBackend.NeedStat> stats, int known) {
        int sampled = stats.stream().mapToInt(TownsteadBackend.NeedStat::sampled).sum();
        if (sampled <= 0) return new NeedSummary(need, known > 0 ? Simulation.NOT_SIMULATED : Simulation.NO_SAMPLES, 0, 0, 0, 0, 0, Optional.empty());
        double weighted = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int crisis = 0;
        String band = null;
        for (TownsteadBackend.NeedStat stat : stats) {
            weighted += stat.mean() * stat.sampled();
            min = Math.min(min, stat.min());
            max = Math.max(max, stat.max());
            crisis += Math.max(0, stat.inCrisis());
            // The most severe band among the villages; an unknown band word is not ranked at all.
            int rank = BAND_ORDER.indexOf(stat.band());
            if (rank >= 0 && (band == null || rank > BAND_ORDER.indexOf(band))) band = stat.band();
        }
        boolean thermal = TownsteadNeedLevelView.TEMPERATURE.equals(need);
        return new NeedSummary(need, Simulation.SIMULATED, sampled, weighted / sampled, min, max, crisis,
                thermal ? Optional.empty() : Optional.ofNullable(band));
    }
}
