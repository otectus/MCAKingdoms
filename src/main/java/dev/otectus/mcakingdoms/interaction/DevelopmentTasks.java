package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.development.*;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Project;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.State;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.Predicate;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for settlement development (D5): read a settlement's development, propose, approve, fund, pause, resume,
 * cancel, survey again and roll back works, allow or withdraw construction in a settlement, protect one's home, and claim
 * refunds. Works are offered only in settlements the player knows; the server checks the mode, the office and the plot again.
 *
 * <p>Each review depends only on what the decision reads (AUD-10): the chosen work's own revision and state, whether the
 * player may decide for its settlement's current kingdom, the mode the decision needs, and for a delivery the matching
 * items carried. Another settlement's construction no longer sends a player back to review, while a change to the work,
 * the authority or the inventory still does, and the service checks everything again when it applies.
 */
final class DevelopmentTasks {
    private DevelopmentTasks() {
    }

    private static Component d(String key, String fallback, Object... args) {
        return tr("development.mcakingdoms." + key, fallback, args);
    }

    private static DevelopmentService service(Context c) { return DevelopmentService.get(c.server()); }

    private static Optional<Project> work(Context c) {
        try { return DevelopmentSavedData.get(c.server()).records().project(UUID.fromString(c.text("work"))); }
        catch (IllegalArgumentException none) { return Optional.empty(); }
    }

    /** May the player decide for this work's settlement now: an operator, or an official of its current kingdom. */
    private static boolean decides(Context c, Project p) {
        if (c.player.hasPermissions(2)) return true;
        var settlement = McaKingdomsApi.get(c.server()).getSettlement(p.settlement());
        String kingdom = settlement.map(s -> s.kingdomId().toString()).orElse(p.kingdom());
        return dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(c.server()).authorized(c.player, kingdom,
                dev.otectus.mcakingdoms.api.politics.Politics.Permission.APPOINT);
    }

    /** The review fingerprint of a decision about one work: that work only, its authority, and the mode the decision needs. */
    private static String workVersion(Context c) {
        var p = work(c);
        return Review.of("work", p.map(x -> x.id() + "@" + x.revision()).orElse("none"), "state", p.map(x -> x.state().name()).orElse(""),
                "authority", p.map(x -> Boolean.toString(decides(c, x))).orElse(""), "mode", DevelopmentMode.current().name());
    }

    /** A delivery's review also covers what the player carries that the work still needs. */
    private static String deliveryVersion(Context c) {
        var p = work(c);
        return Review.of("work", p.map(x -> x.id() + "@" + x.revision()).orElse("none"), "state", p.map(x -> x.state().name()).orElse(""),
                "inventory", p.map(x -> carried(c, x).toString()).orElse(""), "mode", DevelopmentMode.current().name());
    }

    private static String settlementVersion(Context c) {
        UUID id; try { id = c.uuid("settlement"); } catch (RuntimeException none) { return Review.of("settlement", "none"); }
        var settlement = McaKingdomsApi.get(c.server()).getSettlement(id);
        var e = DevelopmentSavedData.get(c.server()).records().economy(id);
        boolean official = c.player.hasPermissions(2) || settlement.map(s -> dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(c.server())
                .authorized(c.player, s.kingdomId().toString(), dev.otectus.mcakingdoms.api.politics.Politics.Permission.APPOINT)).orElse(false);
        return Review.of("settlement", id.toString(), "kingdom", settlement.map(s -> s.kingdomId().toString()).orElse(""), "consent", Boolean.toString(e.optedIn()),
                "authority", Boolean.toString(official), "mode", DevelopmentMode.current().name(), "place", Long.toString(c.player.blockPosition().asLong()));
    }

    private static String refundVersion(Context c) {
        var records = DevelopmentSavedData.get(c.server()).records();
        var owed = records.refunds().stream().filter(r -> r.player().equals(c.player.getUUID())).mapToInt(DevelopmentSavedData.Refund::count).sum();
        var waiting = records.operations().stream().filter(o -> o.player().equals(c.player.getUUID()) && !o.stage().resolved()).count();
        return Review.of("owed", Integer.toString(owed), "waiting", Long.toString(waiting));
    }

    /** What the player carries that each demand of the work could take now, by demand, in order. */
    private static Map<Integer, Integer> carried(Context c, Project p) {
        var offers = new ArrayList<Development.Offer>();
        var inventory = c.player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty() || stack.hasTag()) continue;
            var tags = new HashSet<String>();
            stack.getTags().forEach(tag -> tags.add(tag.location().toString()));
            offers.add(new Development.Offer(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), tags, stack.getCount()));
        }
        var out = new TreeMap<Integer, Integer>();
        for (var take : Development.plan(p.demand(), offers)) out.merge(take.requirement(), take.count(), Integer::sum);
        return out;
    }

    private static Component ingredient(String ingredient) {
        if (ingredient.startsWith("#")) return d("page.tag", "any %s", words(ingredient.substring(ingredient.indexOf(':') + 1).replace('/', ' ')));
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(ingredient));
        return item.<Component>map(i -> i.getDescription()).orElse(Component.literal(ingredient));
    }

    private static List<Choice> works(Context c, Predicate<Project> filter) {
        var out = new ArrayList<Choice>();
        var knowledge = SettlementKnowledge.get(c.server());
        var api = McaKingdomsApi.get(c.server());
        for (var p : DevelopmentSavedData.get(c.server()).records().projects()) {
            // Settlement ids resolve through merges (AUD-06): a work under a retired id is still offered for its survivor.
            UUID settlement = api.resolveSettlementId(p.settlement());
            if (!filter.test(p) || !knowledge.visible(c.player, settlement)) continue;
            String where = api.getSettlement(settlement).map(s -> s.displayName()).orElse("?");
            out.add(Choice.of(p.id().toString(), d("work.choice", "%s · %s", DevelopmentService.templateTitle(p.template()), where),
                    d("work.choice.detail", "%s · %s", enumLabel(p.state()), Keyed.component(p.reasonKey()))));
        }
        return out;
    }

    /** A work task's typed outcome (AUD-10): the work it acted on, read again by Check without repeating anything. */
    private static Outcome outcome(DevelopmentService.Reply reply) {
        return reply.pending() ? Outcome.pending(Keyed.component(reply.text()), reply.operation()) : Outcome.applied(Keyed.component(reply.text()), reply.operation());
    }

    /** Check for a work: its state now, what it needs and what comes next; never runs the decision again. */
    private static Optional<Outcome> workStatus(Context c, String operation) {
        UUID id;
        try { id = UUID.fromString(operation); } catch (IllegalArgumentException malformed) { return Optional.empty(); }
        var p = DevelopmentSavedData.get(c.server()).records().project(id);
        if (p.isEmpty() || !SettlementKnowledge.get(c.server()).visible(c.player, McaKingdomsApi.get(c.server()).resolveSettlementId(p.get().settlement()))) return Optional.empty();
        var lines = new ArrayList<Object>(status(c, p.get()));
        boolean waiting = p.get().state().open() && p.get().state() != State.PAUSED && p.get().state() != State.BLOCKED && p.get().state() != State.REPAIR_NEEDED;
        var text = Component.empty();
        for (int i = 0; i < lines.size(); i++) { if (i > 0) text.append("\n"); text.append(lines.get(i) instanceof Component comp ? comp : Component.literal(String.valueOf(lines.get(i)))); }
        return Optional.of(waiting ? Outcome.pending(text, operation) : Outcome.applied(text, operation));
    }

    /** One work as players need to read it (AUD-10): where, its kind, its state and reason, what it still needs and what can happen next. */
    static List<Component> status(Context c, Project p) {
        var lines = new ArrayList<Component>();
        var api = McaKingdomsApi.get(c.server());
        String where = api.getSettlement(p.settlement()).map(s -> s.displayName()).orElse("?");
        lines.add(d("status.work", "%s in %s: %s", DevelopmentService.templateTitle(p.template()), where, enumLabel(p.state())));
        lines.add(p.construction() ? d("status.kind_construction", "Construction on a surveyed plot at %s; %s of %s block(s) built.", p.origin() == null ? "?" : p.origin().toShortString(),
                p.journal().stream().filter(DevelopmentSavedData.Placement::placed).count(), p.journal().size())
                : d("status.kind_milestone", "A funded milestone: completing it builds nothing."));
        lines.add(Keyed.component(p.reasonKey()));
        for (var b : p.blockers().stream().skip(1).limit(3).toList()) lines.add(DevelopmentText.component(b));
        for (var r : p.demand()) lines.add(d("status.need", "%s: %s of %s delivered, %s still needed.", ingredient(r.ingredient()), r.delivered(), r.count(), r.remaining()));
        lines.add(next(p));
        return lines;
    }

    private static Component next(Project p) {
        return switch (p.state()) {
            case PROPOSED, SURVEYED -> d("next.approve", "Next: an official approves it (Approve a public work).");
            case APPROVED -> d("next.deliver", "Next: anyone delivers what it needs, in person (Deliver to a public work).");
            case FUNDED -> p.construction() ? d("next.funded_construction", "Next: it is built once the settlement allows construction and the server's mode permits it.")
                    : d("next.funded_milestone", "Next: it is completed at the next maintenance pass.");
            case READY, BUILDING -> d("next.building", "Next: construction continues a few blocks at a time; it completes after a world save confirms every block.");
            case PAUSED -> d("next.resume", "Next: an official resumes it (Resume a public work) once what stopped it is resolved.");
            case BLOCKED, REPAIR_NEEDED -> d("next.repair", "Next: an official surveys it again (Survey a public work again) or rolls it back (Roll back a public work).");
            case ROLLING_BACK -> d("next.rollback", "Next: the rollback finishes as its area loads, or an official finalizes it (Roll back a public work).");
            case COMPLETED -> d("next.completed", "Complete; it counts toward the settlement's development.");
            case CANCELLED -> d("next.cancelled", "Cancelled; contributors claim any refunds with Claim development refunds.");
        };
    }

    static void init() {
        targets("development_template", c -> {
            String kingdom = c.choices("settlement").stream().filter(s -> s.value().equals(c.text("settlement"))).findFirst().map(s -> s.metadata().getOrDefault("kingdom", "")).orElse("");
            return DevelopmentTemplate.all().stream().filter(t -> kingdom.isEmpty() || t.allowedIn(kingdom)).filter(t -> DevelopmentMode.current().atLeast(t.minimumMode()))
                    .map(t -> Choice.of(t.id().toString(), DevelopmentService.templateTitle(t), t.buildable() && DevelopmentMode.current().atLeast(DevelopmentMode.MANAGED_GROWTH)
                            ? d("template.buildable_now", "%s · construction: surveyed where you stand and built on that plot once funded and allowed", enumLabel(t.role()))
                            : t.buildable() ? d("template.buildable_later", "%s · a funded milestone now; this server's mode does not build it", enumLabel(t.role()))
                            : d("template.milestone", "%s · a funded milestone, never built", enumLabel(t.role())))).toList();
        });
        targets("approvable_work", c -> works(c, p -> p.state() == State.PROPOSED || p.state() == State.SURVEYED));
        targets("deliverable_work", c -> works(c, p -> p.state() == State.APPROVED));
        targets("pausable_work", c -> works(c, p -> p.state().open() && p.state() != State.PAUSED && p.state() != State.ROLLING_BACK));
        targets("paused_work", c -> works(c, p -> p.state() == State.PAUSED));
        targets("open_work", c -> works(c, p -> p.state().open() && p.state() != State.ROLLING_BACK));
        targets("repairable_work", c -> works(c, p -> p.state() == State.REPAIR_NEEDED || p.state() == State.BLOCKED));
        targets("rollbackable_work", c -> works(c, p -> p.construction() && (p.state() == State.ROLLING_BACK
                || (p.state() == State.REPAIR_NEEDED || p.state() == State.BLOCKED || p.state() == State.PAUSED) && p.journal().stream().anyMatch(DevelopmentSavedData.Placement::placed))));
        targets("any_work", c -> works(c, p -> true));
        targets("rollback_choice", c -> {
            var p = work(c);
            var out = new ArrayList<Choice>();
            out.add(Choice.of("rollback", d("rollback.restore", "Remove what this work built, only where its blocks are unchanged")));
            if (p.map(x -> x.state() == State.ROLLING_BACK).orElse(false))
                out.add(Choice.of("finalize", d("rollback.finalize", "Close the rollback now and leave the listed blocks in place")));
            return out;
        });
        for (var kind : List.of("approvable_work", "deliverable_work", "pausable_work", "paused_work", "open_work", "repairable_work", "rollbackable_work", "any_work"))
            targetHint(kind, d("hint.work", "No work in a settlement you know is in that state."));
        targetHint("development_template", d("hint.template", "No work is available for this settlement under the server's development mode."));

        add(new Task("development.read", "Settlements", "Read a settlement's development",
                "Shows the server's development mode, the settlement's tier and what earned it, whether it allows construction, its recognized buildings, and every work there with what it still needs, why it waits and what comes next. Read-only.",
                false, false, List.of(pick("settlement", "Settlement", "settlement")), c -> "", c -> {
                    var api = McaKingdomsApi.get(c.server());
                    var settlement = api.getSettlement(c.uuid("settlement"))
                            .orElseThrow(() -> new InteractionNetwork.FormException(d("refusal.no_settlement", "That settlement is not known.")));
                    var lines = new ArrayList<Object>();
                    lines.add(d("read.mode", "Development mode: %s", enumLabel(DevelopmentMode.current())));
                    if (DevelopmentMode.current() == DevelopmentMode.OFF && DevelopmentSavedData.get(c.server()).records().projects().isEmpty()) {
                        lines.add(d("page.mode.off", "Settlement development is off on this server: nothing is planned, funded or built."));
                        return lines;
                    }
                    lines.addAll(service(c).observe(settlement));
                    for (var p : DevelopmentSavedData.get(c.server()).records().projects())
                        if (api.resolveSettlementId(p.settlement()).equals(settlement.id()) && (p.state().open() || p.state() == State.COMPLETED))
                            lines.addAll(status(c, p));
                    return lines;
                }));
        add(new Task("development.propose", "Settlements", "Propose a public work",
                "For an official with the Appoint officials responsibility, or an operator. Posts a work for a settlement; players fund it with real deliveries. A proposal is a record, not construction: in managed growth a buildable work is surveyed where you stand (stand on its plot), and it is built only after it is approved, fully funded and the settlement allows construction.",
                false, true, List.of(pick("settlement", "Settlement", "settlement"), pick("template", "Work", "development_template").after("settlement")), DevelopmentTasks::settlementVersion,
                c -> outcome(service(c).propose(c.player, DevelopmentService.request(c.server()), c.uuid("settlement"), new ResourceLocation(c.text("template"))))));
        probe("development.propose", DevelopmentTasks::workStatus);
        add(new Task("development.approve", "Settlements", "Approve a public work",
                "For an official with the Appoint officials responsibility in the settlement's kingdom, or an operator: opens a surveyed work to deliveries.",
                false, true, List.of(pick("work", "Work", "approvable_work")), DevelopmentTasks::workVersion,
                c -> outcome(service(c).approve(c.player, DevelopmentService.request(c.server()), c.uuid("work")))));
        probe("development.approve", DevelopmentTasks::workStatus);
        add(new Task("development.contribute", "Settlements", "Deliver to a public work",
                "Inside the work's settlement, hand over what you carry that the work still needs. Only plain items are taken (nothing named, enchanted or marked), never more than it needs. The delivery is confirmed when your player data is saved, at once in normal play; if the server stops first, it is settled from your saved inventory and nothing is taken twice.",
                false, true, List.of(pick("work", "Work", "deliverable_work")), DevelopmentTasks::deliveryVersion,
                c -> outcome(service(c).contribute(c.player, DevelopmentService.request(c.server()), c.uuid("work")))));
        requirements("development.contribute", c -> work(c).<List<Requirement>>map(p -> {
            var rows = new ArrayList<Requirement>();
            var carried = carried(c, p);
            for (int i = 0; i < p.demand().size(); i++) {
                var r = p.demand().get(i);
                int have = carried.getOrDefault(i, 0);
                rows.add(r.remaining() == 0 ? new Requirement(ingredient(r.ingredient()), Requirement.Check.NOTE, d("req.need_done", "%s of %s delivered; no more needed", r.delivered(), r.count()))
                        : Requirement.of(have > 0, ingredient(r.ingredient()), d("req.need", "%s still needed; you would hand over %s", r.remaining(), have)));
            }
            return rows;
        }).orElse(List.of()));
        effects("development.contribute", c -> work(c).<List<Object>>map(p -> {
            var lines = new ArrayList<Object>();
            var carried = carried(c, p);
            int total = carried.values().stream().mapToInt(Integer::intValue).sum();
            lines.add(total == 0 ? d("effect.deliver_none", "You carry nothing this work still needs, so nothing would be taken.")
                    : d("effect.deliver", "%s item(s) leave your inventory for %s and count toward it. They come back only if the work is cancelled before any block is built.", total,
                    DevelopmentService.templateTitle(p.template())));
            return lines;
        }).orElse(List.of()));
        probe("development.contribute", (c, operation) -> {
            try { var reply = service(c).operationStatus(UUID.fromString(operation)); return Optional.of(outcome(reply)); }
            catch (IllegalArgumentException malformed) { return Optional.empty(); }
        });
        add(new Task("development.pause", "Settlements", "Pause a public work",
                "For an official: stops a work at once; construction stops before its next block. Resume it after review. Works even while development is turned down.",
                false, true, List.of(pick("work", "Work", "pausable_work")), DevelopmentTasks::workVersion,
                c -> outcome(service(c).pause(c.player, DevelopmentService.request(c.server()), c.uuid("work")))));
        probe("development.pause", DevelopmentTasks::workStatus);
        add(new Task("development.resume", "Settlements", "Resume a public work",
                "For an official of the settlement's current kingdom: continues a paused work, and you become the official it answers to. Construction first surveys every block it has not built yet; if anything is in the way, it stays paused and lists what.",
                false, true, List.of(pick("work", "Work", "paused_work")), DevelopmentTasks::workVersion,
                c -> outcome(service(c).resume(c.player, DevelopmentService.request(c.server()), c.uuid("work")))));
        probe("development.resume", DevelopmentTasks::workStatus);
        add(new Task("development.cancel", "Settlements", "Cancel a public work",
                "For an official. Before any block is built, contributors claim their deliveries back with Claim development refunds. Once a block is built, the materials count as used and nothing is refunded; built blocks stay. Works even while development is turned down.",
                false, true, List.of(pick("work", "Work", "open_work")), DevelopmentTasks::workVersion,
                c -> outcome(service(c).cancel(c.player, DevelopmentService.request(c.server()), c.uuid("work")))));
        effects("development.cancel", c -> work(c).<List<Object>>map(p -> List.of(p.journal().stream().anyMatch(DevelopmentSavedData.Placement::placed)
                ? d("effect.cancel_built", "Blocks are already built, so the deliveries count as used: nothing is refunded and the built blocks stay. Roll the work back first if they should be removed.")
                : d("effect.cancel_refund", "Nothing is built yet: every delivery (%s contributor(s)) becomes a refund to claim.", p.contributions().stream().map(x -> x.player()).distinct().count())))
                .orElse(List.of()));
        probe("development.cancel", DevelopmentTasks::workStatus);
        add(new Task("development.repair", "Settlements", "Survey a public work again",
                "For an official, when a work is blocked or met a block someone changed: surveys every block it has not built yet as it is now and continues only if all are clear. A block someone else placed is never built over or taken as the work's; resolve it first.",
                false, true, List.of(pick("work", "Work", "repairable_work")), DevelopmentTasks::workVersion,
                c -> outcome(service(c).repair(c.player, DevelopmentService.request(c.server()), c.uuid("work"), DevelopmentService.Repair.RESURVEY))));
        probe("development.repair", DevelopmentTasks::workStatus);
        add(new Task("development.rollback", "Settlements", "Roll back a public work",
                "For an official: removes what a construction work built, only where its blocks are still exactly its own and your rights allow; changed or protected blocks stay and are listed. Unloaded blocks are removed when their area loads. Works even while development is turned down.",
                false, true, List.of(pick("work", "Work", "rollbackable_work"), pick("choice", "Rollback", "rollback_choice").after("work")), DevelopmentTasks::workVersion,
                c -> outcome(service(c).repair(c.player, DevelopmentService.request(c.server()), c.uuid("work"),
                        c.text("choice").equals("finalize") ? DevelopmentService.Repair.FINALIZE : DevelopmentService.Repair.ROLLBACK))));
        probe("development.rollback", DevelopmentTasks::workStatus);
        add(new Task("development.opt_in", "Settlements", "Allow construction in a settlement",
                "For an official, or an operator: in managed growth, lets funded works be built on their surveyed plots in this settlement. Approval, funding and the server's mode are still needed.",
                false, true, List.of(pick("settlement", "Settlement", "settlement")), DevelopmentTasks::settlementVersion,
                c -> outcome(service(c).optIn(c.player, DevelopmentService.request(c.server()), c.uuid("settlement")))));
        add(new Task("development.opt_out", "Settlements", "Stop construction in a settlement",
                "For an official, or an operator: withdraws the settlement's consent. Works being built stop before their next block and wait for consent and a resume; funded works stay funded. Works even while development is turned down.",
                false, true, List.of(pick("settlement", "Settlement", "settlement")), DevelopmentTasks::settlementVersion,
                c -> outcome(service(c).optOut(c.player, DevelopmentService.request(c.server()), c.uuid("settlement")))));
        add(new Task("development.protect_home", "Settlements", "Protect your home from construction",
                "Keeps every construction away from the land around the bed you last slept in. Stand near your bed.",
                false, true, List.of(toggle("protect", "Protect my home")), c -> Review.of("bed", String.valueOf(c.player.getRespawnPosition()), "protect", c.text("protect")),
                c -> outcome(service(c).protectHome(c.player, DevelopmentService.request(c.server()), Boolean.parseBoolean(c.text("protect"))))));
        add(new Task("development.refunds", "Settlements", "Claim development refunds",
                "Returns the deliveries you are owed from works cancelled before any block was built, as far as your inventory has room; the rest stays owed. Shows a delivery or refund still being confirmed. Works even while development is turned down.",
                false, true, List.of(), DevelopmentTasks::refundVersion, c -> outcome(service(c).claimRefunds(c.player, DevelopmentService.request(c.server())))));
        requirements("development.refunds", c -> {
            var records = DevelopmentSavedData.get(c.server()).records();
            var owed = records.refunds().stream().filter(r -> r.player().equals(c.player.getUUID())).toList();
            var rows = new ArrayList<Requirement>();
            rows.add(Requirement.of(!owed.isEmpty(), d("req.refunds", "Refunds owed to you"), d("req.refunds_detail", "%s item(s)", owed.stream().mapToInt(DevelopmentSavedData.Refund::count).sum())));
            records.operations().stream().filter(o -> o.player().equals(c.player.getUUID()) && !o.stage().resolved()).findFirst().ifPresent(o ->
                    rows.add(new Requirement(d("req.operation_waiting", "A delivery or refund is still being confirmed"), Requirement.Check.UNMET, Keyed.component(service(c).operationStatus(o.id()).text()))));
            return rows;
        });
        probe("development.refunds", (c, operation) -> {
            try { return Optional.of(outcome(service(c).operationStatus(UUID.fromString(operation)))); }
            catch (IllegalArgumentException malformed) { return Optional.empty(); }
        });
        add(new Task("development.status", "Settlements", "Check a public work",
                "Reads one work as it stands now: where, what kind, its state and reason, what it still needs, and what can happen next. Read-only.",
                false, false, List.of(pick("work", "Work", "any_work")), c -> "", c -> work(c).<Object>map(p -> new ArrayList<Object>(status(c, p)))
                .orElseThrow(() -> new InteractionNetwork.FormException(d("refusal.no_project", "That work is not in the records.")))));
    }
}
