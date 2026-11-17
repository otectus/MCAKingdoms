package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.progression.ProgressionPredicate;
import dev.otectus.mcakingdoms.api.progression.ProgressionResult;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCareerView;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Townstead career prerequisites (spec 9.1), read only: a player's tier in one canonical profession, a learned skill, or a
 * tier in any profession of an explicit list. A profession is resolved through Townstead's own registry first, so an
 * alias or bare path means what Townstead means by it, and a profession without a progression track is reported as an
 * unsupported requirement rather than becoming an impossible one. When careers are absent, unreadable or switched off in
 * the world the answer is "unavailable", never a failed qualification, and the guild rules that use it do not apply it.
 */
public final class TownsteadProgression {
    private TownsteadProgression() {
    }

    public static boolean handles(ProgressionPredicate.Kind kind) {
        return kind == ProgressionPredicate.Kind.TOWNSTEAD_PROFESSION_TIER || kind == ProgressionPredicate.Kind.TOWNSTEAD_SKILL
                || kind == ProgressionPredicate.Kind.TOWNSTEAD_PROFESSION_ANY;
    }

    public static ProgressionResult evaluate(ServerPlayer player, ProgressionPredicate predicate) {
        if (!TownsteadIntegrationConfig.CAREER_REQUIREMENTS.get()) return unavailable("progression.townstead_disabled");
        Optional<TownsteadRuntime.Session> session = TownsteadRuntime.session(player.getServer());
        if (session.isEmpty()) return new ProgressionResult(ProgressionResult.Status.ABSENT, false, "progression.provider_absent");
        TownsteadServiceImpl townstead = session.get().service();
        TownsteadCapabilityState state = townstead.states().state(TownsteadCapability.READ_CAREERS);
        switch (state.status()) {
            case ABSENT -> { return new ProgressionResult(ProgressionResult.Status.ABSENT, false, "progression.provider_absent"); }
            case UNSUPPORTED -> { return new ProgressionResult(ProgressionResult.Status.UNSUPPORTED, false, "progression.provider_unsupported"); }
            case DISABLED -> { return unavailable("progression.townstead_careers_disabled"); }
            default -> { }
        }
        TownsteadBackend backend = townstead.backendImpl();
        Optional<TownsteadCareerView> career = backend.career(player);
        return switch (predicate.kind()) {
            case TOWNSTEAD_SKILL -> {
                ResourceLocation skill = ResourceLocation.tryParse(predicate.subject());
                if (skill == null || !backend.knownSkill(skill).orElse(false)) yield unavailable("progression.townstead_unknown_skill");
                Optional<Boolean> has = backend.hasSkill(player, skill);
                yield has.isEmpty() ? unavailable("progression.townstead_unavailable") : matched(has.get());
            }
            case TOWNSTEAD_PROFESSION_TIER, TOWNSTEAD_PROFESSION_ANY -> {
                List<String> professions = predicate.kind() == ProgressionPredicate.Kind.TOWNSTEAD_PROFESSION_TIER
                        ? List.of(predicate.subject()) : List.of(predicate.subject().split("\\|"));
                boolean anyTracked = false;
                String unknown = "";
                for (String raw : professions) {
                    Optional<TownsteadBackend.Profession> profession = backend.profession(raw.trim());
                    if (profession.isEmpty()) { unknown = "progression.townstead_unknown_profession"; continue; }
                    if (!profession.get().progressive()) { if (unknown.isEmpty()) unknown = "progression.townstead_no_track"; continue; }
                    anyTracked = true;
                    int tier = career.flatMap(c -> c.progress(profession.get().id())).map(TownsteadCareerView.Progress::tier).orElse(0);
                    if (tier >= Math.max(1, predicate.minimum())) yield matched(true);
                }
                if (!anyTracked) yield unavailable(unknown.isEmpty() ? "progression.townstead_unknown_profession" : unknown);
                if (career.isEmpty()) yield unavailable("progression.townstead_unavailable");
                yield matched(false);
            }
            default -> unavailable("progression.townstead_unavailable");
        };
    }

    /** One mapped prerequisite as a predicate. */
    public static ProgressionPredicate predicateOf(TownsteadMappings.Requirement requirement) {
        return switch (requirement.kind()) {
            case SKILL -> new ProgressionPredicate(ProgressionPredicate.Kind.TOWNSTEAD_SKILL, requirement.skill().orElseThrow().toString(), 1);
            case PROFESSION_TIER -> new ProgressionPredicate(ProgressionPredicate.Kind.TOWNSTEAD_PROFESSION_TIER, requirement.professions().get(0), requirement.minimumTier());
            case ANY_PROFESSION -> new ProgressionPredicate(ProgressionPredicate.Kind.TOWNSTEAD_PROFESSION_ANY, String.join("|", requirement.professions()), requirement.minimumTier());
        };
    }

    /** What a prerequisite says, for requirement rows: exactly which careers count, never "any profession". */
    public static Component describe(TownsteadMappings.Requirement requirement) {
        return switch (requirement.kind()) {
            // AUD-15: names, not ids; "Check a Townstead career" offers the same choices by name.
            case SKILL -> TownsteadText.tr("career.skill", "The Townstead skill %s", requirement.skill().map(s -> TownsteadCareers.skillName(s.toString())).orElse(Component.literal("?")));
            case PROFESSION_TIER -> TownsteadText.tr("career.profession", "Townstead career %s at tier %s or higher", TownsteadCareers.professionName(requirement.professions().get(0)), requirement.minimumTier());
            case ANY_PROFESSION -> {
                var names = Component.empty();
                for (int i = 0; i < requirement.professions().size(); i++) { if (i > 0) names.append(", "); names.append(TownsteadCareers.professionName(requirement.professions().get(i))); }
                yield TownsteadText.tr("career.any", "Any of the Townstead careers %s at tier %s or higher", names, requirement.minimumTier());
            }
        };
    }

    /** The mapped career prerequisites of one guild service a player has not met; unavailable ones are not applied. */
    public static List<String> unmet(ServerPlayer player, ResourceLocation organization, ResourceLocation service) {
        List<String> out = new ArrayList<>();
        for (TownsteadMappings.Career career : TownsteadMappings.careers(organization, service)) {
            ProgressionResult result = evaluate(player, predicateOf(career.requires()));
            if (result.status() == ProgressionResult.Status.AVAILABLE && !result.matches())
                out.add("organization.townstead_career_required:" + career.requires().skill().map(ResourceLocation::toString).orElse(career.requires().professions().get(0)));
        }
        return out;
    }

    private static ProgressionResult matched(boolean matches) {
        return new ProgressionResult(ProgressionResult.Status.AVAILABLE, matches, matches ? "progression.matches" : "progression.requirement_not_met");
    }

    private static ProgressionResult unavailable(String reason) {
        return new ProgressionResult(ProgressionResult.Status.UNAVAILABLE, false, reason);
    }
}
