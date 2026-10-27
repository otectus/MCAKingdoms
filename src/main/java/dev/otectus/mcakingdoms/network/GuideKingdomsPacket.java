package dev.otectus.mcakingdoms.network;

import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The Book's kingdom gallery: the server's public kingdom definitions (name key, heraldry, colour) and, for each, how many
 * of its settlements this viewer knows. Nothing else about other players or undiscovered places is included.
 */
public record GuideKingdomsPacket(long requestId, List<KingdomSummary> kingdoms, List<Integer> known) {
    private static final int MAX_KINGDOMS = 64;

    public GuideKingdomsPacket {
        kingdoms = List.copyOf(kingdoms);
        known = List.copyOf(known);
        if (requestId < 1 || kingdoms.size() > MAX_KINGDOMS || known.size() != kingdoms.size() || known.stream().anyMatch(n -> n < 0))
            throw new IllegalArgumentException("Invalid kingdom gallery");
    }

    public static GuideKingdomsPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_KINGDOMS) throw new IllegalArgumentException("Invalid kingdom gallery size " + count);
        List<KingdomSummary> kingdoms = new ArrayList<>(count);
        List<Integer> known = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            kingdoms.add(KingdomSummary.decode(buffer));
            known.add(buffer.readVarInt());
        }
        return new GuideKingdomsPacket(requestId, kingdoms, known);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeVarInt(kingdoms.size());
        for (int i = 0; i < kingdoms.size(); i++) {
            kingdoms.get(i).encode(buffer);
            buffer.writeVarInt(known.get(i));
        }
    }

    /** The request: carries only its id. */
    public record Request(long requestId) {
        public Request {
            if (requestId < 1) throw new IllegalArgumentException("Invalid kingdom gallery request");
        }

        public static Request decode(FriendlyByteBuf buffer) {
            return new Request(buffer.readVarLong());
        }

        public void encode(FriendlyByteBuf buffer) {
            buffer.writeVarLong(requestId);
        }
    }
}
