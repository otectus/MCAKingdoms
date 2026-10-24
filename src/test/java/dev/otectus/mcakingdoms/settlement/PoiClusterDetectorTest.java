package dev.otectus.mcakingdoms.settlement;

import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class PoiClusterDetectorTest {
    private static final PoiClusterDetector.Settings DEFAULTS = new PoiClusterDetector.Settings(48, 5, 40, true, true);

    private static PoiClusterDetector.Poi bed(int x, int y, int z) {
        return new PoiClusterDetector.Poi(new BlockPos(x, y, z), true);
    }

    private static PoiClusterDetector.Poi workstation(int x, int y, int z) {
        return new PoiClusterDetector.Poi(new BlockPos(x, y, z), false);
    }

    /** Five points 25 blocks apart along x: one cluster under a 40-block link, centroid (50, 64, 0). */
    private static List<PoiClusterDetector.Poi> street(int y) {
        return IntStream.range(0, 5).mapToObj(i -> i == 0 ? bed(i * 25, y, 0) : workstation(i * 25, y, 0)).toList();
    }

    @Test void separatedGroupsFormSeparateClusters() {
        var positions = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 4), new BlockPos(20, 64, 8),
                new BlockPos(300, 64, 0), new BlockPos(310, 64, 6), new BlockPos(322, 64, 2));
        var clusters = PoiClusterDetector.clusters(positions, 32);
        assertEquals(2, clusters.size());
        assertTrue(clusters.stream().allMatch(c -> c.size() == 3));
    }
    @Test void aChainOfNearbyPointsIsOneCluster() {
        var positions = List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0), new BlockPos(60, 64, 0), new BlockPos(90, 64, 0), new BlockPos(120, 64, 0));
        assertEquals(1, PoiClusterDetector.clusters(positions, 32).size());
        assertEquals(5, PoiClusterDetector.clusters(positions, 20).size());
    }

    @Test void boundsFollowTheClusterExtentInsteadOfASquare() {
        var cluster = PoiClusterDetector.recognise(street(64), DEFAULTS, (x, z) -> 66).get(0);
        assertEquals(new BlockPos(50, 64, 0), cluster.anchor());
        // 0..100 along x plus the 16-block edge; along z the 32-block minimum, not the 66 a square would give.
        assertEquals(new SettlementBounds(-16, -32, 116, 32), cluster.bounds());
        assertEquals(66, cluster.radius());
        var lone = PoiClusterDetector.extent(List.of(bed(0, 64, 0)), BlockPos.ZERO.atY(64));
        assertEquals(new SettlementBounds(-32, -32, 32, 32), lone, "a tight cluster still reaches the minimum radius");
    }

    @Test void distanceIsHorizontalSoHighGroundIsSeenFromSeaLevel() {
        BlockPos seaLevel = new BlockPos(8, 63, 8);
        assertTrue(PoiClusterDetector.withinHorizontally(seaLevel, new BlockPos(8, 110, 8), 48), "47 blocks straight up is in range");
        assertTrue(PoiClusterDetector.withinHorizontally(seaLevel, new BlockPos(8 + 33, 111, 8 + 33), 48));
        assertFalse(PoiClusterDetector.withinHorizontally(seaLevel, new BlockPos(8 + 34, 63, 8 + 34), 48));
    }

    @Test void aSettlementNeedsEnoughPointsAndABedOrBell() {
        assertTrue(PoiClusterDetector.recognise(street(64).subList(0, 4), DEFAULTS, (x, z) -> 66).isEmpty(), "four points are too few");
        var workstations = IntStream.range(0, 5).mapToObj(i -> workstation(i * 25, 64, 0)).toList();
        assertTrue(PoiClusterDetector.recognise(workstations, DEFAULTS, (x, z) -> 66).isEmpty(), "job sites alone are not a home");
        assertEquals(1, PoiClusterDetector.recognise(workstations, new PoiClusterDetector.Settings(48, 5, 40, false, true),
                (x, z) -> 66).size(), "the signal can be switched off");
    }

    @Test void clustersDeepBelowTheSurfaceAreNotSettlements() {
        assertTrue(PoiClusterDetector.recognise(street(30), DEFAULTS, (x, z) -> 64).isEmpty(), "34 blocks under the surface");
        assertEquals(1, PoiClusterDetector.recognise(street(40), DEFAULTS, (x, z) -> 64).size(), "exactly 24 blocks under is kept");
        assertEquals(1, PoiClusterDetector.recognise(street(30), new PoiClusterDetector.Settings(48, 5, 40, true, false),
                (x, z) -> 64).size(), "the surface check can be switched off");
        assertEquals(1, PoiClusterDetector.recognise(street(30), DEFAULTS, (x, z) -> -64).size(),
                "an unloaded centroid column reports the world floor and filters nothing");
    }

    @Test void linkDistanceComesFromTheSettings() {
        assertEquals(1, PoiClusterDetector.recognise(street(64), DEFAULTS, (x, z) -> 66).size());
        assertTrue(PoiClusterDetector.recognise(street(64), new PoiClusterDetector.Settings(48, 5, 20, true, true),
                (x, z) -> 66).isEmpty(), "a 20-block link breaks the street into single points");
        assertEquals(PoiClusterDetector.LINK_DISTANCE, PoiClusterDetector.linkDistance(),
                "without a loaded config the former constant applies");
    }

    @Test void aClusterJoinsTheVillageMostOfItsPointsLieIn() {
        var dimension = dev.otectus.mcakingdoms.core.TestKeys.overworld();
        var west = new VillageFootprint(dimension, new ResourceLocation("minecraft", "village_plains"), new ChunkPos(0, 0),
                new BoundingBox(-10, 50, -20, 60, 90, 20));
        var east = new VillageFootprint(dimension, new ResourceLocation("minecraft", "village_plains"), new ChunkPos(8, 0),
                new BoundingBox(61, 50, -20, 200, 90, 20));
        var cluster = PoiClusterDetector.recognise(street(64), DEFAULTS, (x, z) -> 66).get(0);
        assertEquals(Optional.of(west), PoiClusterDetector.vote(cluster, pos -> Optional.of(pos.getX() <= 60 ? west : east)),
                "three of five points lie in the western village");
        assertEquals(Optional.of(east), PoiClusterDetector.vote(cluster, pos -> pos.getX() > 60 ? Optional.of(east) : Optional.empty()),
                "points no village claims do not vote");
        assertTrue(PoiClusterDetector.vote(cluster, pos -> Optional.empty()).isEmpty());
    }
}
