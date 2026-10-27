package dev.otectus.mcakingdoms.client.book;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** Pixel manuscript materials and ornaments, shared by the chronicle, ledger and task folio. */
public final class BookSkin {
    public static final int INK = 0x38291F, MUTED = 0x68513A, RED = 0x802D34, GOLD = 0x92703B;
    public static final int PAPER = 0xF2E6C9, LINE = 0xBA9A66, FOCUS = 0xF3D58A;
    public static final int CONTROL_TEXT = 0xFFFFFF;
    public static final ResourceLocation MATERIALS = new ResourceLocation("mcakingdoms", "textures/gui/book/materials.png");

    public enum Binding {
        CHRONICLE(128, 0), LEDGER(192, 0), TASKS(128, 64);
        private final int u, v;
        Binding(int u, int v) { this.u = u; this.v = v; }
    }

    private BookSkin() { }

    public static FormattedCharSequence inkText(FormattedCharSequence text) {
        return sink -> text.accept((index, style, codepoint) -> sink.accept(index,
                style.getColor() == null ? style : style.withColor(ink(style.getColor().getValue())), codepoint));
    }

    public static FormattedCharSequence controlText(FormattedCharSequence text) {
        return sink -> text.accept((index, style, codepoint) -> sink.accept(index, style.withColor(CONTROL_TEXT), codepoint));
    }

    /** The paper field's thumb uses the native scrollbar hit area and scroll range. */
    public static void scrollbar(GuiGraphics g, int x, int top, int height, int innerHeight, double scroll, int maxScroll) {
        if (maxScroll <= 0 || height <= 0) return;
        int thumb = Math.min(height, Math.max(12, height * height / Math.max(1, innerHeight)));
        int y = top + (int) (scroll / maxScroll * (height - thumb));
        g.fill(x + 2, top, x + 6, top + height, 0x30000000 | GOLD);
        g.fill(x + 1, y, x + 7, y + thumb, 0xFF000000 | MUTED);
        g.fill(x + 2, y + 1, x + 6, y + thumb - 1, 0xFF000000 | GOLD);
        g.fill(x + 2, y + 1, x + 6, y + 2, 0xFF000000 | FOCUS);
        if (thumb >= 10) {
            int middle = y + thumb / 2;
            g.fill(x + 2, middle, x + 6, middle + 1, 0xFF000000 | MUTED);
            g.fill(x + 2, middle + 2, x + 6, middle + 3, 0xFF000000 | MUTED);
        }
    }

    /** Converts the existing rich-panel palette to legible manuscript inks, leaving other panels alone. */
    public static int ink(int color) {
        int alpha = color & 0xFF000000, rgb = color & 0xFFFFFF;
        int mapped = switch (rgb) {
            case 0xFFFFFF -> INK;
            case 0xC0C0C0, 0xA0A0A0, 0x808080 -> MUTED;
            case 0xFFE080, 0xFFAA00, 0xFFFF55 -> RED;
            case 0x55FF55 -> 0x355D35;
            case 0xFF7070, 0xFF5555, 0xAA0000 -> RED;
            case 0x55FFFF, 0x55AAFF -> 0x285865;
            default -> rgb;
        };
        return alpha | mapped;
    }

    public static void cover(GuiGraphics g, int x, int y, int width, int height, boolean spread) {
        cover(g, x, y, width, height, spread, Binding.CHRONICLE);
    }

    /** Small branded plaque for embedded headers and HUD arrivals. Texture-only so shader alpha fades every layer. */
    public static void plaque(GuiGraphics g, int x, int y, int width, int height, Binding binding) {
        g.blitNineSliced(MATERIALS, x, y, width, height, 3, 64, 64, binding.u, binding.v);
        g.blitNineSliced(MATERIALS, x + 1, y + 1, width - 2, height - 2, 2, 64, 32, 0, 128);
        g.blitNineSliced(MATERIALS, x + 3, y + 3, width - 6, height - 6, 3, 128, 128, 0, 0);
    }

    public static void cover(GuiGraphics g, int x, int y, int width, int height, boolean spread, Binding binding) {
        g.fill(x + 2, y + 3, x + width + 4, y + height + 5, 0x30000000);
        g.fill(x + 4, y + 6, x + width + 2, y + height + 7, 0x20000000);
        g.blitNineSliced(MATERIALS, x, y, width, height, 8, 64, 64, binding.u, binding.v);
        // Gilt tooling and the edges of the page block remain pixel crisp at every GUI scale.
        g.renderOutline(x + 4, y + 4, width - 8, height - 8, 0xFF9B7141);
        g.renderOutline(x + 6, y + 6, width - 12, height - 12, 0x80000000);
        int innerX = x + 12, innerY = y + 10, innerW = width - 24, innerH = height - 22;
        if (spread) {
            int half = innerW / 2;
            leaf(g, innerX, innerY, half - 2, innerH, true);
            leaf(g, innerX + half + 2, innerY, innerW - half - 2, innerH, false);
            g.fill(innerX + half - 2, innerY + 2, innerX + half + 2, innerY + innerH - 1, 0xFF765335);
            // Narrow, graduated spine shadow; the writing area stays uniformly lit.
            for (int i = 0; i < 4; i++) {
                int shade = ((4 - i) * 10) << 24 | 0x6B4B2E;
                g.fill(innerX + half - 3 - i, innerY + 2, innerX + half - 2 - i, innerY + innerH - 2, shade);
                g.fill(innerX + half + 2 + i, innerY + 2, innerX + half + 3 + i, innerY + innerH - 2, shade);
            }
        } else leaf(g, innerX, innerY, innerW, innerH, false);
        corner(g, x + 5, y + 5, 1, 1);
        corner(g, x + width - 6, y + 5, -1, 1);
        corner(g, x + 5, y + height - 6, 1, -1);
        corner(g, x + width - 6, y + height - 6, -1, -1);
    }

    private static void leaf(GuiGraphics g, int x, int y, int w, int h, boolean left) {
        g.fill(x, y + 3, x + w, y + h + 3, 0xFF9B8056);
        g.fill(x + 1, y + h, x + w - 1, y + h + 1, 0xFFE8D0A1);
        g.fill(x + 1, y + h + 2, x + w - 1, y + h + 3, 0xFFD4B580);
        g.blitNineSliced(MATERIALS, x, y, w, h, 8, 128, 128, 0, 0);
        int edge = left ? x + w - 5 : x;
        g.fill(edge, y + 3, edge + 1, y + h - 1, 0x35937849);
        g.fill(edge + 1, y + 3, edge + 3, y + h - 1, 0x20937849);
    }

    private static void corner(GuiGraphics g, int x, int y, int dx, int dy) {
        for (int i = 0; i < 9; i++) {
            g.fill(x + i * dx, y, x + i * dx + 1, y + 1, 0xFFCBA65C);
            g.fill(x, y + i * dy, x + 1, y + i * dy + 1, 0xFFCBA65C);
        }
        g.fill(x + 3 * dx, y + 3 * dy, x + 3 * dx + 2, y + 3 * dy + 2, 0xFFD7B66C);
    }

    public static void divider(GuiGraphics g, int x, int y, int width) {
        int middle = x + width / 2;
        g.fill(x, y, middle - 5, y + 1, 0xFF000000 | LINE);
        g.fill(middle + 6, y, x + width, y + 1, 0xFF000000 | LINE);
        g.fill(middle - 2, y - 1, middle + 3, y + 2, 0xFF000000 | GOLD);
        g.fill(middle - 1, y - 2, middle + 2, y + 3, 0xFF000000 | GOLD);
    }

    public static void title(GuiGraphics g, Font font, Component text, int x, int y, int width) {
        var line = Language.getInstance().getVisualOrder(font.ellipsize(text, width));
        g.drawString(font, line, x + (width - font.width(line)) / 2, y, RED, false);
    }

    public static void button(GuiGraphics g, int x, int y, int w, int h, boolean active, boolean hover, boolean row) {
        button(g, x, y, w, h, active, hover, row, false);
    }

    public static void button(GuiGraphics g, int x, int y, int w, int h, boolean active, boolean hover, boolean row, boolean selected) {
        int tile = selected ? 3 : !active ? 2 : hover ? 1 : 0;
        g.blitNineSliced(MATERIALS, x, y, w, h, 3, 64, 32, tile * 64, 128);
        if (row) g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, 0x35000000);
        if (selected) {
            g.fill(x + 2, y + 3, x + 4, y + h - 3, 0xFF000000 | FOCUS);
            // A physical tab, not just a change of color, marks the current entry.
            g.fill(x + 4, y + h / 2 - 1, x + 6, y + h / 2 + 2, 0xFF000000 | FOCUS);
        }
        if (hover && active) g.renderOutline(x, y, w, h, 0xFF000000 | FOCUS);
    }

    public static void focus(GuiGraphics g, int x, int y, int w, int h) {
        g.renderOutline(x + 2, y + 2, w - 4, h - 4, 0xFF000000 | FOCUS);
    }

    /** Recessed dark leather distinguishes an editable field from the raised action buttons. */
    public static void field(GuiGraphics g, int x, int y, int w, int h, boolean focused, boolean hover) {
        g.fill(x, y, x + w, y + h, 0xFF3C3029);
        g.renderOutline(x, y, w, h, 0xFF000000 | (focused ? FOCUS : hover ? LINE : GOLD));
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF251D19);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF71604A);
        if (focused) g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, 0xFF000000 | FOCUS);
    }

    /** Fine gilt mat stays inside the image's bounds, including a scrolled/clipped page. */
    public static void illustrationFrame(GuiGraphics g, int x, int y, int w, int h) {
        if (w < 7 || h < 7) return;
        g.renderOutline(x, y, w, h, 0xFF000000 | GOLD);
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, 0xFF000000 | PAPER);
        g.renderOutline(x + 2, y + 2, w - 4, h - 4, 0xFF000000 | MUTED);
    }
}
