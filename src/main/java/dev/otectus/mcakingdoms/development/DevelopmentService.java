package dev.otectus.mcakingdoms.development;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Notify;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Settlement development and the economy (D1-D6). Officials post public works; players fund them with real deliveries,
 * taken from their inventory exactly once per request and handed back if the record cannot be saved; a funded work is a
 * recorded milestone, or, in {@code MANAGED_GROWTH} and above for a settlement that opted in, is built on its surveyed
 * plot a few blocks at a time. Construction re-checks every block before placing it, never replaces what changed since
 * the survey, stops when its approver loses office or the mode is lowered, and journals each block so a restart neither
 * repeats nor loses work. {@code FULL_ECONOMY} adds an abstract, labelled goods ledger with capped trade between
 * settlements whose kingdoms have an agreement in force; it never creates or moves a real item or coin. There is no tax,
 * no currency and no offline catch-up. Nothing is written while the mode is {@code OFF}.
 */
public final class DevelopmentService {
    static final String P = "development.mcakingdoms.";
    private static final Logger LOG = LogUtils.getLogger();
    /** Ticks between maintenance passes; also the period of the block budget. */
    static final int PERIOD = 20;
    /** Most works one pass advances. */
    static final int PROJECT_BUDGET = 16;
    static final long DAY = 24_000;

    private final MinecraftServer server;
    private final DevelopmentSavedData data;

    public DevelopmentService(MinecraftServer server, DevelopmentSavedData data) {
        this.server = server; this.data = data;
    }

    public static DevelopmentService get(MinecraftServer server) {
        return new DevelopmentService(server, DevelopmentSavedData.get(server));
    }

    public DevelopmentSavedData data() { return data; }
    public MinecraftServer server() { return server; }
    long now() { return server.overworld().getGameTime(); }

    private GovernmentService politics() {
        if (KingdomsPoliticsApi.get(server) instanceof GovernmentService government) return government;
        throw Keyed.refuse(P + "refusal.politics_unavailable", "Political records are unavailable on this server.");
    }

    static boolean operator(ServerPlayer player) { return player.hasPermissions(2); }

    /** An official who may run a settlement's works: an operator, or whoever holds the Appoint officials responsibility in its kingdom. */
    boolean official(ServerPlayer actor, String kingdom) {
        return operator(actor) || politics().permitted(actor.getUUID(), kingdom, Politics.Permission.APPOINT);
    }

    /** The settlement as it is now, following a merge to the settlement that absorbed it. */
    Optional<SettlementView> settlement(UUID id) {
        return id == null ? Optional.empty() : McaKingdomsApi.get(server).findSettlement(id.toString());
    }

    public static Component templateTitle(DevelopmentTemplate template) {
        return Component.translatableWithFallback(template.titleKey(), ActionRegistry.words(template.id().getPath()));
    }

    public static Component templateTitle(ResourceLocation id) {
        return DevelopmentTemplate.get(id).map(DevelopmentService::templateTitle).orElse(Component.literal(ActionRegistry.words(id.getPath())));
    }

    // --- decisions ---------------------------------------------------------------------------------------------------

    private record Change(Records next, List<Runnable> after) { }

    private String change(ServerPlayer actor, UUID request, String fingerprint, DevelopmentMode minimum, Function<Change, String> body) {
        Keyed.require(actor.getServer() == server && server.isSameThread() && !actor.hasDisconnected(), P + "refusal.connected", "You must be connected to this server.");
        Keyed.require(DevelopmentMode.current().atLeast(minimum), P + "refusal.mode", "Settlement development is not turned on to this extent on this server (mode %s).",
                ActionRegistry.enumLabel(DevelopmentMode.current()));
        var replay = data.receipt(request);
        if (replay.isPresent()) {
            Keyed.require(replay.get().actor().equals(actor.getUUID()) && replay.get().fingerprint().equals(fingerprint), P + "refusal.request_used",
                    "That request was already used for a different decision.");
            return replay.get().result();
        }
        Keyed.require(data.writable(), P + "refusal.read_only", "The development records are kept read-only on this server, so nothing can change them now.");
        var change = new Change(data.transaction(), new ArrayList<>());
        String result = body.apply(change);
        change.next().receipts.put(request, new DevelopmentSavedData.Receipt(actor.getUUID(), fingerprint, result));
        Keyed.require(data.commit(server, change.next()), P + "refusal.save_failed", "The development records could not be saved, so nothing changed.");
        for (Runnable step : change.after()) {
            try { step.run(); } catch (RuntimeException failure) { LOG.error("[MCA: Kingdoms] A development notice failed after its record was saved", failure); }
        }
        return result;
    }

    private Project project(Records records, UUID id) {
        return Optional.ofNullable(records.projects.get(id)).orElseThrow(() -> Keyed.refuse(P + "refusal.no_project", "That work is not in the records."));
    }

    /** Posts a public work for a settlement; buildable works in {@code MANAGED_GROWTH} are surveyed at the official's position now. */
    public String propose(ServerPlayer actor, UUID request, UUID settlementId, ResourceLocation templateId) {
        return change(actor, request, "propose|" + settlementId + "|" + templateId + "|" + actor.blockPosition().asLong(), DevelopmentMode.PROJECTS, c -> {
            var settlement = settlement(settlementId).orElseThrow(() -> Keyed.refuse(P + "refusal.no_settlement", "That settlement is not known."));
            String kingdom = settlement.kingdomId().toString();
            Keyed.require(official(actor, kingdom), P + "refusal.official", "Works are run by an official with the Appoint officials responsibility in %s, or by an operator.",
                    dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, kingdom));
            var template = DevelopmentTemplate.get(templateId).orElseThrow(() -> Keyed.refuse(P + "refusal.no_template", "That work is not defined on this server."));
            Keyed.require(DevelopmentMode.current().atLeast(template.minimumMode()), P + "refusal.template_mode", "That work needs development mode %s.", ActionRegistry.enumLabel(template.minimumMode()));
            Keyed.require(template.allowedIn(kingdom), P + "refusal.template_kingdom", "That work is not part of this kingdom's style.");
            long open = c.next().projects.values().stream().filter(p -> p.state().open()).count();
            Keyed.require(open < DevelopmentMode.maxProjects(), P + "refusal.server_full", "The server already has its most open works.");
            long here = c.next().projects.values().stream().filter(p -> p.state().open() && p.settlement().equals(settlement.id())).count();
            Keyed.require(here < DevelopmentMode.projectsPerSettlement(), P + "refusal.settlement_full", "%s already has its most open works.", settlement.displayName());
            UUID id = UUID.nameUUIDFromBytes(("work|" + request).getBytes(StandardCharsets.UTF_8));
            long now = now();
            var demand = template.demand().stream().map(d -> new Requirement(d.ingredient(), d.count(), 0)).toList();
            String dimension = ""; BlockPos origin = null; State state = State.SURVEYED; String reason = P + "reason.milestone";
            List<Placement> plan = List.of();
            if (template.buildable() && DevelopmentMode.current().atLeast(DevelopmentMode.MANAGED_GROWTH)) {
                var level = actor.serverLevel();
                origin = template.placement() == DevelopmentTemplate.Placement.GROUND ? actor.blockPosition().below() : actor.blockPosition();
                dimension = level.dimension().location().toString();
                var survey = PlotSurvey.survey(level, template, origin, settlement, c.next().homes.values(), actor.getUUID());
                plan = survey.plan();
                state = survey.clear() ? State.SURVEYED : State.BLOCKED;
                reason = survey.clear() ? P + "reason.surveyed" : survey.blockers().get(0);
            }
            c.next().projects.put(id, new Project(id, settlement.id(), kingdom, templateId, state, reason, actor.getUUID(), null, false, dimension, origin, demand, List.of(),
                    plan, null, now, now, c.next().revision));
            return state == State.BLOCKED ? Keyed.text(P + "result.proposed_blocked", "%s is recorded for %s, but its plot cannot be built: %s", templateTitle(template), settlement.displayName(), Keyed.component(reason))
                    : Keyed.text(P + "result.proposed", "%s is proposed for %s. An official approves it; then players fund it.", templateTitle(template), settlement.displayName());
        });
    }

    /** Approves a surveyed work for funding. */
    public String approve(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "approve|" + projectId, DevelopmentMode.PROJECTS, c -> {
            var p = project(c.next(), projectId);
            Keyed.require(official(actor, p.kingdom()), P + "refusal.official_here", "Works are run by an official with the Appoint officials responsibility in this kingdom, or by an operator.");
            Keyed.require(p.state() == State.SURVEYED || p.state() == State.PROPOSED, P + "refusal.not_surveyed", "Only a proposed or surveyed work can be approved.");
            c.next().projects.put(projectId, p.approved(actor.getUUID(), operator(actor) && !politics().permitted(actor.getUUID(), p.kingdom(), Politics.Permission.APPOINT), now(), c.next().revision));
            return Keyed.text(P + "result.approved", "%s is approved; players can now deliver what it needs.", templateTitle(p.template()));
        });
    }

    /**
     * Delivers what the actor carries toward an approved work: plain stacks only (nothing named, enchanted or holding
     * data), never more than each demand still needs. The items leave the inventory, the record is written, and if it
     * cannot be written they are handed back. Only inside the work's settlement.
     */
    public String contribute(ServerPlayer actor, UUID request, UUID projectId) {
        Keyed.require(!DevelopmentMode.economyPaused(), P + "refusal.economy_paused", "Deliveries are paused on this server.");
        var taken = new ArrayList<ItemStack>();
        try {
            return change(actor, request, "contribute|" + projectId, DevelopmentMode.PROJECTS, c -> {
                var p = project(c.next(), projectId);
                Keyed.require(p.state() == State.APPROVED, P + "refusal.not_open_for_delivery", "That work is not taking deliveries now.");
                var here = McaKingdomsApi.get(server).getSettlementAt(actor.serverLevel(), actor.blockPosition());
                Keyed.require(here.map(s -> s.id().equals(p.settlement())).orElse(false), P + "refusal.deliver_here", "Deliver in person, inside the work's settlement.");
                var inventory = actor.getInventory();
                var offers = new ArrayList<Development.Offer>();
                for (int slot = 0; slot < inventory.items.size(); slot++) {
                    ItemStack stack = inventory.items.get(slot);
                    if (stack.isEmpty() || stack.hasTag()) continue;
                    var tags = new HashSet<String>();
                    stack.getTags().forEach(tag -> tags.add(tag.location().toString()));
                    offers.add(new Development.Offer(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), tags, stack.getCount()));
                }
                var takes = Development.plan(p.demand(), offers);
                Keyed.require(!takes.isEmpty(), P + "refusal.nothing_matches", "You carry nothing this work still needs (named, enchanted or marked items are never taken).");
                var demand = Development.apply(p.demand(), takes);
                var contributions = new ArrayList<>(p.contributions());
                long now = now();
                var given = new TreeMap<String, Integer>();
                for (var take : takes) {
                    taken.add(inventory.items.get(take.slot()).split(take.count()));
                    given.merge(take.item(), take.count(), Integer::sum);
                }
                given.forEach((item, count) -> contributions.add(new Contribution(actor.getUUID(), actor.getGameProfile().getName(), item, count, now)));
                Keyed.require(contributions.size() <= DevelopmentSavedData.CONTRIBUTIONS, P + "refusal.contributions_full", "That work has recorded its most deliveries.");
                var updated = p.delivered(demand, contributions, now, c.next().revision);
                c.next().projects.put(projectId, updated);
                int total = given.values().stream().mapToInt(Integer::intValue).sum();
                if (updated.state() == State.FUNDED) c.after().add(() -> Notify.government(server, p.kingdom(), actor.getUUID(), ActionRegistry.tr(P + "notice.funded",
                        "%s in %s is fully funded.", templateTitle(p.template()), settlement(p.settlement()).map(SettlementView::displayName).orElse("")), "development.read"));
                return updated.state() == State.FUNDED ? Keyed.text(P + "result.delivered_funded", "You delivered %s item(s). %s is now fully funded.", total, templateTitle(p.template()))
                        : Keyed.text(P + "result.delivered", "You delivered %s item(s) to %s.", total, templateTitle(p.template()));
            });
        } catch (RuntimeException refused) {
            // The record was not written: every item taken goes back to the player.
            for (ItemStack stack : taken) if (!stack.isEmpty() && !actor.getInventory().add(stack)) actor.drop(stack, false);
            throw refused;
        }
    }

    /** Pauses an open work; construction stops at once. */
    public String pause(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "pause|" + projectId, DevelopmentMode.PROJECTS, c -> {
            var p = project(c.next(), projectId);
            Keyed.require(official(actor, p.kingdom()), P + "refusal.official_here", "Works are run by an official with the Appoint officials responsibility in this kingdom, or by an operator.");
            Keyed.require(p.state().open() && p.state() != State.PAUSED, P + "refusal.not_pausable", "That work cannot be paused now.");
            c.next().projects.put(projectId, p.with(State.PAUSED, P + "reason.paused", now(), c.next().revision));
            return Keyed.text(P + "result.paused", "%s is paused.", templateTitle(p.template()));
        });
    }

    /** Resumes a paused work after review: construction re-surveys the rest of its plot first. */
    public String resume(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "resume|" + projectId, DevelopmentMode.PROJECTS, c -> {
            var p = project(c.next(), projectId);
            Keyed.require(official(actor, p.kingdom()), P + "refusal.official_here", "Works are run by an official with the Appoint officials responsibility in this kingdom, or by an operator.");
            Keyed.require(p.state() == State.PAUSED, P + "refusal.not_paused", "That work is not paused.");
            State back = p.pausedFrom() == null ? State.SURVEYED : p.pausedFrom() == State.BUILDING ? State.READY : p.pausedFrom();
            c.next().projects.put(projectId, p.with(back, P + "reason.resumed", now(), c.next().revision));
            return Keyed.text(P + "result.resumed", "%s resumes.", templateTitle(p.template()));
        });
    }

    /** Cancels an open work; every delivery becomes a refund its contributor claims in person. Blocks already built stay. */
    public String cancel(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "cancel|" + projectId, DevelopmentMode.PROJECTS, c -> {
            var p = project(c.next(), projectId);
            Keyed.require(official(actor, p.kingdom()), P + "refusal.official_here", "Works are run by an official with the Appoint officials responsibility in this kingdom, or by an operator.");
            Keyed.require(p.state().open(), P + "refusal.closed", "That work is already finished or cancelled.");
            boolean built = p.journal().stream().anyMatch(Placement::placed);
            if (!built) for (var contribution : p.contributions()) c.next().refunds.add(new Refund(contribution.player(), contribution.item(), contribution.count(), p.id()));
            Keyed.require(c.next().refunds.size() <= DevelopmentSavedData.REFUNDS, P + "refusal.refunds_full", "The refund register is full on this server.");
            c.next().projects.put(projectId, p.with(State.CANCELLED, built ? P + "reason.cancelled_built" : P + "reason.cancelled", now(), c.next().revision));
            return built ? Keyed.text(P + "result.cancelled_built", "%s is cancelled. Its materials are already built in, so nothing is refunded.", templateTitle(p.template()))
                    : Keyed.text(P + "result.cancelled", "%s is cancelled. Contributors claim their deliveries back with Claim development refunds.", templateTitle(p.template()));
        });
    }

    /** Repairs a work that met a changed block or a blocked plot: survey the rest again, or roll back the blocks it placed. */
    public String repair(ServerPlayer actor, UUID request, UUID projectId, boolean rollback) {
        return change(actor, request, "repair|" + projectId + "|" + rollback, DevelopmentMode.MANAGED_GROWTH, c -> {
            var p = project(c.next(), projectId);
            Keyed.require(official(actor, p.kingdom()), P + "refusal.official_here", "Works are run by an official with the Appoint officials responsibility in this kingdom, or by an operator.");
            Keyed.require(p.state() == State.REPAIR_NEEDED || p.state() == State.BLOCKED, P + "refusal.not_repairable", "Only a blocked work or one needing repair can be repaired.");
            var level = level(p).orElseThrow(() -> Keyed.refuse(P + "refusal.no_level", "The work's dimension is not available."));
            if (rollback) {
                var journal = new ArrayList<Placement>();
                int undone = 0, kept = 0;
                for (var placement : p.journal()) {
                    if (placement.placed() && level.hasChunkAt(placement.pos()) && PlotSurvey.ours(level, placement)) {
                        var before = PlotSurvey.state(level, placement.before());
                        if (before.isPresent()) { level.setBlock(placement.pos(), before.get(), Block.UPDATE_ALL); undone++; journal.add(new Placement(placement.pos(), placement.before(), placement.after(), false)); continue; }
                    }
                    if (placement.placed()) kept++;
                    journal.add(placement);
                }
                c.next().projects.put(projectId, p.journal(journal, State.CANCELLED, P + "reason.rolled_back", now(), c.next().revision));
                return Keyed.text(P + "result.rolled_back", "%s is rolled back: %s block(s) removed; %s changed since by someone else stay.", templateTitle(p.template()), undone, kept);
            }
            var template = DevelopmentTemplate.get(p.template()).orElseThrow(() -> Keyed.refuse(P + "refusal.no_template", "That work is not defined on this server."));
            var settlement = settlement(p.settlement()).orElseThrow(() -> Keyed.refuse(P + "refusal.no_settlement", "That settlement is not known."));
            if (p.origin() == null) {
                c.next().projects.put(projectId, p.with(p.funded() ? State.FUNDED : p.approver() == null ? State.SURVEYED : State.APPROVED, P + "reason.resumed", now(), c.next().revision));
                return Keyed.text(P + "result.repaired", "%s was surveyed again and continues.", templateTitle(p.template()));
            }
            var survey = PlotSurvey.survey(level, template, p.origin(), settlement, c.next().homes.values(), p.approver() == null ? actor.getUUID() : p.approver());
            // Blocks this work already placed stay placed; the rest of the plot must be clear again.
            var placed = new HashMap<BlockPos, Placement>();
            p.journal().stream().filter(Placement::placed).forEach(x -> placed.put(x.pos(), x));
            var blockers = survey.blockers().stream().filter(b -> placed.isEmpty() || !b.contains("occupied")).toList();
            if (!blockers.isEmpty()) {
                c.next().projects.put(projectId, p.journal(p.journal(), State.BLOCKED, blockers.get(0), now(), c.next().revision));
                return Keyed.text(P + "result.still_blocked", "%s still cannot be built: %s", templateTitle(p.template()), Keyed.component(blockers.get(0)));
            }
            var journal = survey.plan().stream().map(x -> placed.getOrDefault(x.pos(), x)).toList();
            State next = p.funded() ? State.FUNDED : p.approver() == null ? State.SURVEYED : State.APPROVED;
            c.next().projects.put(projectId, p.journal(journal, next, P + "reason.resumed", now(), c.next().revision));
            return Keyed.text(P + "result.repaired", "%s was surveyed again and continues.", templateTitle(p.template()));
        });
    }

    /** Opts a settlement in or out of construction ({@code MANAGED_GROWTH}); works there are only funded milestones while it is out. */
    public String optIn(ServerPlayer actor, UUID request, UUID settlementId, boolean in) {
        return change(actor, request, "opt|" + settlementId + "|" + in, DevelopmentMode.OBSERVE, c -> {
            var settlement = settlement(settlementId).orElseThrow(() -> Keyed.refuse(P + "refusal.no_settlement", "That settlement is not known."));
            Keyed.require(official(actor, settlement.kingdomId().toString()), P + "refusal.official", "Works are run by an official with the Appoint officials responsibility in %s, or by an operator.",
                    dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, settlement.kingdomId().toString()));
            var e = c.next().economy(settlement.id());
            c.next().economies.put(settlement.id(), new Economy(e.settlement(), e.points(), e.roles(), in, e.abstractGoods(), e.lastProduced()));
            return in ? Keyed.text(P + "result.opted_in", "%s now allows funded works to be built on their surveyed plots.", settlement.displayName())
                    : Keyed.text(P + "result.opted_out", "%s no longer allows construction; funded works there are milestones only.", settlement.displayName());
        });
    }

    /** Protects (or stops protecting) the land around the actor's bed from every construction. */
    public String protectHome(ServerPlayer actor, UUID request, boolean protect) {
        return change(actor, request, "home|" + protect + "|" + actor.getRespawnPosition(), DevelopmentMode.OBSERVE, c -> {
            if (!protect) {
                c.next().homes.remove(actor.getUUID());
                return Keyed.text(P + "result.home_unprotected", "Your home is no longer kept apart from construction.");
            }
            BlockPos bed = actor.getRespawnPosition();
            var level = server.getLevel(actor.getRespawnDimension());
            Keyed.require(bed != null && level != null && level.hasChunkAt(bed) && level.getBlockState(bed).getBlock() instanceof BedBlock,
                    P + "refusal.no_bed", "Sleep in your bed first, and stand near it: the protected home is around the bed you last slept in.");
            Keyed.require(c.next().homes.size() < DevelopmentSavedData.HOMES || c.next().homes.containsKey(actor.getUUID()), P + "refusal.homes_full", "The protected-home register is full on this server.");
            c.next().homes.put(actor.getUUID(), new Home(actor.getUUID(), actor.getRespawnDimension().location().toString(), bed, DevelopmentMode.homeRadius()));
            return Keyed.text(P + "result.home_protected", "No construction will touch the land within %s blocks of your bed.", DevelopmentMode.homeRadius());
        });
    }

    /** Hands the actor every refund they are owed; written first, so a refund is claimed once. */
    public String claimRefunds(ServerPlayer actor, UUID request) {
        var owed = new ArrayList<Refund>();
        String result = change(actor, request, "refunds", DevelopmentMode.PROJECTS, c -> {
            c.next().refunds.removeIf(r -> { if (!r.player().equals(actor.getUUID())) return false; owed.add(r); return true; });
            Keyed.require(!owed.isEmpty(), P + "refusal.no_refunds", "You are owed no refunds.");
            return Keyed.text(P + "result.refunded", "%s refunded delivery(ies) returned to you.", owed.size());
        });
        for (var refund : owed) {
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(refund.item()));
            if (item.isEmpty()) continue;
            int left = refund.count();
            while (left > 0) {
                int size = Math.min(left, item.get().getMaxStackSize());
                ItemStack stack = new ItemStack(item.get(), size);
                if (!actor.getInventory().add(stack)) actor.drop(stack, false);
                left -= size;
            }
        }
        return result;
    }

    // --- maintenance -------------------------------------------------------------------------------------------------

    Optional<ServerLevel> level(Project p) {
        var id = ResourceLocation.tryParse(p.dimension());
        return id == null ? Optional.empty() : Optional.ofNullable(server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id)));
    }

    /**
     * One pass: follows merged settlements, pauses construction when the mode drops below {@code MANAGED_GROWTH}, finishes
     * funded milestones, builds at most {@code blocksPerPass} blocks across the server, and once a day runs the abstract
     * economy. Bounded by {@link #PROJECT_BUDGET} works; unloaded plots wait.
     */
    public void maintain() {
        var mode = DevelopmentMode.current();
        if (mode == DevelopmentMode.OFF || !data.writable() || data.records().projects.isEmpty() && !mode.atLeast(DevelopmentMode.FULL_ECONOMY)) return;
        long started = System.nanoTime();
        long now = now();
        var next = data.transaction();
        boolean changed = false;
        int budget = DevelopmentMode.blocksPerPass();
        int advanced = 0;
        var notices = new ArrayList<Runnable>();
        for (var p : next.projects.values().stream().filter(x -> x.state().open()).sorted(Comparator.comparingLong(Project::updatedAt).thenComparing(Project::id)).toList()) {
            if (advanced >= PROJECT_BUDGET) break;
            var current = p;
            var canonical = settlement(current.settlement());
            if (canonical.isPresent() && !canonical.get().id().equals(current.settlement())) { current = current.movedTo(canonical.get().id(), next.revision); changed = true; }
            Project result = step(current, mode, now, next, budget, notices);
            if (result != current || current != p) { next.projects.put(p.id(), result); changed = true; advanced++; }
            budget -= (int) (result.journal().stream().filter(Placement::placed).count() - current.journal().stream().filter(Placement::placed).count());
        }
        if (mode.atLeast(DevelopmentMode.FULL_ECONOMY) && !DevelopmentMode.economyPaused()) changed |= economy(next, now);
        if (changed && data.commit(server, next)) notices.forEach(Runnable::run);
        DevelopmentMetrics.pass(System.nanoTime() - started, data.records().projects.values().stream().filter(x -> x.state() == State.BUILDING || x.state() == State.READY).count());
    }

    private Project step(Project p, DevelopmentMode mode, long now, Records next, int budget, List<Runnable> notices) {
        var template = DevelopmentTemplate.get(p.template());
        boolean construction = p.origin() != null && template.map(DevelopmentTemplate::buildable).orElse(false);
        switch (p.state()) {
            case FUNDED -> {
                if (construction && mode.atLeast(DevelopmentMode.MANAGED_GROWTH) && next.economy(p.settlement()).optedIn())
                    return p.journal(p.journal(), State.READY, P + "reason.ready", now, next.revision);
                if (construction && mode.atLeast(DevelopmentMode.MANAGED_GROWTH)) return p.state() == State.FUNDED && p.reasonKey().equals(P + "reason.not_opted_in") ? p
                        : p.journal(p.journal(), State.FUNDED, P + "reason.not_opted_in", now, next.revision);
                if (template.isEmpty()) return p.journal(p.journal(), State.BLOCKED, P + "reason.template_missing", now, next.revision);
                return complete(p, template.get(), now, next, notices);
            }
            case READY, BUILDING -> {
                if (!mode.atLeast(DevelopmentMode.MANAGED_GROWTH)) return p.with(State.PAUSED, P + "reason.mode_lowered", now, next.revision);
                if (template.isEmpty()) return p.journal(p.journal(), State.BLOCKED, P + "reason.template_missing", now, next.revision);
                if (!p.approvedByOperator() && (p.approver() == null || !politics().permitted(p.approver(), p.kingdom(), Politics.Permission.APPOINT)))
                    return p.with(State.PAUSED, P + "reason.approver_lost", now, next.revision);
                if (DevelopmentMode.constructionPaused() || budget <= 0) return p.state() == State.READY ? p.journal(p.journal(), State.BUILDING, P + "reason.building", now, next.revision) : p;
                return build(p, template.get(), now, next, budget, notices);
            }
            default -> { return p; }
        }
    }

    private Project build(Project p, DevelopmentTemplate template, long now, Records next, int budget, List<Runnable> notices) {
        var level = level(p).orElse(null);
        if (level == null) return p.journal(p.journal(), State.BLOCKED, P + "reason.no_level", now, next.revision);
        var journal = new ArrayList<>(p.journal());
        var builder = net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(p.approver() == null ? p.proposer() : p.approver(), "[MCA: Kingdoms]"));
        int placed = 0;
        for (int i = 0; i < journal.size() && placed < budget; i++) {
            var x = journal.get(i);
            if (x.placed()) continue;
            // The block, and every neighbour its shape and updates read, must already be loaded: construction never loads a chunk.
            if (!level.hasChunksAt(x.pos().offset(-1, 0, -1), x.pos().offset(1, 0, 1))) return p.journal(journal, State.BUILDING, P + "reason.waiting_chunk", now, next.revision);
            // A restart after placing and before saving: the block is already what this work places, so it counts once.
            if (PlotSurvey.ours(level, x)) { journal.set(i, new Placement(x.pos(), x.before(), x.after(), true)); continue; }
            // Never build into someone standing there: wait for them to step away.
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(x.pos())).isEmpty())
                return p.journal(journal, State.BUILDING, P + "reason.occupied", now, next.revision);
            if (!PlotSurvey.unchanged(level, x))
                return p.journal(journal, State.REPAIR_NEEDED, Keyed.text(P + "reason.changed", "%s changed since the survey; nothing there is replaced.", x.pos().toShortString()), now, next.revision);
            var after = PlotSurvey.state(level, x.after());
            if (after.isEmpty()) return p.journal(journal, State.BLOCKED, P + "reason.template_missing", now, next.revision);
            var event = new net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent(net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, x.pos()),
                    level.getBlockState(x.pos().below()), builder);
            if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event))
                return p.journal(journal, State.REPAIR_NEEDED, Keyed.text(P + "reason.protected", "The land at %s is now protected; construction stopped.", x.pos().toShortString()), now, next.revision);
            BlockState shaped = Block.updateFromNeighbourShapes(after.get(), level, x.pos());
            if (shaped.isAir() || !shaped.canSurvive(level, x.pos()))
                return p.journal(journal, State.REPAIR_NEEDED, Keyed.text(P + "reason.unsupported", "%s cannot stand where it was planned; nothing there was placed.", x.pos().toShortString()), now, next.revision);
            level.setBlock(x.pos(), shaped, Block.UPDATE_ALL);
            journal.set(i, new Placement(x.pos(), x.before(), x.after(), true));
            placed++;
            DevelopmentMetrics.placed();
        }
        if (journal.stream().allMatch(Placement::placed)) return complete(p.journal(journal, State.BUILDING, P + "reason.building", now, next.revision), template, now, next, notices);
        return p.journal(journal, State.BUILDING, P + "reason.building", now, next.revision);
    }

    private Project complete(Project p, DevelopmentTemplate template, long now, Records next, List<Runnable> notices) {
        var e = next.economy(p.settlement());
        var roles = new TreeMap<>(e.roles());
        roles.merge(template.role().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        next.economies.put(p.settlement(), new Economy(p.settlement(), e.points() + template.tierPoints(), roles, e.optedIn(), e.abstractGoods(), e.lastProduced()));
        boolean built = p.journal().stream().anyMatch(Placement::placed);
        notices.add(() -> Notify.government(server, p.kingdom(), null, ActionRegistry.tr(built ? P + "notice.built" : P + "notice.completed", built ? "%s in %s is built." : "%s in %s is completed.",
                templateTitle(template), settlement(p.settlement()).map(SettlementView::displayName).orElse("")), "development.read"));
        return p.journal(p.journal(), State.COMPLETED, built ? P + "reason.built" : P + "reason.completed", now, next.revision);
    }

    /** Once a day: abstract production from completed works, then capped abstract trade between settlements of kingdoms with an agreement in force. */
    private boolean economy(Records next, long now) {
        boolean changed = false;
        var outputs = new HashMap<UUID, Map<String, Integer>>();
        for (var p : next.projects.values()) {
            if (p.state() != State.COMPLETED) continue;
            DevelopmentTemplate.get(p.template()).ifPresent(t -> t.abstractOutput().forEach((k, v) -> outputs.computeIfAbsent(p.settlement(), x -> new TreeMap<>()).merge(k, v, Integer::sum)));
        }
        for (var entry : outputs.entrySet()) {
            var e = next.economy(entry.getKey());
            if (now - e.lastProduced() < DAY) continue;
            next.economies.put(entry.getKey(), new Economy(e.settlement(), e.points(), e.roles(), e.optedIn(), Development.produce(e.abstractGoods(), entry.getValue()), now));
            changed = true;
        }
        if (next.tradeDay == now / DAY) return changed;
        next.tradeDay = now / DAY;
        changed = true;
        var politics = politics();
        var ledgers = next.economies.values().stream().filter(e -> !e.abstractGoods().isEmpty()).sorted(Comparator.comparing(Economy::settlement)).limit(64).toList();
        int pairs = 0;
        for (int i = 0; i < ledgers.size() && pairs < 16; i++) for (int j = i + 1; j < ledgers.size() && pairs < 16; j++) {
            var a = next.economy(ledgers.get(i).settlement()); var b = next.economy(ledgers.get(j).settlement());
            var sa = settlement(a.settlement()); var sb = settlement(b.settlement());
            if (sa.isEmpty() || sb.isEmpty()) continue;
            String ka = sa.get().kingdomId().toString(), kb = sb.get().kingdomId().toString();
            if (ka.equals(kb) || politics.activeAgreements(ka).stream().noneMatch(x -> x.involves(kb))) continue;
            var transfers = Development.trade(a.abstractGoods(), b.abstractGoods(), DevelopmentMode.tradeCap());
            if (transfers.isEmpty()) continue;
            next.economies.put(a.settlement(), new Economy(a.settlement(), a.points(), a.roles(), a.optedIn(), Development.after(a.abstractGoods(), transfers, true), a.lastProduced()));
            next.economies.put(b.settlement(), new Economy(b.settlement(), b.points(), b.roles(), b.optedIn(), Development.after(b.abstractGoods(), transfers, false), b.lastProduced()));
            for (var t : transfers) next.trades.add(new Trade(t.firstGives() ? a.settlement() : b.settlement(), t.firstGives() ? b.settlement() : a.settlement(), t.commodity(), t.amount(), now));
            pairs++;
        }
        return changed;
    }

    // --- reads -------------------------------------------------------------------------------------------------------

    /** OBSERVE: what development a settlement shows, from verified buildings and completed works only. */
    public List<Component> observe(SettlementView settlement) {
        var lines = new ArrayList<Component>();
        var e = data.records().economy(settlement.id());
        lines.add(ActionRegistry.tr(P + "observe.tier", "Development tier %s, from %s point(s) of completed works.", e.tier(), e.points()));
        e.specialization().ifPresent(role -> lines.add(ActionRegistry.tr(P + "observe.specialization", "Known for its %s.", ActionRegistry.enumLabel(DevelopmentTemplate.Role.valueOf(role.toUpperCase(Locale.ROOT))))));
        var level = server.getLevel(settlement.dimension());
        try {
            var townstead = dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi.get(server);
            if (level != null && townstead.capabilities().contains(dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_BUILDING)) {
                var types = new TreeMap<String, Integer>();
                townstead.buildings(level, settlement.id()).stream().limit(256).forEach(b -> types.merge(b.type(), 1, Integer::sum));
                lines.add(types.isEmpty() ? ActionRegistry.tr(P + "observe.no_buildings", "No recognized buildings are recorded for it.")
                        : ActionRegistry.tr(P + "observe.buildings", "Recognized buildings: %s.", String.join(", ", types.entrySet().stream().map(t -> t.getValue() + " " + t.getKey()).toList())));
            } else lines.add(ActionRegistry.tr(P + "observe.buildings_unreadable", "Its buildings cannot be read on this server, so only completed works count."));
        } catch (RuntimeException unavailable) {
            lines.add(ActionRegistry.tr(P + "observe.buildings_unreadable", "Its buildings cannot be read on this server, so only completed works count."));
        }
        var missing = Arrays.stream(DevelopmentTemplate.Role.values()).filter(r -> !e.roles().containsKey(r.name().toLowerCase(Locale.ROOT))).map(ActionRegistry::enumLabel).toList();
        if (!missing.isEmpty()) {
            var list = Component.empty();
            for (int i = 0; i < missing.size(); i++) { if (i > 0) list.append(", "); list.append(missing.get(i)); }
            lines.add(ActionRegistry.tr(P + "observe.missing", "No completed work yet for: %s.", list));
        }
        return lines;
    }
}
