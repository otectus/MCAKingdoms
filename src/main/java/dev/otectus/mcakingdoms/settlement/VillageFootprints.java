package dev.otectus.mcakingdoms.settlement;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.api.VillageFootprintSource;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves positions to generated villages, so that every detector's candidate inside one village resolves to that
 * village's structure start. The built-in reader reads only chunks already in memory: the structure references of
 * loaded chunks, and each referenced start once per session. Registered {@link VillageFootprintSource}s follow it.
 */
public final class VillageFootprints {
    /** External-reference namespace holding a village's structure key, as the built-in structure detector writes it. */
    public static final String STRUCTURE_REF = "minecraft:structure";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int START_CACHE_LIMIT = 4_096;

    private final Supplier<Map<ResourceLocation, VillageFootprintSource>> sources;
    /** Village starts read so far, by key; empty for a start that is not a valid structure. Starts never change. */
    private final Map<String, Optional<VillageFootprint>> starts = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<VillageFootprint>> eldest) {
            return size() > START_CACHE_LIMIT;
        }
    };
    /** Footprints referenced by each loaded chunk, kept for one server tick. */
    private final Map<LoadedChunk, List<VillageFootprint>> referenced = new HashMap<>();
    private final Set<ResourceLocation> failedSources = new HashSet<>();
    private int referencedTick = Integer.MIN_VALUE;

    public VillageFootprints(Supplier<Map<ResourceLocation, VillageFootprintSource>> sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** Every village whose box, grown by {@code margin}, contains {@code pos}: best first (see {@link #order}). */
    public List<VillageFootprint> near(ServerLevel level, BlockPos pos, int margin) {
        Map<String, VillageFootprint> found = new LinkedHashMap<>();
        fromLoadedChunks(level, pos, margin).forEach(footprint -> found.putIfAbsent(footprint.key(), footprint));
        for (Map.Entry<ResourceLocation, VillageFootprintSource> source : sources.get().entrySet()) {
            List<VillageFootprint> listed;
            try {
                listed = source.getValue().footprintsNear(level, pos, margin);
            } catch (RuntimeException failure) {
                if (failedSources.add(source.getKey())) {
                    LOGGER.error("[MCA: Kingdoms] Village footprint source {} failed; it is skipped", source.getKey(), failure);
                }
                continue;
            }
            if (listed == null) continue;
            listed.stream()
                    .filter(Objects::nonNull)
                    .filter(footprint -> footprint.dimension().equals(level.dimension()) && footprint.contains(pos, margin))
                    .forEach(footprint -> found.putIfAbsent(footprint.key(), footprint));
        }
        return order(found.values(), pos);
    }

    public Optional<VillageFootprint> at(ServerLevel level, BlockPos pos, int margin) {
        return near(level, pos, margin).stream().findFirst();
    }

    /** The village with this structure key, if a source places it within {@code margin} of {@code pos}. */
    public Optional<VillageFootprint> find(ServerLevel level, BlockPos pos, String key, int margin) {
        return near(level, pos, margin).stream().filter(footprint -> footprint.key().equals(key)).findFirst();
    }

    /**
     * For the start-up reconciliation only: reads the chunk at {@code pos} at {@code STRUCTURE_REFERENCES} status, and
     * any start chunk not in memory at {@code STRUCTURE_STARTS} status, while {@code budget} lasts.
     */
    public Optional<VillageFootprint> read(ServerLevel level, BlockPos pos, int margin, Budget budget) {
        if (!budget.take()) return Optional.empty();
        ChunkAccess chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                ChunkStatus.STRUCTURE_REFERENCES, true);
        if (chunk == null) return Optional.empty();
        return order(footprints(level, chunk, budget).stream()
                .filter(footprint -> footprint.contains(pos, margin)).toList(), pos).stream().findFirst();
    }

    /** Containing {@code pos} without the margin first, then the nearest box centre; the key breaks ties. */
    static List<VillageFootprint> order(Collection<VillageFootprint> found, BlockPos pos) {
        return found.stream()
                .sorted(Comparator.comparing((VillageFootprint footprint) -> !footprint.contains(pos, 0))
                        .thenComparingLong(footprint -> horizontalDistanceSquared(footprint.box(), pos))
                        .thenComparing(VillageFootprint::key))
                .toList();
    }

    /** Whether the configured village tags or ids ({@code villageStructureTags}) include this structure. */
    public static boolean village(Registry<Structure> registry, Structure structure) {
        Holder<Structure> holder = registry.wrapAsHolder(structure);
        ResourceLocation id = registry.getKey(structure);
        for (String entry : McaKingdomsConfig.VILLAGE_STRUCTURE_TAGS.get()) {
            ResourceLocation selector = ResourceLocation.tryParse(entry.startsWith("#") ? entry.substring(1) : entry);
            if (selector != null && (selector.equals(id) || holder.is(TagKey.create(Registries.STRUCTURE, selector)))) {
                return true;
            }
        }
        return false;
    }

    /** The village structure key a candidate carries, if any. */
    public static Optional<String> villageKey(SettlementCandidate candidate) {
        return Optional.ofNullable(candidate.externalRefs().get(STRUCTURE_REF));
    }

    /** The candidate attached to a village: its structure key as identity and, when it has none, the village's style. */
    public static SettlementCandidate attach(SettlementCandidate candidate, VillageFootprint footprint) {
        Map<String, String> refs = new LinkedHashMap<>(candidate.externalRefs());
        refs.put(STRUCTURE_REF, footprint.key());
        return new SettlementCandidate(candidate.dimension(), candidate.anchor(), candidate.radius(), candidate.bounds(),
                candidate.sourceId(), candidate.sourceKey(), candidate.detectionSource(),
                candidate.styleId().or(() -> Optional.of(footprint.structureId())), candidate.explicitKingdom(), refs,
                candidate.proposedName());
    }

    private List<VillageFootprint> fromLoadedChunks(ServerLevel level, BlockPos pos, int margin) {
        int radius = (margin + 15) >> 4;
        int centerX = pos.getX() >> 4;
        int centerZ = pos.getZ() >> 4;
        List<VillageFootprint> result = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                for (VillageFootprint footprint : referencedBy(level, x, z)) {
                    if (footprint.contains(pos, margin)) result.add(footprint);
                }
            }
        }
        return result;
    }

    private List<VillageFootprint> referencedBy(ServerLevel level, int chunkX, int chunkZ) {
        int tick = level.getServer().getTickCount();
        if (tick != referencedTick) {
            referencedTick = tick;
            referenced.clear();
        }
        return referenced.computeIfAbsent(new LoadedChunk(level.dimension(), ChunkPos.asLong(chunkX, chunkZ)), ignored -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            return chunk == null ? List.of() : footprints(level, chunk, null);
        });
    }

    private List<VillageFootprint> footprints(ServerLevel level, ChunkAccess chunk, Budget budget) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<VillageFootprint> result = new ArrayList<>();
        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
            if (entry.getValue().isEmpty() || !village(registry, entry.getKey())) continue;
            ResourceLocation id = registry.getKey(entry.getKey());
            if (id == null) continue;
            for (long start : entry.getValue()) {
                start(level, entry.getKey(), id, new ChunkPos(start), budget).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    private Optional<VillageFootprint> start(ServerLevel level, Structure structure, ResourceLocation id,
                                             ChunkPos startChunk, Budget budget) {
        String key = VillageFootprint.key(level.dimension(), id, startChunk);
        Optional<VillageFootprint> cached = starts.get(key);
        if (cached != null) return cached;
        ChunkAccess chunk = level.getChunkSource().getChunkNow(startChunk.x, startChunk.z);
        if (chunk == null) {
            chunk = level.getChunkSource().getChunk(startChunk.x, startChunk.z, ChunkStatus.STRUCTURE_STARTS, false);
        }
        if (chunk == null && budget != null && budget.take()) {
            chunk = level.getChunkSource().getChunk(startChunk.x, startChunk.z, ChunkStatus.STRUCTURE_STARTS, true);
        }
        if (chunk == null) return Optional.empty(); // Not in memory: asked again once it is.
        StructureStart start = chunk.getStartForStructure(structure);
        Optional<VillageFootprint> result = start == null || !start.isValid() ? Optional.empty()
                : Optional.of(new VillageFootprint(level.dimension(), id, startChunk, start.getBoundingBox()));
        starts.put(key, result);
        return result;
    }

    private static long horizontalDistanceSquared(BoundingBox box, BlockPos pos) {
        long dx = (long) (box.minX() + box.maxX()) / 2 - pos.getX();
        long dz = (long) (box.minZ() + box.maxZ()) / 2 - pos.getZ();
        return dx * dx + dz * dz;
    }

    /** Chunk reads the start-up reconciliation may still spend. */
    public static final class Budget {
        private int remaining;
        private int spent;

        public Budget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        public boolean take() {
            if (remaining <= 0) return false;
            remaining--;
            spent++;
            return true;
        }

        public int remaining() {
            return remaining;
        }

        public int spent() {
            return spent;
        }
    }

    private record LoadedChunk(ResourceKey<Level> dimension, long chunk) {
    }
}
