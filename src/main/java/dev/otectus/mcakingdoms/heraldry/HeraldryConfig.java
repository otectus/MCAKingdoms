package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.config.McaKingdomsConfig;

/** Whether physical heraldry is on ({@code heraldry.enabled}); before the config loads (tests, early start) it is on. */
public final class HeraldryConfig {
    private HeraldryConfig() {
    }

    private static volatile Boolean override;

    public static boolean enabled() {
        var forced = override;
        if (forced != null) return forced;
        return !McaKingdomsConfig.SPEC.isLoaded() || McaKingdomsConfig.PHYSICAL_HERALDRY.get();
    }

    /** Test seam: the switch for GameTests without rewriting the config file; null restores the setting. */
    public static void overrideForTests(Boolean enabled) { override = enabled; }
}
