package dev.otectus.mcakingdoms.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H06: one durable-write policy. A failed or non-atomic replacement never touches the previous file, leaves no staged
 * file behind and is reported; the first replacement of a session keeps the loaded generation as a backup; a store whose
 * save failed stays dirty so the next save retries.
 */
class AtomicSavedDataWriterTest {
    @TempDir Path folder;

    @BeforeEach void session() { net.minecraft.SharedConstants.tryDetectVersion(); AtomicSavedDataWriter.newSession(); }
    @AfterEach void restore() { AtomicSavedDataWriter.mover = AtomicSavedDataWriter.ATOMIC; AtomicSavedDataWriter.newSession(); }

    private static CompoundTag value(int value) { CompoundTag tag = new CompoundTag(); tag.putInt("Value", value); return tag; }
    private static int read(File file) throws IOException { return NbtIo.readCompressed(file).getCompound("data").getInt("Value"); }
    private long staged() throws IOException { try (var files = Files.list(folder)) { return files.filter(p -> p.toString().endsWith(".tmp")).count(); } }

    @Test
    void firstReplacementOfASessionKeepsTheLoadedGenerationAsBackup() throws IOException {
        File store = folder.resolve("mcakingdoms_test.dat").toFile();
        AtomicSavedDataWriter.write(store, value(1));
        assertFalse(new File(store + ".bak").exists(), "A new store has nothing to back up");
        AtomicSavedDataWriter.newSession();
        AtomicSavedDataWriter.write(store, value(2));
        assertEquals(1, read(new File(store + ".bak")), "The backup is the generation this session loaded");
        AtomicSavedDataWriter.write(store, value(3));
        assertEquals(1, read(new File(store + ".bak")), "Later saves in the same session keep that backup");
        assertEquals(3, read(store));
        AtomicSavedDataWriter.newSession();
        AtomicSavedDataWriter.write(store, value(4));
        assertEquals(3, read(new File(store + ".bak")), "A new session backs up what it loaded");
        assertEquals(0, staged());
    }

    @Test
    void unsupportedAtomicReplacementLeavesThePreviousFileAndIsReported() throws IOException {
        File store = folder.resolve("mcakingdoms_atomic.dat").toFile();
        AtomicSavedDataWriter.write(store, value(7));
        AtomicSavedDataWriter.newSession();
        AtomicSavedDataWriter.mover = (from, to) -> { throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test"); };
        assertThrows(AtomicMoveNotSupportedException.class, () -> AtomicSavedDataWriter.write(store, value(8)));
        assertEquals(7, read(store), "No fallback may replace the store non-atomically");
        assertEquals(0, staged(), "The staged file is removed");
        var failures = AtomicSavedDataWriter.failures();
        assertEquals(1, failures.size());
        assertEquals("mcakingdoms_atomic.dat", failures.get(0).store());
        assertTrue(failures.get(0).reason().contains("atomically"), failures.get(0).reason());
    }

    @Test
    void failureAfterStagingDoesNotDestroyTheLastGoodSave() throws IOException {
        File store = folder.resolve("mcakingdoms_crash.dat").toFile();
        AtomicSavedDataWriter.write(store, value(11));
        AtomicSavedDataWriter.mover = (from, to) -> {
            if (to.toString().endsWith(".bak")) AtomicSavedDataWriter.ATOMIC.move(from, to);
            else throw new IOException("simulated crash before rename");
        };
        AtomicSavedDataWriter.newSession();
        assertThrows(IOException.class, () -> AtomicSavedDataWriter.write(store, value(12)));
        assertEquals(11, read(store));
        assertEquals(11, read(new File(store + ".bak")));
        assertEquals(0, staged());
    }

    @Test
    void truncatedStoreIsUnreadableButItsBackupIsWhole() throws IOException {
        File store = folder.resolve("mcakingdoms_truncated.dat").toFile();
        AtomicSavedDataWriter.write(store, value(21));
        AtomicSavedDataWriter.newSession();
        AtomicSavedDataWriter.write(store, value(22));
        byte[] bytes = Files.readAllBytes(store.toPath());
        Files.write(store.toPath(), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(Exception.class, () -> read(store), "A truncated store must fail to read, never decode partially");
        assertEquals(21, read(new File(store + ".bak")));
        // Next session: Minecraft would start this store empty; its first save must not replace the evidence or the backup.
        AtomicSavedDataWriter.newSession();
        byte[] damaged = Files.readAllBytes(store.toPath());
        IOException refused = assertThrows(IOException.class, () -> AtomicSavedDataWriter.write(store, value(0)));
        assertEquals(AtomicSavedDataWriter.UNREADABLE_REASON, refused.getMessage());
        assertArrayEquals(damaged, Files.readAllBytes(store.toPath()), "The unreadable file stays exactly as found");
        assertEquals(21, read(new File(store + ".bak")), "The last good backup is kept");
        assertThrows(IOException.class, () -> AtomicSavedDataWriter.write(store, value(0)), "Every later write this session is refused too");
        assertEquals(1, AtomicSavedDataWriter.failures().size(), "Reported once");
        assertEquals(0, staged());
    }

    @Test
    void durableStoreStaysDirtyUntilASaveSucceeds() throws IOException {
        var store = new DurableSavedData() {
            @Override public CompoundTag save(CompoundTag tag) { tag.putInt("Value", 5); return tag; }
        };
        File file = folder.resolve("mcakingdoms_dirty.dat").toFile();
        store.setDirty();
        AtomicSavedDataWriter.mover = (from, to) -> { throw new IOException("disk full"); };
        store.save(file);
        assertTrue(store.isDirty(), "A failed save must be retried, not forgotten");
        assertFalse(file.exists());
        AtomicSavedDataWriter.mover = AtomicSavedDataWriter.ATOMIC;
        store.save(file);
        assertFalse(store.isDirty());
        assertEquals(5, read(file));
    }
}
