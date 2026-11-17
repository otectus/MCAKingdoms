package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.*;

/**
 * Townstead careers as players read them (AUD-15): the professions and skills the installed Townstead knows, with names
 * instead of ids, for the career check and for guild requirements. Empty means Townstead's registries cannot be read on
 * this server, which is "cannot check", never "not qualified".
 */
public final class TownsteadCareers {
    private TownsteadCareers() {
    }

    /** One profession: its id (kept for technical detail), its name, and the highest tier Townstead gives it. */
    public record ProfessionChoice(String id, Component name, int maxTier, boolean progressive) { }

    /** One learned skill: its id and its name. */
    public record SkillChoice(String id, Component name) { }

    private static Optional<TownsteadBackend> backend(MinecraftServer server) {
        return TownsteadRuntime.session(server).map(s -> s.service().backendImpl());
    }

    /** A profession's name: the villager profession's own translation, then the words of its path. */
    public static Component professionName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return Component.literal(id);
        return Component.translatableWithFallback("entity." + key.getNamespace() + ".villager." + key.getPath(),
                Component.translatableWithFallback("townstead.profession." + key.getPath(), ActionRegistry.words(key.getPath())).getString());
    }

    /** A skill's name: Townstead's translation when it has one, then the words of its path. */
    public static Component skillName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return Component.literal(id);
        return Component.translatableWithFallback(key.getNamespace() + ".skill." + key.getPath(), ActionRegistry.words(key.getPath().replace('/', ' ')));
    }

    /** The professions Townstead knows (hidden ones left out), or empty when they cannot be read. */
    public static Optional<List<ProfessionChoice>> professions(MinecraftServer server) {
        try {
            var backend = backend(server);
            if (backend.isEmpty()) return Optional.empty();
            var ids = backend.get().registryIds(TownsteadBackend.Registry.PROFESSIONS);
            if (ids.isEmpty()) return Optional.empty();
            var out = new ArrayList<ProfessionChoice>();
            for (String id : ids.get().stream().sorted().limit(256).toList()) {
                var profession = backend.get().profession(id);
                if (profession.isPresent() && profession.get().hidden()) continue;
                int tiers = profession.map(p -> Math.max(1, p.maxTier())).orElse(1);
                out.add(new ProfessionChoice(id, professionName(id), tiers, profession.map(TownsteadBackend.Profession::progressive).orElse(false)));
            }
            return Optional.of(out);
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
    }

    /** The skills Townstead knows, or empty when they cannot be read. */
    public static Optional<List<SkillChoice>> skills(MinecraftServer server) {
        try {
            var backend = backend(server);
            if (backend.isEmpty()) return Optional.empty();
            return backend.get().registryIds(TownsteadBackend.Registry.SKILLS)
                    .map(ids -> ids.stream().sorted().limit(256).map(id -> new SkillChoice(id, skillName(id))).toList());
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
    }
}
