package dev.otectus.mcakingdoms.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Tells one client that its Book gallery is out of date (F11): the player learned of a settlement, or the server's
 * kingdom definitions were reloaded. It carries no data; the client asks again through the ordinary gallery request,
 * which the server answers from the viewer's own knowledge.
 */
public record GalleryStalePacket(Reason reason) {
    public enum Reason { KNOWLEDGE, DEFINITIONS }

    public static GalleryStalePacket decode(FriendlyByteBuf buffer) {
        int ordinal = buffer.readVarInt();
        if (ordinal < 0 || ordinal >= Reason.values().length) throw new IllegalArgumentException("Invalid gallery change reason " + ordinal);
        return new GalleryStalePacket(Reason.values()[ordinal]);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(reason.ordinal());
    }
}
