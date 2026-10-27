package dev.otectus.mcakingdoms.evolution;

import org.junit.jupiter.api.Test;

import java.util.*;

import static dev.otectus.mcakingdoms.evolution.EvolutionState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F06: a scenario's deadline advances with the time its region is actually occupied, whichever regions the bounded
 * generation budget reaches. The review's reproduction (eight populated regions, budget four, alternating samples)
 * advanced no region at all under the old consecutive-sample rule.
 */
class EvolutionActivityTest {
    private static final long INTERVAL = 1_200;

    private static Scenario scenario(long created, long duration) {
        return new Scenario(UUID.randomUUID(), "mcakingdoms:test", new Template(1, "Aid", Trigger.DEMAND,
                List.of(Outcome.AID, Outcome.DECLINE), duration, 2400, 1, false), UUID.randomUUID(),
                null, "mcakingdoms:a", new Evidence("owner", "receipt", "DEMAND", created, "Verified demand"), null,
                created, created + duration, 1, Phase.OPEN, Map.of(), null, "");
    }

    @Test
    void creditCountsOnlyOccupiedTime() {
        assertEquals(0, EvolutionService.creditedTicks(null, 100, 1300), "an empty region earns nothing");
        assertEquals(1200, EvolutionService.creditedTicks(50L, 100, 1300), "occupied throughout");
        assertEquals(500, EvolutionService.creditedTicks(800L, 100, 1300), "occupied from mid-window");
        assertEquals(0, EvolutionService.creditedTicks(1300L, 100, 1300), "arrived just now");
    }

    @Test
    void eightContinuouslyPopulatedRegionsAllAdvanceUnderABudgetOfFour() {
        // Every region holds a player the whole time; occupation was first observed at tick 0.
        List<Scenario> scenarios = new ArrayList<>();
        for (int region = 0; region < 8; region++) scenarios.add(scenario(0, 12_000));
        long last = 0;
        for (int pass = 1; pass <= 20; pass++) {
            long now = pass * INTERVAL;
            for (int i = 0; i < scenarios.size(); i++) scenarios.set(i, EvolutionService.account(scenarios.get(i), 0L, last, now));
            last = now;
        }
        // 20 windows of 1,200 ticks = 24,000 ticks of presence: every 12,000-tick opportunity ran its course.
        for (Scenario e : scenarios) {
            assertEquals(12_000, e.deadline(), "continuous presence never extends the deadline");
            assertEquals(Phase.EXPIRED, e.phase(), "every populated region advanced to its deadline");
        }
    }

    @Test
    void anEmptyRegionPausesAndReturningAppliesNoCatchUp() {
        var e = scenario(0, 2_400);
        e = EvolutionService.account(e, null, 0, 1_200);          // nobody there for the first window
        assertEquals(3_600, e.deadline(), "the empty window is added back to the deadline");
        e = EvolutionService.account(e, null, 1_200, 6_000);      // a long absence: still frozen
        assertEquals(8_400, e.deadline());
        assertEquals(Phase.OPEN, e.phase(), "absence alone never expires an opportunity");
        e = EvolutionService.account(e, 6_000L, 6_000, 7_200);    // back for one full window
        assertEquals(8_400, e.deadline());
        assertEquals(Phase.OPEN, e.phase());
        e = EvolutionService.account(e, 6_000L, 7_200, 8_400);    // and the next: now due
        assertEquals(Phase.EXPIRED, e.phase());
    }

    @Test
    void partialOccupationCreditsOnlyThePresentPart() {
        var e = scenario(0, 10_000);
        e = EvolutionService.account(e, 900L, 0, 1_200);           // arrived at 900 of a 0..1200 window
        assertEquals(10_900, e.deadline(), "the 900 unoccupied ticks are frozen, the 300 occupied ones count");
    }

    @Test
    void aScenarioCreatedMidWindowIsOnlyAccountedFromItsCreation() {
        var e = scenario(1_000, 5_000);
        e = EvolutionService.account(e, null, 0, 1_200);
        assertEquals(6_200, e.deadline(), "only the 200 ticks after creation are frozen");
    }

    /** F09: a failed save announces nothing; a successful one announces each prepared opportunity once. */
    @Test
    void opportunitiesAreAnnouncedOnlyAfterTheirCommit() {
        List<String> sent = new ArrayList<>();
        List<Runnable> announcements = List.of(() -> sent.add("opened at Bellmeadow"), () -> sent.add("opened at Greenford"));
        assertThrows(IllegalArgumentException.class, () -> EvolutionService.commitThenAnnounce(
                () -> { throw new IllegalArgumentException("Scenario save unavailable; action refused"); }, announcements));
        assertTrue(sent.isEmpty(), "a failed write announced an opportunity that was never saved");
        EvolutionService.commitThenAnnounce(() -> { }, announcements);
        assertEquals(List.of("opened at Bellmeadow", "opened at Greenford"), sent);
    }

    @Test
    void resolvingAndFinishedScenariosAreNeverMoved() {
        UUID player = UUID.randomUUID();
        var e = scenario(0, 1_200).contribute(new Contribution(player, Outcome.AID, new Evidence("q", "r", "AID", 1, "Aid")), 1, 1)
                .reserve(new Intent(player, Outcome.AID, UUID.randomUUID(), 0, ""), 2, 2);
        assertEquals(e, EvolutionService.account(e, null, 0, 50_000), "a pending owner request keeps its deadline and phase");
        assertEquals(e, EvolutionService.account(e, 0L, 0, 50_000));
    }
}
