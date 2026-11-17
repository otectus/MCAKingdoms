package dev.otectus.mcakingdoms.development;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Notify;
import dev.otectus.mcakingdoms.persistence.RequestIds;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Settlement development and the economy (D1-D6). Officials post public works; players fund them with real deliveries,
 * which are journaled inventory operations confirmed from the player's own saved file ({@link InventoryOps}); a funded
 * work is a recorded milestone, or, when it was proposed as construction, is built on its surveyed plot a few blocks at
 * a time ({@link Construction}) once the settlement allows it and the server is in {@code MANAGED_GROWTH} or above.
 * {@code FULL_ECONOMY} adds an abstract, labelled goods ledger with capped trade between settlements whose kingdoms have
 * an agreement in force; it never creates or moves a real item or coin. There is no tax, no currency and no offline
 * catch-up.
 *
 * <p>What each decision needs (AUD-08): starting or expanding work needs the mode that allows it; stopping, cancelling,
 * withdrawing consent, protecting a home, rolling back and claiming refunds only need writable records, so turning the
 * feature down never strands anyone. Authority for every new action is the settlement's current kingdom, never the
 * kingdom a work was proposed in (AUD-06). A world that never used development is never written: every action that may
 * run below {@code PROJECTS} acts on records that already exist.
 */
public final class DevelopmentService {
    static final String P = "development.mcakingdoms.";
    /** A receipt result that names an inventory operation; its current state answers a repeated request. */
    static final String OPERATION = "op:";
    private static final Logger LOG = LogUtils.getLogger();
    /** Ticks between maintenance passes; also the period of the block budget. */
    static final int PERIOD = 20;
    /** Most works one pass advances. */
    static final int PROJECT_BUDGET = 16;
    static final long DAY = 24_000;
    private static volatile String session = UUID.randomUUID().toString();

    private final MinecraftServer server;
    private final DevelopmentSavedData data;
    private final Construction construction = new Construction(this);
    private final InventoryOps operations = new InventoryOps(this);

    /** What a decision did: its words, whether it still waits on something (a save, a review), and the record it names. */
    public record Reply(String text, boolean pending, String operation) { }

    public DevelopmentService(MinecraftServer server, DevelopmentSavedData data) {
        this.server = server; this.data = data;
    }

    public static DevelopmentService get(MinecraftServer server) {
        return new DevelopmentService(server, DevelopmentSavedData.get(server));
    }

    /** This server run; a placement written in another run is checked against the world loaded from disk (AUD-03). */
    static String session() { return session; }

    /** A new server run; called before any world loads. */
    public static void newSession() { session = UUID.randomUUID().toString(); }

    public DevelopmentSavedData data() { return data; }
    public MinecraftServer server() { return server; }
    long now() { return server.overworld().getGameTime(); }
    KingdomsService api() { return McaKingdomsApi.get(server); }

    /** A new server-issued request id (AUD-07): every decision takes one, and a reused or expired one is refused. */
    public static UUID request(MinecraftServer server) { return RequestIds.issue(server); }

    private GovernmentService politics() {
        if (KingdomsPoliticsApi.get(server) instanceof GovernmentService government) return government;
        throw Keyed.refuse(P + "refusal.politics_unavailable", "Political records are unavailable on this server.");
    }

    static boolean operator(ServerPlayer player) { return player.hasPermissions(2); }

    /** Whether {@code id} is an operator now, online or not (AUD-04): an approval as operator lasts only as long as the rights do. */
    boolean currentOperator(UUID id) {
        var online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.hasPermissions(2);
        var cache = server.getProfileCache();
        var profile = cache == null ? Optional.<GameProfile>empty() : cache.get(id);
        return server.getPlayerList().isOp(profile.orElse(new GameProfile(id, "")));
    }

    /** An official who may run a settlement's works: an operator, or whoever holds the Appoint officials responsibility in its kingdom. */
    boolean official(ServerPlayer actor, String kingdom) {
        return operator(actor) || politics().permitted(actor.getUUID(), kingdom, Politics.Permission.APPOINT);
    }

    /** The kingdom whose officials act for a work now: its settlement's current kingdom (AUD-06). */
    String authority(Project p) {
        return settlement(p.settlement()).map(s -> s.kingdomId().toString()).orElse(p.kingdom());
    }

    boolean officialFor(ServerPlayer actor, Project p) { return official(actor, authority(p)); }

    /** Whether the work's approver may still let construction continue: an official of the current kingdom, or an operator now if approved as one. */
    boolean approverValid(Project p) {
        if (p.approver() == null) return false;
        if (politics().permitted(p.approver(), authority(p), Politics.Permission.APPOINT)) return true;
        return p.approvedByOperator() && currentOperator(p.approver());
    }

    /** The settlement as it is now, following a merge to the settlement that absorbed it. */
    Optional<SettlementView> settlement(UUID id) {
        return id == null ? Optional.empty() : api().findSettlement(id.toString());
    }

    public static Component templateTitle(DevelopmentTemplate template) {
        return Component.translatableWithFallback(template.titleKey(), ActionRegistry.words(template.id().getPath()));
    }

    public static Component templateTitle(ResourceLocation id) {
        return DevelopmentTemplate.get(id).map(DevelopmentService::templateTitle).orElse(Component.literal(ActionRegistry.words(id.getPath())));
    }

    // --- decisions ---------------------------------------------------------------------------------------------------

    private record Change(Records next, List<Runnable> after) { }

    /**
     * The checks every decision passes before it may change anything, and the answer to a repeated request. {@code
     * minimum} null means the decision is allowed in any mode (stopping, cancelling, recovering; AUD-08).
     */
    Optional<Reply> admit(ServerPlayer actor, UUID request, String fingerprint, DevelopmentMode minimum) {
        Keyed.require(actor.getServer() == server && server.isSameThread() && !actor.hasDisconnected(), P + "refusal.connected", "You must be connected to this server.");
        if (minimum != null) Keyed.require(DevelopmentMode.current().atLeast(minimum), P + "refusal.mode",
                "Settlement development is not turned on to this extent on this server (mode %s).", ActionRegistry.enumLabel(DevelopmentMode.current()));
        var replay = data.receipt(request);
        if (replay.isPresent()) {
            Keyed.require(replay.get().actor().equals(actor.getUUID()) && replay.get().fingerprint().equals(fingerprint), P + "refusal.request_used",
                    "That request was already used for a different decision.");
            String result = replay.get().result();
            if (result.startsWith(OPERATION)) return Optional.of(operations.status(UUID.fromString(result.substring(OPERATION.length()))));
            return Optional.of(new Reply(result, false, ""));
        }
        Keyed.require(RequestIds.issued(request), P + "refusal.request_unissued", "That request was not issued by this server; start the task again.");
        Keyed.require(RequestIds.issuedAt(request) > data.records().receiptWatermark(), P + "refusal.request_expired",
                "That request is older than the records kept to check it, so it cannot be applied. Start the task again.");
        Keyed.require(data.writable(), P + "refusal.read_only", "The development records are kept read-only on this server, so nothing can change them now.");
        return Optional.empty();
    }

    void receipt(Records next, UUID request, ServerPlayer actor, String fingerprint, String result) {
        next.receipts.put(request, new Receipt(actor.getUUID(), fingerprint, result, RequestIds.issuedAt(request)));
    }

    private Reply change(ServerPlayer actor, UUID request, String fingerprint, DevelopmentMode minimum, Function<Change, Reply> body) {
        var answered = admit(actor, request, fingerprint, minimum);
        if (answered.isPresent()) return answered.get();
        var change = new Change(data.transaction(), new ArrayList<>());
        Reply reply = body.apply(change);
        receipt(change.next(), request, actor, fingerprint, reply.text());
        Keyed.require(data.commit(server, change.next()), P + "refusal.save_failed", "The development records could not be saved, so nothing changed.");
        for (Runnable step : change.after()) {
            try { step.run(); } catch (RuntimeException failure) { LOG.error("[MCA: Kingdoms] A development notice failed after its record was saved", failure); }
        }
        return reply;
    }

    private Project project(Records records, UUID id) {
        return Optional.ofNullable(records.projects.get(id)).orElseThrow(() -> Keyed.refuse(P + "refusal.no_project", "That work is not in the records."));
    }

    private Project officialProject(Records records, ServerPlayer actor, UUID id) {
        var p = project(records, id);
        Keyed.require(officialFor(actor, p), P + "refusal.official_here", "Works are run by an official with the Appoint officials responsibility in %s, or by an operator.",
                dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, authority(p)));
        return p;
    }

    private static Reply reply(String text, UUID project) { return new Reply(text, false, project.toString()); }

    /** Posts a public work for a settlement; buildable works in {@code MANAGED_GROWTH} are surveyed at the official's position now. */
    public Reply propose(ServerPlayer actor, UUID request, UUID settlementId, ResourceLocation templateId) {
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
            List<Blocker> blockers = List.of();
            // The kind is chosen now and kept (AUD-04): a lower mode later pauses construction, it never turns it into a milestone.
            WorkKind kind = template.buildable() && DevelopmentMode.current().atLeast(DevelopmentMode.MANAGED_GROWTH) ? WorkKind.CONSTRUCTION : WorkKind.MILESTONE;
            if (kind == WorkKind.CONSTRUCTION) {
                var level = actor.serverLevel();
                origin = template.placement() == DevelopmentTemplate.Placement.GROUND ? actor.blockPosition().below() : actor.blockPosition();
                dimension = level.dimension().location().toString();
                var survey = PlotSurvey.survey(level, template, origin, settlement, c.next().homes.values(), actor.getUUID());
                plan = survey.plan();
                blockers = survey.blockers();
                state = survey.clear() ? State.SURVEYED : State.BLOCKED;
                reason = survey.clear() ? P + "reason.surveyed" : DevelopmentText.blocker(survey.blockers().get(0));
            }
            c.next().projects.put(id, new Project(id, settlement.id(), kingdom, templateId, state, reason, actor.getUUID(), null, false, dimension, origin, demand, List.of(),
                    plan, null, now, now, c.next().revision, kind, blockers, null));
            // Retention drops only finished works whose effects are kept elsewhere (AUD-07); past that, a new work is refused.
            DevelopmentSavedData.retain(c.next());
            Keyed.require(c.next().projects.size() <= DevelopmentSavedData.PROJECTS, P + "refusal.works_full",
                    "The register of public works is full on this server: every listed work is still open or still owed something. An operator sees it under Capacity in Server health.");
            return reply(state == State.BLOCKED ? Keyed.text(P + "result.proposed_blocked", "%s is recorded for %s, but its plot cannot be built: %s", templateTitle(template), settlement.displayName(), Keyed.component(reason))
                    : Keyed.text(P + "result.proposed", "%s is proposed for %s. An official approves it; then players fund it.", templateTitle(template), settlement.displayName()), id);
        });
    }

    /** Approves a surveyed work for funding. */
    public Reply approve(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "approve|" + projectId, DevelopmentMode.PROJECTS, c -> {
            var p = officialProject(c.next(), actor, projectId);
            Keyed.require(p.state() == State.SURVEYED || p.state() == State.PROPOSED, P + "refusal.not_surveyed", "Only a proposed or surveyed work can be approved.");
            c.next().projects.put(projectId, p.approved(actor.getUUID(), operator(actor) && !politics().permitted(actor.getUUID(), authority(p), Politics.Permission.APPOINT), now(), c.next().revision));
            return reply(Keyed.text(P + "result.approved", "%s is approved; players can now deliver what it needs.", templateTitle(p.template())), projectId);
        });
    }

    /**
     * Delivers what the actor carries toward an approved work: plain stacks only (nothing named, enchanted or holding
     * data), never more than each demand still needs, only inside the work's settlement. A journaled inventory operation
     * (AUD-02): see {@link InventoryOps}.
     */
    public Reply contribute(ServerPlayer actor, UUID request, UUID projectId) {
        return operations.contribute(actor, request, projectId);
    }

    /** Pauses an open work; construction stops before its next block. Allowed in any mode (AUD-08). */
    public Reply pause(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "pause|" + projectId, null, c -> {
            var p = officialProject(c.next(), actor, projectId);
            Keyed.require(p.state().open() && p.state() != State.PAUSED && p.state() != State.ROLLING_BACK, P + "refusal.not_pausable", "That work cannot be paused now.");
            c.next().projects.put(projectId, p.with(State.PAUSED, P + "reason.paused", now(), c.next().revision));
            return reply(Keyed.text(P + "result.paused", "%s is paused.", templateTitle(p.template())), projectId);
        });
    }

    /**
     * Resumes a paused work after review (AUD-04): a construction work surveys every cell it has not built yet as it is
     * now, and stays paused, listing what is in the way, until the plot is clear. Whoever resumes it approves its
     * continuation, so the work answers to an official of the settlement's current kingdom.
     */
    public Reply resume(ServerPlayer actor, UUID request, UUID projectId) {
        var p0 = data.records().project(projectId).orElse(null);
        boolean building = p0 != null && p0.construction() && (p0.pausedFrom() == State.READY || p0.pausedFrom() == State.BUILDING);
        return change(actor, request, "resume|" + projectId, building ? DevelopmentMode.MANAGED_GROWTH : DevelopmentMode.PROJECTS, c -> {
            var p = officialProject(c.next(), actor, projectId);
            Keyed.require(p.state() == State.PAUSED, P + "refusal.not_paused", "That work is not paused.");
            State back = p.pausedFrom() == null ? State.SURVEYED : p.pausedFrom() == State.BUILDING ? State.READY : p.pausedFrom();
            boolean byOperator = operator(actor) && !politics().permitted(actor.getUUID(), authority(p), Politics.Permission.APPOINT);
            var resumed = p.approver() == null ? p : p.approvedBy(actor.getUUID(), byOperator);
            if (p.construction() && (back == State.READY || back == State.FUNDED || back == State.APPROVED)) {
                if (back == State.READY) Keyed.require(c.next().economy(p.settlement()).optedIn(), P + "refusal.resume_consent",
                        "%s does not allow construction now; allow it first with Allow construction in a settlement.", settlement(p.settlement()).map(SettlementView::displayName).orElse("?"));
                var survey = resurvey(resumed, c.next(), actor.getUUID());
                if (!survey.clear()) {
                    c.next().projects.put(projectId, resumed.blocked(State.PAUSED, DevelopmentText.blocker(survey.blockers().get(0)), survey.blockers(), now(), c.next().revision));
                    return reply(Keyed.text(P + "result.still_paused", "%s stays paused: the rest of its plot is not clear (%s). Resolve that, then resume it again.",
                            templateTitle(p.template()), Keyed.component(DevelopmentText.blocker(survey.blockers().get(0)))), projectId);
                }
            }
            c.next().projects.put(projectId, resumed.with(back, P + "reason.resumed", now(), c.next().revision));
            return reply(Keyed.text(P + "result.resumed", "%s resumes; the rest of its plot was surveyed again and is clear.", templateTitle(p.template())), projectId);
        });
    }

    /** The rest of a construction work's plot as it is now: a fresh survey when nothing is built yet, otherwise every cell not provably its own. */
    private PlotSurvey.Result resurvey(Project p, Records next, UUID builder) {
        var template = DevelopmentTemplate.get(p.template()).orElseThrow(() -> Keyed.refuse(P + "refusal.no_template", "That work is not defined on this server."));
        var settlement = settlement(p.settlement()).orElseThrow(() -> Keyed.refuse(P + "refusal.no_settlement", "That settlement is not known."));
        var level = level(p).orElseThrow(() -> Keyed.refuse(P + "refusal.no_level", "The work's dimension is not available."));
        if (p.journal().stream().noneMatch(x -> x.stage() != Stage.PLANNED)) return PlotSurvey.survey(level, template, p.origin(), settlement, next.homes.values(), builder);
        return PlotSurvey.remaining(level, template, p, settlement, next.homes.values(), builder);
    }

    /**
     * Cancels an open work. Allowed in any mode (AUD-08). Before any block is built, every delivery becomes a refund its
     * contributor claims; once a block is built the work's materials count as used, which the review says before it is applied.
     */
    public Reply cancel(ServerPlayer actor, UUID request, UUID projectId) {
        return change(actor, request, "cancel|" + projectId, null, c -> {
            var p = officialProject(c.next(), actor, projectId);
            Keyed.require(p.state().open(), P + "refusal.closed", "That work is already finished or cancelled.");
            Keyed.require(p.state() != State.ROLLING_BACK, P + "refusal.rolling_back", "That work is being rolled back; finish the rollback with Roll back a public work.");
            Keyed.require(!c.next().unconfirmed(p.id()), P + "refusal.delivery_unconfirmed",
                    "A delivery to this work is confirmed at the server's next save; cancel it after that, so its refunds are exact.");
            boolean built = p.journal().stream().anyMatch(Placement::placed);
            if (!built) for (var contribution : p.contributions()) c.next().refunds.add(new Refund(contribution.player(), contribution.item(), contribution.count(), p.id()));
            Keyed.require(c.next().refunds.size() <= DevelopmentSavedData.REFUNDS, P + "refusal.refunds_full", "The refund register is full on this server.");
            var journal = p.journal().stream().map(x -> x.stage() == Stage.INTENT ? x.at(Stage.PLANNED, session) : x).toList();
            c.next().projects.put(projectId, p.journal(journal, State.CANCELLED, built ? P + "reason.cancelled_built" : P + "reason.cancelled", now(), c.next().revision));
            return reply(built ? Keyed.text(P + "result.cancelled_built", "%s is cancelled. Its materials are already built in, so nothing is refunded.", templateTitle(p.template()))
                    : Keyed.text(P + "result.cancelled", "%s is cancelled. Contributors claim their deliveries back with Claim development refunds.", templateTitle(p.template())), projectId);
        });
    }

    /** What Repair a public work does: survey the rest again and continue, roll back what was built, or close a rollback. */
    public enum Repair { RESURVEY, ROLLBACK, FINALIZE }

    /**
     * Repairs a work (AUD-05). Resurvey (needs {@code MANAGED_GROWTH}) continues only when every cell not provably the
     * work's own is clear again; a newly occupied cell is never planned over. Rollback and finalize restore and close in
     * any mode (AUD-08): only cells still holding exactly the work's block, under the ordering official's protection
     * rights, are put back; changed, protected and unreadable cells are kept and listed; unloaded cells keep the rollback
     * open until they load or an official finalizes it.
     */
    public Reply repair(ServerPlayer actor, UUID request, UUID projectId, Repair choice) {
        return change(actor, request, "repair|" + projectId + "|" + choice, choice == Repair.RESURVEY ? DevelopmentMode.MANAGED_GROWTH : null, c -> {
            var p = officialProject(c.next(), actor, projectId);
            long now = now();
            switch (choice) {
                case FINALIZE -> {
                    Keyed.require(p.state() == State.ROLLING_BACK, P + "refusal.not_rolling_back", "Only a work being rolled back can be finalized.");
                    long kept = p.journal().stream().filter(Placement::placed).count();
                    c.next().projects.put(projectId, p.journal(p.journal(), State.CANCELLED, kept == 0 ? P + "reason.rolled_back"
                            : Keyed.text(P + "reason.rolled_back_kept", "Rolled back and cancelled; %s block(s) changed by others or protected were left in place.", kept), now, c.next().revision));
                    return reply(Keyed.text(P + "result.rollback_finalized", "%s is closed: %s block(s) were left in place and are no longer the work's.", templateTitle(p.template()), kept), projectId);
                }
                case ROLLBACK -> {
                    Keyed.require(p.construction() && (p.state() == State.REPAIR_NEEDED || p.state() == State.BLOCKED || p.state() == State.PAUSED || p.state() == State.ROLLING_BACK),
                            P + "refusal.not_rollbackable", "Only a blocked, paused or damaged construction work can be rolled back.");
                    Keyed.require(p.state() == State.ROLLING_BACK || p.journal().stream().anyMatch(Placement::placed), P + "refusal.nothing_built",
                            "Nothing of this work is built, so there is nothing to roll back. Cancel it instead; its deliveries are then refunded.");
                    var level = level(p).orElseThrow(() -> Keyed.refuse(P + "refusal.no_level", "The work's dimension is not available."));
                    // The official who orders the rollback is the one whose rights every removal is checked against, now and later.
                    var ordered = p.approvedBy(actor.getUUID(), operator(actor));
                    var result = construction.rollback(ordered, level, actor.getUUID(), now, c.next().revision, 64);
                    long kept = result.kept().stream().filter(b -> b.cause() != Cause.UNLOADED).count();
                    c.next().projects.put(projectId, result.project());
                    return new Reply(Keyed.text(P + "result.rolling_back",
                            "%s: %s block(s) removed; %s left in place because they changed or are protected; %s still to remove when their area loads. The work closes by itself once the world has saved the removals, or finalize the rollback to close it now.",
                            templateTitle(p.template()), result.restored(), kept, result.pending()), true, projectId.toString());
                }
                default -> {
                    Keyed.require(p.state() == State.REPAIR_NEEDED || p.state() == State.BLOCKED, P + "refusal.not_repairable", "Only a blocked work or one needing repair can be repaired.");
                    var template = DevelopmentTemplate.get(p.template()).orElseThrow(() -> Keyed.refuse(P + "refusal.no_template", "That work is not defined on this server."));
                    State next = p.funded() ? State.FUNDED : p.approver() == null ? State.SURVEYED : State.APPROVED;
                    if (!p.construction()) {
                        c.next().projects.put(projectId, p.with(next, P + "reason.resumed", now, c.next().revision));
                        return reply(Keyed.text(P + "result.repaired", "%s was surveyed again and continues.", templateTitle(template)), projectId);
                    }
                    var survey = resurvey(p, c.next(), p.approver() == null ? actor.getUUID() : p.approver());
                    if (!survey.clear()) {
                        c.next().projects.put(projectId, p.blocked(p.state(), DevelopmentText.blocker(survey.blockers().get(0)), survey.blockers(), now, c.next().revision));
                        return reply(Keyed.text(P + "result.still_blocked", "%s still cannot be built: %s", templateTitle(template), Keyed.component(DevelopmentText.blocker(survey.blockers().get(0)))), projectId);
                    }
                    var journal = p.journal().stream().anyMatch(x -> x.stage() != Stage.PLANNED) ? p.journal() : survey.plan();
                    c.next().projects.put(projectId, p.journal(journal, next, P + "reason.resumed", now, c.next().revision));
                    return reply(Keyed.text(P + "result.repaired", "%s was surveyed again and continues.", templateTitle(template)), projectId);
                }
            }
        });
    }

    /** Lets funded works be built in a settlement ({@code MANAGED_GROWTH}); a new allowance, so it needs the feature on. */
    public Reply optIn(ServerPlayer actor, UUID request, UUID settlementId) {
        return change(actor, request, "opt|" + settlementId + "|true", DevelopmentMode.OBSERVE, c -> {
            var settlement = settlement(settlementId).orElseThrow(() -> Keyed.refuse(P + "refusal.no_settlement", "That settlement is not known."));
            Keyed.require(official(actor, settlement.kingdomId().toString()), P + "refusal.official", "Works are run by an official with the Appoint officials responsibility in %s, or by an operator.",
                    dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, settlement.kingdomId().toString()));
            c.next().economies.put(settlement.id(), c.next().economy(settlement.id()).withConsent(true));
            return reply(Keyed.text(P + "result.opted_in", "%s now allows funded works to be built on their surveyed plots.", settlement.displayName()), settlementId);
        });
    }

    /**
     * Withdraws a settlement's consent (AUD-04/08): construction there stops before its next block, in any mode. Works
     * being built are paused and wait for consent and an official's resume; nothing is written when there was no consent.
     */
    public Reply optOut(ServerPlayer actor, UUID request, UUID settlementId) {
        return change(actor, request, "opt|" + settlementId + "|false", null, c -> {
            var settlement = settlement(settlementId).orElseThrow(() -> Keyed.refuse(P + "refusal.no_settlement", "That settlement is not known."));
            Keyed.require(official(actor, settlement.kingdomId().toString()), P + "refusal.official", "Works are run by an official with the Appoint officials responsibility in %s, or by an operator.",
                    dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, settlement.kingdomId().toString()));
            Keyed.require(c.next().economy(settlement.id()).optedIn(), P + "refusal.not_opted_in", "%s does not allow construction already.", settlement.displayName());
            c.next().economies.put(settlement.id(), c.next().economy(settlement.id()).withConsent(false));
            long now = now();
            int paused = 0;
            for (var p : List.copyOf(c.next().projects.values()))
                if (p.settlement().equals(settlement.id()) && p.construction() && (p.state() == State.READY || p.state() == State.BUILDING)) {
                    var blocker = new Blocker(Cause.CONSENT, null, "");
                    c.next().projects.put(p.id(), p.blocked(State.PAUSED, DevelopmentText.blocker(blocker), List.of(blocker), now, c.next().revision));
                    paused++;
                }
            return reply(Keyed.text(P + "result.opted_out", "%s no longer allows construction; %s work(s) being built stopped and wait for review. Funded works stay funded.",
                    settlement.displayName(), paused), settlementId);
        });
    }

    /** Protects (or stops protecting) the land around the actor's bed from every construction; in any mode once development holds records (AUD-08). */
    public Reply protectHome(ServerPlayer actor, UUID request, boolean protect) {
        Keyed.require(DevelopmentMode.current() != DevelopmentMode.OFF || !data.records().projects().isEmpty() || data.records().homes().stream().anyMatch(h -> h.player().equals(actor.getUUID())),
                P + "refusal.home_not_needed", "Settlement development is off on this server, so nothing is built and your home needs no protection.");
        return change(actor, request, "home|" + protect + "|" + actor.getRespawnPosition(), null, c -> {
            if (!protect) {
                c.next().homes.remove(actor.getUUID());
                return new Reply(Keyed.text(P + "result.home_unprotected", "Your home is no longer kept apart from construction."), false, "");
            }
            BlockPos bed = actor.getRespawnPosition();
            var level = server.getLevel(actor.getRespawnDimension());
            Keyed.require(bed != null && level != null && level.hasChunkAt(bed) && level.getBlockState(bed).getBlock() instanceof BedBlock,
                    P + "refusal.no_bed", "Sleep in your bed first, and stand near it: the protected home is around the bed you last slept in.");
            Keyed.require(c.next().homes.size() < DevelopmentSavedData.HOMES || c.next().homes.containsKey(actor.getUUID()), P + "refusal.homes_full", "The protected-home register is full on this server.");
            c.next().homes.put(actor.getUUID(), new Home(actor.getUUID(), actor.getRespawnDimension().location().toString(), bed, DevelopmentMode.homeRadius()));
            return new Reply(Keyed.text(P + "result.home_protected", "No construction will touch the land within %s blocks of your bed.", DevelopmentMode.homeRadius()), false, "");
        });
    }

    /** Hands the actor what they are owed, as far as their inventory has room; in any mode (AUD-08). See {@link InventoryOps}. */
    public Reply claimRefunds(ServerPlayer actor, UUID request) {
        return operations.claim(actor, request);
    }

    /** The current reading of an inventory operation, for Check (AUD-10). */
    public Reply operationStatus(UUID operation) {
        return operations.status(operation);
    }

    /**
     * Settles {@code player}'s pending deliveries and claims from the markers their saved data holds (AUD-02):
     * {@code fromSave} after a save of it (confirms only), otherwise after a load (confirms or rolls back). The login
     * hook, the save hook and operator reconciliation all come here.
     */
    public int settleFromPlayerData(UUID player, Map<UUID, net.minecraft.nbt.CompoundTag> markers, ServerPlayer online, boolean fromSave) {
        return operations.settle(player, markers, online, fromSave);
    }

    /** Operator reconciliation of pending inventory operations from saved player files. */
    public String reconcileOperations() {
        var report = operations.reconcile();
        return Keyed.text(P + "command.operations_reconciled", "%s operation(s) settled from saved player files; %s marked unresolved (unreadable file); %s still wait for an online player's save.",
                report.settled(), report.unresolved(), report.stillPending());
    }

    // --- identity (AUD-01, AUD-06) -------------------------------------------------------------------------------

    /** The identity outbox's development consumer: everything of the retired settlement moves to the survivor. True once on disk. */
    public static boolean settlementMerged(MinecraftServer server, UUID source, UUID target, String sourceName) {
        var service = get(server);
        if (!service.data.writable()) return false;
        if (!DevelopmentMigration.mentions(service.data.records(), source)) return true;
        var next = service.data.transaction();
        DevelopmentMigration.merge(next, source, target, sourceName, service.now());
        return service.data.commit(server, next);
    }

    /** The identity outbox's development consumer for a kingdom change: construction there waits for the new kingdom's review. */
    public static boolean settlementReassigned(MinecraftServer server, UUID settlement, String before, String after) {
        var service = get(server);
        if (!service.data.writable()) return false;
        var next = service.data.transaction();
        if (!DevelopmentMigration.reassigned(next, settlement, dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, after).getString(), service.now())) return true;
        return service.data.commit(server, next);
    }

    /** Moves records still under a retired settlement id to its survivor (worlds merged before 0.3.1, or a consumer still pending). */
    private boolean canonical(Records next, long now) {
        var ids = new LinkedHashSet<UUID>();
        next.projects.values().forEach(p -> ids.add(p.settlement()));
        ids.addAll(next.economies.keySet());
        next.assets.values().forEach(a -> ids.add(a.settlement()));
        next.trades.forEach(t -> { ids.add(t.from()); ids.add(t.to()); });
        boolean changed = false;
        for (UUID id : ids) {
            UUID canonical;
            try { canonical = api().resolveSettlementId(id); } catch (RuntimeException unknown) { continue; }
            if (!canonical.equals(id)) changed |= DevelopmentMigration.merge(next, id, canonical, "", now);
        }
        return changed;
    }

    /** {@code /kingdoms economy reconcile} and the start of a server run: canonical ids, then points and roles from the recorded assets. */
    public static String reconcile(MinecraftServer server) {
        var service = get(server);
        if (!service.data.writable()) throw Keyed.refuse(P + "refusal.read_only", "The development records are kept read-only on this server, so nothing can change them now.");
        var next = service.data.transaction();
        int moved = (int) next.projects.values().stream().filter(p -> { try { return !service.api().resolveSettlementId(p.settlement()).equals(p.settlement()); } catch (RuntimeException e) { return false; } }).count();
        boolean changed = service.canonical(next, service.now());
        var points = new HashMap<UUID, Integer>(); var roles = new HashMap<UUID, Map<String, Integer>>();
        for (var a : next.assets.values()) {
            points.merge(a.settlement(), a.points(), (x, y) -> (int) Math.min(Integer.MAX_VALUE, (long) x + y));
            if (!a.role().isEmpty()) roles.computeIfAbsent(a.settlement(), x -> new TreeMap<>()).merge(a.role(), 1, Integer::sum);
        }
        var settlements = new HashSet<>(next.economies.keySet()); settlements.addAll(points.keySet());
        int recomputed = 0;
        for (UUID settlement : settlements) {
            var e = next.economy(settlement);
            int p = points.getOrDefault(settlement, 0); var r = roles.getOrDefault(settlement, Map.of());
            if (e.points() != p || !e.roles().equals(r)) { next.economies.put(settlement, e.withPoints(p, r)); recomputed++; changed = true; }
        }
        if (changed && !service.data.commit(server, next)) throw Keyed.refuse(P + "refusal.save_failed", "The development records could not be saved, so nothing changed.");
        return Keyed.text(P + "command.reconciled", "Reconciled: %s work(s) followed a merged settlement; %s settlement record(s) recomputed from their completed works.", moved, recomputed);
    }

    /** At the start of a server run: records under retired ids follow their settlement; nothing is written if none does. */
    public static void canonicalizeAtStart(MinecraftServer server) {
        var service = get(server);
        if (!service.data.writable() || service.data.records().projects().isEmpty() && service.data.records().assets().isEmpty()) return;
        var next = service.data.transaction();
        if (service.canonical(next, service.now()) && !service.data.commit(server, next))
            LOG.warn("[MCA: Kingdoms] Development records under merged settlements could not be moved now; they are retried by maintenance");
    }

    // --- maintenance -------------------------------------------------------------------------------------------------

    Optional<ServerLevel> level(Project p) {
        var id = ResourceLocation.tryParse(p.dimension());
        return id == null ? Optional.empty() : Optional.ofNullable(server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id)));
    }

    /**
     * One pass: settles cells a previous run left unverified, finishes funded milestones, builds at most
     * {@code blocksPerPass} blocks across the server, continues rollbacks, and once a day runs the abstract economy.
     * Bounded by {@link #PROJECT_BUDGET} works; unloaded plots wait. In {@code OFF} only obligations move: construction
     * pauses and rollbacks continue (AUD-04/08).
     */
    public void maintain() {
        var mode = DevelopmentMode.current();
        if (!data.writable()) return;
        var records = data.records();
        if (mode == DevelopmentMode.OFF && !records.obligations()) return;
        if (records.projects.isEmpty() && !mode.atLeast(DevelopmentMode.FULL_ECONOMY)) return;
        long started = System.nanoTime();
        long now = now();
        var next = data.transaction();
        boolean changed = canonical(next, now);
        int budget = DevelopmentMode.blocksPerPass();
        int[] recovery = {Construction.RECOVERY_BUDGET};
        int advanced = 0;
        var notices = new ArrayList<Runnable>();
        for (var p : next.projects.values().stream().filter(x -> x.state().open()).sorted(Comparator.comparingLong(Project::updatedAt).thenComparing(Project::id)).toList()) {
            if (advanced >= PROJECT_BUDGET) break;
            var current = p;
            if (current.construction()) {
                var level = level(current);
                if (level.isPresent()) current = construction.recover(current, level.get(), now, next, recovery);
            }
            int[] placed = {0};
            Project result = step(current, mode, now, next, budget, notices, placed);
            if (!result.equals(p)) { next.projects.put(p.id(), result); changed = true; advanced++; }
            budget -= placed[0];
        }
        if (mode.atLeast(DevelopmentMode.FULL_ECONOMY) && !DevelopmentMode.economyPaused()) changed |= economy(next, now);
        if (changed && data.commit(server, next)) notices.forEach(Runnable::run);
        DevelopmentMetrics.pass(System.nanoTime() - started, data.records().projects.values().stream().filter(x -> x.state() == State.BUILDING || x.state() == State.READY).count());
    }

    private Project step(Project p, DevelopmentMode mode, long now, Records next, int budget, List<Runnable> notices, int[] placed) {
        var template = DevelopmentTemplate.get(p.template());
        switch (p.state()) {
            case FUNDED -> {
                if (template.isEmpty()) return p.blocked(State.BLOCKED, P + "reason.template_missing", List.of(new Blocker(Cause.TEMPLATE_MISSING, null, p.template().toString())), now, next.revision);
                // A delivery not yet in its player's saved data could still be undone by a crash: nothing irreversible until then (AUD-02).
                if (next.unconfirmed(p.id()))
                    return p.reasonKey().equals(P + "reason.awaiting_confirmation") ? p : p.with(State.FUNDED, P + "reason.awaiting_confirmation", now, next.revision);
                if (!p.construction()) return mode.atLeast(DevelopmentMode.PROJECTS) ? complete(p, template.get(), now, next, notices) : p;
                // A construction work is only ever built, never completed as a milestone (AUD-04).
                if (!mode.atLeast(DevelopmentMode.MANAGED_GROWTH))
                    return p.reasonKey().equals(P + "reason.awaiting_construction") ? p : p.with(State.FUNDED, P + "reason.awaiting_construction", now, next.revision);
                if (!next.economy(p.settlement()).optedIn())
                    return p.reasonKey().equals(P + "reason.not_opted_in") ? p : p.with(State.FUNDED, P + "reason.not_opted_in", now, next.revision);
                var blocker = construction.policy(p, next, mode);
                if (blocker.isPresent()) return p.blocked(State.PAUSED, DevelopmentText.blocker(blocker.get()), List.of(blocker.get()), now, next.revision);
                return p.journal(p.journal(), State.READY, P + "reason.ready", now, next.revision);
            }
            case READY, BUILDING -> {
                if (template.isEmpty()) return p.blocked(State.BLOCKED, P + "reason.template_missing", List.of(new Blocker(Cause.TEMPLATE_MISSING, null, p.template().toString())), now, next.revision);
                var blocker = construction.policy(p, next, mode);
                if (blocker.isPresent()) return p.blocked(State.PAUSED, DevelopmentText.blocker(blocker.get()), List.of(blocker.get()), now, next.revision);
                if (DevelopmentMode.constructionPaused() || budget <= 0) return p.state() == State.READY ? p.journal(p.journal(), State.BUILDING, P + "reason.building", now, next.revision) : p;
                return construction.build(p, template.get(), now, next, budget, notices, placed);
            }
            case ROLLING_BACK -> {
                // Cells that were unloaded (or over budget) when the rollback was ordered are restored once their area loads;
                // the work closes once every restore is on disk (AUD-05).
                var level = level(p).orElse(null);
                if (level == null) return p;
                Project current = p;
                if (p.approver() != null && Construction.rollbackCanAdvance(p, level)) {
                    var result = construction.rollback(p, level, p.approver(), now, next.revision, Math.max(1, budget));
                    placed[0] += result.restored();
                    current = result.project();
                }
                if (!Construction.rollbackDone(current)) return current;
                long kept = current.journal().stream().filter(Placement::placed).count();
                return current.journal(current.journal(), State.CANCELLED, kept == 0 ? P + "reason.rolled_back"
                        : Keyed.text(P + "reason.rolled_back_kept", "Rolled back and cancelled; %s block(s) changed by others or protected were left in place.", kept), now, next.revision);
            }
            default -> { return p; }
        }
    }

    /** Completes a work once (AUD-03/07): its asset and points are recorded under one completion id and announced after the commit. */
    Project complete(Project p, DevelopmentTemplate template, long now, Records next, List<Runnable> notices) {
        if (p.completion() != null) return p;
        if (!next.assets.containsKey(p.id()) && next.assets.size() >= DevelopmentSavedData.ASSETS)
            return p.reasonKey().equals(P + "reason.assets_full") ? p : p.with(p.state(), P + "reason.assets_full", now, next.revision);
        UUID operation = UUID.nameUUIDFromBytes(("complete|" + p.id()).getBytes(StandardCharsets.UTF_8));
        String role = template.role().name().toLowerCase(Locale.ROOT);
        next.assets.put(p.id(), new Asset(p.id(), p.settlement(), p.template(), role, template.tierPoints(), template.abstractOutput(), now));
        var e = next.economy(p.settlement());
        var roles = new TreeMap<>(e.roles());
        roles.merge(role, 1, Integer::sum);
        next.economies.put(p.settlement(), e.withPoints((int) Math.min(Integer.MAX_VALUE, (long) e.points() + template.tierPoints()), roles));
        boolean built = p.journal().stream().anyMatch(Placement::placed);
        String kingdom = authority(p);
        notices.add(() -> Notify.government(server, kingdom, null, ActionRegistry.tr(built ? P + "notice.built" : P + "notice.completed", built ? "%s in %s is built." : "%s in %s is completed.",
                templateTitle(template), settlement(p.settlement()).map(SettlementView::displayName).orElse("")), "development.read"));
        // After the commit, like the notice: listeners (the Townstead Chronicle bridge) narrate it at most once.
        notices.add(() -> {
            try {
                ResourceLocation id = ResourceLocation.tryParse(kingdom);
                if (id != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new dev.otectus.mcakingdoms.api.event.PublicWorkCompletedEvent(
                        server, p.id(), p.settlement(), id, p.template(), p.proposer(), p.approver(), built));
            } catch (RuntimeException listenerFailure) {
                LOG.error("A public work was completed but a listener failed; nothing was undone", listenerFailure);
            }
        });
        return p.completed(operation, built ? P + "reason.built" : P + "reason.completed", now, next.revision);
    }

    /** A world save of {@code level} finished: cells this run placed there and the save wrote are verified (AUD-03). */
    public void worldSaved(ServerLevel level) {
        if (!data.writable() || data.records().projects().stream().noneMatch(p -> p.state().open() && p.construction())) return;
        var next = data.transaction();
        boolean changed = false;
        long now = now();
        for (var p : List.copyOf(next.projects.values())) {
            if (!p.state().open() || !p.construction()) continue;
            var updated = construction.saved(p, level, now, next.revision);
            if (updated.isPresent() && !updated.get().equals(p)) { next.projects.put(p.id(), updated.get()); changed = true; }
        }
        if (changed && !data.commit(server, next)) LOG.warn("[MCA: Kingdoms] Construction verified by this save could not be recorded; it is checked again at the next save");
    }

    /** Once a day: abstract production from completed works' assets, then capped abstract trade between settlements of kingdoms with an agreement in force. */
    private boolean economy(Records next, long now) {
        boolean changed = false;
        var outputs = new HashMap<UUID, Map<String, Integer>>();
        for (var a : next.assets.values()) a.output().forEach((k, v) -> outputs.computeIfAbsent(a.settlement(), x -> new TreeMap<>()).merge(k, v, Integer::sum));
        for (var entry : outputs.entrySet()) {
            var e = next.economy(entry.getKey());
            if (now - e.lastProduced() < DAY) continue;
            next.economies.put(entry.getKey(), e.withGoods(Development.produce(e.abstractGoods(), entry.getValue()), now));
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
            next.economies.put(a.settlement(), a.withGoods(Development.after(a.abstractGoods(), transfers, true), a.lastProduced()));
            next.economies.put(b.settlement(), b.withGoods(Development.after(b.abstractGoods(), transfers, false), b.lastProduced()));
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
        lines.add(e.optedIn() ? ActionRegistry.tr(P + "observe.consent_yes", "Construction here: allowed on surveyed plots (an official can withdraw it).")
                : ActionRegistry.tr(P + "observe.consent_no", "Construction here: not allowed; funded works wait (an official can allow it)."));
        var level = server.getLevel(settlement.dimension());
        try {
            var townstead = dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi.get(server);
            if (level != null && townstead.capabilities().contains(dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_BUILDING)) {
                var types = new TreeMap<String, Integer>();
                var evidence = townstead.buildingEvidence(level, settlement.id()).stream().limit(256).toList();
                evidence.stream().filter(b -> b.completion() != dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence.Completion.INCOMPLETE)
                        .forEach(b -> types.merge(b.building().type(), 1, Integer::sum));
                lines.add(types.isEmpty() ? ActionRegistry.tr(P + "observe.no_buildings", "No recognized buildings are recorded for it.")
                        : ActionRegistry.tr(P + "observe.buildings", "Recognized buildings: %s.", String.join(", ", types.entrySet().stream().map(t -> t.getValue() + " " + t.getKey()).toList())));
                // Unfinished buildings are named, never counted: a work's own deliveries and completion still decide it (spec 7.4).
                var unfinished = evidence.stream().filter(b -> b.completion() == dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence.Completion.INCOMPLETE)
                        .map(b -> b.building().type()).distinct().sorted().toList();
                if (!unfinished.isEmpty()) lines.add(ActionRegistry.tr(P + "observe.unfinished", "Not complete yet, so not counted: %s.", String.join(", ", unfinished)));
                if (evidence.stream().anyMatch(b -> b.completion() == dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence.Completion.UNKNOWN))
                    lines.add(ActionRegistry.tr(P + "observe.completion_unknown", "This server cannot tell whether every building is complete; such buildings are counted as before."));
                townstead.spirit(level, settlement.id()).ifPresent(spirit -> spirit.primaryId().ifPresent(primary -> lines.add(ActionRegistry.tr(P + "observe.spirit",
                        "Townstead's village spirit: %s, tier %s.", primary.replace('_', ' '), spirit.tier()))));
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
