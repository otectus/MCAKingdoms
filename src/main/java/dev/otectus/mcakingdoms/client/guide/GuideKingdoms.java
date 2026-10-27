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
 * The connected server's kingdoms for the Book's gallery, requested once per connection when an article shows them and
 * forgotten on disconnect, so another server's kingdoms are never shown.
 */
public final class GuideKingdoms {
    public enum State { NONE, LOADING, READY, FAILED }
    private static final int TIMEOUT = 40, ATTEMPTS = 3;
    private static State state = State.NONE;
    private static long request;
    private static int timeout, attempts;
    private static GuideKingdomsPacket gallery;

    private GuideKingdoms() {
    }

    public static State state() {
        return state;
    }

    /** Asks the server once; later calls reuse the answer until disconnect. */
    public static void ensure() {
        if (state == State.LOADING || state == State.READY || Minecraft.getInstance().getConnection() == null) return;
        attempts = 0;
        send();
    }

    private static void send() {
        attempts++;
        state = State.LOADING;
        timeout = TIMEOUT;
        request = NetworkHandler.requestKingdomGallery();
    }

    public static void receive(GuideKingdomsPacket packet) {
        if (packet.requestId() != request || state != State.LOADING) return;
        gallery = packet;
        state = State.READY;
        if (Minecraft.getInstance().screen instanceof KingdomGuideScreen screen) screen.refreshArticle();
    }

    public static void tick() {
        if (state != State.LOADING || --timeout > 0) return;
        if (attempts < ATTEMPTS) send();
        else {
            state = State.FAILED;
            if (Minecraft.getInstance().screen instanceof KingdomGuideScreen screen) screen.refreshArticle();
        }
    }

    public static void clear() {
        state = State.NONE;
        gallery = null;
        request = 0;
    }

    /** The tiles in the server's order; empty until the answer arrives. */
    public static List<Pieces.KingdomTile> tiles() {
        if (state != State.READY || gallery == null) return List.of();
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
        return switch (state) {
            case NONE, LOADING -> Component.translatableWithFallback("guide.mcakingdoms.kingdoms.loading", "Loading this server's kingdoms…");
            case FAILED -> Component.translatableWithFallback("guide.mcakingdoms.kingdoms.failed",
                    "This server's kingdoms could not be loaded. Close and reopen the Book to try again.");
            case READY -> gallery.kingdoms().isEmpty()
                    ? Component.translatableWithFallback("guide.mcakingdoms.kingdoms.none", "This server defines no kingdoms.")
                    : Component.translatableWithFallback("guide.mcakingdoms.kingdoms.count", "This server has %s kingdoms:", gallery.kingdoms().size());
        };
    }
}
