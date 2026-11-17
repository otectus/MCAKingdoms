package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadSpiritView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView.NeedSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * Optional civic aid suggestions (spec 8.2): a small set of data-pack rules ({@code mcakingdoms_townstead/suggestions})
 * that point at existing tasks and commissions when a village shows sustained, well-sampled need. A rule turns on only
 * after {@code sustain} consecutive fresh readings at or above its {@code enter} level with enough of the roll known,
 * turns off below {@code exit}, and then waits its cooldown, so one fluctuating villager neither creates nor cancels
 * anything. The first reading of a village is its baseline and never a transition. Nothing here penalizes, taxes, kills
 * or rebels; a rule whose need is not simulated (thirst without a thirst provider, temperature without a climate mod) or
 * whose Townstead system is switched off simply never applies. The owning task or commission decides everything else.
 */
final class TownsteadSuggestions {
    /** Families that make a learning site for the {@code learning} signal. */
    static final Set<String> LEARNING = Set.of("library", "archives");

    private final Map<String, Track> tracks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Track> eldest) { return size() > 2_048; }
    };
    private final Map<UUID, Long> readings = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) { return size() > 512; }
    };

    /** One rule's state for one settlement. */
    static final class Track {
        boolean active;
        int streak;
        long cooldownUntil = Long.MIN_VALUE;
        boolean baseline = true;
    }

    /** Context a rule may read besides the welfare view. */
    record Context(TownsteadVillageLifeView life, Optional<TownsteadSpiritView> spirit, List<TownsteadBuildingEvidence> buildings,
                   boolean careersUsable) {
    }

    record Shown(TownsteadMappings.Suggestion rule, double value) {
    }

    /**
     * Steps every rule once for a new reading of the settlement ({@code reading} identifies it; the same reading is never
     * counted twice however many players look) and returns the rules that are on.
     */
    List<Shown> step(UUID settlement, long reading, Context context, List<TownsteadMappings.Suggestion> rules) {
        boolean fresh = !Objects.equals(readings.put(settlement, reading), reading);
        List<Shown> out = new ArrayList<>();
        long day = context.life().worldDay();
        for (TownsteadMappings.Suggestion rule : rules) {
            Track track = tracks.computeIfAbsent(settlement + "|" + rule.id(), ignored -> new Track());
            OptionalDouble value = signal(rule, context);
            if (fresh) advance(track, rule, value, day);
            if (track.active && value.isPresent()) out.add(new Shown(rule, value.getAsDouble()));
        }
        return out;
    }

    static void advance(Track track, TownsteadMappings.Suggestion rule, OptionalDouble value, long day) {
        if (value.isEmpty()) {
            // No usable reading: nothing changes, and a streak in progress does not carry over a gap.
            track.streak = 0;
            return;
        }
        double v = value.getAsDouble();
        if (track.baseline) {
            // The first reading is a baseline, not a crisis transition (spec 8.2).
            track.baseline = false;
            track.streak = v >= rule.enter() ? 1 : 0;
            return;
        }
        if (track.active) {
            if (v < rule.exit()) {
                track.active = false;
                track.streak = 0;
                track.cooldownUntil = day + rule.cooldownDays();
            }
            return;
        }
        track.streak = v >= rule.enter() ? track.streak + 1 : 0;
        if (track.streak >= rule.sustain() && day >= track.cooldownUntil) track.active = true;
    }

    /** The rule's reading, or empty when the data do not support one (poor coverage, stale, not simulated, switched off). */
    static OptionalDouble signal(TownsteadMappings.Suggestion rule, Context context) {
        TownsteadVillageLifeView life = context.life();
        boolean needSignal = switch (rule.signal()) {
            case HUNGER, THIRST, THERMAL, EXHAUSTION -> true;
            default -> false;
        };
        if (needSignal) {
            if (life.coverage() == TownsteadVillageLifeView.Coverage.NONE || life.coverage() == TownsteadVillageLifeView.Coverage.INSUFFICIENT) return OptionalDouble.empty();
            if (life.coverageFraction() < rule.minimumCoverage() || life.oldestSampleAgeDays() > rule.maximumAgeDays()) return OptionalDouble.empty();
        }
        return switch (rule.signal()) {
            case HUNGER -> crisisShare(life.need(TownsteadNeedLevelView.HUNGER));
            case THIRST -> crisisShare(life.need(TownsteadNeedLevelView.THIRST));
            case THERMAL -> crisisShare(life.need(TownsteadNeedLevelView.TEMPERATURE));
            case EXHAUSTION -> {
                OptionalDouble energy = crisisShare(life.need(TownsteadNeedLevelView.ENERGY));
                double collapsed = life.known() <= 0 ? 0.0 : life.collapsed() / (double) Math.min(life.known(), Math.max(1, life.residents()));
                yield energy.isPresent() ? OptionalDouble.of(Math.max(energy.getAsDouble(), collapsed)) : OptionalDouble.empty();
            }
            case LEARNING -> {
                if (!context.careersUsable()) yield OptionalDouble.empty();
                boolean site = context.buildings().stream().anyMatch(b -> LEARNING.contains(b.building().family()) && b.verifiedComplete());
                yield OptionalDouble.of(site ? 1.0 : 0.0);
            }
            case SPIRIT_TIER -> context.spirit().map(s -> OptionalDouble.of(s.tier())).orElse(OptionalDouble.empty());
        };
    }

    /** Share of sampled residents in this need's crisis band; empty when it is not simulated or nobody was sampled. */
    static OptionalDouble crisisShare(Optional<NeedSummary> need) {
        if (need.isEmpty() || need.get().simulation() != NeedSummary.Simulation.SIMULATED || need.get().sampled() <= 0) return OptionalDouble.empty();
        return OptionalDouble.of(need.get().inCrisis() / (double) need.get().sampled());
    }

    static boolean careersUsable(TownsteadServiceImpl townstead) {
        return townstead.states().usable(TownsteadCapability.READ_CAREERS);
    }

    void clear() {
        tracks.clear();
        readings.clear();
    }

    int tracked() {
        return tracks.size();
    }
}
