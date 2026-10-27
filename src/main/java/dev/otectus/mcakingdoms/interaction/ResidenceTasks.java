package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration.Check;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration.Intent;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import net.minecraft.network.chat.Component;

import java.util.*;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for verified player residence (A01): Register my residence, Move my residence, its operator override,
 * and Check my residence. Each review shows the current record, the settlement the evidence names, the evidence itself,
 * what changes and any wait, before anything is recorded; the service checks everything again on apply.
 */
public final class ResidenceTasks {
    private ResidenceTasks() {
    }

    static Component r(String key, String fallback, Object... args) {
        return tr("interaction.mcakingdoms.residence." + key, fallback, args);
    }

    private static Component name(SettlementView settlement) {
        return Component.literal(settlement.displayName());
    }

    private static Component kingdom(Context c, SettlementView settlement) {
        return McaKingdomsApi.get(c.server()).getKingdom(settlement.kingdomId()).<Component>map(k -> Component.translatable(k.translationKey()))
                .orElse(Component.literal(settlement.kingdomId().toString()));
    }

    /** The reason for a check, with the next practical step, in the reader's language. */
    static Component explain(Context c, ResidenceRegistration.Review review) {
        return explain(review);
    }

    /** As {@link #explain(Context, ResidenceRegistration.Review)}, for commands. */
    public static Component explain(ResidenceRegistration.Review review) {
        var evidence = review.evidence();
        return switch (review.decision()) {
            case READY -> r("ready", "Ready: your evidence names %s.", evidence.settlement().map(ResidenceTasks::name).orElse(Component.empty()));
            case SAME -> r("same", "%s is already your recorded residence; nothing needs to change.", review.currentResidence().map(ResidenceTasks::name).orElse(Component.empty()));
            case DISABLED -> r("disabled", "Players cannot register their own residence on this server (civicIdentity.playerRegistration = false). An operator can record it.");
            case NO_BED -> r("no_bed", "You have no respawn point from a bed. Sleep in (or set your spawn at) a bed inside a house in the settlement, then try again.");
            case COMMAND_SPAWN -> r("command_spawn", "Your respawn point was set by a command, not by a bed. Set it by sleeping in a bed inside the settlement.");
            case BED_UNLOADED -> r("bed_unloaded", "Your respawn bed is in an area that is not loaded, so it cannot be checked; no chunk is loaded for this. Go to the settlement and try again.");
            case BED_MISSING -> r("bed_missing", "The block at your respawn point is no longer a bed. Set your respawn point at a bed in the settlement again.");
            case BED_OUTSIDE_SETTLEMENT -> r("bed_outside", "Your respawn bed is not inside a recognized settlement. Use a bed inside a settlement's bounds.");
            case NOT_PRESENT -> r("not_present", "Stand in %s, where your evidence is, to register it.", evidence.settlement().map(ResidenceTasks::name).orElse(Component.empty()));
            case COOLDOWN -> r("cooldown", "You changed residence recently. You can move again in %s.", Names.durationText(review.cooldownRemaining()));
            case ALREADY_REGISTERED -> r("already", "You already have a recorded residence, %s. Use Move my residence to change it.", review.currentResidence().map(ResidenceTasks::name).orElse(Component.empty()));
            case NOT_REGISTERED -> r("not_registered", "You have no recorded residence yet. Use Register my residence first.");
        };
    }

    private static List<Requirement> requirements(Context c, Intent intent) {
        var review = ResidenceRegistration.review(c.player, intent, false);
        var evidence = review.evidence();
        var rows = new ArrayList<Requirement>();
        if (evidence.check() == Check.DISABLED) { rows.add(Requirement.of(false, r("req.enabled", "Self-registration on this server"), explain(c, review))); return rows; }
        if (evidence.source().equals("provider"))
            rows.add(Requirement.of(evidence.settlement().isPresent(), r("req.provider", "Your home, as recorded by an installed mod"), evidence.settlement().map(ResidenceTasks::name).orElse(Component.empty())));
        else {
            boolean bed = evidence.check() != Check.NO_BED && evidence.check() != Check.COMMAND_SPAWN;
            rows.add(Requirement.of(bed, r("req.bed", "A respawn point set by a bed"), evidence.bed().<Component>map(p -> Component.literal(p.toShortString())).orElse(r("req.none", "none"))));
            if (bed) {
                boolean standing = evidence.check() != Check.BED_UNLOADED && evidence.check() != Check.BED_MISSING;
                rows.add(Requirement.of(standing, r("req.bed_present", "The bed is still there (checked in a loaded area)"), Component.empty()));
                if (standing) rows.add(Requirement.of(evidence.check() != Check.BED_OUTSIDE_SETTLEMENT, r("req.in_settlement", "The bed is inside a recognized settlement"),
                        evidence.settlement().map(ResidenceTasks::name).orElse(Component.empty())));
            }
        }
        if (evidence.settlement().isPresent())
            rows.add(Requirement.of(evidence.check() != Check.NOT_PRESENT, r("req.present", "You are in %s now", name(evidence.settlement().get())), Component.empty()));
        if (intent == Intent.MOVE && review.currentResidence().isPresent())
            rows.add(Requirement.of(review.cooldownRemaining() == 0, r("req.cooldown", "Time since your last residence change"),
                    review.cooldownRemaining() == 0 ? r("req.cooldown.done", "the wait is over") : r("req.cooldown.left", "%s left", Names.durationText(review.cooldownRemaining()))));
        return rows;
    }

    private static List<Object> effects(Context c, Intent intent) {
        var review = ResidenceRegistration.review(c.player, intent, false);
        var lines = new ArrayList<Object>();
        lines.add(r("current", "Your record now: residence %s; origin %s.",
                review.currentResidence().map(ResidenceTasks::name).orElse(r("none", "none")),
                review.current().flatMap(v -> v.originSettlement()).flatMap(id -> McaKingdomsApi.get(c.server()).getSettlement(id)).map(ResidenceTasks::name).orElse(r("none", "none"))));
        review.evidence().settlement().ifPresent(target -> {
            lines.add(r("effect.residence", "Your civic residence becomes %s, in %s.", name(target), kingdom(c, target)));
            lines.add(review.setsOrigin() ? r("effect.origin_set", "You have no origin yet, so %s becomes your origin too; it does not change when you move later.", name(target))
                    : r("effect.origin_kept", "Your origin stays as it is."));
            var politics = KingdomsPoliticsApi.get(c.server());
            if (McaKingdomsConfig.OPEN_FOUNDING.get() && politics.government(target.kingdomId().toString()).isEmpty())
                lines.add(r("effect.founding", "%s has no government yet: as a resident you could found it in Kingdom Tasks, if its charter allows.", kingdom(c, target)));
        });
        if (intent == Intent.MOVE) lines.add(r("effect.cooldown", "After a move you wait %s before moving again.", Names.durationText(McaKingdomsConfig.RESIDENCE_MOVE_COOLDOWN.get())));
        lines.add(r("effect.limits", "This records a civic home only. It grants no office, land or building, does not change any villager's home, and does not join you to a guild or a war. Elections already open keep their electorate."));
        return lines;
    }

    private static Object apply(Context c, Intent intent, boolean override) {
        var review = ResidenceRegistration.apply(c.player, intent, override);
        String operation = review.evidence().settlement().map(s -> s.id().toString()).orElse("");
        return switch (review.decision()) {
            case READY -> Outcome.applied(List.of(intent == Intent.REGISTER
                    ? r("done.registered", "Registered: %s is your civic residence.", name(review.evidence().settlement().orElseThrow()))
                    : r("done.moved", "Moved: %s is your civic residence now.", name(review.evidence().settlement().orElseThrow())),
                    review.setsOrigin() ? r("done.origin", "It is your origin too.") : r("done.origin_kept", "Your origin is unchanged.")), operation)
                    .then("politics.founding_status", Map.of());
            case SAME -> Outcome.of(Outcome.State.APPLIED, explain(c, review), operation);
            default -> Outcome.refused(explain(c, review), operation).then("citizen.residence_status", Map.of());
        };
    }

    private static void registration(String id, String title, String help, Intent intent, boolean operator, boolean override) {
        add(new Task(id, operator ? "Administration" : "Settlements", title, help, operator, true, List.of(),
                c -> ResidenceRegistration.fingerprint(c.player, intent) + "|" + override, c -> apply(c, intent, override)));
        ActionRegistry.requirements(id, c -> requirements(c, intent));
        ActionRegistry.effects(id, c -> effects(c, intent));
        ActionRegistry.applyLabel(id, c -> ResidenceRegistration.evidence(c.player).settlement()
                .map(s -> intent == Intent.REGISTER ? r("label.register", "Register %s as my residence", name(s)) : r("label.move", "Move my residence to %s", name(s)))
                .orElse(intent == Intent.REGISTER ? r("label.register_plain", "Register my residence") : r("label.move_plain", "Move my residence")));
    }

    static void init() {
        registration("citizen.register_residence", "Register my residence",
                "Record the settlement where your bed is as your civic home, while you stand there. Founding and civic services read it.",
                Intent.REGISTER, false, false);
        registration("citizen.move_residence", "Move my residence",
                "Move your civic home to the settlement where your bed is now, while you stand there. Your origin stays; a short wait applies between moves.",
                Intent.MOVE, false, false);
        registration("citizen.move_residence_now", "Move my residence now (operator)",
                "As Move my residence, without the wait between moves. The evidence is still required.",
                Intent.MOVE, true, true);
        add(new Task("citizen.residence_status", "Settlements", "Check my residence",
                "Shows your recorded residence and origin, what your evidence names now, and what to do next. Read-only.", false, false, List.of(),
                c -> "status", c -> {
                    var intent = McaKingdomsApi.get(c.server()).getResidence(c.player).isPresent() ? Intent.MOVE : Intent.REGISTER;
                    var review = ResidenceRegistration.review(c.player, intent, false);
                    var lines = new ArrayList<Object>(effects(c, intent).subList(0, 1));
                    requirements(c, intent).forEach(row -> lines.add(row.line()));
                    lines.add(explain(c, review));
                    lines.add(intent == Intent.REGISTER ? r("next.register", "Next: Register my residence.") : r("next.move", "Next: Move my residence, when you want a different civic home."));
                    return lines;
                }));
    }
}
