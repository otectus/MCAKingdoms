package dev.otectus.mcakingdoms.compat.townstead;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Townstead authority store round-trips, and keeps a newer or damaged file read-only and unchanged. */
class TownsteadSavedDataTest {
    @Test
    void decisionsAndBaselinesRoundTrip() {
        UUID settlement = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", 1);
        tag.putLong("Revision", 4);
        ListTag decisions = new ListTag();
        CompoundTag d = new CompoundTag();
        d.putUUID("Settlement", settlement); d.putString("Owner", "TOWNSTEAD"); d.putString("Faction", "townstead:ash_council");
        d.putInt("Governance", 77); d.putString("Version", "0.8.0+1.20.1"); d.putLong("At", 1200); d.putString("Note", "released at the council's request");
        decisions.add(d);
        tag.put("Decisions", decisions);
        ListTag baselines = new ListTag();
        CompoundTag b = new CompoundTag();
        b.putUUID("Settlement", settlement); b.putString("Version", "0.8.0+1.20.1"); b.putInt("Revision", 2); b.putLong("Day", 31); b.putLong("At", 900);
        baselines.add(b);
        tag.put("Baselines", baselines);
        var data = TownsteadSavedData.load(tag);
        assertTrue(data.writable());
        var decision = data.decision(settlement).orElseThrow();
        assertEquals(TownsteadSavedData.Owner.TOWNSTEAD, decision.owner());
        assertEquals("townstead:ash_council", decision.faction());
        assertNull(decision.decidedBy(), "decided from the console");
        assertEquals(31, data.baseline(settlement).orElseThrow().townsteadDay());
        assertEquals(tag, data.save(new CompoundTag()));
    }

    /** AUD-07: the baselines bound is relieved by dropping baselines of settlements that no longer exist; never while read-only. */
    @Test
    void baselinesOfSettlementsThatNoLongerExistAreDropped() {
        UUID live = UUID.randomUUID(), merged = UUID.randomUUID();
        var data = new TownsteadSavedData();
        data.baseline(new TownsteadSavedData.Baseline(live, "0.8.0+1.20.1", 2, 31, 900));
        data.baseline(new TownsteadSavedData.Baseline(merged, "0.8.0+1.20.1", 2, 12, 400));
        assertEquals(1, data.retainBaselines(live::equals));
        assertTrue(data.baseline(live).isPresent());
        assertTrue(data.baseline(merged).isEmpty());
        assertEquals(0, data.retainBaselines(live::equals), "a second pass found more to drop");
        CompoundTag future = new CompoundTag();
        future.putInt("Schema", 99);
        assertEquals(0, TownsteadSavedData.load(future).retainBaselines(id -> false), "a read-only store was changed");
    }

    @Test
    void aNewerSchemaOrADamagedFileIsKeptReadOnlyAndUnchanged() {
        CompoundTag future = new CompoundTag();
        future.putInt("Schema", 99);
        future.putString("Unknown", "kept");
        var data = TownsteadSavedData.load(future);
        assertFalse(data.writable());
        assertEquals(future, data.save(new CompoundTag()));
        CompoundTag damaged = new CompoundTag();
        damaged.putInt("Schema", 1);
        ListTag decisions = new ListTag();
        CompoundTag bad = new CompoundTag();
        bad.putString("Owner", "NOBODY");
        decisions.add(bad);
        damaged.put("Decisions", decisions);
        var refused = TownsteadSavedData.load(damaged);
        assertFalse(refused.writable());
        assertEquals(damaged, refused.save(new CompoundTag()));
        assertTrue(refused.decisions().isEmpty());
    }
}
