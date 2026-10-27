package dev.otectus.mcakingdoms.client.book;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;

/** Leather presentation around a native cycle button; native values, Shift/wheel cycling and narration are retained. */
public final class BookCycleButton<T> extends AbstractButton {
    private final CycleButton<T> cycle;

    public BookCycleButton(CycleButton<T> cycle) {
        super(cycle.getX(), cycle.getY(), cycle.getWidth(), cycle.getHeight(), cycle.getMessage());
        this.cycle = cycle;
        sync();
    }

    private void sync() {
        cycle.active = active;
        cycle.visible = visible;
        setMessage(cycle.getMessage());
        setTooltip(cycle.getTooltip());
    }
    @Override public void onPress() { cycle.onPress(); sync(); }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (!active || !visible || !isMouseOver(x, y)) return false;
        boolean handled = cycle.mouseScrolled(x, y, delta);
        sync();
        return handled;
    }
    @Override public void setFocused(boolean focused) { super.setFocused(focused); cycle.setFocused(focused); }
    @Override public void updateWidgetNarration(NarrationElementOutput output) { cycle.updateWidgetNarration(output); }
    @Override public void renderWidget(GuiGraphics graphics, int x, int y, float partial) {
        sync();
        BookSkin.button(graphics, getX(), getY(), width, height, active, isHoveredOrFocused(), false);
        renderScrollingString(graphics, Minecraft.getInstance().font, 4, BookSkin.CONTROL_TEXT);
        if (isFocused()) BookSkin.focus(graphics, getX(), getY(), width, height);
    }
}
