package dev.otectus.mcakingdoms.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F12: near capacity is 80% of a bound, full is the bound itself. */
class CapacityTest {
    private static Capacity of(long used, long limit) {
        return new Capacity("store", "records", used, limit, Capacity.Relief.OFFLINE_PROCEDURE);
    }

    @Test
    void levelsFollowTheDocumentedThresholds() {
        assertEquals(Capacity.Level.NORMAL, of(818, 1024).level());
        assertEquals(Capacity.Level.NEAR, of(820, 1024).level());
        assertEquals(Capacity.Level.NEAR, of(1023, 1024).level());
        assertEquals(Capacity.Level.FULL, of(1024, 1024).level());
        assertEquals(Capacity.Level.NORMAL, of(0, 0).level());
        assertEquals(100, of(2048, 1024).percent());
        assertEquals(80, of(820, 1024).percent());
    }
}
