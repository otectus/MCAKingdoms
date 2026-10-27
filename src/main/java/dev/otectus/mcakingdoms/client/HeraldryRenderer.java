package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.client.book.BookSkin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class HeraldryRenderer {
    private HeraldryRenderer() {
    }

    public static void render(GuiGraphics graphics, KingdomSummary kingdom, int x, int y, int size) {
        render(graphics, kingdom.heraldryIcon(), kingdom.uiColor(), x, y, size);
    }

    public static void render(GuiGraphics graphics, ResourceLocation logical, int uiColor,
                              int x, int y, int size) {
        renderInBook(graphics, logical, x, y, size);
    }

    public static void renderInBook(GuiGraphics graphics, ResourceLocation logical, int x, int y, int size) {
        graphics.fill(x - 3, y - 3, x + size + 3, y + size + 3, 0xFFE5CEA2);
        graphics.renderOutline(x - 3, y - 3, size + 6, size + 6, 0xFF000000 | BookSkin.GOLD);
        draw(graphics, logical, x, y, size);
    }

    private static void draw(GuiGraphics graphics, ResourceLocation logical, int x, int y, int size) {
        ResourceLocation texture = new ResourceLocation(logical.getNamespace(),
                "textures/gui/heraldry/" + logical.getPath() + ".png");
        graphics.blit(texture, x, y, 0.0F, 0.0F, size, size, size, size);
    }
}
