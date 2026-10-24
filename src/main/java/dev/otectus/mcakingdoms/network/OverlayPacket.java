package dev.otectus.mcakingdoms.network;

import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/** The settlement a player stands in, if any; {@code firstVisit} marks their first recorded entry into it. */
public record OverlayPacket(Optional<ResourceLocation> dimension,
                            Optional<SettlementSummary> settlement, Optional<KingdomSummary> kingdom,
                            boolean firstVisit) {
    public OverlayPacket {
        if (settlement.isPresent() != kingdom.isPresent()) {
            throw new IllegalArgumentException("Settlement and kingdom overlay data must be present together");
        }
        if (settlement.isPresent() && (dimension.isEmpty()
                || !settlement.orElseThrow().dimension().equals(dimension.orElseThrow()))) {
            throw new IllegalArgumentException("Overlay dimension does not match its settlement");
        }
        if (firstVisit && settlement.isEmpty()) {
            throw new IllegalArgumentException("Only a settlement overlay can mark a first visit");
        }
    }

    public OverlayPacket(Optional<ResourceLocation> dimension,
                         Optional<SettlementSummary> settlement, Optional<KingdomSummary> kingdom) {
        this(dimension, settlement, kingdom, false);
    }

    public static OverlayPacket empty() {
        return new OverlayPacket(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static OverlayPacket clear(ResourceLocation dimension) {
        return new OverlayPacket(Optional.of(dimension), Optional.empty(), Optional.empty());
    }

    public OverlayPacket asFirstVisit() {
        return new OverlayPacket(dimension, settlement, kingdom, true);
    }

    public static OverlayPacket decode(FriendlyByteBuf buffer) {
        Optional<ResourceLocation> dimension = buffer.readBoolean()
                ? Optional.of(buffer.readResourceLocation()) : Optional.empty();
        if (!buffer.readBoolean()) return new OverlayPacket(dimension, Optional.empty(), Optional.empty());
        return new OverlayPacket(dimension, Optional.of(SettlementSummary.decode(buffer)),
                Optional.of(KingdomSummary.decode(buffer)), buffer.readBoolean());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(dimension.isPresent());
        dimension.ifPresent(buffer::writeResourceLocation);
        buffer.writeBoolean(settlement.isPresent());
        settlement.ifPresent(summary -> summary.encode(buffer));
        kingdom.ifPresent(summary -> summary.encode(buffer));
        if (settlement.isPresent()) buffer.writeBoolean(firstVisit);
    }
}
