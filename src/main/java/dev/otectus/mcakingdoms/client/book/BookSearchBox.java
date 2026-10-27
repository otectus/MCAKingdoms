package dev.otectus.mcakingdoms.client.book;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Native editable search, with recessed leather and a visible gilt focus border. */
public final class BookSearchBox extends EditBox {
    private final Font hintFont;
    private Component fullHint;

    public BookSearchBox(Font font, int x, int y, int w, int h, Component label) {
        super(font, x, y, w, h, label);
        hintFont = font;
        setBordered(false);
        setTextColor(BookSkin.CONTROL_TEXT);
        setTextColorUneditable(BookSkin.CONTROL_TEXT);
        setHint(label.copy().withStyle(style -> style.withColor(BookSkin.CONTROL_TEXT)));
    }

    @Override public int getInnerWidth() { return width - 8; }

    @Override public void setHint(Component hint) {
        fullHint = hint;
        updateHint();
    }

    @Override public void setWidth(int width) {
        super.setWidth(width);
        updateHint();
    }

    private void updateHint() {
        if (fullHint == null || hintFont == null) { super.setHint(fullHint); return; }
        super.setHint(Component.literal(hintFont.ellipsize(fullHint, Math.max(1, getInnerWidth())).getString())
                .withStyle(style -> style.withColor(BookSkin.CONTROL_TEXT)));
        setTooltip(hintFont.width(fullHint) > getInnerWidth() ? Tooltip.create(fullHint) : null);
    }

    @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        BookSkin.field(g, getX(), getY(), width, height, isFocused(), isHovered());
        g.enableScissor(getX() + 3, getY() + 2, getX() + width - 3, getY() + height - 2);
        g.pose().pushPose();
        g.pose().translate(4, (height - 8) / 2.0F, 0);
        super.renderWidget(g, mx - 4, my - (height - 8) / 2, partial);
        g.pose().popPose();
        g.disableScissor();
    }

    @Override public void onClick(double mx, double my) { super.onClick(mx - 4, my); }
}
