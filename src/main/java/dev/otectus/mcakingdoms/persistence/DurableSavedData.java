package dev.otectus.mcakingdoms.persistence;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.io.IOException;

/**
 * A store whose autosave uses {@link AtomicSavedDataWriter} instead of vanilla's direct overwrite (H06). Vanilla writes
 * the file in place and forgets a failed write; here the previous file stays whole until the new one is complete, and
 * a store whose write failed stays dirty so the next save retries it. Stores that commit changes durably as they make
 * them use the same writer; this covers their periodic saves.
 */
public abstract class DurableSavedData extends SavedData {
    @Override
    public void save(File file) {
        if (!isDirty()) return;
        try {
            AtomicSavedDataWriter.write(file, save(new CompoundTag()));
            setDirty(false);
        } catch (IOException failure) {
            LogUtils.getLogger().error("[MCA: Kingdoms] Could not save {}; the previous file is unchanged and the save is retried", file.getName(), failure);
        }
    }
}
