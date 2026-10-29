package dev.otectus.mcakingdoms.interaction;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Service wording for players (H03): a bundled key, its English fallback and arguments, read in each player's language.
 * Services that answer with text (results, refusals, details kept in saved records) pass the {@link #text} form, the
 * component's JSON; {@link #component(String)} reads it back, and reads a bare bundled key or older plain English too,
 * so records written before 0.3.0 still display. A refusal is a {@code Keyed} exception: still an
 * {@link IllegalArgumentException} for every existing catch, with the English reading as its message for logs.
 */
public final class Keyed extends IllegalArgumentException {
    /** Longest encoded text a service result may carry; longer ones are refused when built, never cut mid-structure. */
    public static final int LIMIT = 4096;
    private final transient Component component;

    private Keyed(Component component) {
        super(component.getString());
        this.component = component;
    }

    /** A refusal worded by {@code key}, with {@code english} as the fallback format. */
    public static Keyed refuse(String key, String english, Object... args) {
        return new Keyed(Component.translatableWithFallback(key, english, args(args)));
    }

    /** A refusal from encoded text ({@link #text}, a bundled key or plain English). */
    public static Keyed of(String encoded) {
        return new Keyed(component(encoded));
    }

    /** Throws {@link #refuse} unless {@code condition} holds. */
    public static void require(boolean condition, String key, String english, Object... args) {
        if (!condition) throw refuse(key, english, args);
    }

    /**
     * The encoded form of a result or detail: a bundled key on its own when there are no arguments (compact, and what
     * older results already carried), otherwise the translatable component's JSON.
     */
    public static String text(String key, String english, Object... args) {
        if (args.length == 0 && PlayerWords.isKey(key)) return key;
        String json = Component.Serializer.toJson(Component.translatableWithFallback(key, english, args(args)));
        if (json.length() > LIMIT) throw new IllegalStateException("Encoded result exceeds " + LIMIT + " characters: " + key);
        return json;
    }

    /** The encoded form of a component already built from bundled keys (a War Room line, for one). */
    public static String encode(Component component) {
        String json = Component.Serializer.toJson(component);
        if (json.length() > LIMIT) throw new IllegalStateException("Encoded result exceeds " + LIMIT + " characters");
        return json;
    }

    /** What a refusal tells a player: its component when keyed, otherwise its message read as encoded text. */
    public static Component component(Throwable failure) {
        if (failure instanceof Keyed keyed) return keyed.component;
        return component(failure.getMessage());
    }

    /** Encoded text for a failure, so a result keeps the key and arguments of a {@code Keyed} refusal. */
    public static String text(Throwable failure) {
        if (failure instanceof Keyed keyed) {
            if (keyed.component.getSiblings().isEmpty()) {
                if (keyed.component.getContents() instanceof TranslatableContents translatable && translatable.getArgs().length == 0
                        && PlayerWords.isKey(translatable.getKey())) return translatable.getKey();
                // Plain text (an older English message, a store diagnostic) stays plain.
                if (keyed.component.getContents() instanceof net.minecraft.network.chat.contents.LiteralContents literal) return literal.text();
            }
            return encode(keyed.component);
        }
        return failure.getMessage() == null ? "" : failure.getMessage();
    }

    /**
     * Reads encoded text: component JSON, a bundled key, or older plain English kept literal. JSON is accepted only as one
     * of this mod's own bundled messages, and every click, hover and insertion style is removed from it, so text a player
     * wrote can never become a command link or impersonate anything but this mod's wording.
     */
    public static Component component(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Component.empty();
        if (PlayerWords.encoded(encoded)) {
            try {
                Component parsed = Component.Serializer.fromJson(encoded);
                if (parsed != null && parsed.getContents() instanceof TranslatableContents translatable && PlayerWords.isKey(translatable.getKey()))
                    return inert(parsed);
            } catch (RuntimeException notJson) {
                // Plain text that happens to look like JSON stays literal.
            }
        }
        return PlayerWords.literalOrKey(encoded);
    }

    /** A copy with no click, hover or insertion behaviour anywhere in its tree, arguments included. */
    static Component inert(Component component) {
        net.minecraft.network.chat.MutableComponent copy;
        if (component.getContents() instanceof TranslatableContents translatable) {
            Object[] args = translatable.getArgs().clone();
            for (int i = 0; i < args.length; i++) if (args[i] instanceof Component nested) args[i] = inert(nested);
            copy = Component.translatableWithFallback(translatable.getKey(), translatable.getFallback(), args);
        } else copy = net.minecraft.network.chat.MutableComponent.create(component.getContents());
        copy.setStyle(component.getStyle().withClickEvent(null).withHoverEvent(null).withInsertion(null));
        for (Component sibling : component.getSiblings()) copy.append(inert(sibling));
        return copy;
    }

    /** The English reading of encoded text, for logs, tests and string-only consumers. */
    public static String english(String encoded) {
        return component(encoded).getString();
    }

    /** The bundled key of encoded text, or empty for plain text; lets callers branch on meaning, never on wording. */
    public static String key(String encoded) {
        ComponentContents contents = component(encoded).getContents();
        return contents instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    public Component component() {
        return component;
    }

    /** This refusal with {@code cause} attached for the log, unchanged for players. */
    public Keyed because(Throwable cause) {
        initCause(cause);
        return this;
    }

    /** Arguments as components keep their keys; null becomes empty text instead of the word "null". */
    private static Object[] args(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = args[i] == null ? "" : args[i] instanceof Enum<?> value ? ActionRegistry.enumLabel(value) : args[i];
        return out;
    }
}
