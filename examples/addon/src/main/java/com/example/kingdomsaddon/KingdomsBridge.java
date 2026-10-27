package com.example.kingdomsaddon;

import dev.otectus.mcakingdoms.api.KingdomView;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.event.SettlementMergedEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that uses the MCA: Kingdoms API. Forge calls these handlers on the server thread, which every
 * {@link KingdomsService} method requires; work started on another thread must hand back with {@code server.execute}.
 */
final class KingdomsBridge {
    private static final ResourceLocation OWNER = new ResourceLocation(KingdomsAddonExample.MOD_ID, "resolver");

    /** Settlement ids this addon saved for its own purposes. A merge may retire one, so they are resolved before use. */
    private final Map<String, UUID> remembered = new HashMap<>();
    private Registration resolver;

    /** The service is attached while the server starts; from {@code ServerStartedEvent} it is ready. */
    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        KingdomsService kingdoms;
        try {
            kingdoms = McaKingdomsApi.get(event.getServer());
        } catch (IllegalStateException notReady) {
            return; // MCA: Kingdoms is installed but did not start on this server; carry on without it.
        }
        // A kingdom resolver may choose the kingdom of a new settlement. Returning empty leaves it to the datapack rules.
        resolver = kingdoms.registerKingdomResolver(OWNER, (level, candidate) -> Optional.empty());

        // This is a trusted server API: it returns every settlement, including ones a player has not discovered.
        // Check what a player knows before showing them anything taken from it.
        kingdoms.getKingdoms().stream().filter(KingdomView::defined).findFirst()
                .flatMap(kingdom -> kingdoms.getSettlementPage(Optional.of(kingdom.id()), 0, 1).stream().findFirst())
                .ifPresent(settlement -> remembered.put("example", settlement.id()));
    }

    /** After a merge the retired id still resolves, but stored copies should follow the surviving record. */
    @SubscribeEvent
    public void merged(SettlementMergedEvent event) {
        KingdomsService kingdoms = McaKingdomsApi.get(event.server());
        remembered.replaceAll((purpose, id) -> kingdoms.resolveSettlementId(id));
    }

    /** Reads a stored settlement: resolve the id first, and expect the record may be gone. */
    Optional<SettlementView> remembered(MinecraftServer server, String purpose) {
        UUID stored = remembered.get(purpose);
        if (stored == null) return Optional.empty();
        KingdomsService kingdoms = McaKingdomsApi.get(server);
        return kingdoms.getSettlement(kingdoms.resolveSettlementId(stored));
    }

    /** Registrations end with the server; the service itself is detached after the server stops. */
    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        if (resolver != null) {
            resolver.close();
            resolver = null;
        }
        remembered.clear();
    }
}
