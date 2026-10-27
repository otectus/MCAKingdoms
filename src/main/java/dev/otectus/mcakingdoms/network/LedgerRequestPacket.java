package dev.otectus.mcakingdoms.network;

import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/** A ledger page request: kingdom filter, words of a settlement name (or a former name) and the order. */
public record LedgerRequestPacket(long requestId, long expectedRegistryRevision,
                                  Optional<ResourceLocation> kingdomId, int offset, String query, SettlementKnowledge.Sort sort) {
    public static final int QUERY_LIMIT = 64;

    public LedgerRequestPacket {
        query = query == null ? "" : query;
        if (requestId < 1 || expectedRegistryRevision < 0 || query.length() > QUERY_LIMIT || query.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid ledger request metadata");
        }
        sort = sort == null ? SettlementKnowledge.Sort.NAME : sort;
    }

    public static LedgerRequestPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        long expectedRevision = buffer.readVarLong();
        Optional<ResourceLocation> kingdom = buffer.readBoolean() ? Optional.of(buffer.readResourceLocation()) : Optional.empty();
        int offset = buffer.readVarInt();
        return new LedgerRequestPacket(requestId, expectedRevision, kingdom, offset, buffer.readUtf(QUERY_LIMIT),
                buffer.readEnum(SettlementKnowledge.Sort.class));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeVarLong(expectedRegistryRevision);
        buffer.writeBoolean(kingdomId.isPresent());
        kingdomId.ifPresent(buffer::writeResourceLocation);
        buffer.writeVarInt(offset);
        buffer.writeUtf(query, QUERY_LIMIT);
        buffer.writeEnum(sort);
    }
}
