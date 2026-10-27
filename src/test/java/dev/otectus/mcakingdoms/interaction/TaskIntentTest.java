package dev.otectus.mcakingdoms.interaction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F08: turning a feature off never hides the safe way out of an operation that already started. Inspecting retained
 * records, cancelling before any effect, verifying an existing effect and restoring one need no creation switch; a native
 * step still needs its provider. Starting or expanding keeps every gate, and stricter service rules (world-event
 * recovery, campaign withdrawal) stay gated exactly as their services are.
 */
class TaskIntentTest {
    @BeforeAll
    static void catalogue() {
        InteractionTasks.init();
    }

    private static ActionRegistry.Task task(String id) {
        return ActionRegistry.find(id).orElseThrow(() -> new AssertionError("no task " + id));
    }

    @Test
    void cleanupNeedsNoCreationSwitch() {
        for (String id : List.of("evolution.withdraw", "evolution.release", "drama.exit", "transfer.cancel",
                "warfare.deployments", "transfer.inspect", "drama.inspect", "evolution.inspect", "evolution.retry")) {
            var features = TaskMeta.features(task(id));
            assertFalse(features.contains(TaskMeta.Feature.EVOLUTION), id);
            assertFalse(features.contains(TaskMeta.Feature.DRAMA), id);
            assertFalse(features.contains(TaskMeta.Feature.WARFARE), id);
            assertFalse(features.contains(TaskMeta.Feature.MILITARY), id);
        }
    }

    @Test
    void nativeRecoveryStillNeedsItsProvider() {
        for (String id : List.of("warfare.deployment_dismiss", "warfare.deployment_recover", "transfer.restore", "transfer.confirm")) {
            assertEquals(List.of(TaskMeta.Feature.RECRUITS), TaskMeta.features(task(id)), id);
        }
    }

    @Test
    void startingAndStricterServiceRulesKeepTheirGates() {
        assertTrue(TaskMeta.features(task("warfare.muster")).contains(TaskMeta.Feature.MILITARY));
        assertTrue(TaskMeta.features(task("evolution.contribute")).contains(TaskMeta.Feature.EVOLUTION));
        assertTrue(TaskMeta.features(task("drama.propose")).contains(TaskMeta.Feature.DRAMA));
        assertTrue(TaskMeta.features(task("drama.recover")).contains(TaskMeta.Feature.DRAMA), "world-event recovery is stricter than exit");
        assertTrue(TaskMeta.features(task("warfare.withdraw")).contains(TaskMeta.Feature.MILITARY), "campaign withdrawal needs military actions, as its service does");
    }

    @Test
    void everyListedIntentNamesARealTask() {
        for (var task : ActionRegistry.all()) {
            if (TaskMeta.intent(task) != TaskMeta.Intent.START) assertNotNull(ActionRegistry.find(task.id()).orElse(null));
        }
        assertEquals(TaskMeta.Intent.CANCEL, TaskMeta.intent(task("drama.exit")));
        assertEquals(TaskMeta.Intent.RESTORE, TaskMeta.intent(task("warfare.deployment_recover")));
        assertEquals(TaskMeta.Intent.VERIFY, TaskMeta.intent(task("transfer.confirm")));
    }

    @Test
    void everyOperationWithANativeOrPendingPhaseIsProbed() {
        for (String id : List.of("transfer.propose", "transfer.consent", "transfer.cancel", "transfer.apply", "transfer.confirm", "transfer.restore",
                "warfare.declare", "warfare.retry_campaign", "warfare.withdraw", "warfare.accord", "warfare.accord_sign", "warfare.accord_apply",
                "warfare.accord_reject", "warfare.muster", "warfare.muster_nearby", "warfare.deployment_dismiss", "warfare.deployment_recover",
                "drama.propose", "drama.consent", "drama.execute", "drama.negotiate", "drama.exit", "drama.recover",
                "evolution.contribute", "evolution.withdraw", "evolution.resolve", "evolution.retry", "evolution.release",
                "politics.propose", "politics.sign", "politics.petition_honor", "politics.election_close")) {
            assertTrue(ActionRegistry.probed(task(id)), id + " must be readable again by Check");
        }
    }
}
