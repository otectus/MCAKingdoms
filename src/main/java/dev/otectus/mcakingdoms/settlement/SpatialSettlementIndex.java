package dev.otectus.mcakingdoms.settlement;

import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.core.SettlementSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Which settlements may contain a position, by chunk (AUD-12). A settlement touching at most {@link #INDEXED_CHUNKS}
 * chunks is entered under each of them; a larger one that is still {@linkplain SettlementBounds#supported supported} is
 * kept in a short per-dimension list checked by its bounds, so no lookup or rebuild ever enumerates a huge rectangle.
 * Bounds beyond {@link SettlementBounds#MAX_SPAN} are not indexed at all: the record stays loaded and is reported by
 * {@link #isolated()} for an operator to repair; it is never shrunk or deleted here.
 */
public final class SpatialSettlementIndex {
    /** The most chunks one settlement is entered under: a 64 by 64 chunk square (1024 blocks). */
    public static final int INDEXED_CHUNKS = 4_096;

    private final Map<DimensionChunk, Set<UUID>> chunks = new HashMap<>();
    private final Map<ResourceKey<Level>, Map<UUID, SettlementBounds>> large = new HashMap<>();
    private final Map<UUID, SettlementBounds> isolated = new LinkedHashMap<>();

    public void rebuild(Collection<SettlementSnapshot> settlements) {
        chunks.clear();
        large.clear();
        isolated.clear();
        settlements.forEach(this::add);
    }

    public void add(SettlementSnapshot settlement) {
        SettlementBounds bounds = settlement.bounds();
        if (!bounds.supported()) { isolated.put(settlement.id(), bounds); return; }
        if (bounds.chunkCount() > INDEXED_CHUNKS) { large.computeIfAbsent(settlement.dimension(), ignored -> new LinkedHashMap<>()).put(settlement.id(), bounds); return; }
        visit(settlement.dimension(), bounds, key -> chunks.computeIfAbsent(key, ignored -> new HashSet<>()).add(settlement.id()));
    }

    public void remove(SettlementSnapshot settlement) {
        SettlementBounds bounds = settlement.bounds();
        if (isolated.remove(settlement.id()) != null && !bounds.supported()) return;
        var big = large.get(settlement.dimension());
        if (big != null && big.remove(settlement.id()) != null) { if (big.isEmpty()) large.remove(settlement.dimension()); return; }
        if (!bounds.supported() || bounds.chunkCount() > INDEXED_CHUNKS) return;
        visit(settlement.dimension(), bounds, key -> {
            Set<UUID> ids = chunks.get(key);
            if (ids != null) {
                ids.remove(settlement.id());
                if (ids.isEmpty()) chunks.remove(key);
            }
        });
    }

    public Set<UUID> at(ResourceKey<Level> dimension, BlockPos pos) {
        Set<UUID> ids = chunks.get(new DimensionChunk(dimension, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)));
        var big = large.get(dimension);
        if (big == null || big.isEmpty()) return ids == null ? Set.of() : Set.copyOf(ids);
        Set<UUID> out = ids == null ? new HashSet<>() : new HashSet<>(ids);
        big.forEach((id, bounds) -> { if (bounds.contains(pos)) out.add(id); });
        return Set.copyOf(out);
    }

    /** Settlements whose saved bounds exceed {@link SettlementBounds#MAX_SPAN}: loaded, not indexed, kept for repair. */
    public Map<UUID, SettlementBounds> isolated() {
        return Collections.unmodifiableMap(isolated);
    }

    /** How many chunk entries the index holds, for diagnostics and tests. */
    public int entries() {
        return chunks.values().stream().mapToInt(Set::size).sum() + large.values().stream().mapToInt(Map::size).sum();
    }

    private static void visit(ResourceKey<Level> dimension, SettlementBounds bounds, java.util.function.Consumer<DimensionChunk> consumer) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                consumer.accept(new DimensionChunk(dimension, ChunkPos.asLong(x, z)));
            }
        }
    }

    private record DimensionChunk(ResourceKey<Level> dimension, long chunk) {
    }
}
