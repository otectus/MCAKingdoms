package dev.otectus.mcakingdoms.presentation;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.network.NetworkHandler;
import dev.otectus.mcakingdoms.network.OverlayPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ServerPresentationEvents {
    private static final Map<UUID, OverlayPacket> LAST_SENT = new HashMap<>();

    private ServerPresentationEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || player.tickCount % 10 != 0) {
            return;
        }
        poll(player).ifPresent(packet -> NetworkHandler.sendOverlay(player, packet));
        // At most one gallery-stale notice per player every ten ticks, however many settlements they just learned of.
        try {
            if (SettlementKnowledge.get(player.getServer()).consumeChanged(player.getUUID()))
                NetworkHandler.galleryChanged(player, dev.otectus.mcakingdoms.network.GalleryStalePacket.Reason.KNOWLEDGE);
        } catch (IllegalStateException unavailable) {
            // Discovery is not loaded on this thread or server yet; the next poll tells the client.
        }
    }

    /** Reloaded kingdom definitions change every player's gallery. */
    @SubscribeEvent
    public static void onDefinitionsReloaded(dev.otectus.mcakingdoms.api.event.KingdomDefinitionsReloadedEvent event) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            NetworkHandler.galleryChanged(player, dev.otectus.mcakingdoms.network.GalleryStalePacket.Reason.DEFINITIONS);
    }

    /** The overlay change this player has not been sent yet, if any. */
    static Optional<OverlayPacket> poll(ServerPlayer player) {
        KingdomsService service;
        try {
            service = McaKingdomsApi.get(player.getServer());
        } catch (IllegalStateException ignored) {
            return Optional.empty();
        }
        SettlementKnowledge knowledge = SettlementKnowledge.get(player.getServer());
        Optional<SettlementView> current = service.getSettlementAt(player.serverLevel(), player.blockPosition());
        current.ifPresent(settlement -> knowledge.discover(player.getUUID(), settlement.id()));
        OverlayPacket packet = current.flatMap(settlement -> service.getKingdom(settlement.kingdomId())
                        .map(kingdom -> new OverlayPacket(Optional.of(player.level().dimension().location()),
                                Optional.of(SettlementSummary.from(settlement)),
                                Optional.of(KingdomSummary.from(kingdom)))))
                .orElseGet(() -> OverlayPacket.clear(player.level().dimension().location()));
        OverlayPacket previous = LAST_SENT.get(player.getUUID());
        if (packet.equals(previous) || previous == null && packet.settlement().isEmpty()) return Optional.empty();
        LAST_SENT.put(player.getUUID(), packet);
        // The visit is recorded whatever the player's client shows, so each client option sees the same first entry.
        boolean firstVisit = packet.settlement()
                .map(settlement -> knowledge.recordVisit(player.getUUID(), settlement.id())).orElse(false);
        return Optional.of(firstVisit ? packet.asFirstVisit() : packet);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
        NetworkHandler.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
    }
}
