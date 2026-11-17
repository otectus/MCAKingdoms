package dev.otectus.mcakingdoms.api.townstead;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An entity's Townstead career: villager or player alike. A profession tier is Townstead's own progression; it is not a
 * vanilla trade level or a MCA: Kingdoms guild rank.
 */
public record TownsteadCareerView(Optional<String> primaryVocation, Set<String> acquiredCareers, Set<ResourceLocation> learnedSkills,
                                  List<Progress> progress) {
    public record Progress(String professionId, int xp, int tier, int maxTier) {
        public Progress {
            Objects.requireNonNull(professionId, "professionId");
        }
    }

    public TownsteadCareerView {
        primaryVocation = Objects.requireNonNull(primaryVocation, "primaryVocation");
        acquiredCareers = Set.copyOf(Objects.requireNonNull(acquiredCareers, "acquiredCareers"));
        learnedSkills = Set.copyOf(Objects.requireNonNull(learnedSkills, "learnedSkills"));
        progress = List.copyOf(Objects.requireNonNull(progress, "progress"));
    }

    public Optional<Progress> progress(String professionId) {
        return progress.stream().filter(p -> p.professionId().equals(professionId)).findFirst();
    }
}
