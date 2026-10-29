package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics.Permission;
import dev.otectus.mcakingdoms.civic.CivicRequirements;
import dev.otectus.mcakingdoms.civic.CivicService;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Requirements, review consequences and confirm labels for the tasks players use most: guild services, joining and
 * leaving, resident founding, political actions that need a responsibility, agreements and recruit transfers. Each is a
 * read-only projection of the owning service's rule; the service checks again when the action is applied.
 */
final class Requirements {
    private Requirements() {
    }

    private static Component r(String key, String fallback, Object... args) {
        return tr("interaction.mcakingdoms.requirements." + key, fallback, args);
    }

    private static Optional<ResourceLocation> organization(Context c) {
        return c.text("organization").isBlank() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(c.text("organization")));
    }

    static void init() {
        // ---- guild services: one guild's route and thresholds once it is chosen, the contact rule before that.
        Map<String, ResourceLocation> services = Map.of("civic.introduction", CivicService.INTRODUCTIONS, "civic.commissions", CivicService.COMMISSIONS,
                "civic.workshop", CivicService.COMMISSIONS);
        services.forEach((task, permission) -> requirements(task, c -> {
            var org = organization(c);
            if (org.isEmpty()) return List.of(CivicRequirements.anyContact(c.player));
            var rows = new ArrayList<>(CivicRequirements.service(c.player, org.get(), permission));
            rows.add(CivicRequirements.contact(c.player, org.get()));
            return rows;
        }));
        requirements("civic.hospitality", c -> organization(c).map(org -> List.of(CivicRequirements.contact(c.player, org),
                new Requirement(r("hospitality", "A hospitality agreement between the two kingdoms"), Requirement.Check.UNKNOWN,
                        r("hospitality.detail", "Checked when you ask: the destination's kingdom must have an operational hospitality agreement with this one."))))
                .orElse(List.of(CivicRequirements.anyContact(c.player))));
        requirements("civic.explain", c -> {
            var org = organization(c);
            ResourceLocation permission = ResourceLocation.tryParse(c.text("permission"));
            if (org.isEmpty() || permission == null || c.text("permission").isBlank()) return List.of();
            var rows = new ArrayList<>(CivicRequirements.service(c.player, org.get(), permission));
            rows.add(CivicRequirements.contact(c.player, org.get()));
            return rows;
        });

        // ---- joining and leaving a guild: what changes, in plain words, and the button that names it.
        applyLabel("civic.join", c -> c.choice("organization") == null ? tr("task.mcakingdoms.civic.join.title", "Join a guild")
                : r("join.button", "Join %s", c.choice("organization").shown()));
        applyLabel("civic.leave", c -> c.choice("organization") == null ? tr("task.mcakingdoms.civic.leave.title", "Leave a guild")
                : r("leave.button", "Leave %s", c.choice("organization").shown()));
        effects("civic.join", c -> List.of(r("join.effect", "You become an active member. Your standing and completed jobs stay as they are; member thresholds and rank start to apply."),
                r("join.effect.leave", "You can leave again at any time.")));
        effects("civic.leave", c -> List.of(r("leave.effect", "Your active membership ends. Standing, completed jobs and service history are kept, and accepted jobs stay with you until completed or cancelled."),
                r("leave.effect.services", "Member-only services stop; independent routes, where a guild offers them, still apply.")));

        // ---- resident founding: each condition of GovernmentService's own rule, for this player.
        requirements("politics.bootstrap", c -> {
            var rows = new ArrayList<Requirement>();
            boolean operator = c.player.hasPermissions(2);
            var crown = c.text("kingdom").isBlank() || !(dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(c.server()) instanceof dev.otectus.mcakingdoms.politics.GovernmentService politics)
                    ? dev.otectus.mcakingdoms.politics.Sovereignty.NATIVE : politics.sovereignty(c.text("kingdom"));
            rows.add(Requirement.of(crown.native_(), r("founding.native", "MCA: Kingdoms governs this kingdom's crown"),
                    crown.native_() ? Component.empty() : PlayerWords.component(crown.statusKey())));
            if (operator) rows.add(new Requirement(r("founding.operator", "Operator permission"), Requirement.Check.MET,
                    r("founding.operator.detail", "You may found any ungoverned kingdom's first government.")));
            else {
                rows.add(Requirement.of(McaKingdomsConfig.OPEN_FOUNDING.get(), r("founding.open", "Resident founding is on"),
                        McaKingdomsConfig.OPEN_FOUNDING.get() ? Component.empty() : r("founding.open.off", "Only operators found governments on this server.")));
                var api = McaKingdomsApi.get(c.server());
                var home = api.getCivicIdentity(c.player).flatMap(dev.otectus.mcakingdoms.api.CivicIdentityView::residenceSettlement)
                        .map(api::resolveSettlementId).flatMap(api::getSettlement);
                rows.add(Requirement.of(home.isPresent(), r("founding.residence", "A recorded civic residence"),
                        home.<Component>map(h -> Component.literal(h.displayName())).orElse(r("founding.residence.none", "None recorded. Visiting or sleeping in a settlement does not record one."))));
                home.ifPresent(h -> rows.add(Requirement.of(KingdomsPoliticsApi.get(c.server()).government(h.kingdomId().toString()).isEmpty(),
                        r("founding.ungoverned", "Your kingdom has no government yet"), Component.empty())));
            }
            return rows;
        });
        effects("politics.bootstrap", c -> List.of(r("founding.effect", "Records the kingdom's first government with this capital, charter and first leader. It claims no territory and creates no buildings."),
                r("founding.effect.offices", "Other offices start empty; the leader can appoint officials and grant responsibilities afterwards.")));

        // ---- political actions that need a responsibility in the chosen kingdom.
        Map<String, Permission> needs = new LinkedHashMap<>();
        for (String task : List.of("propose")) needs.put("politics." + task, Permission.PROPOSE);
        for (String task : List.of("sign", "decline", "terminate")) needs.put("politics." + task, Permission.RATIFY);
        for (String task : List.of("appoint", "name_successor", "house", "transition_rule", "election_open", "election_close", "appoint_regent", "end_regency"))
            needs.put("politics." + task, Permission.APPOINT);
        for (String task : List.of("delegate", "revoke")) needs.put("politics." + task, Permission.DELEGATE);
        needs.put("politics.seat", Permission.SEAT);
        for (String task : List.of("review", "approve", "reject")) needs.put("politics." + task, Permission.REVIEW);
        for (String task : List.of("recognize", "revalidate", "suspend_recognition")) needs.put("politics." + task, Permission.RECOGNIZE);
        for (String task : List.of("honor", "revoke_honor")) needs.put("politics." + task, Permission.HONOR);
        needs.forEach((task, permission) -> {
            if (ActionRegistry.find(task).isEmpty()) return;
            requirements(task, c -> {
                if (c.text("kingdom").isBlank()) return List.of(new Requirement(r("responsibility", "The %s responsibility", enumLabel(permission)), Requirement.Check.UNKNOWN,
                        r("responsibility.choose", "Choose a kingdom to check whether you hold it.")));
                boolean held = KingdomsPoliticsApi.get(c.server()).authorized(c.player, c.text("kingdom"), permission);
                Component kingdom = c.choice("kingdom") == null ? Component.literal(c.text("kingdom")) : c.choice("kingdom").shown();
                return List.of(Requirement.of(held, r("responsibility.in", "The %s responsibility in %s", enumLabel(permission), kingdom),
                        held ? Component.empty() : r("responsibility.missing", "Ask %s's leader to grant it, or to appoint you to an office that carries it.", kingdom)));
            });
        });

        // ---- removing an official: the decision for the office chosen, from the policy the service enforces (H02).
        requirements("politics.remove_office", c -> {
            if (c.text("kingdom").isBlank()) return List.of(new Requirement(r("responsibility", "The %s responsibility", enumLabel(Permission.APPOINT)), Requirement.Check.UNKNOWN,
                    r("responsibility.choose", "Choose a kingdom to check whether you hold it.")));
            if (!(KingdomsPoliticsApi.get(c.server()) instanceof dev.otectus.mcakingdoms.politics.GovernmentService government)) return List.of();
            var target = c.text("office").isBlank() ? dev.otectus.mcakingdoms.politics.GovernmentActionPolicy.Target.ANY
                    : dev.otectus.mcakingdoms.politics.GovernmentActionPolicy.Target.office(c.text("office"));
            var denial = government.actionDenial(c.player, c.text("kingdom"), dev.otectus.mcakingdoms.api.politics.Politics.Action.REMOVE_OFFICE, target);
            Component label = c.text("office").isBlank() ? r("remove_office.any", "An office you may vacate")
                    : r("remove_office.chosen", "You may vacate %s", c.choice("office") == null ? Component.literal(c.text("office")) : c.choice("office").shown());
            return List.of(Requirement.of(denial.isEmpty(), label, denial.map(PlayerWords::component).orElse(Component.empty())));
        });

        // ---- agreements: who must agree, and that consent is not execution.
        effects("politics.propose", c -> List.of(r("propose.effect", "Creates a proposal with these exact terms. Nothing is in force yet."),
                r("propose.effect.consent", "Both governments must sign before it takes effect, in either order. Unsigned proposals expire."),
                r("propose.effect.withdraw", "You can withdraw it while it is still a proposal.")));
        applyLabel("politics.propose", c -> r("propose.button", "Send agreement proposal"));
        effects("politics.sign", c -> List.of(r("sign.effect", "Adds your government's signature to these terms. When both governments have signed, the agreement is in force.")));
        applyLabel("politics.sign", c -> r("sign.button", "Sign the agreement"));

        // ---- recruit transfers: the recipient shares a destination first; proposing, consenting and applying are separate stages.
        requirements("transfer.propose", c -> {
            if (c.text("recipient").isBlank()) return List.of(new Requirement(r("transfer.shared", "A destination group shared with you by the recipient"),
                    Requirement.Check.UNKNOWN, r("transfer.shared.choose", "Choose the recipient to check. They share one with Share a recruit transfer destination; it lasts while you are both online.")));
            boolean shared = !RecruitTransferDestinations.forSender(c.player, c.uuid("recipient")).isEmpty();
            return List.of(Requirement.of(shared, r("transfer.shared", "A destination group shared with you by the recipient"),
                    shared ? Component.empty() : r("transfer.shared.missing", "Ask the recipient to open Share a recruit transfer destination and choose you.")));
        });
        // ---- recruit transfers: separate stages for proposing, consenting and applying.
        effects("transfer.propose", c -> List.of(r("transfer.propose.effect", "Sends a private offer to the recipient. Nothing moves yet."),
                r("transfer.propose.effect.next", "Next: the recipient consents; after the notice period you apply the transfer before its deadline. Either of you can cancel until then.")));
        applyLabel("transfer.propose", c -> r("transfer.propose.button", "Send transfer proposal"));
        effects("transfer.consent", c -> List.of(r("transfer.consent.effect", "Records your consent. The recruit does not move until the source owner applies the transfer after the notice period.")));
        applyLabel("transfer.consent", c -> r("transfer.consent.button", "Consent to this transfer"));
        effects("transfer.apply", c -> List.of(r("transfer.apply.effect", "Moves the recruit to the recipient's chosen group through Recruits and confirms the save. Civic identity, residence, family and buildings do not move.")));
        applyLabel("transfer.apply", c -> r("transfer.apply.button", "Confirm troop transfer"));
    }
}
