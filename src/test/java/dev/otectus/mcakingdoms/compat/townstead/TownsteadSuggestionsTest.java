package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Suggestions turn on only for sustained, well-sampled need and never from the first reading (spec 8.2). */
class TownsteadSuggestionsTest {
    private static final TownsteadMappings.Suggestion FOOD = new TownsteadMappings.Suggestion(new ResourceLocation("mcakingdoms:food_relief"),
            TownsteadMappings.Signal.HUNGER, 0.25, 0.10, 0.5, 3, 2, 1, Optional.of("development.propose"), "townstead.mcakingdoms.suggestion.food");

    private static TownsteadVillageLifeView life(long day, int crisis, int known, String thirstOrNot) {
        var hunger = new TownsteadBackend.NeedStat(TownsteadNeedLevelView.HUNGER, 10, 8, 2, 14, crisis, "strained");
        var summary = new TownsteadBackend.NeedsSummary(10, known, 3, 0, day, day, Map.of(TownsteadNeedLevelView.HUNGER, hunger));
        return TownsteadVillageLife.interpret(UUID.randomUUID(), new ResourceLocation("minecraft:overworld"), Map.of(1, summary), new TownsteadVillageLife.Limits(0.5, 3));
    }

    private static TownsteadSuggestions.Context context(TownsteadVillageLifeView life) {
        return new TownsteadSuggestions.Context(life, Optional.empty(), List.of(), false);
    }

    @Test
    void theFirstReadingIsABaselineAndTwoSustainedReadingsTurnItOn() {
        var suggestions = new TownsteadSuggestions();
        UUID settlement = UUID.randomUUID();
        assertTrue(suggestions.step(settlement, 1, context(life(1, 5, 10, "")), List.of(FOOD)).isEmpty(), "baseline");
        assertEquals(1, suggestions.step(settlement, 2, context(life(2, 5, 10, "")), List.of(FOOD)).size(), "second high reading: on");
        assertEquals(1, suggestions.step(settlement, 2, context(life(2, 5, 10, "")), List.of(FOOD)).size(), "the same reading viewed again counts once");
    }

    @Test
    void itTurnsOffBelowExitAndThenWaitsItsCooldown() {
        var track = new TownsteadSuggestions.Track();
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.5), 1);
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.5), 1);
        assertTrue(track.active);
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.2), 2);
        assertTrue(track.active, "between exit and enter: stays on (hysteresis)");
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.05), 2);
        assertFalse(track.active);
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.5), 2);
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.5), 2);
        assertFalse(track.active, "cooldown of one day");
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.5), 3);
        assertTrue(track.active);
    }

    @Test
    void oneFluctuatingReadingNeverTurnsItOn() {
        var track = new TownsteadSuggestions.Track();
        TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(0.0), 1);
        for (int i = 0; i < 6; i++) {
            TownsteadSuggestions.advance(track, FOOD, OptionalDouble.of(i % 2 == 0 ? 0.6 : 0.0), 1);
            assertFalse(track.active);
        }
    }

    @Test
    void poorCoverageOrANeedNotSimulatedGivesNoReading() {
        assertTrue(TownsteadSuggestions.signal(FOOD, context(life(1, 5, 3, ""))).isEmpty(), "three of ten known");
        var thirst = new TownsteadMappings.Suggestion(new ResourceLocation("mcakingdoms:water"), TownsteadMappings.Signal.THIRST, 0.25, 0.1, 0.5, 3, 2, 1,
                Optional.empty(), "townstead.mcakingdoms.suggestion.water");
        assertTrue(TownsteadSuggestions.signal(thirst, context(life(1, 5, 10, ""))).isEmpty(), "thirst is not simulated here");
        assertEquals(0.5, TownsteadSuggestions.signal(FOOD, context(life(1, 5, 10, ""))).getAsDouble(), 1e-9);
    }

    @Test
    void aLearningSiteNeedsCareersSwitchedOn() {
        var learning = new TownsteadMappings.Suggestion(new ResourceLocation("mcakingdoms:apprenticeship"), TownsteadMappings.Signal.LEARNING, 1, 1, 0.5, 3, 1, 1,
                Optional.of("civic.chapters"), "townstead.mcakingdoms.suggestion.learning");
        assertTrue(TownsteadSuggestions.signal(learning, context(life(1, 0, 10, ""))).isEmpty());
    }
}
