package dev.otectus.mcakingdoms.data;

import dev.otectus.mcakingdoms.testsupport.ExamplePack;
import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The documented example pack loads beside the shipped definitions, and each invalid variant fails as documented. */
class ExamplePackCoreTest {
    @Test
    void examplePackAddsOneKingdomWithItsNamePoolAndBiomeRule() {
        DefinitionSnapshot shipped = DefinitionRegistry.parse(MemoryResources.shipped());
        DefinitionSnapshot example = DefinitionRegistry.parse(MemoryResources.withExamplePack());
        assertEquals(shipped.kingdomCount() + 1, example.kingdomCount());
        assertEquals(shipped.namePoolCount() + 1, example.namePoolCount());
        assertEquals(shipped.biomeRuleCount() + 1, example.biomeRuleCount());
        assertEquals(shipped.styleRuleCount(), example.styleRuleCount());
        assertTrue(example.kingdom(new ResourceLocation("example:harbor_league")).isPresent());
        // Loading at all proves the example declares no second fallback: exactly one kingdom may.
    }

    @Test
    void invalidVariantsFailWithTheDocumentedDiagnostics() {
        var cases = ExamplePack.invalid("core");
        assertFalse(cases.isEmpty());
        for (var example : cases) {
            String message = assertThrows(IllegalArgumentException.class, () -> DefinitionRegistry.parse(example.resources()), example.name()).getMessage();
            for (String expected : example.expected()) assertTrue(message.contains(expected), example.name() + ": \"" + expected + "\" not in " + message);
        }
    }
}
