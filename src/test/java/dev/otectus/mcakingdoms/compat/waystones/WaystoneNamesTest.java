package dev.otectus.mcakingdoms.compat.waystones;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAY-01: the settlement name a waystone gets is literal, legal and collision-safe; a number once given is kept; an
 * unusable name leaves Waystones' own name; the naming records survive a save and protect what a player chose.
 */
class WaystoneNamesTest {
    @Test void theBaseIsTheSettlementNameCleanedButLiteral() {
        assertEquals(Optional.of("Oakfield"), WaystoneNames.base("Oakfield"));
        assertEquals(Optional.of("Saint Anne's Rest"), WaystoneNames.base("  Saint\tAnne's \n Rest  "));
        assertEquals(Optional.of("Oakfield"), WaystoneNames.base("Oak\u0000field​"), "control and format characters must be removed");
        assertEquals(Optional.of("村庄"), WaystoneNames.base("村庄"), "names stay literal, never translated");
        assertEquals(Optional.empty(), WaystoneNames.base("   \u0007 "));
        assertEquals(Optional.empty(), WaystoneNames.base(null));
    }

    @Test void longNamesAreTruncatedWithoutSplittingAPairAndLeaveRoomForANumber() {
        String emoji = "🏰"; // a castle: one code point, two UTF-16 characters
        String longName = "A".repeat(WaystoneNames.NATIVE_LIMIT - WaystoneNames.SUFFIX_ROOM - 1) + emoji + "tail";
        String base = WaystoneNames.base(longName).orElseThrow();
        assertTrue(base.length() <= WaystoneNames.NATIVE_LIMIT - WaystoneNames.SUFFIX_ROOM);
        assertFalse(Character.isHighSurrogate(base.charAt(base.length() - 1)), "a surrogate pair was split");
        String numbered = WaystoneNames.numbered(base, WaystoneNames.SUFFIX_LIMIT);
        assertTrue(numbered.length() <= WaystoneNames.NATIVE_LIMIT, "the largest number does not fit the native limit");
    }

    @Test void collisionsTakeTheSmallestFreeNumberIgnoringCase() {
        assertEquals(new WaystoneNames.Assignment("Oakfield", 1), WaystoneNames.assign("Oakfield", List.of("Elmstead")).orElseThrow());
        assertEquals(new WaystoneNames.Assignment("Oakfield (2)", 2), WaystoneNames.assign("Oakfield", List.of("oakfield")).orElseThrow());
        assertEquals(new WaystoneNames.Assignment("Oakfield (3)", 3), WaystoneNames.assign("Oakfield", List.of("Oakfield", "Oakfield (2)", "Oakfield (4)")).orElseThrow());
        // A player's own stone named "Oakfield (2)" is avoided as well: the next number is used, not the player's name.
        assertEquals("Oakfield (3)", WaystoneNames.assign("Oakfield", List.of("Oakfield", "OAKFIELD (2)")).orElseThrow().name());
        var everything = new ArrayList<String>();
        for (int n = 1; n <= WaystoneNames.SUFFIX_LIMIT; n++) everything.add(WaystoneNames.numbered("Full", n));
        assertTrue(WaystoneNames.assign("Full", everything).isEmpty(), "with no free number the native name must stay");
    }

    /** VAL-01: a backfill keeps one index of names in use and adds each assignment to it, instead of rereading the registry. */
    @Test void oneIndexServesManyAssignmentsInOrder() {
        var used = new java.util.HashSet<String>(List.of(WaystoneNames.key(" OAKFIELD ")));
        var names = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var next = WaystoneNames.assign("Oakfield", used::contains).orElseThrow();
            names.add(next.name());
            used.add(WaystoneNames.key(next.name()));
        }
        assertEquals(List.of("Oakfield (2)", "Oakfield (3)", "Oakfield (4)"), names);
        assertEquals(WaystoneNames.assign("Oakfield", List.of("oakfield", "Oakfield (2)")), WaystoneNames.assign("Oakfield",
                key -> key.equals(WaystoneNames.key("oakfield")) || key.equals(WaystoneNames.key("Oakfield (2)"))), "both forms agree");
        assertEquals("", WaystoneNames.key(null), "an unnamed stone compares as the empty name");
    }

    @Test void twoSettlementsWithOneNameInDifferentDimensionsDoNotShareAName() {
        var first = WaystoneNames.assign("Oakfield", List.of()).orElseThrow();
        var second = WaystoneNames.assign("Oakfield", List.of(first.name())).orElseThrow();
        assertNotEquals(first.name(), second.name());
    }

    @Test void theRecordsSurviveASaveAndReserveTheirNames() {
        net.minecraft.SharedConstants.tryDetectVersion();
        var data = new WaystonesNamingData();
        var tag = new CompoundTag();
        tag.putInt("Schema", WaystonesNamingData.SCHEMA);
        var list = new net.minecraft.nbt.ListTag();
        UUID managed = UUID.randomUUID(), detached = UUID.randomUUID();
        for (var e : List.of(new WaystonesNamingData.Entry(managed, "minecraft:overworld", new BlockPos(1, 64, 1), UUID.randomUUID(), "Oakfield", "Oakfield (2)", 2,
                WaystonesNamingData.Stage.MANAGED, 5, 6, ""), new WaystonesNamingData.Entry(detached, "minecraft:overworld", new BlockPos(9, 64, 9), null, "", "", 0,
                WaystonesNamingData.Stage.DETACHED, 5, 6, "named by Alex"))) {
            var t = new CompoundTag();
            t.putUUID("Stone", e.stone()); t.putString("Dimension", e.dimension()); t.putLong("Pos", e.pos().asLong());
            if (e.settlement() != null) t.putUUID("Settlement", e.settlement());
            t.putString("Base", e.base()); t.putString("Name", e.name()); t.putInt("Number", e.number()); t.putString("Stage", e.stage().name());
            t.putLong("CreatedAt", e.createdAt()); t.putLong("UpdatedAt", e.updatedAt()); t.putString("Detail", e.detail());
            list.add(t);
        }
        tag.put("Entries", list);
        var loaded = WaystonesNamingData.load(tag);
        assertTrue(loaded.writable());
        assertEquals(Set.of("Oakfield (2)"), loaded.reserved(), "a managed name must stay reserved, so it is never given twice");
        assertEquals(2, loaded.entry(managed).orElseThrow().number(), "the number must survive a restart");
        assertEquals(WaystonesNamingData.Stage.DETACHED, loaded.entry(detached).orElseThrow().stage());
        assertEquals(tag.getList("Entries", 10).size(), WaystonesNamingData.load(loaded.save(new CompoundTag())).entries().size());
        assertTrue(data.entries().isEmpty());
    }

    @Test void aNewerOrDamagedFileIsKeptReadOnlyAndUnchanged() {
        var newer = new CompoundTag();
        newer.putInt("Schema", WaystonesNamingData.SCHEMA + 1);
        newer.putString("Future", "kept");
        var data = WaystonesNamingData.load(newer);
        assertFalse(data.writable());
        assertEquals(newer, data.save(new CompoundTag()));
        var damaged = new CompoundTag();
        damaged.putInt("Schema", 1);
        var list = new net.minecraft.nbt.ListTag();
        var bad = new CompoundTag(); bad.putString("Stage", "NOT_A_STAGE"); list.add(bad);
        damaged.put("Entries", list);
        assertFalse(WaystonesNamingData.load(damaged).writable());
    }
}
