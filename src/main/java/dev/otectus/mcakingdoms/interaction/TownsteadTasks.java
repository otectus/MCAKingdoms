package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.compat.townstead.TownsteadCareers;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.progression.ProgressionApi;
import dev.otectus.mcakingdoms.api.progression.ProgressionPredicate;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadAuthority;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadPresentation;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadSavedData;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadText;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for Townstead villages (spec 8.1, 9.2, 10.2, 11.2, 12.2): Village life, Village work, Village news and
 * Government and services for players, a Townstead career qualification check, and the operator's diagnostics and
 * authority decisions. Every page reads public summaries for a settlement the player knows; the owning services decide.
 */
final class TownsteadTasks {
    private static final Constraint PAGE = Constraint.range(1, 8, "", 1);
    private static final Constraint TIER = Constraint.range(1, 64, "", 1);

    private TownsteadTasks() {
    }

    private static void task(String id, String title, String help, boolean operator, boolean change, List<Field> fields, Handler handler) {
        add(new Task(id, "Townstead villages", title, help, operator, change, fields, TownsteadTasks::version, handler));
    }

    private static String version(Context c) {
        return TownsteadPresentation.session(c.server()).map(s -> s.id() + "").orElse("inactive");
    }

    private static UUID here(Context c) {
        return McaKingdomsApi.get(c.server()).getSettlementAt(c.player.serverLevel(), c.player.blockPosition()).map(s -> s.id())
                .orElseThrow(() -> TownsteadText.refuse("none_here", "No settlement here."));
    }

    private static UUID settlementOrHere(Context c) {
        return c.text("settlement").isBlank() ? here(c) : c.uuid("settlement");
    }

    private static Field settlement() {
        return new Field("settlement", "Settlement (empty: the one you stand in)", "interaction.mcakingdoms.help.pick", Kind.CHOICE, "", true,
                c -> c.choices("settlement"), "settlement");
    }

    private static Object read(List<Object> lines) {
        return Outcome.of(Outcome.State.READ, lines, "");
    }

    static void init() {
        // Player tasks.
        task("townstead.life", "Village life", "A village's residents and how much Townstead knows of them, its needs, spirit, culture and today's date, with optional ways to help. Read-only.",
                false, false, List.of(settlement()), c -> {
                    UUID settlement = settlementOrHere(c);
                    Outcome outcome = Outcome.of(Outcome.State.READ, TownsteadPresentation.villageLife(c.player, settlement), "");
                    Optional<String> task = TownsteadPresentation.suggestedTask(c.player, settlement);
                    return task.filter(id -> ActionRegistry.find(id).isPresent()).map(id -> outcome.then(id, Map.of("settlement", settlement.toString()))).orElse(outcome);
                });
        task("townstead.work", "Village work", "What its worksites are asked to produce and an advisory count of goods in its storage. Production requested is not goods delivered. Read-only.",
                false, false, List.of(settlement()), c -> read(TownsteadPresentation.work(c.player, settlementOrHere(c))));
        task("townstead.news", "Village news", "Townstead's public village news (stories villagers tell, not an official record), a page at a time, and where MCA: Kingdoms' own records are. Read-only.",
                false, false, List.of(settlement(), number("page", "Page", 1, PAGE)), c -> read(TownsteadPresentation.news(c.player, settlementOrHere(c), c.integer("page", PAGE))));
        task("townstead.government", "Government and services", "Who governs each part of a settlement (its crown, local government, guilds, names and arms) and where to act. Read-only.",
                false, false, List.of(settlement()), c -> read(TownsteadPresentation.government(c.player, settlementOrHere(c))));
        // AUD-15: named choices from Townstead's own registries; the tier only for a profession; "cannot check" is not "not qualified".
        targets("townstead_career_subject", TownsteadTasks::subjects);
        targetHint("townstead_career_subject", TownsteadText.tr("qualify.unreadable", "Townstead's careers cannot be read on this server right now, so nothing can be checked. An operator can see why in Townstead integration status."));
        task("townstead.qualify", "Check a Townstead career", "Whether your Townstead career meets a requirement: a profession at a tier, or a learned skill, chosen by name. Townstead tiers are not trade levels or guild ranks. Read-only.",
                false, false, List.of(pick("kind", "Requirement", c -> List.of(
                                Choice.of("TOWNSTEAD_PROFESSION_TIER", TownsteadText.tr("qualify.profession", "A profession at a tier")),
                                Choice.of("TOWNSTEAD_SKILL", TownsteadText.tr("qualify.skill", "A learned skill")))),
                        pick("subject", "Profession or skill", "townstead_career_subject").after("kind"),
                        pick("minimum", "Tier", TownsteadTasks::tiers).after("kind", "subject")), c -> {
                    var kind = ProgressionPredicate.Kind.valueOf(c.text("kind"));
                    boolean skill = kind == ProgressionPredicate.Kind.TOWNSTEAD_SKILL;
                    int tier = skill ? 1 : Math.max(1, Integer.parseInt(c.text("minimum")));
                    String subject = c.text("subject");
                    var answer = ProgressionApi.evaluate(c.player, new ProgressionPredicate(kind, subject, tier));
                    Component named = skill ? TownsteadCareers.skillName(subject) : TownsteadText.tr("qualify.profession_at", "%s at tier %s", TownsteadCareers.professionName(subject), tier);
                    List<Object> lines = new ArrayList<>();
                    if (answer.status() != dev.otectus.mcakingdoms.api.progression.ProgressionResult.Status.AVAILABLE)
                        lines.add(TownsteadText.tr("qualify.unknown", "Cannot check %s right now: Townstead's answer is not available. This is not a failed requirement.", named));
                    else lines.add(answer.matches() ? TownsteadText.tr("qualify.yes_named", "Qualified: you meet %s.", named)
                            : TownsteadText.tr("qualify.not_yet", "Not yet: you do not meet %s.", named));
                    lines.add(Component.translatable(answer.reason()));
                    lines.add(TownsteadText.tr("qualify.technical", "Technical detail: %s (%s).", subject, kind.name().toLowerCase(java.util.Locale.ROOT)));
                    return read(lines);
                });

        // Operator tasks.
        task("townstead.status", "Townstead integration status", "Installed versions, the backend in use, every capability's state with its reason, the world's Townstead switches and the mapping checks. Read-only.",
                true, false, List.of(), c -> read(new ArrayList<>(TownsteadPresentation.status(c.server()))));
        task("townstead.inspect", "Inspect a Townstead village", "Its MCA village references, Townstead's view of it, its buildings with completion, and who governs each domain with the evidence. Read-only.",
                true, false, List.of(pick("settlement", "Settlement", "settlement")), c -> {
                    List<Object> lines = new ArrayList<>(TownsteadPresentation.village(c.server(), c.uuid("settlement")));
                    lines.addAll(TownsteadPresentation.authority(c.server(), c.uuid("settlement")));
                    lines.addAll(TownsteadPresentation.buildings(c.server(), c.uuid("settlement")));
                    return read(lines);
                });
        task("townstead.queues", "Townstead notices and caches", "Townstead's change notices waiting and handled, building reconciliation, shared readings, reactions and Chronicle projections. Read-only.",
                true, false, List.of(), c -> read(new ArrayList<>(TownsteadPresentation.queues(c.server()))));
        task("townstead.reconcile", "Re-read a Townstead village", "Re-reads one settlement's buildings and Townstead readings now. Nothing is awarded, changed, reset or replayed.",
                true, false, List.of(pick("settlement", "Settlement", "settlement")), c -> read(new ArrayList<>(TownsteadPresentation.reconcile(c.server(), c.uuid("settlement")))));
        task("townstead.adopt", "Adopt MCA: Kingdoms as local government", "Records that MCA: Kingdoms governs a settlement's local government where a Townstead council also does. Townstead's council keeps running inside Townstead.",
                true, true, List.of(pick("settlement", "Settlement", "settlement"), text("note", "Note for the record", "", true)),
                c -> Outcome.applied(authority(c).decide(c.uuid("settlement"), TownsteadSavedData.Owner.KINGDOMS, c.player.getUUID(), c.text("note")), c.text("settlement")));
        effects("townstead.adopt", c -> c.text("settlement").isBlank() ? List.of() : new ArrayList<>(authority(c).preview(c.uuid("settlement"), TownsteadSavedData.Owner.KINGDOMS)));
        task("townstead.release", "Release local government to Townstead", "Records that Townstead's council governs a settlement's local government: MCA: Kingdoms then refuses new settlement offices and recognitions there. Nothing is deleted.",
                true, true, List.of(pick("settlement", "Settlement", "settlement"), text("note", "Note for the record", "", true)),
                c -> Outcome.applied(authority(c).decide(c.uuid("settlement"), TownsteadSavedData.Owner.TOWNSTEAD, c.player.getUUID(), c.text("note")), c.text("settlement")));
        effects("townstead.release", c -> c.text("settlement").isBlank() ? List.of() : new ArrayList<>(authority(c).preview(c.uuid("settlement"), TownsteadSavedData.Owner.TOWNSTEAD)));
        task("townstead.clear", "Clear a local government decision", "Removes an adoption or release, so the evidence decides again. Records on both sides are unchanged.",
                true, true, List.of(pick("settlement", "Settlement", "settlement")), c -> Outcome.applied(authority(c).clearDecision(c.uuid("settlement")), c.text("settlement")));
        task("townstead.acknowledge", "Accept Townstead's current data", "After checking a restored Townstead backup, records Townstead's current data as the baseline for a settlement. Nothing else changes.",
                true, true, List.of(pick("settlement", "Settlement", "settlement")), c -> Outcome.applied(authority(c).acknowledge(c.uuid("settlement")), c.text("settlement")));
    }

    /** Professions or skills, by name, for the chosen kind; empty (with its hint) when Townstead's registries cannot be read. */
    private static List<Choice> subjects(Context c) {
        if (c.text("kind").equals("TOWNSTEAD_SKILL"))
            return TownsteadCareers.skills(c.server()).orElse(List.of()).stream()
                    .map(skill -> Choice.of(skill.id(), skill.name(), TownsteadText.tr("qualify.skill_detail", "A learned skill · %s", skill.id()))).toList();
        return TownsteadCareers.professions(c.server()).orElse(List.of()).stream()
                .map(p -> Choice.of(p.id(), p.name(), p.progressive() ? TownsteadText.tr("qualify.profession_detail", "Tiers 1 to %s · %s", p.maxTier(), p.id())
                        : TownsteadText.tr("qualify.profession_flat", "One tier · %s", p.id()), Map.of("tiers", Integer.toString(p.maxTier())))).toList();
    }

    /** Tiers 1 to the profession's highest; a skill has no tier, so its single answer is chosen for the player. */
    private static List<Choice> tiers(Context c) {
        if (c.text("kind").equals("TOWNSTEAD_SKILL"))
            return List.of(Choice.of("1", TownsteadText.tr("qualify.no_tier", "No tier: a skill is learned or not"), null, Map.of(ActionRegistry.AUTOMATIC, "true")));
        int highest = 1;
        var chosen = c.choice("subject");
        if (chosen != null) try { highest = Math.max(1, Integer.parseInt(chosen.metadata().getOrDefault("tiers", "1"))); } catch (NumberFormatException ignored) { }
        var out = new ArrayList<Choice>();
        for (int tier = 1; tier <= Math.min(highest, 64); tier++)
            out.add(Choice.of(Integer.toString(tier), TownsteadText.tr("qualify.tier", "Tier %s", tier), null, highest == 1 ? Map.of(ActionRegistry.AUTOMATIC, "true") : Map.of()));
        return out;
    }

    private static TownsteadAuthority authority(Context c) {
        return TownsteadAuthority.get(c.server()).orElseThrow(() -> TownsteadText.refuse("inactive", "The Townstead integration is not attached on this server."));
    }
}
