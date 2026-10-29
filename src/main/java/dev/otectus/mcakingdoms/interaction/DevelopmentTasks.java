package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.development.*;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.State;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Predicate;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for settlement development (D5): read a settlement's development, propose, approve, fund, pause,
 * resume, cancel and repair works, allow construction in a settlement, protect one's home, and claim refunds. Works are
 * offered only in settlements the player knows; the server checks the mode, the office and the plot again.
 */
final class DevelopmentTasks {
    private DevelopmentTasks() {
    }

    private static Component d(String key, String fallback, Object... args) {
        return tr("development.mcakingdoms." + key, fallback, args);
    }

    private static DevelopmentService service(Context c) { return DevelopmentService.get(c.server()); }

    private static String version(Context c) {
        return DevelopmentSavedData.get(c.server()).records().revision() + ":" + DevelopmentMode.current();
    }

    private static List<Choice> works(Context c, Predicate<DevelopmentSavedData.Project> filter) {
        var out = new ArrayList<Choice>();
        var knowledge = SettlementKnowledge.get(c.server());
        var api = McaKingdomsApi.get(c.server());
        for (var p : DevelopmentSavedData.get(c.server()).records().projects()) {
            if (!filter.test(p) || !knowledge.visible(c.player, p.settlement())) continue;
            String where = api.getSettlement(p.settlement()).map(s -> s.displayName()).orElse("?");
            out.add(Choice.of(p.id().toString(), d("work.choice", "%s · %s", DevelopmentService.templateTitle(p.template()), where),
                    d("work.choice.detail", "%s · %s", enumLabel(p.state()), Keyed.component(p.reasonKey()))));
        }
        return out;
    }

    static void init() {
        targets("development_template", c -> {
            String kingdom = c.choices("settlement").stream().filter(s -> s.value().equals(c.text("settlement"))).findFirst().map(s -> s.metadata().getOrDefault("kingdom", "")).orElse("");
            return DevelopmentTemplate.all().stream().filter(t -> kingdom.isEmpty() || t.allowedIn(kingdom)).filter(t -> DevelopmentMode.current().atLeast(t.minimumMode()))
                    .map(t -> Choice.of(t.id().toString(), DevelopmentService.templateTitle(t), t.buildable()
                            ? d("template.buildable", "%s · built on a surveyed plot in managed growth; a funded milestone otherwise", enumLabel(t.role()))
                            : d("template.milestone", "%s · a funded milestone, never built", enumLabel(t.role())))).toList();
        });
        targets("approvable_work", c -> works(c, p -> p.state() == State.PROPOSED || p.state() == State.SURVEYED));
        targets("deliverable_work", c -> works(c, p -> p.state() == State.APPROVED));
        targets("pausable_work", c -> works(c, p -> p.state().open() && p.state() != State.PAUSED));
        targets("paused_work", c -> works(c, p -> p.state() == State.PAUSED));
        targets("open_work", c -> works(c, p -> p.state().open()));
        targets("repairable_work", c -> works(c, p -> p.state() == State.REPAIR_NEEDED || p.state() == State.BLOCKED));
        targets("repair_choice", c -> List.of(Choice.of("resurvey", d("repair.resurvey", "Survey the rest of the plot again and continue")),
                Choice.of("rollback", d("repair.rollback", "Remove what this work built, where it is unchanged, and cancel it"))));
        for (var kind : List.of("approvable_work", "deliverable_work", "pausable_work", "paused_work", "open_work", "repairable_work"))
            targetHint(kind, d("hint.work", "No work in a settlement you know is in that state."));
        targetHint("development_template", d("hint.template", "No work is available for this settlement under the server's development mode."));

        add(new Task("development.read", "Settlements", "Read a settlement's development",
                "Shows the server's development mode, the settlement's tier and what earned it, its recognized buildings, and every work there with what it still needs and why it waits. Read-only.",
                false, false, List.of(pick("settlement", "Settlement", "settlement")), c -> "", c -> {
                    var settlement = McaKingdomsApi.get(c.server()).getSettlement(c.uuid("settlement"))
                            .orElseThrow(() -> new InteractionNetwork.FormException(d("refusal.no_settlement", "That settlement is not known.")));
                    var lines = new ArrayList<Object>();
                    lines.add(d("read.mode", "Development mode: %s", enumLabel(DevelopmentMode.current())));
                    if (DevelopmentMode.current() == DevelopmentMode.OFF) { lines.add(d("page.mode.off", "Settlement development is off on this server: nothing is planned, funded or built.")); return lines; }
                    lines.addAll(service(c).observe(settlement));
                    for (var p : DevelopmentSavedData.get(c.server()).records().projects())
                        if (p.settlement().equals(settlement.id()) && (p.state().open() || p.state() == State.COMPLETED))
                            lines.add(d("read.work", "%s · %s: %s", DevelopmentService.templateTitle(p.template()), enumLabel(p.state()), Keyed.component(p.reasonKey())));
                    return lines;
                }));
        add(new Task("development.propose", "Settlements", "Propose a public work",
                "For an official with the Appoint officials responsibility, or an operator. Posts a work for a settlement; players fund it with real deliveries. In managed growth, a buildable work is surveyed where you stand: stand on its plot.",
                false, true, List.of(pick("settlement", "Settlement", "settlement"), pick("template", "Work", "development_template").after("settlement")), DevelopmentTasks::version,
                c -> service(c).propose(c.player, UUID.randomUUID(), c.uuid("settlement"), new ResourceLocation(c.text("template")))));
        add(new Task("development.approve", "Settlements", "Approve a public work",
                "For an official with the Appoint officials responsibility, or an operator: opens a surveyed work to deliveries.",
                false, true, List.of(pick("work", "Work", "approvable_work")), DevelopmentTasks::version,
                c -> service(c).approve(c.player, UUID.randomUUID(), c.uuid("work"))));
        add(new Task("development.contribute", "Settlements", "Deliver to a public work",
                "Inside the work's settlement, hand over what you carry that the work still needs. Only plain items are taken (nothing named, enchanted or marked), never more than it needs; if the record cannot be saved, they come back.",
                false, true, List.of(pick("work", "Work", "deliverable_work")), DevelopmentTasks::version,
                c -> service(c).contribute(c.player, UUID.randomUUID(), c.uuid("work"))));
        add(new Task("development.pause", "Settlements", "Pause a public work",
                "For an official: stops a work at once, construction included. Resume it after review.",
                false, true, List.of(pick("work", "Work", "pausable_work")), DevelopmentTasks::version,
                c -> service(c).pause(c.player, UUID.randomUUID(), c.uuid("work"))));
        add(new Task("development.resume", "Settlements", "Resume a public work",
                "For an official: continues a paused work. Construction checks the rest of its plot before it places anything.",
                false, true, List.of(pick("work", "Work", "paused_work")), DevelopmentTasks::version,
                c -> service(c).resume(c.player, UUID.randomUUID(), c.uuid("work"))));
        add(new Task("development.cancel", "Settlements", "Cancel a public work",
                "For an official. Contributors claim their deliveries back with Claim development refunds, unless the materials are already built in. Built blocks stay.",
                false, true, List.of(pick("work", "Work", "open_work")), DevelopmentTasks::version,
                c -> service(c).cancel(c.player, UUID.randomUUID(), c.uuid("work"))));
        add(new Task("development.repair", "Settlements", "Repair a public work",
                "For an official, when a work is blocked or met a block someone changed: survey the rest of its plot again and continue, or remove what it built (only where unchanged) and cancel it.",
                false, true, List.of(pick("work", "Work", "repairable_work"), pick("choice", "Repair", "repair_choice")), DevelopmentTasks::version,
                c -> service(c).repair(c.player, UUID.randomUUID(), c.uuid("work"), c.text("choice").equals("rollback"))));
        add(new Task("development.opt_in", "Settlements", "Allow construction in a settlement",
                "For an official, or an operator: in managed growth, lets funded works be built on their surveyed plots in this settlement. Off, its works are milestones only.",
                false, true, List.of(pick("settlement", "Settlement", "settlement"), toggle("allow", "Allow construction")), DevelopmentTasks::version,
                c -> service(c).optIn(c.player, UUID.randomUUID(), c.uuid("settlement"), Boolean.parseBoolean(c.text("allow")))));
        add(new Task("development.protect_home", "Settlements", "Protect your home from construction",
                "Keeps every construction away from the land around the bed you last slept in. Stand near your bed.",
                false, true, List.of(toggle("protect", "Protect my home")), DevelopmentTasks::version,
                c -> service(c).protectHome(c.player, UUID.randomUUID(), Boolean.parseBoolean(c.text("protect")))));
        add(new Task("development.refunds", "Settlements", "Claim development refunds",
                "Returns the deliveries you made to works that were cancelled before their materials were built in.",
                false, true, List.of(), DevelopmentTasks::version, c -> service(c).claimRefunds(c.player, UUID.randomUUID())));
    }
}
