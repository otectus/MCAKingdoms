package dev.otectus.mcakingdoms.civic;

import dev.otectus.mcakingdoms.api.factions.organization.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry.Requirement;
import dev.otectus.mcakingdoms.interaction.PlayerWords;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * A guild service's requirements as separate rows, read from the organization's current definition and the player's
 * own record: standing, different jobs completed and rank for members, the independent thresholds for those who have not
 * joined, and a contact within reach. A display projection of {@link OrganizationService#explainOwn}'s rules; the
 * owning services still decide every request. Shared by the Guilds screen and Kingdom Tasks.
 */
public final class CivicRequirements {
    private CivicRequirements() {
    }

    static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("civic.mcakingdoms.requirement." + key, fallback, args);
    }

    /** The bundled or data-pack name of a rank, with readable words from its id when no translation exists. */
    public static Component rankName(ResourceLocation rank) {
        return Component.translatableWithFallback("organization." + rank.getNamespace() + ".rank." + rank.getPath(), words(rank.getPath()));
    }

    /** The name of a service permission ("Introductions to another branch"). */
    public static Component serviceName(ResourceLocation permission) {
        return Component.translatableWithFallback("organization." + permission.getNamespace() + ".service." + permission.getPath(), words(permission.getPath()));
    }

    public static Component organizationName(OrganizationDefinitionView definition) {
        return Component.translatableWithFallback(definition.nameKey(), PlayerWords.text(definition.nameKey()));
    }

    static String words(String path) {
        String s = path.replace('_', ' ').replace('/', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The rank a standing reaches under {@code definition}: the highest whose minimum it meets. */
    public static Optional<OrganizationRankView> rankFor(OrganizationDefinitionView definition, long standing) {
        OrganizationRankView best = null;
        for (OrganizationRankView rank : definition.ranks()) if (standing >= rank.minimumStanding()) best = rank;
        return Optional.ofNullable(best);
    }

    /** The player's own membership in {@code organization}, if any record exists. */
    public static Optional<OrganizationMembershipSnapshot> membership(ServerPlayer player, ResourceLocation organization) {
        return OrganizationApi.get(player.getServer()).ownSnapshot(player).memberships().stream()
                .filter(m -> m.organizationId().equals(organization)).findFirst();
    }

    /** Whether a member or an independent contributor route applies to this player now. */
    public static boolean member(ServerPlayer player, ResourceLocation organization) {
        return membership(player, organization).map(m -> m.status() == OrganizationMembershipSnapshot.Status.ACTIVE).orElse(false);
    }

    /**
     * Rows for {@code permission} of {@code organization}: the route that applies to this player (member or independent),
     * each threshold with current and required values, and when independent access is not offered, that membership is the
     * only route. Unknown definitions say so instead of guessing.
     */
    public static List<Requirement> service(ServerPlayer player, ResourceLocation organization, ResourceLocation permission) {
        var definition = OrganizationApi.get(player.getServer()).definition(organization);
        if (definition.isEmpty()) return List.of(new Requirement(t("definition", "Guild rules"), Requirement.Check.UNKNOWN,
                t("definition.missing", "This guild's definition is not loaded on this server.")));
        var rule = definition.get().services().stream().filter(r -> r.permission().equals(permission)).findFirst();
        if (rule.isEmpty()) return List.of(Requirement.of(false, serviceName(permission), t("service.missing", "%s does not offer this service.", organizationName(definition.get()))));
        var record = membership(player, organization);
        long standing = record.map(OrganizationMembershipSnapshot::standing).orElse(0L);
        int deeds = record.map(OrganizationMembershipSnapshot::deedCount).orElse(0);
        List<Requirement> rows = new ArrayList<>();
        if (member(player, organization)) {
            rows.add(new Requirement(t("route.member", "Route: member of %s", organizationName(definition.get())), Requirement.Check.NOTE, Component.empty()));
            rows.add(Requirement.of(standing >= rule.get().minimumStanding(), t("standing", "Guild standing"), t("of", "%s of %s", standing, rule.get().minimumStanding())));
            rows.add(Requirement.of(deeds >= rule.get().requiredDeeds(), t("deeds", "Different service jobs completed"), t("of", "%s of %s", deeds, rule.get().requiredDeeds())));
            var current = rankFor(definition.get(), standing);
            int needed = rankIndex(definition.get(), rule.get().minimumRank());
            boolean rankMet = current.isPresent() && rankIndex(definition.get(), current.get().id()) >= needed && current.get().permissions().contains(permission);
            rows.add(Requirement.of(rankMet, t("rank", "Rank"), t("rank.detail", "%s needed; yours is %s", rankName(rule.get().minimumRank()),
                    current.<Component>map(r -> rankName(r.id())).orElse(t("rank.none", "none")))));
        } else if (rule.get().neutralAlternative()) {
            rows.add(new Requirement(t("route.independent", "Route: independent contributor (you have not joined)"), Requirement.Check.NOTE, Component.empty()));
            rows.add(Requirement.of(standing >= rule.get().neutralMinimumStanding(), t("standing", "Guild standing"), t("of", "%s of %s", standing, rule.get().neutralMinimumStanding())));
            rows.add(Requirement.of(deeds >= rule.get().neutralRequiredDeeds(), t("deeds", "Different service jobs completed"), t("of", "%s of %s", deeds, rule.get().neutralRequiredDeeds())));
            rows.add(new Requirement(t("route.alternative", "Or join and use the member route"), Requirement.Check.NOTE,
                    t("route.alternative.detail", "Members need %s standing, %s different jobs and the %s rank.", rule.get().minimumStanding(), rule.get().requiredDeeds(), rankName(rule.get().minimumRank()))));
        } else {
            rows.add(Requirement.of(false, t("membership", "Membership"), t("membership.only", "Only members of %s can use this service; join first.", organizationName(definition.get()))));
        }
        return rows;
    }

    private static int rankIndex(OrganizationDefinitionView definition, ResourceLocation rank) {
        for (int i = 0; i < definition.ranks().size(); i++) if (definition.ranks().get(i).id().equals(rank)) return i;
        return Integer.MAX_VALUE;
    }

    /** Whether a contact of {@code organization} is within reach, with the configured range. */
    public static Requirement contact(ServerPlayer player, ResourceLocation organization) {
        int range = CivicConfig.CONTACT_RANGE.get();
        boolean near = CivicRuntime.get(player.getServer()).nearbyContact(player, organization).isPresent();
        return Requirement.of(near, t("contact", "A guild contact within %s blocks and in sight", range),
                near ? t("contact.found", "Found nearby.") : t("contact.missing", "Move within %s blocks of a known contact of this guild and keep them in sight.", range));
    }

    /** Whether any guild contact is within reach (before a guild is chosen). */
    public static Requirement anyContact(ServerPlayer player) {
        int range = CivicConfig.CONTACT_RANGE.get();
        boolean near = OrganizationApi.get(player.getServer()).definitions().stream()
                .anyMatch(d -> CivicRuntime.get(player.getServer()).nearbyContact(player, d.id()).isPresent());
        return Requirement.of(near, t("contact.any", "A guild contact within %s blocks and in sight", range),
                near ? t("contact.found", "Found nearby.") : t("contact.missing.any", "Move within %s blocks of a guild contact and keep them in sight.", range));
    }
}
