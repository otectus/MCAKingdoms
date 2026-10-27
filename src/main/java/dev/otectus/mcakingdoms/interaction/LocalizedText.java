package dev.otectus.mcakingdoms.interaction;

import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a component in each language this mod bundles, so the server can match a search typed in any of them against
 * record choices whose labels are translation keys. A dedicated server has only English for other mods' and vanilla
 * keys, so those parts read in English; a resource pack's translations exist only on its client and are not searched.
 */
public final class LocalizedText {
    /** The languages whose translations ship in this jar. */
    public static final List<String> BUNDLED = List.of("en_us", "es_es", "zh_cn");
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
    private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

    private LocalizedText() {
    }

    private static synchronized Map<String, String> table(String locale) {
        return TABLES.computeIfAbsent(locale, language -> {
            Map<String, String> words = new HashMap<>();
            try (var stream = LocalizedText.class.getResourceAsStream("/assets/mcakingdoms/lang/" + language + ".json")) {
                if (stream != null) JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()
                        .entrySet().forEach(e -> words.put(e.getKey(), e.getValue().getAsString()));
            } catch (Exception ignored) {
                // A missing or damaged table only narrows search to the other languages.
            }
            return Map.copyOf(words);
        });
    }

    /** Whether {@code key} has a bundled translation in {@code locale}. */
    public static boolean has(String locale, String key) {
        return table(locale).containsKey(key);
    }

    /** The bundled text for {@code key} in {@code locale}, if any. */
    public static Optional<String> get(String locale, String key) {
        return Optional.ofNullable(table(locale).get(key));
    }

    /** Every distinct reading of the component: the server language's, then each bundled language's. */
    public static List<String> readings(Component component) {
        LinkedHashSet<String> readings = new LinkedHashSet<>();
        readings.add(component.getString());
        for (String locale : BUNDLED) readings.add(render(component, locale));
        return List.copyOf(readings);
    }

    /** The component as read in {@code locale}; keys without a bundled translation use the server language or fallback. */
    public static String render(Component component, String locale) {
        StringBuilder out = new StringBuilder();
        append(component, locale, out, 0);
        return out.toString();
    }

    private static void append(Component component, String locale, StringBuilder out, int depth) {
        if (depth > 16) return; // translation arguments cannot nest without bound
        var contents = component.getContents();
        if (contents instanceof LiteralContents literal) {
            out.append(literal.text());
        } else if (contents instanceof TranslatableContents translatable) {
            String pattern = table(locale).get(translatable.getKey());
            if (pattern == null) pattern = Language.getInstance().has(translatable.getKey())
                    ? Language.getInstance().getOrDefault(translatable.getKey())
                    : translatable.getFallback() != null ? translatable.getFallback() : translatable.getKey();
            format(pattern, translatable.getArgs(), locale, out, depth);
        } else if (contents != net.minecraft.network.chat.ComponentContents.EMPTY) {
            out.append(MutableComponent.create(contents).getString());
        }
        for (Component sibling : component.getSiblings()) append(sibling, locale, out, depth + 1);
    }

    private static void format(String pattern, Object[] args, String locale, StringBuilder out, int depth) {
        Matcher matcher = FORMAT.matcher(pattern);
        int position = 0, next = 0;
        while (matcher.find(position)) {
            out.append(pattern, position, matcher.start());
            position = matcher.end();
            String type = matcher.group(2);
            if ("%".equals(type)) { out.append('%'); continue; }
            if (!"s".equals(type)) { out.append(matcher.group()); continue; }
            int index = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) - 1 : next++;
            if (index >= 0 && index < args.length) {
                Object arg = args[index];
                if (arg instanceof Component nested) append(nested, locale, out, depth + 1);
                else out.append(arg);
            }
        }
        out.append(pattern.substring(position));
    }
}
