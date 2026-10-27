package dev.otectus.mcakingdoms.civic;

import dev.otectus.mcakingdoms.api.factions.organization.*;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsObservation;
import dev.otectus.mcakingdoms.interaction.ActionRegistry.Requirement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import net.minecraft.network.chat.Component;

/**
 * Bounded, own-player guild presentation shared by commands and the Guilds screen. Everything is in translatable
 * components: {@link View#lines} for chat readers, and {@link Guild} for the screen's sections (membership, progress,
 * each service with its own requirement rows, nearby contact, recent service). It reads only the player's own records.
 */
public final class CivicViews {
    public record View(String organization, String nameKey, int index, int total, boolean active, List<Component> lines, Guild guild) {
        public View {
            lines = lines.stream().limit(32).map(Component::copy).map(c -> (Component)c).toList();
        }
        public View(String organization, String nameKey, int index, int total, boolean active, List<Component> lines) {
            this(organization, nameKey, index, total, active, lines, null);
        }
    }

    /** One requirement row of a service: serialized label and detail, and {@code MET}, {@code UNMET} or {@code UNKNOWN}. */
    public record Row(String label, String check, String detail) {
        public Component labelComponent() { return decode(label); }
        public Component detailComponent() { return decode(detail); }
    }

    /**
     * One service and its own status: {@code decision} is the service rule's {@code ALLOW}, {@code DENY} or
     * {@code UNAVAILABLE}; {@code task} is the Kingdom Tasks id that uses it (reviewed there), blank when none.
     */
    public record Service(String permission, String name, String decision, String task, List<Row> rows) {
        public Service { rows = List.copyOf(rows); }
        public Component nameComponent() { return decode(name); }
    }

    /**
     * The Guilds screen's structured view. Text fields are serialized components; numbers are exact values from the
     * player's record and the guild's current definition ({@code nextRankStanding} is -1 when there is no next rank).
     */
    public record Guild(String name, String description, String membership, boolean member, long standing, int deeds, String rank,
                        String nextRank, long nextRankStanding, List<Service> services, String contact, boolean contactFound,
                        String quests, List<String> recent, List<String> notes) {
        public Guild { services = List.copyOf(services); recent = List.copyOf(recent); notes = List.copyOf(notes); }
    }

    private CivicViews() { }

    static String encode(Component component) { return Component.Serializer.toJson(component); }

    public static Component decode(String value) {
        if (value == null || value.isEmpty()) return Component.empty();
        try { var component = Component.Serializer.fromJson(value); return component == null ? Component.empty() : component; }
        catch (RuntimeException malformed) { return Component.literal(value); }
    }

    private static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("civic.mcakingdoms.view." + key, fallback, args);
    }

    private static Component questStatus(String status) {
        return switch(status) {
            case "disabled" -> t("quests.disabled", "Guild quest service is turned off on this server.");
            case "ready", "available" -> t("quests.available", "Verified quest service is available: completed guild jobs are credited.");
            case "absent", "unsupported_completion_api" -> t("quests.absent", "Guild jobs need a compatible MCA Quests installation, which this server does not have.");
            case "pending_save" -> t("quests.pending_save", "Your completed job is being saved. Guild credit updates when saving finishes.");
            default -> t("quests.recovering", "Quest service is recovering; your pending work is kept.");
        };
    }

    private static Component membershipWords(Optional<OrganizationMembershipSnapshot> membership) {
        if (membership.isEmpty()) return t("membership.none", "Not a member. Service you do still counts, and independent routes may apply.");
        return switch (membership.get().status()) {
            case ACTIVE -> t("membership.active", "Active member.");
            case LEFT -> t("membership.left", "Not a member now. Your standing and service record are kept.");
            case UNAFFILIATED -> t("membership.unaffiliated", "Not a member. Your service is recorded, and independent routes may apply.");
        };
    }

    /** The Kingdom Tasks id that uses {@code permission}, or blank. */
    static String taskFor(ResourceLocation permission) {
        if (permission.equals(CivicService.INTRODUCTIONS)) return "civic.introduction";
        if (permission.equals(CivicService.COMMISSIONS)) return "civic.commissions";
        return "";
    }

    private static List<Row> rows(List<Requirement> requirements) {
        return requirements.stream().map(r -> new Row(encode(r.label()), r.check().name(), encode(r.detail()))).toList();
    }

    public static View own(ServerPlayer player, int index) {
        OrganizationService service = OrganizationApi.get(player.getServer());
        var snapshot = service.ownSnapshot(player);
        var ids = new TreeSet<ResourceLocation>(Comparator.comparing(ResourceLocation::toString));
        service.definitions().forEach(d -> ids.add(d.id()));
        snapshot.memberships().forEach(m -> ids.add(m.organizationId()));
        if (index < 0 || index > 4096) throw new IllegalArgumentException("Invalid guild page");
        if (ids.isEmpty()) return new View("", "civic.mcakingdoms.no_organizations", 0, 0, false,
                List.of(t("none", "No guilds are defined on this server.")), null);
        int actual = Math.min(index, ids.size() - 1);
        ResourceLocation id = new ArrayList<>(ids).get(actual);
        var definition = service.definition(id);
        var membership = snapshot.memberships().stream().filter(m -> m.organizationId().equals(id)).findFirst();
        boolean active = membership.map(m -> m.status() == OrganizationMembershipSnapshot.Status.ACTIVE).orElse(false);
        long standing = membership.map(OrganizationMembershipSnapshot::standing).orElse(0L);
        int deeds = membership.map(OrganizationMembershipSnapshot::deedCount).orElse(0);
        Component name = definition.map(CivicRequirements::organizationName).orElse(Component.literal(id.toString()));
        Component description = definition.map(d -> (Component) Component.translatableWithFallback(d.descriptionKey(), "")).orElse(Component.empty());
        Optional<OrganizationRankView> rank = active ? membership.flatMap(OrganizationMembershipSnapshot::rank)
                .flatMap(r -> definition.flatMap(d -> d.ranks().stream().filter(v -> v.id().equals(r)).findFirst()))
                .or(() -> definition.flatMap(d -> CivicRequirements.rankFor(d, standing))) : Optional.empty();
        Optional<OrganizationRankView> nextRank = definition.flatMap(d -> d.ranks().stream().filter(r -> r.minimumStanding() > standing)
                .min(Comparator.comparingLong(OrganizationRankView::minimumStanding)));
        Component rankName = rank.<Component>map(r -> CivicRequirements.rankName(r.id())).orElse(active ? t("rank.none", "None yet") : t("rank.not_member", "Ranks are for members"));

        var civic = CivicRuntime.get(player.getServer());
        var contact = civic.nearbyContact(player, id);
        Component contactLine = contact.map(npc -> t("contact.found", "%s is %s blocks away.", npc.getName(), Math.round(Math.sqrt(npc.distanceToSqr(player)))))
                .orElse(t("contact.missing", "No contact of this guild is within %s blocks. Contacts are appointed residents at a guild branch; their services start when you stand near one and can see them.",
                        CivicConfig.CONTACT_RANGE.get()));

        List<Service> services = new ArrayList<>();
        definition.ifPresent(d -> d.services().stream().limit(6).forEach(rule -> {
            var explanation = service.explainOwn(player, id, rule.permission());
            var requirementRows = new ArrayList<>(CivicRequirements.service(player, id, rule.permission()));
            requirementRows.add(CivicRequirements.contact(player, id));
            services.add(new Service(rule.permission().toString(), encode(CivicRequirements.serviceName(rule.permission())), explanation.decision().name(),
                    taskFor(rule.permission()), rows(requirementRows)));
        }));
        if (CivicConfig.INSTITUTIONAL_SERVICES.get() && definition.isPresent()) {
            services.add(new Service("mcakingdoms:hospitality", encode(t("service.hospitality", "Hospitality access")), contact.isPresent() ? "ALLOW" : "DENY", "civic.hospitality",
                    rows(List.of(CivicRequirements.contact(player, id), new Requirement(t("service.hospitality.agreement", "A hospitality agreement between the kingdoms"),
                            Requirement.Check.UNKNOWN, t("service.hospitality.agreement.detail", "Checked when you ask, for the destination you are given."))))));
            if (id.toString().equals("mcakingdoms:lamplighters")) {
                var workshop = new ArrayList<>(CivicRequirements.service(player, id, CivicService.COMMISSIONS));
                workshop.add(CivicRequirements.contact(player, id));
                workshop.add(new Requirement(t("service.workshop.legal", "Legal workshop access (MCA: Crime)"), Requirement.Check.UNKNOWN,
                        t("service.workshop.legal.detail", "Checked at the recognized workshop when you ask.")));
                services.add(new Service("mcakingdoms:workshop", encode(t("service.workshop", "Paid workshop jobs")),
                        service.explainOwn(player, id, CivicService.COMMISSIONS).decision().name(), "civic.workshop", rows(workshop)));
            }
        }

        List<Component> recent = new ArrayList<>();
        membership.ifPresent(m -> m.recentEvidence().stream().limit(4).forEach(e -> recent.add(t("recent.job", "Completed job: %s",
                CivicRequirements.words(e.questId().substring(e.questId().lastIndexOf('/') + 1))))));
        service.ownHistory(player, 4).stream().filter(e -> e.organization().equals(id)).forEach(e ->
                recent.add(t("recent.history", "%s on day %s", Component.translatableWithFallback("civic.mcakingdoms.history." + e.action(), CivicRequirements.words(e.action())),
                        e.gameTime() / 24000 + 1)));

        List<Component> notes = new ArrayList<>();
        notes.add(t("note.separate", "Guild membership is separate from kingdom citizenship and military allegiance. You can leave and rejoin; recorded service is kept."));
        if (definition.isEmpty()) notes.add(t("note.definition", "This guild's definition is unavailable: your membership is kept, and joining and services are suspended."));
        if (CivicConfig.INSTITUTIONAL_SERVICES.get() && contact.isPresent()) {
            var legal = dev.otectus.mcakingdoms.compat.crime.InstitutionalCrimeBridge.workshop(player, contact.get());
            if (!legal.allowed()) notes.add(CivicText.reason(legal.reason()));
            legal.restitution().forEach(line -> notes.add(t("note.restitution", "Your local restitution: %s", line)));
        }
        var military = RecruitsObservation.here(player);
        if (military.status() == RecruitsObservation.Status.AVAILABLE && !military.claimId().isEmpty())
            notes.add(military.underSiege() ? t("note.claim.siege", "A military claim covers this place and is under siege; guild services keep their own rules.")
                    : t("note.claim", "A military claim covers this place; guild services keep their own rules."));
        Component quests = questStatus(dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.status(player));

        // Plain lines for chat readers (/kingdoms guild) and older clients' text view.
        List<Component> lines = new ArrayList<>();
        lines.add(t("line.membership", "Membership: %s", membershipWords(membership)));
        lines.add(t("line.progress", "Guild standing: %s · different jobs completed: %s · rank: %s", standing, deeds, rankName));
        nextRank.ifPresent(r -> lines.add(t("line.next_rank", "Next rank: %s at %s standing (%s more)", CivicRequirements.rankName(r.id()), r.minimumStanding(), r.minimumStanding() - standing)));
        lines.add(contactLine);
        lines.add(quests);
        for (Service s : services) {
            var line = Component.empty().append(t("line.qualification", "Qualification for %s: %s", decode(s.name()),
                    Component.translatableWithFallback("interaction.mcakingdoms.enum.organization_explanation_decision." + s.decision().toLowerCase(Locale.ROOT), s.decision())));
            lines.add(line);
        }
        lines.addAll(recent);
        lines.addAll(notes);
        Guild guild = new Guild(encode(name), encode(description), encode(membershipWords(membership)), active, standing, deeds, encode(rankName),
                nextRank.map(r -> encode(CivicRequirements.rankName(r.id()))).orElse(""), nextRank.map(OrganizationRankView::minimumStanding).orElse(-1L),
                services, encode(contactLine), contact.isPresent(), encode(quests), recent.stream().map(CivicViews::encode).toList(), notes.stream().map(CivicViews::encode).toList());
        return new View(id.toString(), definition.map(OrganizationDefinitionView::nameKey).orElse(id.toString()), actual, ids.size(), active, lines, guild);
    }
}
