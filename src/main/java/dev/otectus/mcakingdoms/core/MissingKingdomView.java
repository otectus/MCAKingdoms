package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.KingdomView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;

record MissingKingdomView(ResourceLocation id) implements KingdomView {
    MissingKingdomView {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public boolean defined() {
        return false;
    }

    @Override
    public String translationKey() {
        return "kingdom." + McaKingdomsApi.MOD_ID + ".missing";
    }

    @Override
    public ResourceLocation namePool() {
        return new ResourceLocation(McaKingdomsApi.MOD_ID, "missing");
    }

    @Override
    public ResourceLocation heraldryIcon() {
        return new ResourceLocation(McaKingdomsApi.MOD_ID, "missing");
    }

    @Override
    public int uiColor() {
        return 0x777777;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("missing_definition", "true");
    }
}
