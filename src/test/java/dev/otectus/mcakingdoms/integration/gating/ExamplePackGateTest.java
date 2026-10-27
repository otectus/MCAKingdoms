package dev.otectus.mcakingdoms.integration.gating;

import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The example kingdom gate loads and admits only its kingdom. */
class ExamplePackGateTest {
    @Test
    void exampleGateMatchesOnlyItsKingdom() {
        var gates = KingdomGateRegistry.parse(MemoryResources.withExamplePack());
        var gate = gates.get(new ResourceLocation("example:harbor_league_only"));
        assertNotNull(gate, "gates: " + gates.keySet());
        assertTrue(gate.matches(new ResourceLocation("example:harbor_league")));
        assertFalse(gate.matches(new ResourceLocation("mcakingdoms:serenum")));
        assertFalse(gate.matches((ResourceLocation) null), "An unknown kingdom must be denied, as the file states");
    }
}
