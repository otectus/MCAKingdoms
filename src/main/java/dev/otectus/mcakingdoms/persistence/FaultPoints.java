package dev.otectus.mcakingdoms.persistence;

import com.mojang.logging.LogUtils;

import java.util.Set;

/**
 * Named points between durable steps where a failure can be injected, so recovery is tested at the boundary it claims
 * to cover rather than around it. A GameTest makes a point throw ({@link #failForTests}); a packaged crash test stops the
 * JVM at a point without shutdown hooks or saves, exactly like a power cut, by starting the server with
 * {@code -Dmcakingdoms.fault.halt=<point>}. Neither is set in normal play, where {@link #reach} costs one volatile read
 * and one property lookup.
 */
public final class FaultPoints {
    /** The exit status of a deliberate halt, so a test harness can tell it from a crash. */
    public static final int HALT_STATUS = 86;
    private static volatile Set<String> failing = Set.of();

    private FaultPoints() {
    }

    /** Thrown at an injected point. */
    public static final class Injected extends IllegalStateException {
        public Injected(String point) { super("Injected failure at " + point); }
    }

    /** Test seam: the named points throw {@link Injected} until called again with none. */
    public static void failForTests(String... points) {
        failing = Set.of(points);
    }

    /** Passes a named point: throws when a test injected it, halts the JVM when a crash test named it. */
    public static void reach(String point) {
        if (failing.contains(point)) throw new Injected(point);
        crash(point);
    }

    /**
     * A point after a change is already on disk: only a crash test can stop here (by halting). Nothing is thrown, since
     * an exception after a commit would turn a completed change into an ambiguous one.
     */
    public static void crash(String point) {
        if (point.equals(System.getProperty("mcakingdoms.fault.halt"))) {
            LogUtils.getLogger().error("[MCA: Kingdoms] Crash test: halting at {}", point);
            Runtime.getRuntime().halt(HALT_STATUS);
        }
    }
}
