package dev.otectus.mcakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Objects;

/**
 * One generated village: the structure it is, the chunk its start is in, and its jigsaw box. {@link #key()} is the
 * identity every settlement detector resolves to, written exactly as the built-in structure detector writes it.
 */
public record VillageFootprint(ResourceKey<Level> dimension, ResourceLocation structureId, ChunkPos startChunk,
                               BoundingBox box) {
    public VillageFootprint {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(startChunk, "startChunk");
        box = copy(Objects.requireNonNull(box, "box"));
    }

    /** A copy, because {@link BoundingBox} has mutators. */
    @Override
    public BoundingBox box() {
        return copy(box);
    }

    public String key() {
        return key(dimension, structureId, startChunk);
    }

    /** {@code <dimension>:<structure>:<startChunkX>:<startChunkZ>}, the built-in structure detector's source key. */
    public static String key(ResourceKey<Level> dimension, ResourceLocation structureId, ChunkPos startChunk) {
        return dimension.location() + ":" + structureId + ":" + startChunk.x + ":" + startChunk.z;
    }

    /** The box's horizontal extent. */
    public SettlementBounds bounds() {
        return new SettlementBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    /** Whether {@code pos} lies inside the box grown by {@code margin} blocks on every side. */
    public boolean contains(BlockPos pos, int margin) {
        return box.inflatedBy(margin).isInside(pos);
    }

    private static BoundingBox copy(BoundingBox box) {
        return new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }
}
