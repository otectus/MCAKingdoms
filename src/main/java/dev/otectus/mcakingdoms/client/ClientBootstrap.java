package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.network.NetworkHandler;
import dev.otectus.mcakingdoms.client.townstead.TownsteadBlueprintHeader;
import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        BookGuideClient.init();
        InteractionClient.init();
        NetworkHandler.setClientReceivers(ClientPresentationState::receiveLedgerPage,
                ClientPresentationState::receiveOverlay, ClientPresentationState::openLedger);
        NetworkHandler.setGalleryReceiver(dev.otectus.mcakingdoms.client.guide.GuideKingdoms::receive);
        NetworkHandler.setGalleryStaleReceiver(ignored -> dev.otectus.mcakingdoms.client.guide.GuideKingdoms.invalidate());
        TownsteadBlueprintHeader.init();
        dev.otectus.mcakingdoms.civic.CivicNetwork.receiver(GuildScreen::receive);
        dev.otectus.mcakingdoms.politics.PoliticalNetwork.receiver(dev.otectus.mcakingdoms.client.politics.KingdomScreen::receive);
        dev.otectus.mcakingdoms.warfare.WarfareNetwork.receiver(WarRoomScreen::receive);
        MinecraftForge.EVENT_BUS.register(ClientPresentationState.class);
        NetworkHandler.setPoliticalReceivers(PoliticalPresentation::notice, PoliticalPresentation::badge);
        MinecraftForge.EVENT_BUS.register(PoliticalPresentation.class);
        ClientExtensions.register();
    }
}
