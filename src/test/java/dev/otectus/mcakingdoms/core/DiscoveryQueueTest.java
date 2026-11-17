package dev.otectus.mcakingdoms.core;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUD-11: the discovery queue is bounded, deduplicated and fair: a busy dimension cannot starve another, a full queue
 * postpones (and counts) rather than grows, requested scans go first, and delayed rescans coalesce until due.
 */
class DiscoveryQueueTest {
    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> key(String path) {
        try {
            var constructor = ResourceKey.class.getDeclaredConstructor(ResourceLocation.class, ResourceLocation.class);
            constructor.setAccessible(true);
            return (ResourceKey<Level>) constructor.newInstance(new ResourceLocation("minecraft", "dimension"), new ResourceLocation("test", path));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final ResourceKey<Level> BUSY = key("busy"), QUIET = key("quiet");

    @Test void aChunkIsQueuedOnceAtATime() {
        var queue = new DiscoveryQueue();
        assertTrue(queue.offer(BUSY, 7, false));
        assertFalse(queue.offer(BUSY, 7, false), "a queued chunk was queued twice");
        assertEquals(1, queue.size());
        assertEquals(7L, queue.poll().orElseThrow().chunk());
        assertTrue(queue.poll().isEmpty());
        assertTrue(queue.offer(BUSY, 7, false), "a scanned chunk could not be queued again");
    }

    @Test void aBusyDimensionCannotStarveAnother() {
        var queue = new DiscoveryQueue();
        for (long chunk = 0; chunk < 1000; chunk++) queue.offer(BUSY, chunk, false);
        queue.offer(QUIET, 1, false);
        var first = new ArrayList<ResourceKey<Level>>();
        for (int i = 0; i < 4; i++) first.add(queue.poll().orElseThrow().dimension());
        assertTrue(first.contains(QUIET), "the quiet dimension waited behind 1000 busy chunks: " + first);
    }

    @Test void aFullQueuePostponesAndCounts() {
        var queue = new DiscoveryQueue();
        for (long chunk = 0; chunk < DiscoveryQueue.LIMIT; chunk++) assertTrue(queue.offer(BUSY, chunk, false));
        assertFalse(queue.offer(QUIET, 1, false));
        assertFalse(queue.offer(BUSY, DiscoveryQueue.LIMIT, true));
        assertEquals(DiscoveryQueue.LIMIT, queue.size(), "the queue grew past its bound");
        assertEquals(2, queue.postponed());
        queue.poll();
        assertTrue(queue.offer(QUIET, 1, false), "a postponed chunk could not be offered again once there was room");
    }

    @Test void requestedScansGoFirst() {
        var queue = new DiscoveryQueue();
        for (long chunk = 0; chunk < 10; chunk++) queue.offer(BUSY, chunk, false);
        queue.offer(BUSY, 99, true);
        assertEquals(99L, queue.poll().orElseThrow().chunk());
        queue.offer(BUSY, 5, true);
        assertEquals(5L, queue.poll().orElseThrow().chunk(), "an already queued chunk asked for again did not move to the front");
    }

    @Test void delayedRescansCoalesceUntilDue() {
        var queue = new DiscoveryQueue();
        assertTrue(queue.delay(BUSY, 3, 100));
        assertFalse(queue.delay(BUSY, 3, 140), "a second change in the same chunk scheduled a second rescan");
        assertTrue(queue.waiting(BUSY, 3));
        assertEquals(0, queue.release(99));
        assertTrue(queue.poll().isEmpty(), "a rescan ran before it was due");
        assertEquals(1, queue.release(100));
        assertEquals(3L, queue.poll().orElseThrow().chunk());
        assertFalse(queue.waiting(BUSY, 3));
        List<Long> none = new ArrayList<>();
        for (long chunk = 0; chunk < DiscoveryQueue.DELAYED_LIMIT; chunk++) if (!queue.delay(QUIET, chunk, 0)) none.add(chunk);
        assertTrue(none.isEmpty());
        assertFalse(queue.delay(QUIET, DiscoveryQueue.DELAYED_LIMIT, 0), "the delayed rescans grew past their bound");
    }
}
