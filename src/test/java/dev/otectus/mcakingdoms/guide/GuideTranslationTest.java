package dev.otectus.mcakingdoms.guide;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Spanish and Chinese Book cover every English entry by its stable id. A translated entry replaces the English one
 * whole, so it must keep the same chapter, task links, command syntax and article structure: the same blocks (ids and
 * types, so history and links survive a language change), the same links and recipes, and the same number of items in
 * each list.
 */
class GuideTranslationTest {
    private static final Path GUIDE = Path.of("src/main/resources/assets/mcakingdoms/guide");

    private static Map<String, KingdomGuide.Entry> entries(String locale) throws IOException {
        Map<String, KingdomGuide.Entry> entries = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(GUIDE.resolve(locale))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                try (var reader = Files.newBufferedReader(file)) {
                    for (KingdomGuide.Entry entry : KingdomGuide.read(reader)) entries.put(entry.id(), entry);
                }
            }
        }
        return entries;
    }

    /** What must not differ between languages: block identities and types, list sizes, links, recipes and items. */
    private static List<String> structure(KingdomGuide.Entry entry) {
        List<String> out = new ArrayList<>();
        out.add("schema " + entry.schema());
        for (KingdomGuide.Block block : entry.blocks()) {
            String shape = block.getClass().getSimpleName() + " " + block.id() + " items=" + block.text().size();
            if (block instanceof KingdomGuide.RecipeBlock recipe) shape += " recipe=" + recipe.recipe() + " output=" + recipe.output();
            if (block instanceof KingdomGuide.ItemBlock item) shape += " item=" + item.item() + " x" + item.count();
            if (block instanceof KingdomGuide.Callout callout) shape += " tone=" + callout.tone();
            if (block instanceof KingdomGuide.Diagram diagram) shape += " nodes=" + diagram.nodes().stream().map(n -> n.id() + n.item()).toList()
                    + " edges=" + diagram.edges().stream().map(e -> e.from() + ">" + e.to()).toList() + " vertical=" + diagram.vertical();
            if (block instanceof KingdomGuide.Comparison comparison) shape += " columns=" + comparison.columns().stream().map(c -> c.item() + "/" + c.points().size()).toList();
            if (block instanceof KingdomGuide.Requirements requirements) shape += " states=" + requirements.rows().stream().map(KingdomGuide.Requirement::state).toList();
            if (block instanceof KingdomGuide.Image image) shape += " texture=" + image.texture();
            out.add(shape);
            block.links().forEach(link -> out.add("  link " + link.kind() + " " + link.target() + (link.operator() ? " operator" : "")));
        }
        return out;
    }

    @Test
    void everyEnglishEntryIsTranslatedWithItsLinksIntact() throws IOException {
        var english = entries("en_us");
        Set<String> problems = new TreeSet<>();
        for (String locale : List.of("es_es", "zh_cn")) {
            var translated = entries(locale);
            assertEquals(english.keySet(), translated.keySet(), locale + " must translate exactly the English entry ids");
            english.forEach((id, source) -> {
                var target = translated.get(id);
                if (!source.category().equals(target.category())) problems.add(locale + " " + id + ": category changed");
                if (!source.task().equals(target.task()) || source.operatorTask() != target.operatorTask()
                        || !source.playerTask().equals(target.playerTask())) problems.add(locale + " " + id + ": task link changed");
                if (!source.commands().equals(target.commands())) problems.add(locale + " " + id + ": command syntax changed");
                if (!structure(source).equals(structure(target))) problems.add(locale + " " + id + ": article structure differs: " + structure(target) + " vs " + structure(source));
                if (target.title().isBlank() || target.summary().isBlank()) problems.add(locale + " " + id + ": blank title or summary");
                if (locale.equals("zh_cn") && target.title().equals(source.title())) problems.add(locale + " " + id + ": title left in English");
                if (source.keywords().isEmpty() != target.keywords().isEmpty()) problems.add(locale + " " + id + ": keywords missing in one language");
            });
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void noArticleHardCodesTheTasksKey() throws IOException {
        Set<String> problems = new TreeSet<>();
        for (String locale : List.of("en_us", "es_es", "zh_cn")) {
            entries(locale).forEach((id, entry) -> {
                List<String> text = new ArrayList<>(entry.body());
                text.add(entry.summary());
                for (String paragraph : text)
                    if (paragraph.matches("(?s).*(\\bPress K\\b|\\bPulsa K\\b|按K|\\bK menu\\b|\\bK tasks?\\b|K事务|K菜单).*")) problems.add(locale + " " + id);
            });
        }
        assertTrue(problems.isEmpty(), "Use {key:tasks}, which shows the player's own binding: " + problems);
    }
}
