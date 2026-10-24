package dev.otectus.mcakingdoms.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

/** Shared vanilla panel skin. Uses the active resource pack's Minecraft texture. */
public final class VanillaGui {
    private static final ResourceLocation PANEL = new ResourceLocation("minecraft", "textures/gui/demo_background.png");
    public static final int TEXT = 0x404040;
    public static final int SECONDARY = 0x404040;
    public static final int ERROR = 0xAA0000;
    public static final int STATUS = 0x404040;

    private VanillaGui() { }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        if (width < 8 || height < 8) return;
        // Keep the original pixel-sized bevel; tile the middle rather than stretching the border.
        graphics.blitNineSliced(PANEL, x, y, width, height, 4, 248, 166, 0, 0);
    }

    public static void title(GuiGraphics graphics, Font font, Component title, int center, int y, int width) {
        var text = Language.getInstance().getVisualOrder(font.ellipsize(title, Math.max(0, width)));
        graphics.drawString(font, text, center - font.width(text) / 2, y, TEXT, false);
    }
}
