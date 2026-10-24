package dev.otectus.mcakingdoms.settlement;

import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementDetector;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;

/**
 * Recognises villages from clusters of village points of interest in loaded chunks. A cluster needs enough points, a
 * bed or bell, and must lie near the surface; one inside a generated village's footprint becomes a candidate for that
 * village, any other stands for a settlement of its own.
 */
public final class PoiClusterDetector implements SettlementDetector {
    private static final ResourceLocation ID = new ResourceLocation(McaKingdomsApi.MOD_ID, "poi_cluster");
    /** Village points of interest closer than this belong to the same settlement, if {@code poiLinkDistance} is unavailable. */
    static final int LINK_DISTANCE = 40;
    /** Blocks added around a cluster's outermost points. */
    static final int EDGE_MARGIN = 16;
    /** A cluster's bounds reach at least this far from its anchor. */
    static final int MINIMUM_RADIUS = 32;
    /** A cluster whose mean height lies further below the surface than this is not a settlement. */
    static final int SURFACE_DEPTH = 24;

    private final VillageFootprints footprints;

    public PoiClusterDetector(VillageFootprints footprints) {
        this.footprints = footprints;
    }

    /** One village point of interest; {@code homeOrMeeting} for beds and bells. */
    record Poi(BlockPos pos, boolean homeOrMeeting) {
    }

    /** A recognised cluster: its points, their centroid, and bounds that follow their real extent. */
    record Cluster(List<Poi> pois, BlockPos anchor, SettlementBounds bounds, int radius) {
    }

    record Settings(int searchRadius, int minimumCount, int linkDistance, boolean requireHomeOrMeeting,
                    boolean surfaceOnly) {
        static Settings current() {
            return new Settings(McaKingdomsConfig.POI_SEARCH_RADIUS.get(), McaKingdomsConfig.POI_MINIMUM_COUNT.get(),
                    PoiClusterDetector.linkDistance(), McaKingdomsConfig.POI_REQUIRE_HOME_OR_MEETING.get(), McaKingdomsConfig.POI_SURFACE_ONLY.get());
        }
    }

    /** {@code poiLinkDistance}, or {@link #LINK_DISTANCE} while the config is not loaded. */
    public static int linkDistance() {
        try {
            return McaKingdomsConfig.POI_LINK_DISTANCE.get();
        } catch (IllegalStateException notLoaded) {
            return LINK_DISTANCE;
        }
    }

    @Override
    public Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks) {
        Settings settings = Settings.current();
        int scanRadius = Math.max(settings.searchRadius(), radiusChunks * 16);
        // Horizontal distance only: a scan centred at sea level must still see a village on high ground.
        List<Poi> pois = level.getPoiManager()
                .getInSquare(holder -> holder.is(PoiTypeTags.VILLAGE), center, scanRadius, PoiManager.Occupancy.ANY)
                .filter(record -> withinHorizontally(center, record.getPos(), scanRadius))
                .filter(record -> level.hasChunk(record.getPos().getX() >> 4, record.getPos().getZ() >> 4))
                .map(record -> new Poi(record.getPos().immutable(),
                        record.getPoiType().is(PoiTypes.HOME) || record.getPoiType().is(PoiTypes.MEETING)))
                .toList();
        boolean resolve = McaKingdomsConfig.VILLAGE_IDENTITY.get() == McaKingdomsConfig.VillageIdentity.STRUCTURE;
        int margin = McaKingdomsConfig.STRUCTURE_MERGE_MARGIN.get();
        List<SettlementCandidate> candidates = new ArrayList<>();
        for (Cluster cluster : recognise(pois, settings,
                (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z))) {
            Optional<VillageFootprint> village = resolve
                    ? vote(cluster, pos -> footprints.at(level, pos, margin)) : Optional.empty();
            candidates.add(candidate(level, cluster, village));
        }
        return candidates.stream();
    }

    /** The clusters that may stand for a settlement: enough points, a bed or bell, and near the surface. */
    static List<Cluster> recognise(List<Poi> pois, Settings settings, IntBinaryOperator surfaceHeight) {
        if (pois.size() < settings.minimumCount()) return List.of();
        List<Cluster> result = new ArrayList<>();
        for (List<Poi> members : clusters(pois, Poi::pos, settings.linkDistance())) {
            if (members.size() < settings.minimumCount()) continue;
            if (settings.requireHomeOrMeeting() && members.stream().noneMatch(Poi::homeOrMeeting)) continue;
            BlockPos anchor = centroid(members);
            if (settings.surfaceOnly()
                    && anchor.getY() < surfaceHeight.applyAsInt(anchor.getX(), anchor.getZ()) - SURFACE_DEPTH) continue;
            SettlementBounds bounds = extent(members, anchor);
            int radius = Math.max(Math.max(anchor.getX() - bounds.minX(), bounds.maxX() - anchor.getX()),
                    Math.max(anchor.getZ() - bounds.minZ(), bounds.maxZ() - anchor.getZ()));
            result.add(new Cluster(List.copyOf(members), anchor, bounds, radius));
        }
        return result;
    }

    /**
     * The village most of the cluster's points lie in; a tie goes to the village whose box holds the centroid, then to
     * the lowest key. Points no village claims do not vote.
     */
    static Optional<VillageFootprint> vote(Cluster cluster, Function<BlockPos, Optional<VillageFootprint>> resolve) {
        Map<String, Integer> votes = new HashMap<>();
        Map<String, VillageFootprint> villages = new HashMap<>();
        for (Poi poi : cluster.pois()) {
            resolve.apply(poi.pos()).ifPresent(village -> {
                votes.merge(village.key(), 1, Integer::sum);
                villages.putIfAbsent(village.key(), village);
            });
        }
        return votes.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> villages.get(entry.getKey()).contains(cluster.anchor(), 0))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(entry -> villages.get(entry.getKey()));
    }

    /** The cluster's real extent plus {@link #EDGE_MARGIN}, reaching at least {@link #MINIMUM_RADIUS} from the anchor. */
    static SettlementBounds extent(List<Poi> members, BlockPos anchor) {
        int minX = anchor.getX() - MINIMUM_RADIUS, minZ = anchor.getZ() - MINIMUM_RADIUS;
        int maxX = anchor.getX() + MINIMUM_RADIUS, maxZ = anchor.getZ() + MINIMUM_RADIUS;
        for (Poi poi : members) {
            minX = Math.min(minX, poi.pos().getX() - EDGE_MARGIN);
            minZ = Math.min(minZ, poi.pos().getZ() - EDGE_MARGIN);
            maxX = Math.max(maxX, poi.pos().getX() + EDGE_MARGIN);
            maxZ = Math.max(maxZ, poi.pos().getZ() + EDGE_MARGIN);
        }
        return new SettlementBounds(minX, minZ, maxX, maxZ);
    }

    static boolean withinHorizontally(BlockPos center, BlockPos pos, int radius) {
        long dx = pos.getX() - center.getX(), dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private static SettlementCandidate candidate(ServerLevel level, Cluster cluster, Optional<VillageFootprint> village) {
        ChunkPos chunk = new ChunkPos(cluster.anchor());
        String sourceKey = level.dimension().location() + ":poi:" + chunk.x + ":" + chunk.z;
        if (village.isEmpty()) {
            return new SettlementCandidate(level.dimension(), cluster.anchor(), cluster.radius(), cluster.bounds(), ID,
                    sourceKey, DetectionSource.POI, Optional.empty(), Optional.empty(), Map.of(), Optional.empty());
        }
        // A cluster inside a village stands for the whole village: the structure box, plus any of its own extent beyond it.
        SettlementBounds box = village.get().bounds();
        SettlementBounds bounds = new SettlementBounds(Math.min(box.minX(), cluster.bounds().minX()),
                Math.min(box.minZ(), cluster.bounds().minZ()), Math.max(box.maxX(), cluster.bounds().maxX()),
                Math.max(box.maxZ(), cluster.bounds().maxZ()));
        int radius = Math.max(MINIMUM_RADIUS, Math.max(bounds.maxX() - bounds.minX(), bounds.maxZ() - bounds.minZ()) / 2);
        return VillageFootprints.attach(new SettlementCandidate(level.dimension(), cluster.anchor(), radius, bounds, ID,
                sourceKey, DetectionSource.POI, Optional.empty(), Optional.empty(), Map.of(), Optional.empty()), village.get());
    }

    private static BlockPos centroid(List<Poi> members) {
        long sumX = 0, sumY = 0, sumZ = 0;
        for (Poi poi : members) {
            sumX += poi.pos().getX();
            sumY += poi.pos().getY();
            sumZ += poi.pos().getZ();
        }
        return new BlockPos((int) (sumX / members.size()), (int) (sumY / members.size()), (int) (sumZ / members.size()));
    }

    /** Points closer than {@code linkDistance} (horizontally) belong to one settlement; groups are found by union-find. */
    static List<List<BlockPos>> clusters(List<BlockPos> positions, int linkDistance) {
        return clusters(positions, Function.identity(), linkDistance);
    }

    static <T> List<List<T>> clusters(List<T> items, Function<T, BlockPos> position, int linkDistance) {
        int n = items.size(); int[] parent = new int[n]; for (int i = 0; i < n; i++) parent[i] = i;
        long limit = (long) linkDistance * linkDistance;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            BlockPos a = position.apply(items.get(i)), b = position.apply(items.get(j));
            long dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
            if (dx * dx + dz * dz <= limit) { int p = find(parent, i), q = find(parent, j); if (p != q) parent[p] = q; }
        }
        var groups = new java.util.LinkedHashMap<Integer, List<T>>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(items.get(i));
        return List.copyOf(groups.values());
    }

    private static int find(int[] parent, int i) { while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
}
