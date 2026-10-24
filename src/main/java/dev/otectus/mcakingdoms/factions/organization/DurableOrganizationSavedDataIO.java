package dev.otectus.mcakingdoms.factions.organization;

import net.minecraft.nbt.CompoundTag;
import java.io.File;
import java.io.IOException;

final class DurableOrganizationSavedDataIO {
    private DurableOrganizationSavedDataIO() { }
    static void write(File destination, CompoundTag data) throws IOException {
        dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter.write(destination, data);
    }
}
