package dev.otectus.mcakingdoms.client.book;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** A vanilla Button (focus, narration, tooltip and input) with high-contrast tooled leather. */
public final class BookButton extends Button {
    public enum Kind { CONTROL, INDEX, RIBBON }
    private Kind kind = Kind.CONTROL;
    private String glyph;
    private boolean selected;
    private Component overflowLabel;
    private Tooltip overflowTooltip;
    private int overflowWidth = -1;

    private BookButton(Button.Builder builder) { super(builder); }

    public BookButton kind(Kind value) { kind = value; return this; }
    /** The visible symbol can be short while the button keeps its full localized narration. */
    public BookButton glyph(String value) { glyph = value; return this; }
    public BookButton selected(boolean value) { selected = value; return this; }

    public static Builder builder(Component label, OnPress press) { return new Builder(label, press); }

    @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        boolean hover = active && isHoveredOrFocused();
        if (kind == Kind.RIBBON) {
            g.fill(getX() + 1, getY() + 2, getX() + width + 1, getY() + height + 1, 0x40000000);
            g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF000000 | (hover ? 0x893C3F : BookSkin.RED));
            g.renderOutline(getX(), getY(), width, height, selected || hover ? 0xFF000000 | BookSkin.FOCUS : 0xFFBA9557);
            if (selected) g.fill(getX() + 2, getY() + height - 3, getX() + width - 2, getY() + height - 1, 0xFF000000 | BookSkin.FOCUS);
        } else BookSkin.button(g, getX(), getY(), width, height, active, hover, kind == Kind.INDEX, selected);
        var font = Minecraft.getInstance().font;
        // Explicit help wins. Otherwise clipped/localized names remain available without waiting for scrolling text.
        if (glyph == null && (getTooltip() == null || getTooltip() == overflowTooltip)
                && (overflowWidth != width || !getMessage().equals(overflowLabel))) {
            overflowWidth = width;
            overflowLabel = getMessage().copy();
            overflowTooltip = font.width(getMessage()) > width - (kind == Kind.INDEX ? 18 : 8) ? Tooltip.create(getMessage()) : null;
            setTooltip(overflowTooltip);
        }
        int color = BookSkin.CONTROL_TEXT;
        if (glyph != null) {
            g.drawString(font, glyph, getX() + (width - font.width(glyph)) / 2, getY() + (height - 8) / 2, color, true);
        } else if (kind == Kind.INDEX) {
            var line = net.minecraft.locale.Language.getInstance().getVisualOrder(font.ellipsize(getMessage(), width - 18));
            g.drawString(font, BookSkin.controlText(line), getX() + 10, getY() + (height - 8) / 2, color, true);
        } else renderScrollingString(g, font, 4, color);
        if (isFocused()) BookSkin.focus(g, getX(), getY(), width, height);
    }

    public static final class Builder extends Button.Builder {
        private Builder(Component label, OnPress press) { super(label, press); }
        @Override public Builder bounds(int x, int y, int w, int h) { super.bounds(x, y, w, h); return this; }
        @Override public Builder tooltip(Tooltip tip) { super.tooltip(tip); return this; }
        @Override public Builder createNarration(CreateNarration narration) { super.createNarration(narration); return this; }
        @Override public BookButton build() { return (BookButton) super.build(BookButton::new); }
    }
}
