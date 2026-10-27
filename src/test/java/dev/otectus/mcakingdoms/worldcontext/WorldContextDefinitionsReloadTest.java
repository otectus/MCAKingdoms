package dev.otectus.mcakingdoms.worldcontext;

import dev.otectus.mcakingdoms.data.ReloadReport;
import dev.otectus.mcakingdoms.testsupport.MemoryResources;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** World-context selectors keep their last complete snapshot when a reload rejects them, and say so. */
class WorldContextDefinitionsReloadTest {
    private static final String SHIPPED = "mcakingdoms:warfare/world/defaults.json";

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
    void invalidSelectorsRetainThePriorSnapshot() {
        assertEquals(ReloadReport.State.ACCEPTED, reload(MemoryResources.shipped()).state());
        var before = WorldContextDefinitions.get();
        assertFalse(before.structures().isEmpty() && before.encounters().isEmpty(), "Shipped selectors should load");

        var rejected = reload(MemoryResources.shipped().put("mcakingdoms:warfare/world/broken.json",
                "{\"encounters\":[{\"id\":\"minecraft:raid\",\"role\":\"raid\",\"repeat_budget\":99}]}"));
        assertEquals(ReloadReport.State.PARTIAL, rejected.state());
        assertSame(before, WorldContextDefinitions.get(), "The rejected reload replaced the active snapshot");
        String text = ReloadReport.describe(Optional.of(rejected), null).stream().map(Component::getString).collect(Collectors.joining("\n"));
        assertTrue(text.contains("warfare/world/broken.json") && text.contains("repeat_budget must be from 1 to 32 (found 99)"), text);
        assertTrue(text.contains("World-context selectors: the previous definitions remain active."), text);
    }

    @Test
    void malformedSelectorFileIsReported() {
        reload(MemoryResources.shipped());
        var rejected = reload(MemoryResources.shipped().put(SHIPPED, "{\"structures\": ["));
        assertEquals(ReloadReport.State.PARTIAL, rejected.state());
        String text = ReloadReport.describe(Optional.of(rejected), null).stream().map(Component::getString).collect(Collectors.joining("\n"));
        assertTrue(text.contains("warfare/world/defaults.json: malformed JSON"), text);
    }
}
