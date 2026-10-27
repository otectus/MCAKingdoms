package dev.otectus.mcakingdoms.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * One committed political moment for players who opted in (A02): a founding, an accession, a vacancy or a memorial. The
 * text is a serialized component built from the committed record, so each client reads it in its own language;
 * {@code eventId} is the committed fact's id, so a client shows each moment once.
 */
public record PoliticalNoticePacket(UUID eventId, String text, boolean memorial) {
    static final int MAX_TEXT = 4096;

    public PoliticalNoticePacket {
        if (eventId == null || text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("Invalid political notice");
    }

    public static PoliticalNoticePacket decode(FriendlyByteBuf buffer) {
        return new PoliticalNoticePacket(buffer.readUUID(), buffer.readUtf(MAX_TEXT), buffer.readBoolean());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(eventId);
        buffer.writeUtf(text, MAX_TEXT);
        buffer.writeBoolean(memorial);
    }
}
