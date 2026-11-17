package dev.otectus.mcakingdoms.compat.townstead;

import net.minecraft.network.chat.Component;

/** Player and operator text for the Townstead integration, keyed under {@code townstead.mcakingdoms.} with an English fallback. */
public final class TownsteadText {
    public static final String PREFIX = "townstead.mcakingdoms.";

    private TownsteadText() {
    }

    public static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(PREFIX + key, fallback, args);
    }

    public static IllegalArgumentException refuse(String key, String fallback, Object... args) {
        return dev.otectus.mcakingdoms.interaction.Keyed.refuse(PREFIX + key, fallback, args);
    }
}
