package dev.otectus.mcakingdoms.api.townstead;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * One MCA village as Townstead's api.v1 sees it. {@code min}/{@code max} bound its buildings and {@code rangeMargin} grows
 * that box into the town range; neither is a MCA: Kingdoms claim. {@code culture} is Townstead's civic culture, which is
 * not a kingdom, a root or a faction.
 */
public record TownsteadVillageView(ResourceLocation dimension, int villageId, String name, BlockPos center, BlockPos min, BlockPos max,
                                   int rangeMargin, int residents, int loadedResidents, int buildingCount,
                                   Optional<ResourceLocation> culture, long establishedWorldDay, boolean playerFounded) {
    public TownsteadVillageView {
        Objects.requireNonNull(dimension, "dimension");
        name = name == null ? "" : name;
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        culture = Objects.requireNonNull(culture, "culture");
    }
}
