package dev.otectus.mcakingdoms.politics.dynasty;

import dev.otectus.mcakingdoms.api.politics.Politics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A5: every dynasty record survives a save and load; a newer or damaged file is kept read-only and unchanged. */
class DynastySavedDataTest {
    private static UUID id(int n) { return new UUID(7, n); }

    @Test void recordsRoundTrip() {
        var data = new DynastySavedData();
        var r = data.transaction();
        var ruler = new Politics.Person(id(1), Politics.Kind.NPC);
        var heir = new Politics.Person(id(2), Politics.Kind.PLAYER);
        r.laws.put("mcakingdoms:serenum", new DynastySavedData.LawChoice(new ResourceLocation("mcakingdoms", "equal_primogeniture"), id(9), 120));
        r.rulers.put("mcakingdoms:serenum", new DynastySavedData.Ruler(ruler, 40, false));
        var candidate = new SuccessionResolver.Candidate(id(2), Lineage.Relation.CHILD, Lineage.Stage.ADULT, true, "dynasty.mcakingdoms.candidate.eligible", "Ana");
        var k = new DynastySavedData.Case(id(3), "mcakingdoms:serenum", ruler, "Old King", DynastySavedData.Cause.DEATH, DynastySavedData.Status.CLAIMS_PENDING,
                DynastySavedData.Pending.ACCEPTANCE, new ResourceLocation("mcakingdoms", "equal_primogeniture"), heir, List.of(candidate), "dynasty.mcakingdoms.outcome.offered",
                100, 110, 5000, Set.of(id(4)), Set.of(id(5)), 1);
        r.cases.put(k.id(), k);
        r.renounced.put("mcakingdoms:serenum", new TreeSet<>(Set.of(id(6))));
        r.recognized.put("mcakingdoms:serenum", new TreeSet<>(Set.of(id(7))));
        r.priority.put("mcakingdoms:serenum", id(8));
        r.claims.put(id(10), new DynastySavedData.Claim(id(10), "mcakingdoms:serenum", id(8), "Bo", DynastySavedData.ClaimKind.CONTEST, DynastySavedData.ClaimState.UPHELD,
                "Mine by right", 50, id(9), 60, 1));
        r.titles.put(id(11), new DynastySavedData.Title(id(11), "mcakingdoms:serenum", heir, "Ana", new ResourceLocation("mcakingdoms", "first_rank"), id(9), "official",
                id(12), 70, 0, DynastySavedData.TitleState.ACTIVE, null, 0, 1));
        r.mourning.put("mcakingdoms:serenum", new DynastySavedData.Mourning(id(3), ruler, "Old King", 100, 24100));
        r.memorials.add(new DynastySavedData.Memorial(id(13), "mcakingdoms:serenum", ruler, "Old King", DynastySavedData.Cause.DEATH, 40, 100));
        r.receipts.put(id(14), new DynastySavedData.Receipt(id(9), "law|x", "dynasty.mcakingdoms.result.law"));
        CompoundTag saved = writeRecords(data, r);
        var loaded = DynastySavedData.load(saved);
        assertTrue(loaded.writable());
        var l = loaded.records();
        assertEquals(r.laws, l.laws);
        assertEquals(r.rulers, l.rulers);
        assertEquals(r.cases, l.cases);
        assertEquals(r.renounced, l.renounced);
        assertEquals(r.recognized, l.recognized);
        assertEquals(r.priority, l.priority);
        assertEquals(r.claims, l.claims);
        assertEquals(r.titles, l.titles);
        assertEquals(r.mourning, l.mourning);
        assertEquals(r.memorials, l.memorials);
        assertEquals(r.receipts, l.receipts);
        assertEquals(Optional.of(k), l.current("mcakingdoms:serenum"));
    }

    /** Saves {@code records} the way a commit writes them, without a server. */
    private static CompoundTag writeRecords(DynastySavedData data, DynastySavedData.Records records) {
        try {
            var field = DynastySavedData.class.getDeclaredField("records");
            field.setAccessible(true);
            field.set(data, records);
        } catch (ReflectiveOperationException impossible) { throw new AssertionError(impossible); }
        return data.save(new CompoundTag());
    }

    @Test void aNewerSchemaIsKeptReadOnlyAndUnchanged() {
        var tag = new CompoundTag();
        tag.putInt("Schema", 99); tag.putString("Future", "kept");
        var loaded = DynastySavedData.load(tag);
        assertFalse(loaded.writable());
        assertEquals(tag, loaded.save(new CompoundTag()));
    }

    @Test void aDamagedFileIsKeptReadOnlyAndUnchanged() {
        var tag = new CompoundTag();
        tag.putInt("Schema", 1);
        var cases = new net.minecraft.nbt.ListTag();
        var broken = new CompoundTag(); broken.putString("Status", "NOT_A_STATUS");
        cases.add(broken);
        tag.put("Cases", cases);
        var loaded = DynastySavedData.load(tag);
        assertFalse(loaded.writable());
        assertEquals(tag, loaded.save(new CompoundTag()));
    }
}
