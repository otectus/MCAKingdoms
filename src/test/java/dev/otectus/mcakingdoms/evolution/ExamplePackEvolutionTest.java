package dev.otectus.mcakingdoms.evolution;

import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The example scenario loads as an ordinary (not dramatic) opportunity with DECLINE among its outcomes. */
class ExamplePackEvolutionTest {
    @Test
    void exampleScenarioLoads() {
        var scenarios = EvolutionDefinitions.parse(MemoryResources.withExamplePack());
        var relief = scenarios.get("example:harbor_relief");
        assertNotNull(relief, "scenarios: " + scenarios.keySet());
        assertFalse(relief.dramatic());
        assertTrue(relief.outcomes().contains(EvolutionState.Outcome.DECLINE));
        assertEquals(2, relief.contributions());
    }
}
