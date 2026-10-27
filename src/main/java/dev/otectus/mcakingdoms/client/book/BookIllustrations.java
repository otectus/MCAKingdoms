package dev.otectus.mcakingdoms.client.book;

import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Each manuscript has its own packaged frontispiece; no generated or disk-loaded work during rendering. */
public enum BookIllustrations {
    CHRONICLE("kingdom", "A village, cultivated fields and a royal castle in the mountains."),
    LEDGER("ledger", "A riverside settlement with a watermill, stone bridge, gardens and a trading boat."),
    TASKS("tasks", "A guild courtyard with a carpenter, a smithy, a noticeboard and a delivery cart.");

    private static final int SOURCE_WIDTH = 2172, SOURCE_HEIGHT = 724;
    private final ResourceLocation texture;
    private final String name, fallback;

    BookIllustrations(String name, String fallback) {
        this.name = name;
        this.fallback = fallback;
        texture = new ResourceLocation("mcakingdoms", "textures/gui/book/" + name + ".png");
    }

    public ResourceLocation texture() { return texture; }
    public Component description() {
        return Component.translatableWithFallback("book.mcakingdoms.illustration." + name, fallback);
    }

    public RichPanel.Piece piece(String anchor, int width) {
        return Pieces.framedImage(anchor, texture, width, width / 3, SOURCE_WIDTH, SOURCE_HEIGHT, description());
    }

    /** Center-crop to the short chapter banner; never squash buildings to fit its available height. */
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        int sourceW = Math.min(SOURCE_WIDTH, SOURCE_HEIGHT * width / height);
        int sourceH = Math.min(SOURCE_HEIGHT, SOURCE_WIDTH * height / width);
        g.blit(texture, x, y, width, height, (SOURCE_WIDTH - sourceW) / 2.0F, (SOURCE_HEIGHT - sourceH) / 2.0F,
                sourceW, sourceH, SOURCE_WIDTH, SOURCE_HEIGHT);
        BookSkin.illustrationFrame(g, x, y, width, height);
    }
}
