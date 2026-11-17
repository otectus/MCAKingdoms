package dev.otectus.mcakingdoms.api;

import net.minecraft.core.BlockPos;

/**
 * A settlement's horizontal extent, inclusive. Settlement membership is horizontal: every height inside the rectangle
 * belongs to it.
 *
 * <p>Bounds are ordered and their spans are computed without overflow. A settlement may span at most {@link #MAX_SPAN}
 * blocks on each axis (AUD-12): far beyond any configurable growth ({@code defaultSettlementRadius} 512 times eight is
 * 4096) and any generated village, while keeping lookups bounded. Registration refuses larger bounds with the measured
 * span; saved records with larger bounds are kept and reported for repair, never shrunk or deleted.
 */
public record SettlementBounds(int minX, int minZ, int maxX, int maxZ) {
    /** The most blocks a settlement may span on one axis. */
    public static final int MAX_SPAN = 16_384;

    public SettlementBounds {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Settlement bounds minimum must not exceed maximum");
        }
    }

    /**
     * The square of {@code radius} blocks around {@code anchor}. Refuses a negative radius, and a radius whose square
     * would leave the integer coordinate range, instead of wrapping around.
     */
    public static SettlementBounds around(BlockPos anchor, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Settlement radius must be non-negative");
        }
        long minX = (long) anchor.getX() - radius, maxX = (long) anchor.getX() + radius;
        long minZ = (long) anchor.getZ() - radius, maxZ = (long) anchor.getZ() + radius;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Settlement radius " + radius + " around " + anchor.toShortString() + " leaves the world's coordinate range");
        }
        return new SettlementBounds((int) minX, (int) minZ, (int) maxX, (int) maxZ);
    }

    /** Blocks spanned on the X axis, inclusive, without overflow. */
    public long spanX() {
        return (long) maxX - minX + 1;
    }

    /** Blocks spanned on the Z axis, inclusive, without overflow. */
    public long spanZ() {
        return (long) maxZ - minZ + 1;
    }

    /** Chunks the rectangle touches, without overflow. */
    public long chunkCount() {
        return ((long) (maxX >> 4) - (minX >> 4) + 1) * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
    }

    /** Whether the bounds are within {@link #MAX_SPAN} on both axes. */
    public boolean supported() {
        return spanX() <= MAX_SPAN && spanZ() <= MAX_SPAN;
    }

    /** Refuses unsupported bounds with an explanation an add-on developer can act on. */
    public SettlementBounds requireSupported() {
        if (!supported()) {
            throw new IllegalArgumentException("Settlement bounds " + minX + ".." + maxX + " x " + minZ + ".." + maxZ + " span " + spanX() + " x " + spanZ()
                    + " blocks; at most " + MAX_SPAN + " per axis are supported. Report the settlement's real extent, not a whole region.");
        }
        return this;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getZ());
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects(SettlementBounds other) {
        return minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
