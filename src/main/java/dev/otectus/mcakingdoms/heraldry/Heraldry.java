package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.api.KingdomView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;

import java.util.Optional;

/**
 * A kingdom's heraldry as the world shows it (B1): its colours, its sigil and its motto. Everything comes from the
 * server: the kingdom's definition (the {@code heraldry} icon and colour, and optional metadata
 * {@code heraldry_secondary} and {@code heraldry_motto}), overridden by the colours its ruler chose. Clients receive
 * only these bounded hints; no texture path or colour is ever taken from an item or a client message.
 */
public final class Heraldry {
    /** The hints a heraldic object renders from; {@code revision} changes whenever the ruler changes the colours. */
    public record View(String kingdom, int primary, int secondary, ResourceLocation icon, String nameKey, String mottoKey, long revision) { }

    private Heraldry() {
    }

    public static Optional<View> of(MinecraftServer server, String kingdom) {
        var id = ResourceLocation.tryParse(kingdom == null ? "" : kingdom);
        if (id == null) return Optional.empty();
        return McaKingdomsApi.get(server).getKingdom(id).filter(KingdomView::defined).map(definition -> {
            var choice = dev.otectus.mcakingdoms.document.DocumentLedger.get(server).colours(kingdom);
            int primary = choice.map(c -> c.primary().getTextColor()).orElse(definition.uiColor() & 0xFFFFFF);
            int secondary = choice.map(c -> c.secondary().getTextColor())
                    .orElse(color(definition.metadata().get("heraldry_secondary")).orElse(shade(primary)));
            String motto = definition.metadata().getOrDefault("heraldry_motto", "");
            // Mourning (A4) darkens the second tincture while it lasts; the stored colours are untouched, so it ends exactly.
            boolean mourning = dev.otectus.mcakingdoms.politics.dynasty.DynastyService.mourning(server, kingdom);
            return new View(kingdom, primary, mourning ? shade(shade(secondary)) : secondary, definition.heraldryIcon(), definition.translationKey(),
                    motto.matches("[a-z0-9_.]{1,128}") ? motto : "", choice.map(c -> c.revision()).orElse(0L) * 2 + (mourning ? 1 : 0));
        });
    }

    /** A darker shade of {@code rgb}, the default second tincture. */
    static int shade(int rgb) {
        int r = (rgb >> 16 & 255) * 55 / 100, g = (rgb >> 8 & 255) * 55 / 100, b = (rgb & 255) * 55 / 100;
        return r << 16 | g << 8 | b;
    }

    /** A {@code #RRGGBB} colour from definition metadata, or empty when absent or malformed. */
    static Optional<Integer> color(String value) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) return Optional.empty();
        return Optional.of(Integer.parseInt(value.substring(1), 16));
    }

    /** The dye colours a ruler may choose from; their names are the accessible description (colour is never the only cue). */
    public static DyeColor[] palette() {
        return DyeColor.values();
    }
}
