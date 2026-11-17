package dev.otectus.mcakingdoms.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** AUD-01: an identity operation keeps its pending stores and reasons through a save, and finishes store by store. */
class IdentityOperationTest {
    @Test void itSurvivesASaveAndFinishesStoreByStore() {
        var op = new IdentityOperation(UUID.randomUUID(), IdentityOperation.Kind.MERGE, UUID.randomUUID(), UUID.randomUUID(), "mcakingdoms:madera",
                "mcakingdoms:madera", 42, "Oakfield", Set.of("civic", "development"), Map.of());
        var failed = op.failed("civic", "read-only: civic");
        var loaded = IdentityOperation.load(failed.save());
        assertEquals(failed, loaded);
        assertEquals("read-only: civic", loaded.errors().get("civic"));
        var half = loaded.done("development");
        assertEquals(Set.of("civic"), half.pending());
        var finished = half.done("civic");
        assertTrue(finished.pending().isEmpty());
        assertTrue(finished.errors().isEmpty(), "a finished store keeps no stale reason");
    }

    @Test void aMalformedOperationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> IdentityOperation.load(new net.minecraft.nbt.CompoundTag()));
    }

    @Test void consumersAreNamedForEachKind() {
        assertTrue(IdentityConsumers.names(IdentityOperation.Kind.MERGE).containsAll(Set.of("discovery", "civic", "politics", "development", "world_evolution")));
        assertEquals(Set.of("development"), IdentityConsumers.names(IdentityOperation.Kind.REASSIGN));
    }
}
