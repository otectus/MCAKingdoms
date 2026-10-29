package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.config.McaKingdomsConfig;

/** Whether physical heraldry is on ({@code heraldry.enabled}); before the config loads (tests, early start) it is on. */
public final class HeraldryConfig {
    private HeraldryConfig() {
    }

    public static boolean enabled() {
        return !McaKingdomsConfig.SPEC.isLoaded() || McaKingdomsConfig.PHYSICAL_HERALDRY.get();
    }
}
