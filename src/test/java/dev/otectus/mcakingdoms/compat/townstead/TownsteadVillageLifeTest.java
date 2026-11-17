package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView.NeedSummary.Simulation;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Welfare is interpreted, never guessed (spec 8.1): unavailable is not healthy, heat is not prosperity, coverage is capped. */
class TownsteadVillageLifeTest {
    private static final UUID SETTLEMENT = UUID.randomUUID();
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft:overworld");
    private static final TownsteadVillageLife.Limits LIMITS = new TownsteadVillageLife.Limits(0.5, 3);

    private static TownsteadBackend.NeedStat stat(String need, int sampled, double mean, int crisis, String band) {
        return new TownsteadBackend.NeedStat(need, sampled, mean, (int) mean - 2, (int) mean + 2, crisis, band);
    }

    private static TownsteadVillageLifeView interpret(TownsteadBackend.NeedsSummary... villages) {
        Map<Integer, TownsteadBackend.NeedsSummary> map = new LinkedHashMap<>();
        for (int i = 0; i < villages.length; i++) map.put(i + 1, villages[i]);
        return TownsteadVillageLife.interpret(SETTLEMENT, OVERWORLD, map, LIMITS);
    }

    @Test
    void aNeedNobodySimulatesIsNotSimulatedRatherThanSatisfied() {
        var life = interpret(new TownsteadBackend.NeedsSummary(18, 14, 6, 0, 98, 100,
                Map.of(TownsteadNeedLevelView.HUNGER, stat(TownsteadNeedLevelView.HUNGER, 14, 15, 0, "steady"))));
        assertEquals(Simulation.NOT_SIMULATED, life.need(TownsteadNeedLevelView.THIRST).orElseThrow().simulation());
        assertEquals(Simulation.SIMULATED, life.need(TownsteadNeedLevelView.HUNGER).orElseThrow().simulation());
        assertEquals(2, life.oldestSampleAgeDays());
        assertEquals(14 / 18.0, life.coverageFraction(), 1e-9);
        assertEquals(TownsteadVillageLifeView.Coverage.PARTIAL, life.coverage());
    }

    @Test
    void temperatureCarriesNoMeanFractionBandSoAHotterVillageIsNeverMoreProsperous() {
        var life = interpret(new TownsteadBackend.NeedsSummary(10, 10, 10, 0, 100, 100,
                Map.of(TownsteadNeedLevelView.TEMPERATURE, stat(TownsteadNeedLevelView.TEMPERATURE, 10, 420, 6, "thriving"))));
        var temperature = life.need(TownsteadNeedLevelView.TEMPERATURE).orElseThrow();
        assertEquals(Optional.empty(), temperature.band());
        assertEquals(6, temperature.inCrisis(), "thermal crisis counts are kept, heat or cold");
        assertEquals(TownsteadVillageLifeView.Coverage.GOOD, life.coverage());
    }

    @Test
    void knownAboveTheRollIsReportedAndCoverageIsCapped() {
        var life = interpret(new TownsteadBackend.NeedsSummary(5, 9, 2, 0, 100, 100, Map.of()));
        assertTrue(life.inconsistentCounts());
        assertEquals(1.0, life.coverageFraction(), 1e-9, "capped, never more than the roll");
    }

    @Test
    void poorCoverageOrStaleReadingsAreInsufficientInformation() {
        assertEquals(TownsteadVillageLifeView.Coverage.INSUFFICIENT, interpret(new TownsteadBackend.NeedsSummary(20, 4, 4, 0, 100, 100, Map.of())).coverage());
        assertEquals(TownsteadVillageLifeView.Coverage.INSUFFICIENT, interpret(new TownsteadBackend.NeedsSummary(10, 10, 0, 0, 90, 100, Map.of())).coverage(),
                "nobody loaded and the oldest reading ten days old");
        assertEquals(TownsteadVillageLifeView.Coverage.NONE, interpret(new TownsteadBackend.NeedsSummary(0, 0, 0, 0, 100, 100, Map.of())).coverage());
        assertEquals(Simulation.NO_SAMPLES, interpret(new TownsteadBackend.NeedsSummary(0, 0, 0, 0, 100, 100, Map.of()))
                .need(TownsteadNeedLevelView.HUNGER).orElseThrow().simulation());
    }

    @Test
    void severalVillagesAfterAMergeAddUpAndKeepTheMostSevereBand() {
        var life = interpret(new TownsteadBackend.NeedsSummary(10, 10, 5, 1, 99, 100, Map.of(TownsteadNeedLevelView.HUNGER, stat(TownsteadNeedLevelView.HUNGER, 10, 16, 0, "steady"))),
                new TownsteadBackend.NeedsSummary(6, 6, 0, 2, 97, 100, Map.of(TownsteadNeedLevelView.HUNGER, stat(TownsteadNeedLevelView.HUNGER, 6, 4, 3, "crisis"))));
        var hunger = life.need(TownsteadNeedLevelView.HUNGER).orElseThrow();
        assertEquals(16, hunger.sampled());
        assertEquals((16 * 10 + 4 * 6) / 16.0, hunger.mean(), 1e-9);
        assertEquals(Optional.of("crisis"), hunger.band());
        assertEquals(3, life.collapsed());
        assertEquals(97, life.oldestSampleWorldDay());
        assertEquals(2, life.villageIds().size());
    }

    @Test
    void anUnrecognizedBandWordIsNotRanked() {
        var life = interpret(new TownsteadBackend.NeedsSummary(4, 4, 4, 0, 100, 100, Map.of(TownsteadNeedLevelView.HUNGER, stat(TownsteadNeedLevelView.HUNGER, 4, 10, 0, "peckish"))));
        assertEquals(Optional.empty(), life.need(TownsteadNeedLevelView.HUNGER).orElseThrow().band());
    }
}
