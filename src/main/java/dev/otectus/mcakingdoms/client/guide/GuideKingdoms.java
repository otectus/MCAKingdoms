package dev.otectus.mcakingdoms.client.guide;

import dev.otectus.mcakingdoms.client.KingdomGuideScreen;
import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.network.GuideKingdomsPacket;
import dev.otectus.mcakingdoms.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The connected server's kingdoms for the Book's gallery, forgotten on disconnect so another server's kingdoms are never
 * shown. When to ask is decided by {@link GalleryRequests} (F11): rendering only starts the first request, a failure
 * stays visible until Try again or reopening the Book, and a change the server reports refreshes the gallery once.
 */
public final class GuideKingdoms {
    public enum State { NONE, LOADING, READY, FAILED }
    private static final GalleryRequests REQUESTS = new GalleryRequests();
    private static final GalleryRequests.Sender SEND = NetworkHandler::requestKingdomGallery;
    private static GuideKingdomsPacket gallery;

    private GuideKingdoms() {
    }

    public static State state() {
        return State.valueOf(REQUESTS.state().name());
    }

    /** The last refresh went unanswered; the tiles shown may be out of date. */
    public static boolean refreshFailed() {
        return REQUESTS.refreshFailed();
    }

    private static boolean connected() {
        return Minecraft.getInstance().getConnection() != null;
    }

    private static boolean shown() {
        return Minecraft.getInstance().screen instanceof KingdomGuideScreen;
    }

    private static void redraw() {
        if (Minecraft.getInstance().screen instanceof KingdomGuideScreen screen) screen.refreshArticle();
    }

    /** Called while the gallery is built: asks only for the first answer, or for a refresh the server asked for. */
    public static void ensure() {
        if (connected()) REQUESTS.ensure(SEND);
    }

    /** Try again, chosen by the player. */
    public static void retry() {
        if (connected() && REQUESTS.retry(SEND)) redraw();
    }

    /** The Book was opened: a failed gallery may ask again. */
    public static void reopened() {
        REQUESTS.reopened();
    }

    /** The server reported that this player's kingdoms or known settlements changed. */
    public static void invalidate() {
        REQUESTS.invalidate(shown());
    }

    public static void receive(GuideKingdomsPacket packet) {
        if (!REQUESTS.receive(packet.requestId())) return;
        gallery = packet;
        redraw();
    }

    public static void tick() {
        if (connected() && REQUESTS.tick(SEND, shown())) redraw();
    }

    public static void clear() {
        REQUESTS.clear();
        gallery = null;
    }

    /** The tiles in the server's order; empty until the answer arrives. */
    public static List<Pieces.KingdomTile> tiles() {
        if (REQUESTS.state() != GalleryRequests.State.READY || gallery == null) return List.of();
        List<Pieces.KingdomTile> tiles = new ArrayList<>();
        for (int i = 0; i < gallery.kingdoms().size(); i++) {
            var kingdom = gallery.kingdoms().get(i);
            tiles.add(new Pieces.KingdomTile(kingdom.heraldryIcon(), kingdom.uiColor(), Component.translatable(kingdom.translationKey()),
                    Component.translatableWithFallback("guide.mcakingdoms.kingdoms.known", "You know %s of its settlements", gallery.known().get(i))));
        }
        return tiles;
    }

    /** What the gallery is doing, in words, or empty once it shows the kingdoms. */
    public static Component status() {
        return switch (REQUESTS.state()) {
            case NONE, LOADING -> Component.translatableWithFallback("guide.mcakingdoms.kingdoms.loading", "Loading this server's kingdoms…");
            case FAILED -> Component.translatableWithFallback("guide.mcakingdoms.kingdoms.failed",
                    "This server's kingdoms could not be loaded. Choose Try again, or close and reopen the Book.");
            case READY -> {
                Component count = gallery == null || gallery.kingdoms().isEmpty()
                        ? Component.translatableWithFallback("guide.mcakingdoms.kingdoms.none", "This server defines no kingdoms.")
                        : Component.translatableWithFallback("guide.mcakingdoms.kingdoms.count", "This server has %s kingdoms:", gallery.kingdoms().size());
                yield REQUESTS.refreshFailed() ? Component.empty().append(count).append(" ").append(Component.translatableWithFallback(
                        "guide.mcakingdoms.kingdoms.refresh_failed", "These counts could not be refreshed just now and may be out of date."))
                        : count;
            }
        };
    }
}
