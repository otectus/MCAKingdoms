package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.network.LedgerPagePacket;
import dev.otectus.mcakingdoms.network.OverlayPacket;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.PresentationConfig;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

public final class ClientPresentationState {
    private static LedgerPagePacket ledgerPage;
    private static OverlayPacket currentOverlay = OverlayPacket.empty();
    private static ResourceLocation clientDimension;
    private static UUID lastSettlement;
    private static ResourceLocation lastKingdom;
    private static int overlayTicks;
    private static int overlayDuration;

    private ClientPresentationState() {
    }

    public static void receiveLedgerPage(LedgerPagePacket page) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VillageLedgerScreen ledgerScreen) {
            if (ledgerScreen.receive(page)) ledgerPage = page;
        }
    }

    public static void receiveOverlay(OverlayPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation actualDimension = minecraft.level == null
                ? null : minecraft.level.dimension().location();
        if (packet.dimension().isPresent() && !packet.dimension().orElseThrow().equals(actualDimension)) {
            return;
        }
        if (!java.util.Objects.equals(clientDimension, actualDimension)) {
            clearTransientState();
            clientDimension = actualDimension;
        }
        if (packet.settlement().isEmpty()) {
            // Left the settlement: a banner still waiting behind a screen must not appear for a place no longer here.
            currentOverlay = OverlayPacket.empty();
            lastSettlement = null;
            overlayTicks = 0;
            return;
        }
        SettlementSummary settlement = packet.settlement().orElseThrow();
        KingdomSummary kingdom = packet.kingdom().orElseThrow();
        boolean changedSettlement = !settlement.id().equals(lastSettlement);
        boolean changedKingdom = !kingdom.id().equals(lastKingdom);
        currentOverlay = packet;
        lastSettlement = settlement.id();
        lastKingdom = kingdom.id();
        if (PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get() && changedSettlement
                && (!PresentationConfig.FIRST_VISIT_ONLY.get() || packet.firstVisit())
                && (!PresentationConfig.KINGDOM_BORDERS_ONLY.get() || changedKingdom)) {
            overlayTicks = PresentationConfig.OVERLAY_DURATION_TICKS.get();
            overlayDuration = overlayTicks;
        } else if (changedSettlement) {
            // A different settlement that does not qualify for a banner must not inherit another one's remaining time.
            overlayTicks = 0;
        }
    }

    /** The banner can be seen only with no screen open and the HUD shown; its time runs only then. */
    static boolean overlayVisible(Minecraft minecraft) {
        return minecraft.screen == null && !minecraft.options.hideGui && !minecraft.isPaused();
    }

    public static void openLedger() {
        Minecraft.getInstance().setScreen(new VillageLedgerScreen(ledgerPage));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        InteractionClient.tickKeys();
        dev.otectus.mcakingdoms.client.guide.GuideKingdoms.tick();
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation dimension = minecraft.level == null ? null : minecraft.level.dimension().location();
        if (clientDimension == null) {
            clientDimension = dimension;
        } else if (!java.util.Objects.equals(clientDimension, dimension)) {
            clearTransientState();
            PoliticalPresentation.dimensionChanged();
            clientDimension = dimension;
        } else if (overlayTicks > 0 && overlayVisible(minecraft)) {
            // Deferred while a screen or F1 hides the HUD, so the whole banner shows once the view is clear.
            overlayTicks--;
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (overlayTicks <= 0 || currentOverlay.settlement().isEmpty()
                || !PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) return;

        SettlementSummary settlement = currentOverlay.settlement().orElseThrow();
        KingdomSummary kingdom = currentOverlay.kingdom().orElseThrow();
        SettlementOverlayRenderer.render(event.getGuiGraphics(), minecraft.font, settlement, kingdom,
                event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight(),
                PresentationConfig.OVERLAY_Y.get(), overlayTicks, overlayDuration, event.getPartialTick());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
        // The next server has its own kingdoms; nothing from this one is shown there.
        dev.otectus.mcakingdoms.client.guide.GuideKingdoms.clear();
    }

    /** The server sent its recipes (joining, /reload): an open Book shows the new ones at the same reading position. */
    @SubscribeEvent
    public static void onRecipesUpdated(net.minecraftforge.client.event.RecipesUpdatedEvent event) {
        if (Minecraft.getInstance().screen instanceof KingdomGuideScreen guide) guide.refreshArticle();
    }

    /** Tags decide which items an ingredient accepts, so a tag update is also a recipe change for the Book. */
    @SubscribeEvent
    public static void onTagsUpdated(net.minecraftforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() == net.minecraftforge.event.TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED
                && Minecraft.getInstance().screen instanceof KingdomGuideScreen guide) guide.refreshArticle();
    }

    private static void clearTransientState() {
        currentOverlay = OverlayPacket.empty();
        lastSettlement = null;
        lastKingdom = null;
        overlayTicks = 0;
        ledgerPage = null;
    }

    private static void clearAll() {
        clearTransientState();
        clientDimension = null;
    }
}
