package dev.otectus.mcakingdoms.compat.townstead;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The bundled and example Townstead mappings load through the real parser; an invalid file disables only itself. */
class TownsteadMappingsTest {
    /** Reads {@code <root>/data/<ns>/mcakingdoms_townstead/<kind>/**.json} into the parser's input shape. */
    static Map<String, Map<ResourceLocation, JsonElement>> files(Path... roots) throws IOException {
        Map<String, Map<ResourceLocation, JsonElement>> out = new LinkedHashMap<>();
        for (String kind : TownsteadMappings.KINDS) out.put(kind, new LinkedHashMap<>());
        for (Path root : roots) {
            Path data = root.resolve("data");
            if (!Files.isDirectory(data)) continue;
            try (Stream<Path> namespaces = Files.list(data)) {
                for (Path namespace : namespaces.toList()) {
                    for (String kind : TownsteadMappings.KINDS) {
                        Path folder = namespace.resolve(TownsteadMappings.ROOT).resolve(kind);
                        if (!Files.isDirectory(folder)) continue;
                        try (Stream<Path> found = Files.walk(folder)) {
                            for (Path file : found.filter(p -> p.toString().endsWith(".json")).toList()) {
                                String path = folder.relativize(file).toString().replace('\\', '/');
                                out.get(kind).put(new ResourceLocation(namespace.getFileName().toString(), path.substring(0, path.length() - 5)),
                                        JsonParser.parseString(Files.readString(file)));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static final Path BUNDLED = Path.of("src/main/resources");
    private static final Path EXAMPLE = Path.of("examples/datapack/mcakingdoms-example");

    @Test
    void theBundledMappingsAreAllValid() throws IOException {
        var snapshot = TownsteadMappings.parse(files(BUNDLED));
        assertEquals(List.of(), snapshot.problems());
        assertEquals(3, snapshot.institutions().size());
        assertTrue(snapshot.institutions().get("mcakingdoms:learning").families().contains("archives"));
        assertEquals(6, snapshot.livery().size(), "one design for each built-in kingdom");
        assertEquals(TownsteadMappings.TRIGGERS, snapshot.triggers());
        assertEquals(3, snapshot.reactions().size());
        assertEquals(6, snapshot.suggestions().size());
        assertTrue(snapshot.careers().isEmpty(), "no bundled guild gains a career requirement on upgrade");
        assertTrue(snapshot.cultureHints().isEmpty(), "no bundled settlement assignment changes on upgrade");
    }

    @Test
    void theExamplePackAddsACareerRuleAndACultureHint() throws IOException {
        var snapshot = TownsteadMappings.parse(files(BUNDLED, EXAMPLE));
        assertEquals(List.of(), snapshot.problems());
        assertEquals(1, snapshot.careers().size());
        var career = snapshot.careers().get(0);
        assertEquals(new ResourceLocation("example:harbor_archivists"), career.organization());
        assertEquals(TownsteadMappings.RequirementKind.ANY_PROFESSION, career.requires().kind());
        assertEquals(2, career.requires().minimumTier());
        assertEquals(1, snapshot.cultureHints().size());
    }

    /** A suggestion's next step opens a task a player can use; a missing id would leave the page without one. */
    @Test
    void everyBundledSuggestionPointsAtAPlayerTask() throws IOException {
        dev.otectus.mcakingdoms.interaction.InteractionTasks.init();
        var snapshot = TownsteadMappings.parse(files(BUNDLED));
        for (var suggestion : snapshot.suggestions()) {
            suggestion.task().ifPresent(id -> assertTrue(dev.otectus.mcakingdoms.interaction.ActionRegistry.find(id).map(task -> !task.operator()).orElse(false),
                    suggestion.id() + " names the task " + id));
        }
    }

    /** Townstead 0.8.0 authors no cultures itself, so the example hint names the culture the example pack adds. */
    @Test
    void theExampleCultureHintNamesTheCultureTheExamplePackDefines() throws IOException {
        var hint = TownsteadMappings.parse(files(BUNDLED, EXAMPLE)).cultureHints().values().iterator().next();
        ResourceLocation culture = hint.culture().orElseThrow();
        Path file = EXAMPLE.resolve("data/" + culture.getNamespace() + "/culture/" + culture.getPath() + ".json");
        assertTrue(Files.exists(file), "Townstead reads cultures from data/<namespace>/culture/: " + file);
        assertEquals("townstead:culture/v1", JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("schema").getAsString());
    }

    @Test
    void aSkillRequirementTakesNoTier() {
        Map<String, Map<ResourceLocation, JsonElement>> input = new LinkedHashMap<>();
        for (String kind : TownsteadMappings.KINDS) input.put(kind, new LinkedHashMap<>());
        input.get("careers").put(new ResourceLocation("test:skill_tier"),
                JsonParser.parseString("{\"organization\":\"test:guild\",\"requires\":{\"skill\":\"townstead:copying\",\"minimum_tier\":2}}"));
        var snapshot = TownsteadMappings.parse(input);
        assertTrue(snapshot.careers().isEmpty());
        assertEquals(1, snapshot.problems().size());
        assertTrue(snapshot.problems().get(0).contains("minimum_tier applies to professions"), snapshot.problems().toString());
    }

    @Test
    void anInvalidMappingIsSkippedWithItsDiagnosticAndTheRestStillApply() throws IOException {
        Path variant = Path.of("examples/datapack/invalid/townstead_mapping");
        List<String> expected = Files.readAllLines(variant.resolve("expected.txt")).stream().filter(l -> !l.isBlank()).toList();
        assertEquals("townstead", expected.get(0));
        var input = files(BUNDLED, EXAMPLE);
        files(variant).forEach((kind, entries) -> input.get(kind).putAll(entries));
        var snapshot = TownsteadMappings.parse(input);
        assertEquals(1, snapshot.problems().size(), snapshot.problems().toString());
        for (String text : expected.subList(1, expected.size())) assertTrue(snapshot.problems().get(0).contains(text), snapshot.problems().get(0));
        assertTrue(snapshot.careers().isEmpty(), "the broken career rule is not applied");
        assertEquals(1, snapshot.cultureHints().size(), "the other example mapping still applies");
        assertEquals(6, snapshot.livery().size());
    }

    @Test
    void unrecognizedWordsAndBrokenHysteresisAreRefused() {
        Map<String, Map<ResourceLocation, JsonElement>> input = new LinkedHashMap<>();
        for (String kind : TownsteadMappings.KINDS) input.put(kind, new LinkedHashMap<>());
        input.get("suggestions").put(new ResourceLocation("test:word"), JsonParser.parseString("{\"signal\":\"famine\",\"enter\":0.2,\"message\":\"a.b\"}"));
        input.get("suggestions").put(new ResourceLocation("test:hysteresis"), JsonParser.parseString("{\"signal\":\"hunger\",\"enter\":0.2,\"exit\":0.4,\"message\":\"a.b\"}"));
        input.get("chronicle").put(new ResourceLocation("test:trigger"), JsonParser.parseString("{\"trigger\":\"mcakingdoms:coronation\"}"));
        input.get("livery").put(new ResourceLocation("test:dye"), JsonParser.parseString("{\"kingdom\":\"mcakingdoms:yew\",\"base\":\"chartreuse\",\"patterns\":[]}"));
        input.get("institutions").put(new ResourceLocation("test:a"), JsonParser.parseString("{\"charter\":\"mcakingdoms:learning\",\"families\":[\"library\"]}"));
        input.get("institutions").put(new ResourceLocation("test:b"), JsonParser.parseString("{\"charter\":\"mcakingdoms:learning\",\"families\":[\"archives\"]}"));
        var snapshot = TownsteadMappings.parse(input);
        assertEquals(5, snapshot.problems().size(), snapshot.problems().toString());
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("famine")));
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("hysteresis")));
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("unknown trigger")));
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("chartreuse")));
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("already has a rule")));
        assertEquals(1, snapshot.institutions().size(), "the first rule for the charter applies");
    }
    /**
     * AUD-13: a reload's mappings are staged and become current only at the shared commit boundary. A reload a later step
     * rejects never commits, so the mappings in force stay the previous coherent generation; a commit switches them in one step.
     */
    @Test
    void mappingsBecomeCurrentOnlyWhenTheReloadCommits() throws IOException {
        var bundled = TownsteadMappings.parse(files(Path.of("src/main/resources")));
        TownsteadMappings.installForTests(bundled);
        try {
            var empty = new java.util.LinkedHashMap<String, Map<ResourceLocation, JsonElement>>();
            for (String kind : TownsteadMappings.KINDS) empty.put(kind, Map.of());
            TownsteadMappings.INSTANCE.apply(empty, null, null);
            assertTrue(TownsteadMappings.staged(), "a reload's mappings were not staged");
            assertEquals(bundled.careers(), TownsteadMappings.snapshot().careers(), "staged mappings became current before the reload committed");
            // A later reload step fails: nothing commits, and the next reload replaces what was staged.
            TownsteadMappings.INSTANCE.clearPending();
            assertFalse(TownsteadMappings.staged());
            assertEquals(bundled.careers(), TownsteadMappings.snapshot().careers(), "a rejected reload changed the mappings in force");
            TownsteadMappings.INSTANCE.apply(empty, null, null);
            TownsteadMappings.INSTANCE.commitPending(null);
            assertTrue(TownsteadMappings.snapshot().careers().isEmpty(), "a committed reload did not become current");
            assertFalse(TownsteadMappings.staged());
            TownsteadMappings.INSTANCE.commitPending(null);
            assertTrue(TownsteadMappings.snapshot().careers().isEmpty(), "committing twice changed the mappings");
        } finally {
            TownsteadMappings.INSTANCE.clearPending();
            TownsteadMappings.installForTests(TownsteadMappings.parse(Map.of()));
        }
    }
}
