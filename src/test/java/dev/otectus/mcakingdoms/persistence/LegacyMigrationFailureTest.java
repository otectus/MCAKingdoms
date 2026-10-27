package dev.otectus.mcakingdoms.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F03: a carry-over that fails stops the world instead of starting that store empty, keeps the old file, and finishes
 * on a later start. Successful copies, skipped destinations and failures are reported apart.
 */
class LegacyMigrationFailureTest {
    @TempDir Path data;

    private Path legacy(String suffix, String marker) throws IOException {
        CompoundTag payload = new CompoundTag();
        payload.putString("Marker", "ultima_kingdoms:" + marker);
        CompoundTag root = new CompoundTag();
        root.put("data", payload);
        root.put("DataVersion", IntTag.valueOf(3465));
        Path file = data.resolve("ultima_kingdoms_" + suffix + ".dat");
        NbtIo.writeCompressed(root, file.toFile());
        return file;
    }

    /** Fails the named stage for one file and behaves like the real copier for every other. */
    private static LegacyRename.Copier failing(String file, String stage) {
        return new LegacyRename.Copier() {
            @Override public CompoundTag read(Path from) throws IOException {
                if (stage.equals("read") && from.getFileName().toString().equals(file)) throw new IOException("injected read failure");
                if (stage.equals("decode") && from.getFileName().toString().equals(file)) throw new java.io.UTFDataFormatException("injected decode failure");
                return LegacyRename.FILES.read(from);
            }
            @Override public void write(Path to, CompoundTag root) throws IOException {
                if (stage.equals("write") && to.getFileName().toString().equals(file.replace("ultima_kingdoms_", "mcakingdoms_")))
                    throw new IOException("injected write failure: no space left on device");
                if (stage.equals("move") && to.getFileName().toString().equals(file.replace("ultima_kingdoms_", "mcakingdoms_")))
                    throw new java.nio.file.AtomicMoveNotSupportedException("tmp", to.toString(), "injected move failure");
                LegacyRename.FILES.write(to, root);
            }
        };
    }

    @Test
    void eachInjectedFailureStopsTheWorldAndCreatesNoDestination() throws IOException {
        for (String stage : List.of("read", "decode", "write", "move")) {
            for (Path file : Files.list(data).toList()) Files.delete(file);
            Path old = legacy("politics", stage);
            byte[] before = Files.readAllBytes(old);
            assertEquals(List.of(), LegacyRename.migrateSavedData(data, failing("ultima_kingdoms_politics.dat", stage)));
            assertFalse(Files.exists(data.resolve("mcakingdoms_politics.dat")), stage + ": no empty store may stand in for the failed copy");
            assertArrayEquals(before, Files.readAllBytes(old), stage + ": the old file is never changed");
            var failures = LegacyRename.blockingFailures();
            assertEquals(1, failures.size(), stage);
            assertEquals(LegacyRename.Outcome.FAILED, failures.get(0).outcome());
            var refused = assertThrows(IllegalStateException.class, LegacyRename::requireMigrated, stage);
            assertTrue(refused.getMessage().contains("ultima_kingdoms_politics.dat"), refused.getMessage());
            assertTrue(refused.getMessage().contains("Do not delete existing mcakingdoms_* files"), refused.getMessage());
        }
    }

    @Test
    void aFileWithoutSavedDataIsAFailureNotAnEmptyStore() throws IOException {
        NbtIo.writeCompressed(new CompoundTag(), data.resolve("ultima_kingdoms_control.dat").toFile());
        LegacyRename.migrateSavedData(data);
        assertFalse(Files.exists(data.resolve("mcakingdoms_control.dat")));
        assertThrows(IllegalStateException.class, LegacyRename::requireMigrated);
    }

    @Test
    void anInterruptedMultiFileMigrationFinishesOnTheNextStart() throws IOException {
        legacy("settlements", "a");
        legacy("politics", "b");
        legacy("evolution", "c");
        LegacyRename.migrateSavedData(data, failing("ultima_kingdoms_politics.dat", "write"));
        assertTrue(Files.exists(data.resolve("mcakingdoms_settlements.dat")));
        assertTrue(Files.exists(data.resolve("mcakingdoms_evolution.dat")));
        assertFalse(Files.exists(data.resolve("mcakingdoms_politics.dat")));
        assertThrows(IllegalStateException.class, LegacyRename::requireMigrated);

        // Fixed and restarted: the completed copies are kept and skipped, the failed one is carried over.
        assertEquals(List.of("mcakingdoms_politics.dat"), LegacyRename.migrateSavedData(data));
        assertDoesNotThrow(LegacyRename::requireMigrated);
        var outcomes = LegacyRename.dataSteps().stream().map(step -> step.to() + "=" + step.outcome()).toList();
        assertEquals(Set.of("mcakingdoms_settlements.dat=SKIPPED_DESTINATION_EXISTS", "mcakingdoms_politics.dat=COPIED",
                "mcakingdoms_evolution.dat=SKIPPED_DESTINATION_EXISTS"), Set.copyOf(outcomes));
        assertEquals("mcakingdoms:b", NbtIo.readCompressed(data.resolve("mcakingdoms_politics.dat").toFile())
                .getCompound("data").getString("Marker"));
    }

    @Test
    void oldOnlyNewOnlyAndBothPresentAreDistinguished() throws IOException {
        legacy("knowledge", "old");                                   // old only: copied
        CompoundTag newer = new CompoundTag();
        CompoundTag payload = new CompoundTag();
        payload.putString("Marker", "mcakingdoms:newer");
        newer.put("data", payload);
        NbtIo.writeCompressed(newer, data.resolve("mcakingdoms_factions.dat").toFile()); // new only: untouched, no step
        legacy("campaigns", "old");                                   // both: the newer play wins
        NbtIo.writeCompressed(newer, data.resolve("mcakingdoms_campaigns.dat").toFile());

        assertEquals(List.of("mcakingdoms_knowledge.dat"), LegacyRename.migrateSavedData(data));
        assertDoesNotThrow(LegacyRename::requireMigrated);
        var steps = LegacyRename.dataSteps();
        assertEquals(2, steps.size());
        assertTrue(steps.stream().anyMatch(s -> s.to().equals("mcakingdoms_knowledge.dat") && s.outcome() == LegacyRename.Outcome.COPIED));
        assertTrue(steps.stream().anyMatch(s -> s.to().equals("mcakingdoms_campaigns.dat") && s.outcome() == LegacyRename.Outcome.SKIPPED_DESTINATION_EXISTS));
        assertEquals("mcakingdoms:newer", NbtIo.readCompressed(data.resolve("mcakingdoms_campaigns.dat").toFile())
                .getCompound("data").getString("Marker"), "an existing destination is never overwritten");
    }

    @Test
    void everyOneOfTheSeventeenStoresMigratesUnderItsNewName() throws IOException {
        assertEquals(17, LegacyRename.SAVED_DATA.size());
        for (String suffix : LegacyRename.SAVED_DATA) legacy(suffix, suffix);
        var written = LegacyRename.migrateSavedData(data);
        assertEquals(17, written.size());
        for (String suffix : LegacyRename.SAVED_DATA) {
            CompoundTag copy = NbtIo.readCompressed(data.resolve("mcakingdoms_" + suffix + ".dat").toFile());
            assertEquals("mcakingdoms:" + suffix, copy.getCompound("data").getString("Marker"), suffix);
            assertTrue(Files.exists(data.resolve("ultima_kingdoms_" + suffix + ".dat")), suffix + " old file kept");
        }
        assertDoesNotThrow(LegacyRename::requireMigrated);
    }

    @Test
    void theReportNamesEachOutcome() throws IOException {
        legacy("settlements", "a");
        legacy("politics", "b");
        LegacyRename.migrateSavedData(data, failing("ultima_kingdoms_politics.dat", "read"));
        LegacyRename.writeReport(data);
        String report = Files.readString(data.resolve(LegacyRename.REPORT));
        assertTrue(report.contains("data COPIED ultima_kingdoms_settlements.dat -> mcakingdoms_settlements.dat"), report);
        assertTrue(report.contains("data FAILED ultima_kingdoms_politics.dat -> mcakingdoms_politics.dat: injected read failure"), report);
    }

    @Test
    void onlyCommonConfigFailuresStopTheWorld() {
        assertTrue(LegacyRename.authoritative("mcakingdoms-common.toml"));
        assertTrue(LegacyRename.authoritative("mcakingdoms-warfare-common.toml"));
        assertFalse(LegacyRename.authoritative("mcakingdoms-client.toml"), "display preferences falling back to defaults change no authority");
    }
}
