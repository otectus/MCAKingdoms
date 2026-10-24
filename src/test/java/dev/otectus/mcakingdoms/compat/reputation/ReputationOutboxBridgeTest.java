package dev.otectus.mcakingdoms.compat.reputation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReputationOutboxBridgeTest {
    @Test
    void projectionRoundsEveryNonZeroMagnitudeAwayFromZero() {
        assertEquals(1, ReputationOutboxBridge.project(1, 0.5D));
        assertEquals(-1, ReputationOutboxBridge.project(-1, 0.5D));
        assertEquals(2, ReputationOutboxBridge.project(3, 0.5D));
        assertEquals(-2, ReputationOutboxBridge.project(-3, 0.5D));
        assertEquals(0, ReputationOutboxBridge.project(20, 0D));
    }

    @Test
    void enteringShadowSeedsFromCurrentDurableCursor() {
        assertEquals(44L, ReputationOutboxBridge.shadowCursor(
                dev.otectus.mcakingdoms.factions.config.FactionConfig.SyncMode.MCA_TO_FACTION,
                dev.otectus.mcakingdoms.factions.config.FactionConfig.SyncMode.SHADOW, 44L, 7L));
        assertEquals(7L, ReputationOutboxBridge.shadowCursor(
                dev.otectus.mcakingdoms.factions.config.FactionConfig.SyncMode.SHADOW,
                dev.otectus.mcakingdoms.factions.config.FactionConfig.SyncMode.SHADOW, 44L, 7L));
    }
}
