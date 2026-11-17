package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.settlement.SpatialSettlementIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUD-12: settlement geometry is bounded before anything is allocated. Extreme or malformed bounds are refused quickly at
 * registration or isolated when loaded, never enumerated; ordinary bounds, negative coordinates and the largest supported
 * span still index and are found.
 */
class SettlementGeometryTest {
    /** One key for every record: the game interns level keys, and the index compares them as the game does. */
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> OVERWORLD = TestKeys.overworld();

    private static SettlementRecord record(SettlementBounds bounds) {
        SettlementRecord r = new SettlementRecord();
        r.id = UUID.randomUUID();
        r.dimension = OVERWORLD;
        r.anchor = new BlockPos(bounds.minX(), 64, bounds.minZ());
        r.radius = 32;
        r.bounds = bounds;
        r.kingdomId = new ResourceLocation("mcakingdoms", "serenum");
        r.displayName = "Geometry " + r.id;
        r.slug = new ResourceLocation("mcakingdoms", "geometry_" + Long.toString(r.id.getMostSignificantBits() & 0xffffff, 36));
        r.biomeAtCreation = new ResourceLocation("minecraft:plains");
        r.assignmentSource = AssignmentSource.EXACT_BIOME;
        r.detectionSource = DetectionSource.MANUAL;
        r.sourceId = new ResourceLocation("test:manual");
        r.sourceKey = r.id.toString();
        return r;
    }

    @Test void radiiThatLeaveTheCoordinateRangeAreRefusedNotWrapped() {
        assertThrows(IllegalArgumentException.class, () -> SettlementBounds.around(new BlockPos(Integer.MAX_VALUE - 5, 64, 0), 10));
        assertThrows(IllegalArgumentException.class, () -> SettlementBounds.around(new BlockPos(Integer.MIN_VALUE + 5, 64, 0), 10));
        assertFalse(SettlementBounds.around(BlockPos.ZERO, Integer.MAX_VALUE).supported(), "a radius that fits the coordinates can still exceed the supported span");
        assertThrows(IllegalArgumentException.class, () -> SettlementBounds.around(BlockPos.ZERO, -1));
        var negative = SettlementBounds.around(new BlockPos(-30_000_000, 64, -30_000_000), 64);
        assertEquals(129, negative.spanX());
        assertTrue(negative.supported());
    }

    @Test void spansAreComputedWithoutOverflow() {
        var whole = new SettlementBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(1L << 32, whole.spanX());
        assertFalse(whole.supported());
        assertTrue(whole.chunkCount() > 0, "the chunk count of the whole range must not overflow");
        assertThrows(IllegalArgumentException.class, whole::requireSupported);
        var largest = new SettlementBounds(0, 0, SettlementBounds.MAX_SPAN - 1, SettlementBounds.MAX_SPAN - 1);
        assertTrue(largest.supported());
        assertFalse(new SettlementBounds(0, 0, SettlementBounds.MAX_SPAN, 0).supported());
    }

    @Test void registrationRefusesExtremeCandidatesWithTheirSpan() {
        var huge = new SettlementBounds(-1_000_000, -1_000_000, 1_000_000, 1_000_000);
        var refused = assertThrows(IllegalArgumentException.class, () -> SettlementCandidate.external(TestKeys.overworld(), BlockPos.ZERO, 32, huge,
                new ResourceLocation("test:addon"), "k", null, java.util.Map.of(), null));
        assertTrue(refused.getMessage().contains("2000001"), "the refusal must name the measured span: " + refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new SettlementCandidate(TestKeys.overworld(), BlockPos.ZERO, SettlementBounds.MAX_SPAN + 1,
                SettlementBounds.around(BlockPos.ZERO, 8), new ResourceLocation("test:addon"), "k", DetectionSource.EXTERNAL, Optional.empty(), Optional.empty(),
                java.util.Map.of(), Optional.empty()));
    }

    @Test void theIndexNeverEnumeratesALargeRectangle() {
        var index = new SpatialSettlementIndex();
        var small = record(SettlementBounds.around(new BlockPos(-100, 64, -100), 40));
        var large = record(new SettlementBounds(10_000, 10_000, 10_000 + SettlementBounds.MAX_SPAN - 1, 10_000 + SettlementBounds.MAX_SPAN - 1));
        var extreme = record(new SettlementBounds(-2_000_000_000, -2_000_000_000, 2_000_000_000, 2_000_000_000));
        long started = System.nanoTime();
        index.rebuild(java.util.List.of(small.snapshot(), large.snapshot(), extreme.snapshot()));
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "a rebuild with extreme bounds must stay quick");
        assertTrue(index.entries() <= SpatialSettlementIndex.INDEXED_CHUNKS + 2, "the index enumerated a large rectangle: " + index.entries());
        assertTrue(index.at(OVERWORLD, new BlockPos(-100, 64, -100)).contains(small.id));
        assertTrue(index.at(OVERWORLD, new BlockPos(15_000, 64, 15_000)).contains(large.id), "a large supported settlement must still be found");
        assertFalse(index.at(OVERWORLD, new BlockPos(0, 64, 0)).contains(extreme.id), "an unsupported record must not be indexed");
        assertEquals(java.util.Set.of(extreme.id), index.isolated().keySet(), "an unsupported record must be reported for repair");
        index.remove(large.snapshot());
        index.remove(extreme.snapshot());
        assertFalse(index.at(OVERWORLD, new BlockPos(15_000, 64, 15_000)).contains(large.id));
        assertTrue(index.isolated().isEmpty());
        index.remove(small.snapshot());
        assertEquals(0, index.entries());
    }
}
