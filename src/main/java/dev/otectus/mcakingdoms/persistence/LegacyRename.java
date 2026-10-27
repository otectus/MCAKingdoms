package dev.otectus.mcakingdoms.persistence;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries a world and its configuration over from Ultima Kingdoms, this mod's name before 0.1.1.
 *
 * <p>The rename changed the loader id, and with it every persistent name: the seventeen saved-data
 * files, the villager civic-identity key, the player welcome flag, the config file names, and the
 * namespace of every id the mod writes into its own data (settlement slugs, kingdoms, organisations,
 * petitions, honours, evidence). Without this an upgraded world starts every system over while the old
 * files sit unread beside the new ones.
 *
 * <p>Each step copies, never moves: an old file is left exactly where it was. A step runs only when its new
 * file is absent, which makes it once-only per world and safe to repeat after a crash; each copy is written
 * to a temporary file and moved into place, so a destination is either complete or absent.
 *
 * <p>A failed step is never treated as a new world (0.3.0, F03). {@link #requireMigrated()} stops the world
 * from starting before any store is created, so no empty store can be saved in place of the data that failed
 * to copy and later hide it behind an existing destination. The steps that did succeed are kept and skipped
 * on the next start, so an interrupted multi-file migration finishes where it stopped. A failed copy of a
 * common config file stops the world too: its defaults could quietly change who may found or govern.
 */
public final class LegacyRename {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The loader id, and so the namespace, this mod had as Ultima Kingdoms. */
    public static final String LEGACY_NAMESPACE = "ultima_kingdoms";
    public static final String NAMESPACE = McaKingdomsApi.MOD_ID;

    /** Every saved-data file the mod writes, by the part of its name after the namespace. */
    static final List<String> SAVED_DATA = List.of(
            "settlements", "knowledge", "factions", "organizations", "politics", "civic_network",
            "institutional_commissions", "quest_receipts", "campaigns", "control", "civilian_contracts",
            "evolution", "evolution_drama", "protection", "recruit_transfers", "mobilization", "world_context");

    /** Config files, old name to new. The keys inside them did not change. */
    static final Map<String, String> CONFIG_FILES = configFiles();

    /** What one migration step did at this start. A destination that already exists is skipped, never overwritten. */
    public enum Outcome { COPIED, SKIPPED_DESTINATION_EXISTS, FAILED }

    /** One file's outcome at this start; {@code detail} explains a failure. */
    public record Step(String from, String to, Outcome outcome, String detail) { }

    /**
     * The steps of this process's starts, kept in memory for server health. Earlier starts are not recorded anywhere, so
     * health reports only what this run observed and never reconstructs a past migration.
     */
    private static final List<Step> CONFIG_STEPS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<Step> DATA_STEPS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean dataChecked;

    public static List<Step> configSteps() { return List.copyOf(CONFIG_STEPS); }
    public static List<Step> dataSteps() { return List.copyOf(DATA_STEPS); }
    /** Whether this run has checked the world's data folder for Ultima Kingdoms files yet. */
    public static boolean dataChecked() { return dataChecked; }

    private LegacyRename() {
    }

    private static Map<String, String> configFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ultima_kingdoms-common.toml", "mcakingdoms-common.toml");
        files.put("ultima_kingdoms-integrations-common.toml", "mcakingdoms-integrations-common.toml");
        files.put("ultima-kingdoms-warfare-common.toml", "mcakingdoms-warfare-common.toml");
        files.put("ultima-kingdoms-evolution-common.toml", "mcakingdoms-evolution-common.toml");
        files.put("ultima_kingdoms-townstead-common.toml", "mcakingdoms-townstead-common.toml");
        files.put("ultima_kingdoms-factions-common.toml", "mcakingdoms-factions-common.toml");
        files.put("ultima-kingdoms-civic-common.toml", "mcakingdoms-civic-common.toml");
        files.put("ultima-kingdoms-client.toml", "mcakingdoms-client.toml");
        return Map.copyOf(files);
    }

    /**
     * One string as the renamed mod writes it: ids move from {@code ultima_kingdoms:} to
     * {@code mcakingdoms:}, and translation keys from {@code *.ultima_kingdoms.*} to
     * {@code *.mcakingdoms.*}. Anything else, including the modpack's own {@code ultima:} quests, is kept.
     */
    public static String rewrite(String value) {
        if (value == null || !value.contains(LEGACY_NAMESPACE)) return value;
        return value.replace(LEGACY_NAMESPACE + ':', NAMESPACE + ':')
                .replace('.' + LEGACY_NAMESPACE + '.', '.' + NAMESPACE + '.');
    }

    /**
     * A copy of {@code tag} with {@link #rewrite(String)} applied to every string and every compound key
     * at any depth. JSON payloads stored as strings are rewritten the same way, so one pass serves both
     * the NBT-backed and the JSON-backed stores. Numbers and arrays are shared, not copied.
     */
    public static Tag rewrite(Tag tag) {
        if (tag instanceof StringTag string) {
            String value = string.getAsString();
            String renamed = rewrite(value);
            return renamed.equals(value) ? string : StringTag.valueOf(renamed);
        }
        if (tag instanceof CompoundTag compound) {
            CompoundTag copy = new CompoundTag();
            for (String key : compound.getAllKeys()) copy.put(rewrite(key), rewrite(compound.get(key)));
            return copy;
        }
        if (tag instanceof ListTag list) {
            ListTag copy = new ListTag();
            for (Tag element : list) copy.add(rewrite(element));
            return copy;
        }
        return tag;
    }

    /** Typed convenience for {@link #rewrite(Tag)} on a compound. */
    public static CompoundTag rewrite(CompoundTag tag) {
        return (CompoundTag) rewrite((Tag) tag);
    }

    /**
     * Copies each pre-rename config file to its new name when the new one does not exist yet. Runs from
     * the mod constructor, before Forge reads any config, so the copied settings are the ones loaded.
     */
    public static List<String> migrateConfigs(Path configDirectory) {
        List<String> copied = new ArrayList<>();
        CONFIG_STEPS.clear();
        CONFIG_FILES.forEach((legacy, current) -> {
            Path from = configDirectory.resolve(legacy);
            Path to = configDirectory.resolve(current);
            if (!Files.isRegularFile(from)) return;
            if (Files.exists(to)) {
                CONFIG_STEPS.add(new Step(legacy, current, Outcome.SKIPPED_DESTINATION_EXISTS, ""));
                return;
            }
            try {
                Files.copy(from, to);
                copied.add(current);
                CONFIG_STEPS.add(new Step(legacy, current, Outcome.COPIED, ""));
                LOGGER.info("[MCA: Kingdoms] Copied config {} to {} (renamed from Ultima Kingdoms; the old file is kept)",
                        legacy, current);
            } catch (IOException failure) {
                CONFIG_STEPS.add(new Step(legacy, current, Outcome.FAILED, String.valueOf(failure.getMessage())));
                LOGGER.error("[MCA: Kingdoms] Could not copy config {} to {}; worlds will not start while a common config would fall back "
                        + "to defaults. Copy the old file to the new name by hand while the game is stopped.", legacy, current, failure);
            }
        });
        return copied;
    }

    /** Reads a legacy file and writes its rewritten copy; tests replace it to inject read, decode, write and move failures. */
    interface Copier {
        CompoundTag read(Path from) throws IOException;
        void write(Path to, CompoundTag root) throws IOException;
    }

    static final Copier FILES = new Copier() {
        @Override public CompoundTag read(Path from) throws IOException { return NbtIo.readCompressed(from.toFile()); }
        @Override public void write(Path to, CompoundTag root) throws IOException { AtomicSavedDataWriter.writeRoot(to.toFile(), root); }
    };

    /**
     * Copies each pre-rename saved-data file whose new file does not exist yet, rewriting the old
     * namespace inside it. Runs at server start-up, before any level or saved data is loaded. Returns the
     * new file names it wrote. A failure is recorded and reported by {@link #requireMigrated()}.
     */
    public static List<String> migrateSavedData(Path dataDirectory) {
        return migrateSavedData(dataDirectory, FILES);
    }

    static List<String> migrateSavedData(Path dataDirectory, Copier copier) {
        List<String> migrated = new ArrayList<>();
        DATA_STEPS.clear();
        for (String suffix : SAVED_DATA) {
            Path from = dataDirectory.resolve(LEGACY_NAMESPACE + '_' + suffix + ".dat");
            Path to = dataDirectory.resolve(NAMESPACE + '_' + suffix + ".dat");
            if (!Files.isRegularFile(from)) continue;
            if (Files.exists(to)) {
                DATA_STEPS.add(new Step(from.getFileName().toString(), to.getFileName().toString(), Outcome.SKIPPED_DESTINATION_EXISTS, ""));
                continue;
            }
            try {
                CompoundTag root = copier.read(from);
                if (root == null || !root.contains("data", Tag.TAG_COMPOUND)) throw new IOException("the file holds no saved data");
                copier.write(to, rewrite(root));
                migrated.add(to.getFileName().toString());
                DATA_STEPS.add(new Step(from.getFileName().toString(), to.getFileName().toString(), Outcome.COPIED, ""));
                LOGGER.info("[MCA: Kingdoms] Migrated {} to {} (renamed from Ultima Kingdoms; the old file is kept)",
                        from.getFileName(), to.getFileName());
            } catch (IOException | RuntimeException failure) {
                DATA_STEPS.add(new Step(from.getFileName().toString(), to.getFileName().toString(), Outcome.FAILED, String.valueOf(failure.getMessage())));
                LOGGER.error("[MCA: Kingdoms] Could not migrate {} to {}; this world will not start until the copy succeeds, "
                        + "so nothing is written in its place and the old file is untouched.", from.getFileName(), to.getFileName(), failure);
            }
        }
        dataChecked = true;
        return migrated;
    }

    /** Config files whose defaults decide authority (founding, warfare, evolution, guild services); the client file does not. */
    static boolean authoritative(String currentConfig) {
        return currentConfig.endsWith("-common.toml");
    }

    /** This start's failed steps that must stop the world: every saved-data copy and every common config copy. */
    public static List<Step> blockingFailures() {
        List<Step> failures = new ArrayList<>();
        CONFIG_STEPS.stream().filter(step -> step.outcome() == Outcome.FAILED && authoritative(step.to())).forEach(failures::add);
        DATA_STEPS.stream().filter(step -> step.outcome() == Outcome.FAILED).forEach(failures::add);
        return List.copyOf(failures);
    }

    /**
     * Stops the world before any store starts when a carry-over failed at this start. The message names each file,
     * why it failed and what to do; nothing has been written in place of the old data.
     */
    public static void requireMigrated() {
        var failures = blockingFailures();
        if (failures.isEmpty()) return;
        StringBuilder message = new StringBuilder("MCA: Kingdoms did not start this world because carrying it over from Ultima Kingdoms failed:");
        for (Step step : failures) message.append("\n  ").append(step.from()).append(" -> ").append(step.to()).append(": ").append(step.detail());
        message.append("\nNothing was written in place of the old data, and the old files are unchanged. Fix the cause (file permissions, ")
                .append("free disk space, or restore a damaged file from a backup), then start again. Copies that already succeeded are kept ")
                .append("and skipped; the failed ones are retried. Do not delete existing mcakingdoms_* files to force a copy: they may hold newer play.");
        LOGGER.error(message.toString());
        throw new IllegalStateException(message.toString());
    }

    /**
     * A plain-text account of this start's carry-over beside the world's data, for operators who cannot open the in-game
     * health page (for example when the world refused to start). Written only when an old file was found; a failure to
     * write it is logged and changes nothing else.
     */
    public static void writeReport(Path dataDirectory) {
        if (CONFIG_STEPS.isEmpty() && DATA_STEPS.isEmpty()) return;
        StringBuilder report = new StringBuilder("MCA: Kingdoms carry-over from Ultima Kingdoms, ").append(java.time.Instant.now()).append('\n');
        for (Step step : configSteps()) report.append("config ").append(step.outcome()).append(' ').append(step.from()).append(" -> ").append(step.to())
                .append(step.detail().isEmpty() ? "" : ": " + step.detail()).append('\n');
        for (Step step : dataSteps()) report.append("data ").append(step.outcome()).append(' ').append(step.from()).append(" -> ").append(step.to())
                .append(step.detail().isEmpty() ? "" : ": " + step.detail()).append('\n');
        report.append("COPIED: carried over at this start. SKIPPED_DESTINATION_EXISTS: a current file was already there (an earlier copy or new play) ")
                .append("and was kept. FAILED: the world was not started; see the log.\n");
        try {
            Files.createDirectories(dataDirectory);
            Files.writeString(dataDirectory.resolve(REPORT), report.toString());
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[MCA: Kingdoms] Could not write {}", REPORT, failure);
        }
    }

    /** The report's file name in the world's data folder; Minecraft reads only .dat files there. */
    public static final String REPORT = "mcakingdoms_migration_report.txt";

    /**
     * Moves one compound entry of an entity's persistent data from its pre-rename key to its current key,
     * rewriting the namespace inside it. Entities are stored in chunks, so they migrate one by one as they
     * are read. When both keys exist the current one wins and the old one is dropped. Returns whether
     * anything changed.
     */
    public static boolean moveCompound(CompoundTag data, String legacyKey, String currentKey) {
        if (!data.contains(legacyKey)) return false;
        if (!data.contains(currentKey, Tag.TAG_COMPOUND) && data.contains(legacyKey, Tag.TAG_COMPOUND)) {
            data.put(currentKey, rewrite(data.getCompound(legacyKey)));
        }
        data.remove(legacyKey);
        return true;
    }
}
