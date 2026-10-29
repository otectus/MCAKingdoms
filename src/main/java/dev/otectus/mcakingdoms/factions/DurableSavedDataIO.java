package dev.otectus.mcakingdoms.factions;

import net.minecraft.nbt.CompoundTag;

import java.io.File;
import java.io.IOException;

/**
 * Faction data shares the single durable-write policy (H06). It used to fall back to a plain replacement when the
 * filesystem refused an atomic move; that fallback could leave a truncated file after a crash. Now the write fails, the
 * data stays dirty, and the durable listeners (which acknowledge companion receipts) wait for a save that succeeds.
 */
final class DurableSavedDataIO {
    private DurableSavedDataIO() {
    }

    static void write(File destination, CompoundTag data) throws IOException {
        dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter.write(destination, data);
    }
}
