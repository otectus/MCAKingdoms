package dev.otectus.mcakingdoms.network;

import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record LedgerPagePacket(
        long requestId,
        long registryRevision,
        Optional<ResourceLocation> kingdomId,
        int offset,
        boolean revisionReset,
        int retryAfterTicks,
        List<SettlementSummary> settlements,
        List<KingdomSummary> kingdoms,
        /** Why the ledger is empty when settlement discovery is read-only; blank otherwise. */
        String diagnostic,
        /** The search and order this page answers, so a reply for older words is recognized. */
        String query,
        dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.Sort sort,
        /** How many visible settlements match, across all pages. */
        int total,
        /** A situation the screen explains: blank, or {@code kingdom_missing} when the filtered kingdom is no longer defined. */
        String notice
) {
    public LedgerPagePacket(long requestId, long registryRevision, Optional<ResourceLocation> kingdomId, int offset, boolean revisionReset,
                            int retryAfterTicks, List<SettlementSummary> settlements, List<KingdomSummary> kingdoms, String diagnostic) {
        this(requestId, registryRevision, kingdomId, offset, revisionReset, retryAfterTicks, settlements, kingdoms, diagnostic, "",
                dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.Sort.NAME, 0, "");
    }

    private static final int MAX_PAGE_RESULTS = 21;
    private static final int MAX_KINGDOMS = 64;

    public LedgerPagePacket {
        settlements = List.copyOf(settlements);
        kingdoms = List.copyOf(kingdoms);
        diagnostic = diagnostic == null ? "" : diagnostic;
        if (diagnostic.length() > 512) throw new IllegalArgumentException("Oversized ledger diagnostic");
        query = query == null ? "" : query;
        notice = notice == null ? "" : notice;
        sort = sort == null ? dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.Sort.NAME : sort;
        if (query.length() > LedgerRequestPacket.QUERY_LIMIT || notice.length() > 32 || total < 0) throw new IllegalArgumentException("Invalid ledger search");
        if (requestId < 1 || registryRevision < 0 || offset < 0
                || retryAfterTicks < 0 || retryAfterTicks > 100
                || settlements.size() > MAX_PAGE_RESULTS || kingdoms.size() > MAX_KINGDOMS) {
            throw new IllegalArgumentException("Oversized ledger page");
        }
        if (retryAfterTicks > 0 && (!settlements.isEmpty() || !kingdoms.isEmpty())) {
            throw new IllegalArgumentException("A retry response cannot contain ledger data");
        }
    }

    public static LedgerPagePacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        long revision = buffer.readVarLong();
        Optional<ResourceLocation> kingdom = buffer.readBoolean()
                ? Optional.of(buffer.readResourceLocation()) : Optional.empty();
        int offset = buffer.readVarInt();
        boolean revisionReset = buffer.readBoolean();
        int retryAfterTicks = buffer.readVarInt();
        int resultCount = buffer.readVarInt();
        if (resultCount < 0 || resultCount > MAX_PAGE_RESULTS) {
            throw new IllegalArgumentException("Invalid ledger result count " + resultCount);
        }
        List<SettlementSummary> settlements = new ArrayList<>(resultCount);
        for (int i = 0; i < resultCount; i++) settlements.add(SettlementSummary.decode(buffer));
        int kingdomCount = buffer.readVarInt();
        if (kingdomCount < 0 || kingdomCount > MAX_KINGDOMS) {
            throw new IllegalArgumentException("Invalid ledger kingdom count " + kingdomCount);
        }
        List<KingdomSummary> kingdoms = new ArrayList<>(kingdomCount);
        for (int i = 0; i < kingdomCount; i++) kingdoms.add(KingdomSummary.decode(buffer));
        String diagnostic = buffer.readUtf(512);
        String query = buffer.readUtf(LedgerRequestPacket.QUERY_LIMIT);
        var sort = buffer.readEnum(dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.Sort.class);
        int total = buffer.readVarInt();
        String notice = buffer.readUtf(32);
        return new LedgerPagePacket(requestId, revision, kingdom, offset, revisionReset,
                retryAfterTicks, settlements, kingdoms, diagnostic, query, sort, total, notice);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeVarLong(registryRevision);
        buffer.writeBoolean(kingdomId.isPresent());
        kingdomId.ifPresent(buffer::writeResourceLocation);
        buffer.writeVarInt(offset);
        buffer.writeBoolean(revisionReset);
        buffer.writeVarInt(retryAfterTicks);
        buffer.writeVarInt(settlements.size());
        settlements.forEach(summary -> summary.encode(buffer));
        buffer.writeVarInt(kingdoms.size());
        kingdoms.forEach(summary -> summary.encode(buffer));
        buffer.writeUtf(diagnostic, 512);
        buffer.writeUtf(query, LedgerRequestPacket.QUERY_LIMIT);
        buffer.writeEnum(sort);
        buffer.writeVarInt(total);
        buffer.writeUtf(notice, 32);
    }
}
