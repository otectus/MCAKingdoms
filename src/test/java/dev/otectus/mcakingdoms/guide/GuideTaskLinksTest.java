package dev.otectus.mcakingdoms.guide;

import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.InteractionTasks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Book link names a registered task. A link to an operator-only task is marked as such, so ordinary players are
 * offered a related task they can open or an explanation instead of a dead end; a player alternative is never itself
 * operator-only. The server still checks access whenever a task opens.
 */
class GuideTaskLinksTest {
    @BeforeAll
    static void tasks() {
        InteractionTasks.init();
    }

    private static List<Path> guideFiles() throws IOException {
        try (var files = Files.walk(Path.of("src/main/resources/assets/mcakingdoms/guide"))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyLinkMatchesItsTaskAccess() throws IOException {
        var problems = new TreeSet<String>();
        int links = 0;
        for (Path file : guideFiles()) {
            List<KingdomGuide.Entry> entries;
            try (var reader = Files.newBufferedReader(file)) { entries = KingdomGuide.read(reader); }
            for (var entry : entries) {
                String where = file.getParent().getFileName() + "/" + entry.id();
                if (entry.task().isEmpty() || entry.task().equals("*")) {
                    if (entry.operatorTask()) problems.add(where + ": marked operator without an operator task");
                    continue;
                }
                links++;
                var task = ActionRegistry.find(entry.task());
                if (task.isEmpty()) { problems.add(where + ": unknown task " + entry.task()); continue; }
                if (task.get().operator() != entry.operatorTask())
                    problems.add(where + ": " + entry.task() + (task.get().operator() ? " is operator-only but not marked" : " is open to players but marked operator"));
                if (!entry.playerTask().isEmpty()) {
                    var alternative = ActionRegistry.find(entry.playerTask());
                    if (alternative.isEmpty()) problems.add(where + ": unknown player task " + entry.playerTask());
                    else if (alternative.get().operator()) problems.add(where + ": player alternative " + entry.playerTask() + " is operator-only");
                    if (!entry.operatorTask()) problems.add(where + ": player alternative without an operator task");
                }
            }
        }
        assertTrue(links > 40, "The Book should link most topics to tasks");
        assertTrue(problems.isEmpty(), "Book task links: " + problems);
    }

    /**
     * Links inside articles (steps and link blocks): a task link names a registered task and is marked operator-only
     * exactly when that task is, so ordinary readers never get a dead end; a guide link names a topic of the same
     * language's Book. A4 of the usability plan.
     */
    @Test
    void articleLinksResolveAndRespectAccess() throws IOException {
        var problems = new TreeSet<String>();
        int checked = 0;
        for (String locale : List.of("en_us", "es_es", "zh_cn")) {
            var topics = new java.util.HashMap<String, KingdomGuide.Entry>();
            for (Path file : guideFiles()) {
                if (!file.getParent().getFileName().toString().equals(locale)) continue;
                try (var reader = Files.newBufferedReader(file)) { KingdomGuide.read(reader).forEach(e -> topics.put(e.id(), e)); }
            }
            for (var entry : topics.values()) for (var link : entry.links()) {
                checked++;
                String where = locale + "/" + entry.id() + " -> " + link.target();
                if (link.kind() == KingdomGuide.Link.Kind.GUIDE) {
                    if (!topics.containsKey(link.target())) problems.add(where + ": no such topic");
                    continue;
                }
                if (link.target().equals("*")) continue;
                var task = ActionRegistry.find(link.target());
                if (task.isEmpty()) problems.add(where + ": unknown task");
                else if (task.get().operator() != link.operator())
                    problems.add(where + (task.get().operator() ? ": operator-only task not marked operator" : ": player task marked operator"));
            }
        }
        assertTrue(checked > 60, "Expected the visual topics to carry links: " + checked);
        assertTrue(problems.isEmpty(), "Article links: " + problems);
    }

    @Test
    void generalPoliticalReadingAndFoundingAreReachableByPlayers() throws IOException {
        var linked = new ArrayList<String>();
        for (Path file : guideFiles()) {
            if (!file.getParent().getFileName().toString().equals("en_us")) continue;
            try (var reader = Files.newBufferedReader(file)) {
                for (var entry : KingdomGuide.read(reader)) if (entry.category().equals("politics")) linked.add(entry.id() + "=" + entry.task());
            }
        }
        assertTrue(linked.contains("political_pages=politics.read_overview"), linked.toString());
        assertTrue(linked.contains("offices_and_permissions=politics.read_council"), linked.toString());
        assertTrue(linked.contains("found_a_government=politics.bootstrap"), linked.toString());
        assertTrue(ActionRegistry.find("politics.bootstrap").map(t -> !t.operator()).orElse(false), "Founding must be reachable by eligible players");
        assertTrue(ActionRegistry.find("politics.diagnose").map(ActionRegistry.Task::operator).orElse(false), "Diagnosis stays operator-only");
    }
}
