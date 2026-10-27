package dev.otectus.mcakingdoms.client.book;

import dev.otectus.mcakingdoms.client.rich.RichPanel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Manuscript-only content pieces; semantic diagrams, recipes and links use the shared rich renderer. */
public final class BookPieces {
    private BookPieces() { }

    public static RichPanel.Piece opening(String anchor, Component text) {
        return new RichPanel.Piece(anchor) {
            private String initial = "";
            private List<FormattedCharSequence> lines = List.of();

            @Override protected int layout(Font font, int width) {
                String raw = text.getString();
                int end = raw.isEmpty() ? 0 : raw.offsetByCodePoints(0, 1);
                initial = raw.substring(0, end);
                lines = font.split(Component.literal(raw.substring(end)), Math.max(16, width - 33));
                return Math.max(28, lines.size() * lineHeight(font));
            }

            @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, RichPanel.Hover hover) {
                g.fill(x, y, x + 26, y + 28, 0x20802D34);
                g.renderOutline(x, y, 26, 28, 0xFF000000 | BookSkin.GOLD);
                g.renderOutline(x + 2, y + 2, 22, 24, 0x80000000 | BookSkin.LINE);
                g.pose().pushPose();
                g.pose().translate(x + 13 - font.width(initial), y + 6, 0);
                g.pose().scale(2, 2, 1);
                g.drawString(font, initial, 0, 0, BookSkin.RED, false);
                g.pose().popPose();
                for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x + 33, y + i * lineHeight(font), BookSkin.INK, false);
            }

            @Override protected Component narration() { return text; }
        };
    }
}
