package dev.otectus.mcakingdoms.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Vanilla advancement-toast styling, retaining the configured arrival position and fade timing. */
final class SettlementOverlayRenderer {
    private static final int HEIGHT = 32;
    private static final ResourceLocation TOASTS = new ResourceLocation("minecraft", "textures/gui/toasts.png");

    private SettlementOverlayRenderer() { }

    static void render(GuiGraphics graphics, Font font, SettlementSummary settlement, KingdomSummary kingdom,
                       int screenWidth, int screenHeight, int configuredY, int ticks, int duration, float partialTick) {
        float remaining = ticks - partialTick;
        float opacity = Math.min(Mth.clamp((duration - remaining) / 8.0F, 0, 1),
                Mth.clamp(remaining / 12.0F, 0, 1));
        // Minecraft treats near-zero text alpha as opaque; skip invisible frames entirely.
        if (opacity < 0.02F) return;

        Component title = Component.literal(settlement.displayName());
        Component subtitle = Component.translatable("message.mcakingdoms.kingdom_of",
                Component.translatable(kingdom.translationKey()));
        int width = Math.min(Math.max(160, Math.max(font.width(title), font.width(subtitle)) + 38),
                Math.min(360, screenWidth - 16));
        if (width < 100 || screenHeight < HEIGHT + 4) return;
        int left = (screenWidth - width) / 2;
        int top = Mth.clamp(configuredY, 2, screenHeight - HEIGHT - 2);
        ResourceLocation logical = kingdom.heraldryIcon();
        ResourceLocation texture = new ResourceLocation(logical.getNamespace(),
                "textures/gui/heraldry/" + logical.getPath() + ".png");
        RenderSystem.enableBlend();
        graphics.setColor(1, 1, 1, opacity);
        try {
            // AdvancementToast uses the icon-free 160 x 32 frame at (0, 0).
            graphics.blitNineSliced(TOASTS, left, top, width, HEIGHT, 4, 160, 32, 0, 0);
            graphics.blit(texture, left + 8, top + 8, 0.0F, 0.0F, 16, 16, 16, 16);
        } finally {
            graphics.setColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        }
        var language = Language.getInstance();
        graphics.drawString(font, language.getVisualOrder(font.ellipsize(title, width - 38)),
                left + 30, top + 7, color(0xFFFF00, opacity), false);
        graphics.drawString(font, language.getVisualOrder(font.ellipsize(subtitle, width - 38)),
                left + 30, top + 18, color(0xFFFFFF, opacity), false);
    }

    private static int color(int rgb, float opacity) {
        return (Mth.clamp(Math.round(opacity * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
