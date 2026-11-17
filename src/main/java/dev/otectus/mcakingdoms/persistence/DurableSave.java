package dev.otectus.mcakingdoms.persistence;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Writes one store now instead of at the next autosave, with the same atomic policy ({@link AtomicSavedDataWriter}).
 * Used where a later step may only proceed once an earlier change is on disk, such as a dependent store reporting
 * that it carried a merged settlement over (AUD-01). Call it only for a writable store: a read-only store's
 * {@code save} returns its preserved payload, which is never rewritten here.
 */
public final class DurableSave {
    private DurableSave() {
    }

    /** True when {@code data} (overworld storage file {@code name}) reached disk; it stays dirty otherwise, so autosave retries. */
    public static boolean now(MinecraftServer server, SavedData data, String name) {
        try {
            FaultPoints.reach("store.write." + name);
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat").toFile(), data.save(new CompoundTag()));
            data.setDirty(false);
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            LogUtils.getLogger().error("[MCA: Kingdoms] {}.dat could not be written now; it is kept in memory and saved again later", name, failure);
            data.setDirty(true);
            return false;
        }
    }
}
