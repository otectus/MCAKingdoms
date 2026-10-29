package dev.otectus.mcakingdoms.compat.livingvillages;

import java.util.UUID;

/**
 * Operator recovery of one record after a fresh check of Living Villages' state: an interrupted operation is acknowledged if
 * the provider applied it and closed if it did not; a war record is resumed if the provider still fights it and closed if not;
 * {@code provider} accepts restored provider data as the new baseline. Nothing is replayed, paid or spawned again.
 */
public final class LvRecovery {
    private LvRecovery() {
    }

    public static String recover(LvRuntime rt, String target, UUID operator) {
        return LvCommands.recover(rt, target, operator);
    }
}
