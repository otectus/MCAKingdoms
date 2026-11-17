package dev.otectus.mcakingdoms.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUD-14: an exception while reading a capability is "could not determine", never proof of availability. World
 * evolution and world events read the server's own records; with no server to read, their checks fail, and the shared
 * safe check must answer unknown (keeping the task usable while its owning service decides) with a reason.
 */
class CapabilityFactsTest {
    @Test
    void aCheckThatFailsIsUnknownNeverAvailable() {
        for (var feature : new TaskMeta.Feature[] {TaskMeta.Feature.EVOLUTION, TaskMeta.Feature.DRAMA}) {
            assertThrows(RuntimeException.class, () -> TaskMeta.check(feature, null), feature + " read nothing, so this test proves nothing");
            var answer = TaskMeta.safeCheck(feature, null);
            assertFalse(answer.available(), feature + " claimed to be available when its state could not be read");
            assertEquals(TaskMeta.State.UNKNOWN, answer.state(), feature + " did not say it could not determine its state");
            assertTrue(answer.usable(), feature + ": unknown must leave the owning service to decide");
            assertNotEquals("", answer.reason().getString(), feature + " gave no reason");
        }
    }
}
