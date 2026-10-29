package dev.otectus.mcakingdoms.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * The one durable-write policy of every MCA: Kingdoms store (H06). A save is staged in a temporary file beside the
 * store, forced to disk, then moved over the store with an atomic replacement; the directory is forced where the
 * platform allows. There is no non-atomic fallback: a filesystem that cannot replace atomically leaves the previous
 * file untouched, the write is reported as failed (the caller keeps its change uncommitted or its data dirty) and Server
 * health names the store. Before the first replacement of a store in a server session, the file that session loaded is
 * kept as {@code <store>.dat.bak}, the last generation known to have loaded; restoring it is a stopped-server step in
 * docs/Save-Durability.md. Minecraft starts a store empty when its file exists but cannot be decoded; such a file is never
 * overwritten or copied over the backup: every write to it is refused for the session, so the store's changes are
 * refused instead of replacing the evidence, and Server health says how to restore the backup.
 */
public final class AtomicSavedDataWriter {
    /** Moves a staged file over its destination; replaced in tests to inject failures. */
    @FunctionalInterface
    interface Mover { void move(Path from, Path to) throws IOException; }

    /** Why a write failed: the filesystem refused an atomic replacement, the existing file is unreadable, or another I/O error. */
    public enum Cause { NOT_ATOMIC, UNREADABLE, IO }
    /** One failed write kept for Server health: the store file, the cause, the reason for logs and when (epoch milliseconds). */
    public record Failure(String store, Cause cause, String reason, long at) { }

    static final Mover ATOMIC = (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    static volatile Mover mover = ATOMIC;
    private static final Set<Path> BACKED_UP = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Path> UNREADABLE = Collections.synchronizedSet(new HashSet<>());
    static final String UNREADABLE_REASON = "its file could not be read when the world loaded, so it is protected from being overwritten";
    private static final Deque<Failure> FAILURES = new ArrayDeque<>();
    private static final int FAILURE_HISTORY = 16;

    private AtomicSavedDataWriter() {
    }

    public static void write(File destination, CompoundTag data) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        writeRoot(destination, root);
    }

    /**
     * Writes a complete saved-data root ({@code data} plus its {@code DataVersion}) unchanged, with the
     * same atomic replacement as {@link #write}. Used to carry a file over without re-stamping its version.
     */
    public static void writeRoot(File destination, CompoundTag root) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        try {
            protectUnreadable(destination.toPath());
            backup(destination.toPath());
            File temporary = File.createTempFile(destination.getName(), ".tmp", parent);
            boolean moved = false;
            try {
                NbtIo.writeCompressed(root, temporary);
                try (FileChannel channel = FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                mover.move(temporary.toPath(), destination.toPath());
                moved = true;
                forceDirectory(parent);
            } finally {
                if (!moved) Files.deleteIfExists(temporary.toPath());
            }
        } catch (IOException failure) {
            record(destination.getName(), failure);
            throw failure;
        }
    }

    /** Refuses to replace a store file that does not decode: it is evidence, and its backup is the last good copy. */
    private static void protectUnreadable(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        if (UNREADABLE.contains(absolute)) throw new IOException(UNREADABLE_REASON);
        if (!Files.isRegularFile(absolute) || BACKED_UP.contains(absolute)) return;
        try {
            NbtIo.readCompressed(absolute.toFile());
        } catch (IOException | RuntimeException unreadable) {
            UNREADABLE.add(absolute);
            throw new IOException(UNREADABLE_REASON, unreadable);
        }
    }

    /**
     * Keeps the store as it was before this session's first replacement. The copy is itself staged and moved
     * atomically, so a crash leaves either the earlier backup or the new one, never half of either.
     */
    private static void backup(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || BACKED_UP.contains(absolute)) return;
        Path backup = absolute.resolveSibling(absolute.getFileName() + ".bak");
        Path staged = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".bak.tmp");
        boolean moved = false;
        try {
            Files.copy(absolute, staged, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            mover.move(staged, backup);
            moved = true;
            BACKED_UP.add(absolute);
        } finally {
            if (!moved) Files.deleteIfExists(staged);
        }
    }

    private static void forceDirectory(File parent) {
        if (parent == null) return;
        try (FileChannel directory = FileChannel.open(parent.toPath(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException ignored) {
            // The replacement is complete; some platforms cannot force directory handles.
        }
    }

    private static void record(String store, IOException failure) {
        Cause cause = failure instanceof java.nio.file.AtomicMoveNotSupportedException ? Cause.NOT_ATOMIC
                : UNREADABLE_REASON.equals(failure.getMessage()) ? Cause.UNREADABLE : Cause.IO;
        String reason = cause == Cause.NOT_ATOMIC ? "the filesystem cannot replace files atomically" : cause == Cause.UNREADABLE ? UNREADABLE_REASON
                : failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        synchronized (FAILURES) {
            if (FAILURES.stream().anyMatch(f -> f.store().equals(store) && f.reason().equals(reason))) return;
            FAILURES.addLast(new Failure(store, cause, reason, System.currentTimeMillis()));
            while (FAILURES.size() > FAILURE_HISTORY) FAILURES.removeFirst();
        }
    }

    /** The most recent failed writes this session, oldest first, for Server health. */
    public static List<Failure> failures() {
        synchronized (FAILURES) {
            return List.copyOf(FAILURES);
        }
    }

    /** A new server session: backups are taken again before each store's next replacement; old failures are cleared. */
    public static void newSession() {
        BACKED_UP.clear();
        UNREADABLE.clear();
        synchronized (FAILURES) {
            FAILURES.clear();
        }
    }
}
