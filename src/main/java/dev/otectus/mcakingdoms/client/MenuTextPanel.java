package dev.otectus.mcakingdoms.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** Read-only vanilla text field, with native clipping, focus border and draggable scrollbar. */
public final class MenuTextPanel extends AbstractScrollWidget {
    private List<Component> content = List.of();
    private MultiLineLabel text = MultiLineLabel.EMPTY;

    public MenuTextPanel(int x, int y, int width, int height, Component heading) {
        // AbstractScrollWidget puts its eight-pixel scrollbar outside its content bounds.
        super(x, y, width - 8, height, heading);
    }

    public void setContent(List<Component> value) {
        if (content.equals(value) && text != MultiLineLabel.EMPTY) return;
        content = value.stream().map(Component::copy).map(c -> (Component)c).toList();
        var message = getMessage().copy();
        for (Component paragraph : content) message.append("\n\n").append(paragraph);
        text = MultiLineLabel.create(Minecraft.getInstance().font, message, width - totalInnerPadding());
        setScrollAmount(0);
    }

    // Match the native thumb's content height to the scroll range, including both text margins.
    // Otherwise a field overflowing by only its padding can have a full-height thumb and divide by zero on drag.
    @Override protected int getInnerHeight() { return text.getLineCount() * Minecraft.getInstance().font.lineHeight + innerPadding(); }
    @Override protected int getMaxScrollAmount() { return Math.max(0, getInnerHeight() + innerPadding() - height); }
    @Override protected double scrollRate() { return Minecraft.getInstance().font.lineHeight * 3; }
    @Override protected boolean scrollbarVisible() { return getMaxScrollAmount() > 0; }

    @Override protected void renderContents(GuiGraphics graphics, int mx, int my, float partial) {
        text.renderLeftAligned(graphics, getX() + innerPadding(), getY() + innerPadding(),
                Minecraft.getInstance().font.lineHeight, 0xFFFFFF);
    }

    @Override public boolean isMouseOver(double x, double y) {
        return visible && x >= getX() && x < getX() + width + 8 && y >= getY() && y < getY() + height;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return active && button == 0 && super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        return active && isMouseOver(x, y) && delta != 0 && super.mouseScrolled(x, y, delta);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (!active || !isFocused()) return false;
        switch (key) {
            case GLFW.GLFW_KEY_PAGE_UP -> setScrollAmount(scrollAmount() - height + totalInnerPadding());
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScrollAmount(scrollAmount() + height - totalInnerPadding());
            case GLFW.GLFW_KEY_HOME -> setScrollAmount(0);
            case GLFW.GLFW_KEY_END -> setScrollAmount(getMaxScrollAmount());
            default -> { return super.keyPressed(key, scan, modifiers); }
        }
        return true;
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.USAGE, Component.translatable("menu.mcakingdoms.scroll_help"));
        var narration = Component.empty();
        content.forEach(paragraph -> narration.append(paragraph).append(". "));
        output.add(NarratedElementType.HINT, narration);
    }
}
