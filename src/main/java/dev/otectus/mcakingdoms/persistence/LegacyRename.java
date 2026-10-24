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
        CONFIG_FILES.forEach((legacy, current) -> {
            Path from = configDirectory.resolve(legacy);
            Path to = configDirectory.resolve(current);
            if (!Files.isRegularFile(from) || Files.exists(to)) return;
            try {
                Files.copy(from, to);
                copied.add(current);
                LOGGER.info("[MCA: Kingdoms] Copied config {} to {} (renamed from Ultima Kingdoms; the old file is kept)",
                        legacy, current);
            } catch (IOException failure) {
                LOGGER.error("[MCA: Kingdoms] Could not copy config {} to {}; defaults will be used. Copy it by hand.",
                        legacy, current, failure);
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
        for (String suffix : SAVED_DATA) {
            Path from = dataDirectory.resolve(LEGACY_NAMESPACE + '_' + suffix + ".dat");
            Path to = dataDirectory.resolve(NAMESPACE + '_' + suffix + ".dat");
            if (!Files.isRegularFile(from) || Files.exists(to)) continue;
            try {
                CompoundTag root = NbtIo.readCompressed(from.toFile());
                AtomicSavedDataWriter.writeRoot(to.toFile(), rewrite(root));
                migrated.add(to.getFileName().toString());
                LOGGER.info("[MCA: Kingdoms] Migrated {} to {} (renamed from Ultima Kingdoms; the old file is kept)",
                        from.getFileName(), to.getFileName());
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("[MCA: Kingdoms] Could not migrate {}; this store starts empty. The old file is untouched: "
                        + "stop the server, delete {} and fix or restore {} to retry.", from.getFileName(),
                        to.getFileName(), from.getFileName(), failure);
            }
        }
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
