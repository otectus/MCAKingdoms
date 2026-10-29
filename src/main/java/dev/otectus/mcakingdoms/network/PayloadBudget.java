package dev.otectus.mcakingdoms.network;

import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;

/**
 * Hard size limits for replies sent to clients (H08). Sizes are measured in UTF-8 bytes after serialization, because
 * one character of Chinese text is three bytes; text is cut only between Unicode code points, and a cut is marked.
 */
public final class PayloadBudget {
    /** The ceiling of one serialized reply, in bytes. It is also within the 60,000-character limit of the codecs. */
    public static final int BYTES = 60_000;

    private PayloadBudget() {
    }

    public static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static boolean fits(String text) {
        return text.length() <= BYTES && utf8(text) <= BYTES;
    }

    /** {@code text} cut to at most {@code maxChars} UTF-16 units on a code-point boundary, ending in "…" when cut. */
    public static String clip(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int end = Math.max(0, maxChars - 1);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    /** A component whose reading is longer than {@code maxChars} becomes its clipped plain reading. */
    public static Component clip(Component text, int maxChars) {
        String plain = text.getString();
        return plain.length() <= maxChars ? text : Component.literal(clip(plain, maxChars));
    }
}
