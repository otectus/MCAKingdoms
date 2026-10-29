package dev.otectus.mcakingdoms.compat.livingvillages;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bridge sentence ({@code LvText.tr/text/refuse} and the presentation helper) and every composed key family has an
 * entry in each bundled language, and the English entry is exactly the fallback the code carries.
 */
class LivingVillagesLangTest {
    private static final Pattern CALL = Pattern.compile("(?:LvText\\.(?:tr|text|refuse)|(?<![A-Za-z_.])t)\\(\\s*\"([a-z0-9_.]+)\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static JsonObject lang(String locale) throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mcakingdoms/lang/" + locale + ".json"))).getAsJsonObject();
    }

    @Test
    void everyBridgeSentenceIsTranslatedWithItsEnglishFallback() throws IOException {
        var english = new LinkedHashMap<String, String>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/dev/otectus/mcakingdoms/compat/livingvillages"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = CALL.matcher(Files.readString(file));
                while (matcher.find()) english.put(LvText.PREFIX + matcher.group(1), matcher.group(2).replace("\\\"", "\""));
            }
        }
        var tasks = CALL.matcher(Files.readString(Path.of("src/main/java/dev/otectus/mcakingdoms/interaction/LivingVillagesTasks.java")));
        while (tasks.find()) english.put(LvText.PREFIX + tasks.group(1), tasks.group(2).replace("\\\"", "\""));
        for (String family : List.of("capability.", "readiness.", "reason.", "phase.", "ai.", "realm.status."))
            assertTrue(lang("en_us").keySet().stream().anyMatch(k -> k.startsWith(LvText.PREFIX + family)), family);
        var problems = new TreeSet<String>();
        for (String locale : List.of("en_us", "es_es", "zh_cn")) {
            var table = lang(locale);
            english.forEach((key, fallback) -> {
                if (!table.has(key)) problems.add(locale + " lacks " + key);
                else if (locale.equals("en_us") && !table.get(key).getAsString().equals(fallback)) problems.add("English differs for " + key);
            });
        }
        assertTrue(english.size() > 150, "the scan found the bridge's sentences");
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void composedFamiliesAreCompleteInEveryLanguage() throws IOException {
        var keys = new ArrayList<String>();
        for (var c : LvCompat.Capability.values()) keys.add(LvText.PREFIX + "capability." + c.name().toLowerCase(Locale.ROOT));
        for (var r : LvCompat.Readiness.values()) keys.add(LvText.PREFIX + "readiness." + r.name().toLowerCase(Locale.ROOT));
        for (var p : LvState.Phase.values()) keys.add(LvText.PREFIX + "phase." + p.name().toLowerCase(Locale.ROOT));
        for (var s : LvState.RealmStatus.values()) keys.add(LvText.PREFIX + "realm.status." + s.name().toLowerCase(Locale.ROOT));
        for (String code : List.of("no_quarrel", "hostile", "winning", "losses", "outmatched", "opinion", "at_war", "friendship", "not_friendly", "gains", "refuses_cession"))
            keys.add(LvText.PREFIX + "ai." + code);
        var problems = new TreeSet<String>();
        for (String locale : List.of("en_us", "es_es", "zh_cn")) {
            var table = lang(locale);
            for (String key : keys) if (!table.has(key)) problems.add(locale + " lacks " + key);
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }
}
