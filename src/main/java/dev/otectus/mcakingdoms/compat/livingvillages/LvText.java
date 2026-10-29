package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.network.chat.Component;

/**
 * Bridge wording: every player-facing sentence is a bundled key under {@value #PREFIX} with its English fallback, read in
 * each player's language. {@code LivingVillagesLangTest} checks that each key used here exists in every bundled language.
 */
public final class LvText {
    public static final String PREFIX = "warfare.mcakingdoms.villages.";

    private LvText() {
    }

    public static Keyed refuse(String key, String english, Object... args) {
        return Keyed.refuse(PREFIX + key, english, args);
    }

    public static String text(String key, String english, Object... args) {
        return Keyed.text(PREFIX + key, english, args);
    }

    public static Component tr(String key, String english, Object... args) {
        return Component.translatableWithFallback(PREFIX + key, english, args);
    }
}
