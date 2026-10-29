package dev.otectus.mcakingdoms.persistence;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Opt-in carry-over of player progress recorded under the pre-rename namespace (H09, setting
 * {@code migration.legacyProgress}). Advancement keys in {@code <world>/advancements/*.json} and statistic keys in
 * {@code <world>/stats/*.json} move from {@code ultima_kingdoms:} to {@code mcakingdoms:} before any player loads.
 * An advancement already earned under the new name is kept and the old entry dropped; statistics present under both
 * names are added together. Each changed file is first kept as {@code <file>.ultima-bak} and then replaced
 * atomically. A marker in the world's data folder makes the pass run once. Recipe-book entries stay as they are:
 * the two recipes unlock again as soon as their ingredients are held, and player NBT is not rewritten for them.
 */
public final class LegacyProgress {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String OLD = LegacyRename.LEGACY_NAMESPACE + ":", NEW = LegacyRename.NAMESPACE + ":";
    /** Written beside the world's data once the pass has run; Minecraft reads only .dat files there. */
    public static final String MARKER = "mcakingdoms_progress_migration.txt";

    /** What one pass changed. */
    public record Result(boolean ran, int files, int advancements, int statistics, List<String> failures) { }

    private LegacyProgress() {
    }

    /** Migrates once per world; later starts read the marker and change nothing. */
    public static Result migrate(Path worldRoot) {
        Path marker = worldRoot.resolve("data").resolve(MARKER);
        if (Files.exists(marker)) return new Result(false, 0, 0, 0, List.of());
        int files = 0, advancements = 0, statistics = 0;
        List<String> failures = new ArrayList<>();
        for (String folder : List.of("advancements", "stats")) {
            Path directory = worldRoot.resolve(folder);
            if (!Files.isDirectory(directory)) continue;
            List<Path> players;
            try (var listing = Files.list(directory)) {
                players = listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            } catch (IOException failure) {
                failures.add(folder + ": " + failure.getMessage());
                continue;
            }
            for (Path file : players) {
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (!text.contains(OLD)) continue;
                    JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                    int moved = folder.equals("advancements") ? advancements(root) : statistics(root);
                    if (moved == 0) continue;
                    Path backup = file.resolveSibling(file.getFileName() + ".ultima-bak");
                    if (!Files.exists(backup)) Files.copy(file, backup);
                    Path staged = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
                    try {
                        Files.writeString(staged, GSON.toJson(root), StandardCharsets.UTF_8);
                        Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                    files++;
                    if (folder.equals("advancements")) advancements += moved; else statistics += moved;
                } catch (IOException | RuntimeException failure) {
                    failures.add(folder + "/" + file.getFileName() + ": " + failure.getMessage());
                }
            }
        }
        String summary = "MCA: Kingdoms legacy progress carry-over, " + java.time.Instant.now() + "\nfiles changed: " + files
                + "\nadvancements moved: " + advancements + "\nstatistics moved: " + statistics
                + failures.stream().map(f -> "\nFAILED " + f).reduce("", String::concat) + "\n";
        if (failures.isEmpty()) {
            try {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, summary);
            } catch (IOException failure) {
                LOGGER.warn("[MCA: Kingdoms] Could not write {}; the pass will run again next start (it changes nothing twice)", MARKER, failure);
            }
        } else LOGGER.warn("[MCA: Kingdoms] Legacy progress carry-over left files unchanged; it is retried next start:{}", summary);
        if (files > 0) LOGGER.info("[MCA: Kingdoms] Carried {} advancement(s) and {} statistic(s) over from Ultima Kingdoms in {} file(s)", advancements, statistics, files);
        return new Result(true, files, advancements, statistics, List.copyOf(failures));
    }

    /** Moves top-level advancement keys; an entry already under the new name wins. Returns the entries moved. */
    static int advancements(JsonObject root) {
        int moved = 0;
        for (String key : List.copyOf(root.keySet())) {
            if (!key.startsWith(OLD)) continue;
            String renamed = NEW + key.substring(OLD.length());
            if (!root.has(renamed)) root.add(renamed, root.get(key));
            root.remove(key);
            moved++;
        }
        return moved;
    }

    /** Moves statistic keys inside each statistic type; counts under both names are added. Returns the values moved. */
    static int statistics(JsonObject root) {
        if (!root.has("stats") || !root.get("stats").isJsonObject()) return 0;
        int moved = 0;
        for (var type : root.getAsJsonObject("stats").entrySet()) {
            if (!type.getValue().isJsonObject()) continue;
            JsonObject values = type.getValue().getAsJsonObject();
            for (String key : List.copyOf(values.keySet())) {
                if (!key.startsWith(OLD) || !values.get(key).isJsonPrimitive()) continue;
                String renamed = NEW + key.substring(OLD.length());
                long count = values.get(key).getAsLong() + (values.has(renamed) && values.get(renamed).isJsonPrimitive() ? values.get(renamed).getAsLong() : 0);
                values.addProperty(renamed, (int) Math.min(Integer.MAX_VALUE, count));
                values.remove(key);
                moved++;
            }
        }
        return moved;
    }
}
