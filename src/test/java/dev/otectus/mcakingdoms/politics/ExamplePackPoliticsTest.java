package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.testsupport.ExamplePack;
import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The example pack's government charter loads next to the shipped ones; its invalid variant is refused by name. */
class ExamplePackPoliticsTest {
    @Test
    void exampleCharterLoads() {
        var shipped = PoliticalDefinitions.parse(MemoryResources.shipped());
        var example = PoliticalDefinitions.parse(MemoryResources.withExamplePack());
        assertEquals(shipped.size() + 1, example.size());
        var charter = example.get("example:harbor_league_charter");
        assertNotNull(charter);
        assertEquals("government", charter.kind());
        assertTrue(charter.offices().contains("mcakingdoms:leader"));
    }

    @Test
    void invalidVariantsFailWithTheDocumentedDiagnostics() {
        var cases = ExamplePack.invalid("politics");
        assertFalse(cases.isEmpty());
        for (var example : cases) {
            String message = assertThrows(IllegalArgumentException.class, () -> PoliticalDefinitions.parse(example.resources()), example.name()).getMessage();
            for (String expected : example.expected()) assertTrue(message.contains(expected), example.name() + ": \"" + expected + "\" not in " + message);
        }
    }
}
