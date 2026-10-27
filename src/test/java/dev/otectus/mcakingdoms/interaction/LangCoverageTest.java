package dev.otectus.mcakingdoms.interaction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcakingdoms.api.progression.ProgressionResult;
import dev.otectus.mcakingdoms.warfare.contracts.CivilianContractKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every reason, result and label key the code names reaches the player as words, never as a raw key.
 *
 * <p>A dotted literal under one of the prefixes below must be an English entry, a task id (its title is
 * {@code task.mcakingdoms.<id>.title}), an interaction text suffix ({@code interaction.mcakingdoms.<suffix>}),
 * or a prefix the code completes; the completed families are checked one by one. This is the check that
 * found 29 organization reasons, the progression reasons and the Quests condition description untranslated.
 */
class LangCoverageTest {
    private static final Pattern LITERAL = Pattern.compile(
            "\"((?:organization|progression|condition|civic|warfare|politics|evolution|interaction|task|menu|screen|gui"
                    + "|command|notice|message|text|kingdom)\\.[a-z0-9_./]+)\"");
    /** Keys Minecraft itself translates. */
    private static final Set<String> VANILLA = Set.of("gui.back", "gui.yes", "gui.no");
    /** Prefixes that local helpers put before a short key, such as {@code e("organization.line", ...)} in the evolution tasks. */
    private static final List<String> HELPER_PREFIXES = List.of("interaction.mcakingdoms.", "interaction.mcakingdoms.evolution.",
            "interaction.mcakingdoms.warfare.", "interaction.mcakingdoms.settlement.", "facts.mcakingdoms.", "politics.mcakingdoms.row.",
            "screen.mcakingdoms.ledger.", "interaction.mcakingdoms.outcome.");

    private static JsonObject lang() throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mcakingdoms/lang/en_us.json")))
                .getAsJsonObject();
    }

    @Test
    void everyNamedKeyHasEnglishText() throws IOException {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = LITERAL.matcher(Files.readString(file));
                while (matcher.find()) {
                    String literal = matcher.group(1);
                    if (lang.has(literal) || VANILLA.contains(literal) || literal.endsWith(".") || literal.endsWith("_")
                            || lang.has("task.mcakingdoms." + literal + ".title")
                            || HELPER_PREFIXES.stream().anyMatch(prefix -> lang.has(prefix + literal))) continue;
                    missing.add(literal + " (" + file.getFileName() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "untranslated keys: " + missing);
    }

    @Test
    void composedKeyFamiliesAreComplete() throws IOException {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        for (ProgressionResult.Status status : ProgressionResult.Status.values()) {
            if (status == ProgressionResult.Status.AVAILABLE) continue; // never a refusal
            String key = "progression.provider_" + status.name().toLowerCase(Locale.ROOT);
            if (!lang.has(key)) missing.add(key);
        }
        for (CivilianContractKind kind : CivilianContractKind.values()) {
            String key = "warfare.contract_kind." + kind.id();
            if (!lang.has(key)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "untranslated keys: " + missing);
    }
}
