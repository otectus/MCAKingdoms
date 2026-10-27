package dev.otectus.mcakingdoms.worldcontext;

import dev.otectus.mcakingdoms.data.ReloadReport;
import dev.otectus.mcakingdoms.testsupport.ExamplePack;
import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The example selectors join the shipped ones; a structure listed twice rejects only this domain, keeping its old snapshot. */
class ExamplePackWorldContextTest {
    @BeforeEach
    void fresh() {
        ReloadReport.reset();
    }

    private static ReloadReport.Attempt reload(MemoryResources resources) {
        var listener = new WorldContextDefinitions();
        var attempt = ReloadReport.begin();
        listener.apply(listener.prepare(resources, null), resources, null);
        ReloadReport.committed();
        return attempt;
    }

    @Test
    void exampleSelectorsLoad() {
        assertEquals(ReloadReport.State.ACCEPTED, reload(MemoryResources.withExamplePack()).state());
        var snapshot = WorldContextDefinitions.get();
        assertEquals("ruin", snapshot.structures().get(new ResourceLocation("minecraft:shipwreck")));
        assertTrue(snapshot.encounters().containsKey(new ResourceLocation("minecraft:drowned")));
    }

    @Test
    void invalidVariantsFailWithTheDocumentedDiagnostics() {
        var cases = ExamplePack.invalid("world_context");
        assertFalse(cases.isEmpty());
        for (var example : cases) {
            assertEquals(ReloadReport.State.ACCEPTED, reload(MemoryResources.withExamplePack()).state());
            var before = WorldContextDefinitions.get();
            var attempt = reload(example.resources());
            assertEquals(ReloadReport.State.PARTIAL, attempt.state(), example.name());
            assertSame(before, WorldContextDefinitions.get(), example.name() + ": the rejected reload replaced the active selectors");
            String report = ReloadReport.plain(attempt);
            for (String expected : example.expected()) assertTrue(report.contains(expected), example.name() + ": \"" + expected + "\" not in " + report);
        }
    }
}
