package dev.otectus.mcakingdoms.development;

import java.util.concurrent.atomic.AtomicLong;

/** Development's own counters (D6), for the health page and {@code /kingdoms economy diagnose}: pass times, the construction queue and blocks placed this session. */
public final class DevelopmentMetrics {
    private static final AtomicLong PASSES = new AtomicLong(), LAST_NANOS = new AtomicLong(), MAX_NANOS = new AtomicLong(), QUEUE = new AtomicLong(), PLACED = new AtomicLong();

    private DevelopmentMetrics() {
    }

    static void pass(long nanos, long queue) {
        PASSES.incrementAndGet(); LAST_NANOS.set(nanos); MAX_NANOS.accumulateAndGet(nanos, Math::max); QUEUE.set(queue);
    }

    static void placed() { PLACED.incrementAndGet(); }

    public static void reset() { PASSES.set(0); LAST_NANOS.set(0); MAX_NANOS.set(0); QUEUE.set(0); PLACED.set(0); }

    public record Snapshot(long passes, long lastMicros, long maxMicros, long queue, long placed) { }

    public static Snapshot snapshot() {
        return new Snapshot(PASSES.get(), LAST_NANOS.get() / 1000, MAX_NANOS.get() / 1000, QUEUE.get(), PLACED.get());
    }
}
