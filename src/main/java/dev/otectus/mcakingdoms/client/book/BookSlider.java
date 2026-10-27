package dev.otectus.mcakingdoms.client.book;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Native slider interaction and narration with the manuscript track, gilt handle and white label. */
public abstract class BookSlider extends AbstractSliderButton {
    protected BookSlider(int x, int y, int width, int height, double value) {
        super(x, y, width, height, Component.empty(), Mth.clamp(value, 0, 1));
        updateMessage();
    }

    @Override public void renderWidget(GuiGraphics graphics, int x, int y, float partial) {
        BookSkin.field(graphics, getX(), getY(), width, height, isFocused(), isHovered());
        int handle = getX() + (int) (value * (width - 8));
        BookSkin.button(graphics, handle, getY(), 8, height, active, active && isHoveredOrFocused(), false);
        renderScrollingString(graphics, Minecraft.getInstance().font, 5, BookSkin.CONTROL_TEXT);
        if (isFocused()) BookSkin.focus(graphics, getX(), getY(), width, height);
    }
}
