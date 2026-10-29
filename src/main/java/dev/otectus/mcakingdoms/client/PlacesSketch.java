package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.client.rich.RichPanel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A sketch of the places a player knows (H10): the settlements on the Ledger page that share the player's dimension,
 * drawn around the player, the chosen one marked. It draws only what the server already listed for this viewer, so it
 * shows no place the player has not discovered. Exact positions stay in the record's Location field; there is no map
 * provider to hand off to (Map Atlases exposes no marker API), so the sketch is the bounded schematic.
 */
final class PlacesSketch extends RichPanel.Piece {
    record Place(String name, BlockPos pos, boolean chosen) { }

    private static final int HEIGHT = 96, INK = 0xFF3B2A1E, CHOSEN = 0xFF8C3B2E, FAINT = 0x553B2A1E;
    private final BlockPos centre;
    private final List<Place> places;
    private final Component narration;
    private int shownWidth;

    PlacesSketch(String anchor, BlockPos centre, List<Place> places, Component narration) {
        super(anchor);
        this.centre = centre; this.places = List.copyOf(places); this.narration = narration;
    }

    /** Blocks per pixel, so the farthest place fits (and never closer than 64 blocks to the edge). */
    private double scale(int radius) {
        double far = 64;
        for (var place : places) far = Math.max(far, Math.sqrt(Math.pow(place.pos().getX() - centre.getX(), 2) + Math.pow(place.pos().getZ() - centre.getZ(), 2)));
        return far / Math.max(8, radius - 6);
    }

    @Override protected int layout(Font font, int width) {
        shownWidth = width;
        return HEIGHT + 4;
    }

    @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mouseX, int mouseY, RichPanel.Hover hover) {
        int cx = x + shownWidth / 2, cy = y + HEIGHT / 2, radius = Math.min(shownWidth / 2, HEIGHT / 2);
        g.fill(x, y, x + shownWidth, y + HEIGHT, 0x18000000);
        g.fill(cx - radius, cy, cx + radius, cy + 1, FAINT);
        g.fill(cx, cy - radius, cx + 1, cy + radius, FAINT);
        g.drawString(font, "N", cx - font.width("N") / 2, y + 1, ink(INK), false);
        g.fill(cx - 3, cy, cx + 4, cy + 1, ink(INK));
        g.fill(cx, cy - 3, cx + 1, cy + 4, ink(INK));
        double scale = scale(radius);
        for (var place : places) {
            int px = cx + (int) Math.round((place.pos().getX() - centre.getX()) / scale);
            int pz = cy + (int) Math.round((place.pos().getZ() - centre.getZ()) / scale);
            int size = place.chosen() ? 3 : 2;
            g.fill(px - size, pz - size, px + size + 1, pz + size + 1, ink(place.chosen() ? CHOSEN : INK));
            if (mouseX != Integer.MIN_VALUE && Math.abs(x + mouseX - px) <= 4 && Math.abs(y + mouseY - pz) <= 4)
                hover.set(List.of(Component.literal(place.name()).getVisualOrderText()));
        }
    }

    @Override protected Component narration() { return narration; }
}
