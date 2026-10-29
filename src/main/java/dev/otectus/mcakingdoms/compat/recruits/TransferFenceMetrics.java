package dev.otectus.mcakingdoms.compat.recruits;

import java.util.Arrays;

/**
 * Cost of the Recruits transfer save fence (H07): how long each fence held the server thread, how many entities the
 * recruit's chunk saved and how many writes the entity I/O worker still had queued when the fence began. Kept for this
 * server session only and shown in Server health; the last {@value #WINDOW} fences give the percentiles.
 */
public final class TransferFenceMetrics {
    static final int WINDOW = 128;
    private static final long[] DURATIONS = new long[WINDOW];
    private static long count;
    private static long maxNanos;
    private static int lastEntities;
    private static int lastQueued;

    /** One completed fence. */
    public record Snapshot(long count, long lastNanos, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos, int lastEntities, int lastQueued) { }

    private TransferFenceMetrics() {
    }

    static synchronized void record(long nanos, int entities, int queued) {
        DURATIONS[(int) (count % WINDOW)] = nanos;
        count++;
        maxNanos = Math.max(maxNanos, nanos);
        lastEntities = entities;
        lastQueued = queued;
    }

    public static synchronized Snapshot snapshot() {
        int size = (int) Math.min(count, WINDOW);
        long[] window = Arrays.copyOf(DURATIONS, size);
        Arrays.sort(window);
        long last = count == 0 ? 0 : DURATIONS[(int) ((count - 1) % WINDOW)];
        return new Snapshot(count, last, percentile(window, 50), percentile(window, 95), percentile(window, 99), maxNanos, lastEntities, lastQueued);
    }

    /** {@code percent} of a sorted array, by the nearest-rank method; 0 for an empty array. */
    public static long percentileOf(long[] sorted, int percent) {
        return percentile(sorted, percent);
    }

    static long percentile(long[] sorted, int percent) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** A new server session starts with no measurements. */
    public static synchronized void reset() {
        Arrays.fill(DURATIONS, 0);
        count = 0;
        maxNanos = 0;
        lastEntities = 0;
        lastQueued = 0;
    }
}
