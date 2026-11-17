package dev.otectus.mcakingdoms.persistence;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-issued request ids that carry their issue time (AUD-07), so a store that keeps only its newest receipts can
 * still refuse a request older than the receipts it dropped, instead of mistaking it for a new one. The high half is
 * the overworld game time when the id was issued; the low half is a fixed tag and 48 random bits.
 */
public final class RequestIds {
    private static final long TAG = 0x4B44L; // "KD"

    private RequestIds() {
    }

    public static UUID issue(MinecraftServer server) {
        return issue(server.overworld().getGameTime());
    }

    public static UUID issue(long gameTime) {
        long random = ThreadLocalRandom.current().nextLong() & 0x0000_FFFF_FFFF_FFFFL;
        return new UUID(gameTime, TAG << 48 | random);
    }

    /** Whether {@code id} was issued by {@link #issue}; any other id (a random UUID) has no issue time. */
    public static boolean issued(UUID id) {
        return id != null && id.getLeastSignificantBits() >>> 48 == TAG;
    }

    /** The game time {@code id} was issued at, or {@link Long#MIN_VALUE} for an id this class did not issue. */
    public static long issuedAt(UUID id) {
        return issued(id) ? id.getMostSignificantBits() : Long.MIN_VALUE;
    }
}
