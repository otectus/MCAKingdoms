package dev.otectus.mcakingdoms.core;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * The loaded chunks waiting to be inspected for settlements (AUD-11). Bounded and fair:
 * <ul>
 *   <li>each dimension has its own queue and {@link #poll} takes from them in turn, so a busy dimension cannot starve
 *   another;</li>
 *   <li>a chunk is queued once at a time; past {@link #LIMIT} chunks an offer is postponed (counted, not kept): chunk
 *   loads, block changes and the recovery sweep offer loaded chunks again later, so coverage is late, never lost;</li>
 *   <li>a requested scan (a player asking, or a village point of interest that changed) goes to the front of its
 *   dimension's queue;</li>
 *   <li>a changed point of interest is rescanned after a short delay, so several changes coalesce into one scan and
 *   the point-of-interest records have caught up with the blocks.</li>
 * </ul>
 * Only the server thread polls; chunk events may offer from the chunk system, so every method is synchronized.
 */
final class DiscoveryQueue {
    /** The most chunks queued at once, across dimensions. */
    static final int LIMIT = 16_384;
    /** The most chunks waiting for a delayed rescan at once. */
    static final int DELAYED_LIMIT = 4_096;

    record Entry(ResourceKey<Level> dimension, long chunk) { }

    private final Map<ResourceKey<Level>, ArrayDeque<Long>> queues = new LinkedHashMap<>();
    private final Set<Entry> queued = new HashSet<>();
    private final Map<Entry, Long> delayed = new LinkedHashMap<>();
    private final List<ResourceKey<Level>> order = new ArrayList<>();
    private int cursor;
    private long postponed;

    /** Queues a chunk; false when it was already queued or the queue is full (then the offer is postponed). */
    synchronized boolean offer(ResourceKey<Level> dimension, long chunk, boolean front) {
        Entry entry = new Entry(dimension, chunk);
        if (queued.contains(entry)) {
            if (front) { var queue = queues.get(dimension); if (queue != null && queue.remove(chunk)) queue.addFirst(chunk); }
            return false;
        }
        if (queued.size() >= LIMIT) { postponed++; return false; }
        var queue = queues.computeIfAbsent(dimension, key -> { order.add(key); return new ArrayDeque<>(); });
        if (front) queue.addFirst(chunk); else queue.addLast(chunk);
        queued.add(entry);
        return true;
    }

    /** Asks for a rescan of {@code chunk} at game time {@code due}; a chunk already waiting keeps its earlier time. */
    synchronized boolean delay(ResourceKey<Level> dimension, long chunk, long due) {
        Entry entry = new Entry(dimension, chunk);
        if (delayed.containsKey(entry)) return false;
        if (delayed.size() >= DELAYED_LIMIT) { postponed++; return false; }
        delayed.put(entry, due);
        return true;
    }

    /** Moves every delayed rescan that is due to the front of its queue. */
    synchronized int release(long now) {
        int moved = 0;
        for (var it = delayed.entrySet().iterator(); it.hasNext(); ) {
            var next = it.next();
            if (next.getValue() > now) continue;
            it.remove();
            offer(next.getKey().dimension(), next.getKey().chunk(), true);
            moved++;
        }
        return moved;
    }

    /** The next chunk, taking dimensions in turn; empty when nothing waits. */
    synchronized Optional<Entry> poll() {
        for (int tries = 0; tries < order.size(); tries++) {
            if (cursor >= order.size()) cursor = 0;
            ResourceKey<Level> dimension = order.get(cursor++);
            Long chunk = queues.get(dimension).pollFirst();
            if (chunk == null) continue;
            Entry entry = new Entry(dimension, chunk);
            queued.remove(entry);
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** Whether the chunk waits, queued or delayed. */
    synchronized boolean waiting(ResourceKey<Level> dimension, long chunk) {
        Entry entry = new Entry(dimension, chunk);
        return queued.contains(entry) || delayed.containsKey(entry);
    }

    synchronized int size() { return queued.size(); }
    synchronized int delayedSize() { return delayed.size(); }
    /** Offers turned away while the queue was full, since the server started. */
    synchronized long postponed() { return postponed; }
}
