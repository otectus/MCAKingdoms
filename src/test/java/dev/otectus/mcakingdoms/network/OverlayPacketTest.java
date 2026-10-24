package dev.otectus.mcakingdoms.network;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OverlayPacketTest {
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private static OverlayPacket entry() {
        ResourceLocation kingdom = new ResourceLocation("mcakingdoms", "serenum");
        var settlement = new SettlementSummary(UUID.randomUUID(), "Amberford", new ResourceLocation("mcakingdoms", "amberford"),
                kingdom, OVERWORLD, new BlockPos(3, 64, 8), new ResourceLocation("minecraft", "plains"),
                AssignmentSource.EXACT_BIOME, 20, 1);
        return new OverlayPacket(Optional.of(OVERWORLD), Optional.of(settlement), Optional.of(new KingdomSummary(kingdom,
                "kingdom.mcakingdoms.serenum", new ResourceLocation("mcakingdoms", "heraldry/serenum"), 0xC8A040)));
    }

    private static OverlayPacket roundTrip(OverlayPacket packet) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            OverlayPacket decoded = OverlayPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    @Test void firstVisitFlagSurvivesTheWire() {
        OverlayPacket repeat = entry();
        assertFalse(repeat.firstVisit());
        assertEquals(repeat, roundTrip(repeat));
        OverlayPacket first = repeat.asFirstVisit();
        assertTrue(roundTrip(first).firstVisit());
        assertEquals(first, roundTrip(first));
        assertEquals(OverlayPacket.clear(OVERWORLD), roundTrip(OverlayPacket.clear(OVERWORLD)));
        assertEquals(OverlayPacket.empty(), roundTrip(OverlayPacket.empty()));
    }

    @Test void onlyASettlementOverlayCanMarkAFirstVisit() {
        assertThrows(IllegalArgumentException.class,
                () -> new OverlayPacket(Optional.of(OVERWORLD), Optional.empty(), Optional.empty(), true));
    }
}
