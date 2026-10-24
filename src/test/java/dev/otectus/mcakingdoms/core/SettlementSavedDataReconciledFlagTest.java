package dev.otectus.mcakingdoms.core;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** The start-up reconciliation marker is one optional root key; the schema, and so a downgrade, are unaffected. */
class SettlementSavedDataReconciledFlagTest {
    private static SettlementSavedData load(CompoundTag tag) throws Exception {
        Method load = SettlementSavedData.class.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        return (SettlementSavedData) load.invoke(null, tag);
    }

    @Test
    void schemaStaysOneAndTheMarkerRoundTrips() throws Exception {
        SettlementSavedData data = new SettlementSavedData();
        assertEquals(0, data.reconciled());
        data.markReconciled();
        assertTrue(data.isDirty(), "the marker is written with the next save");
        assertEquals(SettlementSavedData.RECONCILIATION_VERSION, data.reconciled());
        long revision = data.revision();
        data.markReconciled();
        assertEquals(revision, data.revision(), "the marker is not a settlement change");

        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(1, saved.getInt("Schema"));
        assertEquals(SettlementSavedData.RECONCILIATION_VERSION, saved.getInt("Reconciled"));
        assertEquals(SettlementSavedData.RECONCILIATION_VERSION, load(saved).reconciled());
    }

    @Test
    void aFileWithoutTheKeyLoadsAsNotYetReconciled() throws Exception {
        CompoundTag saved = new SettlementSavedData().save(new CompoundTag());
        saved.remove("Reconciled");
        assertEquals(0, load(saved).reconciled());
    }
}
