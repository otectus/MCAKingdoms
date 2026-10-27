package dev.otectus.mcakingdoms.interaction;

import net.minecraft.network.chat.Component;

/**
 * Why something cannot be used, when the owning service or adapter can establish the cause. Each category has one
 * wording and one next step, so the same cause reads the same everywhere. Unknown causes are not forced into one.
 */
public enum Unavailable {
    DISABLED("Turned off on this server: %s. An operator controls this setting."),
    PROVIDER_MISSING("Needs %s, which is not installed on this server."),
    PROVIDER_UNSUPPORTED("%s is installed but does not offer what this needs; an operator can compare versions in Server health."),
    CONTENT_MISSING("The content this needs is missing or invalid: %s. An operator can check the data packs."),
    REQUIREMENT("A requirement is not met: %s."),
    STALE("This changed while you were deciding: %s. Open the task again to see the current state."),
    OFFLINE("A participant is offline or not loaded: %s. Try again when they are present."),
    PENDING("A result is still pending: %s. Check it before repeating the action."),
    READ_ONLY("Saved data is read-only, so changes are refused: %s. Existing records are kept unchanged.");

    private final String english;

    Unavailable(String english) {
        this.english = english;
    }

    /** The explanation for {@code subject} (text or a component), translated on the reader's client. */
    public Component explain(Object subject) {
        return Component.translatableWithFallback("unavailable.mcakingdoms." + name().toLowerCase(java.util.Locale.ROOT), english, subject);
    }
}
