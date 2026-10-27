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
 * <p>Each step copies, never moves: an old file is left exactly where it was, so a failed or unwanted
 * migration can be redone by deleting the new file. A step runs only when its new file is absent, which
 * makes it once-only per world and safe to repeat after a crash.
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
                LOGGER.error("[MCA: Kingdoms] Could not copy config {} to {}; that file's settings start at their defaults. "
                        + "Copy the old file to the new name by hand while the game is stopped.", legacy, current, failure);
            }
        });
        return copied;
    }

    /**
     * Copies each pre-rename saved-data file whose new file does not exist yet, rewriting the old
     * namespace inside it. Runs at server start-up, before any level or saved data is loaded. Returns the
     * new file names it wrote.
     */
    public static List<String> migrateSavedData(Path dataDirectory) {
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
                CompoundTag root = NbtIo.readCompressed(from.toFile());
                AtomicSavedDataWriter.writeRoot(to.toFile(), rewrite(root));
                migrated.add(to.getFileName().toString());
                DATA_STEPS.add(new Step(from.getFileName().toString(), to.getFileName().toString(), Outcome.COPIED, ""));
                LOGGER.info("[MCA: Kingdoms] Migrated {} to {} (renamed from Ultima Kingdoms; the old file is kept)",
                        from.getFileName(), to.getFileName());
            } catch (IOException | RuntimeException failure) {
                DATA_STEPS.add(new Step(from.getFileName().toString(), to.getFileName().toString(), Outcome.FAILED, String.valueOf(failure.getMessage())));
                // Recovery is narrow on purpose: only this failed first copy may be redone, and only before new play saves into it.
                LOGGER.error("[MCA: Kingdoms] Could not migrate {}; that store starts empty for this run and the old file is untouched. "
                        + "Stop the server before anyone plays on. If {} now exists, it holds only what this run saved: move it aside "
                        + "(keep it), fix or restore {}, and start again to retry this one copy. Once new progress is saved, restore "
                        + "a world backup instead.", from.getFileName(), to.getFileName(), from.getFileName(), failure);
            }
        }
        dataChecked = true;
        return migrated;
    }

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
