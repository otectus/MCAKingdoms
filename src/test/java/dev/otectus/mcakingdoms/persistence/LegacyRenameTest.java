package dev.otectus.mcakingdoms.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-time carry-over from Ultima Kingdoms (the 0.1.1 rename). */
class LegacyRenameTest {
    @TempDir Path temporary;

    @Test
    void idsAndTranslationKeysMoveButNothingElseDoes() {
        assertEquals("mcakingdoms:serenum", LegacyRename.rewrite("ultima_kingdoms:serenum"));
        assertEquals("politics.mcakingdoms.office", LegacyRename.rewrite("politics.ultima_kingdoms.office"));
        assertEquals("{\"kingdom\":\"mcakingdoms:lunari\",\"honor\":\"mcakingdoms:winter_record\"}",
                LegacyRename.rewrite("{\"kingdom\":\"ultima_kingdoms:lunari\",\"honor\":\"ultima_kingdoms:winter_record\"}"));
        assertEquals("ultima:civic/lamplighters/workshop_lanterns",
                LegacyRename.rewrite("ultima:civic/lamplighters/workshop_lanterns"), "the modpack's own namespace stays");
        assertEquals("minecraft:overworld#42", LegacyRename.rewrite("minecraft:overworld#42"));
        assertEquals("ultima_kingdoms_settlements", LegacyRename.rewrite("ultima_kingdoms_settlements"));
    }

    @Test
    void everyStringAndKeyIsRewrittenAtAnyDepth() {
        CompoundTag legacy = new CompoundTag();
        CompoundTag governments = new CompoundTag();
        CompoundTag government = new CompoundTag();
        government.putString("Kingdom", "ultima_kingdoms:serenum");
        government.putInt("Revision", 7);
        governments.put("ultima_kingdoms:serenum", government);
        legacy.put("Governments", governments);
        ListTag slugs = new ListTag();
        slugs.add(StringTag.valueOf("ultima_kingdoms:old_town"));
        slugs.add(StringTag.valueOf("test:kept"));
        legacy.put("RetiredSlugs", slugs);
        legacy.putIntArray("Ids", new int[]{1, 2, 3});

        CompoundTag current = LegacyRename.rewrite(legacy);
        CompoundTag renamed = current.getCompound("Governments").getCompound("mcakingdoms:serenum");
        assertEquals("mcakingdoms:serenum", renamed.getString("Kingdom"));
        assertEquals(7, renamed.getInt("Revision"));
        assertEquals("mcakingdoms:old_town", current.getList("RetiredSlugs", 8).getString(0));
        assertEquals("test:kept", current.getList("RetiredSlugs", 8).getString(1));
        assertArrayEquals(new int[]{1, 2, 3}, current.getIntArray("Ids"));
        assertFalse(current.toString().contains("ultima_kingdoms"));
        assertTrue(legacy.toString().contains("ultima_kingdoms"), "the input is not modified");
    }

    private static CompoundTag root(CompoundTag data, int dataVersion) {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.put("DataVersion", IntTag.valueOf(dataVersion));
        return root;
    }

    @Test
    void eachSavedDataFileIsCarriedOverOnceAndTheOriginalIsKept() throws IOException {
        CompoundTag settlements = new CompoundTag();
        settlements.putString("Slug", "ultima_kingdoms:aldwick");
        CompoundTag politics = new CompoundTag();
        politics.putString("Payload", "{\"governments\":{\"ultima_kingdoms:serenum\":{}}}");
        Path oldSettlements = temporary.resolve("ultima_kingdoms_settlements.dat");
        Path oldPolitics = temporary.resolve("ultima_kingdoms_politics.dat");
        NbtIo.writeCompressed(root(settlements, 3465), oldSettlements.toFile());
        NbtIo.writeCompressed(root(politics, 3465), oldPolitics.toFile());
        byte[] before = Files.readAllBytes(oldSettlements);

        assertEquals(List.of("mcakingdoms_settlements.dat", "mcakingdoms_politics.dat"),
                LegacyRename.migrateSavedData(temporary));

        CompoundTag migrated = NbtIo.readCompressed(temporary.resolve("mcakingdoms_settlements.dat").toFile());
        assertEquals(3465, migrated.getInt("DataVersion"), "the version stamp is carried, so data fixers still apply");
        assertEquals("mcakingdoms:aldwick", migrated.getCompound("data").getString("Slug"));
        assertEquals("{\"governments\":{\"mcakingdoms:serenum\":{}}}", NbtIo.readCompressed(
                temporary.resolve("mcakingdoms_politics.dat").toFile()).getCompound("data").getString("Payload"));
        assertArrayEquals(before, Files.readAllBytes(oldSettlements), "the old file is never changed");

        CompoundTag later = new CompoundTag();
        later.putString("Slug", "mcakingdoms:renamed_since");
        NbtIo.writeCompressed(root(later, 3465), temporary.resolve("mcakingdoms_settlements.dat").toFile());
        assertEquals(List.of(), LegacyRename.migrateSavedData(temporary), "an existing new file always wins");
        assertEquals("mcakingdoms:renamed_since", NbtIo.readCompressed(
                temporary.resolve("mcakingdoms_settlements.dat").toFile()).getCompound("data").getString("Slug"));
    }

    @Test
    void anUnreadableLegacyFileIsLeftAloneAndCreatesNothing() throws IOException {
        Path broken = temporary.resolve("ultima_kingdoms_control.dat");
        Files.writeString(broken, "not nbt");
        assertEquals(List.of(), LegacyRename.migrateSavedData(temporary));
        assertFalse(Files.exists(temporary.resolve("mcakingdoms_control.dat")));
        assertEquals("not nbt", Files.readString(broken));
    }

    @Test
    void configFilesAreCopiedOnlyWhereTheNewOneIsMissing() throws IOException {
        Files.writeString(temporary.resolve("ultima-kingdoms-warfare-common.toml"), "enabled = false\n");
        Files.writeString(temporary.resolve("ultima_kingdoms-common.toml"), "old = true\n");
        Files.writeString(temporary.resolve("mcakingdoms-common.toml"), "new = true\n");

        assertEquals(List.of("mcakingdoms-warfare-common.toml"), LegacyRename.migrateConfigs(temporary));
        assertEquals("enabled = false\n", Files.readString(temporary.resolve("mcakingdoms-warfare-common.toml")));
        assertEquals("new = true\n", Files.readString(temporary.resolve("mcakingdoms-common.toml")));
        assertTrue(Files.exists(temporary.resolve("ultima-kingdoms-warfare-common.toml")), "the old file is kept");
    }

    @Test
    void anEntityRecordMovesToItsNewKeyAndTheCurrentOneWins() {
        CompoundTag data = new CompoundTag();
        CompoundTag identity = new CompoundTag();
        identity.putString("OriginKingdom", "ultima_kingdoms:madera");
        data.put("ultima_kingdoms:civic_identity", identity);
        assertTrue(LegacyRename.moveCompound(data, "ultima_kingdoms:civic_identity", "mcakingdoms:civic_identity"));
        assertEquals("mcakingdoms:madera", data.getCompound("mcakingdoms:civic_identity").getString("OriginKingdom"));
        assertFalse(data.contains("ultima_kingdoms:civic_identity"));
        assertFalse(LegacyRename.moveCompound(data, "ultima_kingdoms:civic_identity", "mcakingdoms:civic_identity"));

        data.put("ultima_kingdoms:civic_identity", identity);
        assertTrue(LegacyRename.moveCompound(data, "ultima_kingdoms:civic_identity", "mcakingdoms:civic_identity"));
        assertEquals("mcakingdoms:madera", data.getCompound("mcakingdoms:civic_identity").getString("OriginKingdom"),
                "a record the new key already holds is not overwritten");
        assertFalse(data.contains("ultima_kingdoms:civic_identity"));
    }

    private static Set<String> literals(Pattern pattern) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")
                    && !path.getFileName().toString().equals("LegacyRename.java")).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) found.add(matcher.group(1));
            }
        }
        return found;
    }

    @Test
    void everySavedDataStoreInTheCodeIsMigrated() throws IOException {
        var known = new TreeSet<>(LegacyRename.SAVED_DATA);
        known.addAll(LegacyRename.SINCE_RENAME);
        assertEquals(literals(Pattern.compile("NAME\\s*=\\s*\"mcakingdoms_([a-z_]+)\"")), known,
                "a store missing here would start empty on an upgraded world, or is new and belongs in SINCE_RENAME");
    }

    @Test
    void everyConfigFileInTheCodeIsMigrated() throws IOException {
        Set<String> registered = literals(Pattern.compile("\"(mcakingdoms-[a-z-]+\\.toml)\""));
        registered.add("mcakingdoms-common.toml"); // McaKingdomsConfig takes Forge's default name, <modid>-common.toml
        assertEquals(registered, new TreeSet<>(LegacyRename.CONFIG_FILES.values()));
    }
}
