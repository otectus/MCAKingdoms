package dev.otectus.mcakingdoms.compat.townstead.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record TownsteadCivicHeaderPacket(
        long requestId, ResourceLocation dimension, int villageId,
        Optional<Header> header
) {
    public TownsteadCivicHeaderPacket {
        if (requestId < 1 || villageId < 0) throw new IllegalArgumentException("Invalid civic header response");
        Objects.requireNonNull(dimension, "dimension");
        header = Objects.requireNonNull(header, "header");
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeResourceLocation(dimension);
        buffer.writeVarInt(villageId);
        buffer.writeBoolean(header.isPresent());
        header.ifPresent(value -> value.encode(buffer));
    }

    public static TownsteadCivicHeaderPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        ResourceLocation dimension = buffer.readResourceLocation();
        int villageId = buffer.readVarInt();
        return new TownsteadCivicHeaderPacket(requestId, dimension, villageId,
                buffer.readBoolean() ? Optional.of(Header.decode(buffer)) : Optional.empty());
    }

    /**
     * {@code authorityKey} names who governs the settlement's local government, as a bundled translation key the client
     * reads ({@code townstead.mcakingdoms.header.local_*}); {@code settlement} lets the header's button open the
     * settlement's Government and services task. Protocol 2.
     */
    public record Header(String settlementName, ResourceLocation kingdomId, String kingdomNameKey,
                         ResourceLocation heraldryIcon, int uiColor, String standingTier, String authorityKey, java.util.UUID settlement) {
        public Header {
            if (settlementName == null || settlementName.isBlank() || settlementName.length() > 128
                    || kingdomNameKey == null || kingdomNameKey.isBlank() || kingdomNameKey.length() > 160
                    || standingTier == null || standingTier.isBlank() || standingTier.length() > 64
                    || authorityKey == null || authorityKey.length() > 96 || !authorityKey.matches("[a-z0-9_.]*")) {
                throw new IllegalArgumentException("Invalid civic header text");
            }
            Objects.requireNonNull(kingdomId, "kingdomId");
            Objects.requireNonNull(heraldryIcon, "heraldryIcon");
            Objects.requireNonNull(settlement, "settlement");
        }

        private void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(settlementName, 128);
            buffer.writeResourceLocation(kingdomId);
            buffer.writeUtf(kingdomNameKey, 160);
            buffer.writeResourceLocation(heraldryIcon);
            buffer.writeInt(uiColor);
            buffer.writeUtf(standingTier, 64);
            buffer.writeUtf(authorityKey, 96);
            buffer.writeUUID(settlement);
        }

        private static Header decode(FriendlyByteBuf buffer) {
            return new Header(buffer.readUtf(128), buffer.readResourceLocation(), buffer.readUtf(160),
                    buffer.readResourceLocation(), buffer.readInt(), buffer.readUtf(64), buffer.readUtf(96), buffer.readUUID());
        }
    }
}
