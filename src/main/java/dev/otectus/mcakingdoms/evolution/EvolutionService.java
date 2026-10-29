package dev.otectus.mcakingdoms.evolution;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.warfare.WarfareApi;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.warfare.contracts.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.otectus.mcakingdoms.evolution.EvolutionState.*;

/** Bounded, opt-in opportunities. Provider results are consumed, never invented by the director. */
public final class EvolutionService {
    public record SettingsView(long revision, boolean enabled, boolean drama, boolean subscribed,
                               Set<UUID> eligibleRegions, Set<UUID> activeRegions, boolean writable) {}
    public record ScenarioView(UUID id, String title, Trigger trigger, UUID settlement, String kingdom,
                               Phase phase, long revision, long deadline, List<Outcome> outcomes,
                               int requiredContributions, Map<Outcome,Long> contributions,
                               Optional<Outcome> ownChoice, String cause, String result, boolean privateAudience) {}
    private final MinecraftServer server;
    private final EvolutionSavedData data;
    /**
     * Eligible regions occupied without a break, with the game time each run of occupation was first observed (F06). Kept
     * in memory and observed every 200 ticks for every online player, independently of which regions this evaluation's
     * bounded generation budget reaches. Cleared while evolution is off and on restart, so neither a disabled period nor
     * the time a server was down is ever credited: there is no offline catch-up.
     */
    private final Map<UUID, Long> occupiedSince = new HashMap<>();
    public EvolutionService(MinecraftServer server) { this.server = server; this.data = EvolutionSavedData.get(server); }
    public long revision() { if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread"); return data.revision(); }
    /** The world's opt-in state for capability reports, without a player: enabled, drama, eligible and active regions. */
    public SettingsView worldSettings() { if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread"); var s=data.snapshot();
        return new SettingsView(s.revision,s.enabled,s.drama,false,Set.copyOf(s.eligible),Set.copyOf(s.activeRegions),data.writable()); }
    public SettingsView settings(ServerPlayer player) { actor(player); var s=data.snapshot(); return new SettingsView(s.revision,s.enabled,s.drama,
            s.subscriptions.contains(player.getUUID()),Set.copyOf(s.eligible),Set.copyOf(s.activeRegions),data.writable()); }
    public List<ScenarioView> scenarios(ServerPlayer player) { actor(player); var s=data.snapshot(); return s.scenarios.values().stream()
            .filter(e->visible(player,e)).sorted(Comparator.comparingLong(Scenario::created).reversed().thenComparing(Scenario::id)).map(e->view(player,e)).toList(); }
    public Optional<ScenarioView> scenario(ServerPlayer player,UUID id) { actor(player); var s=data.snapshot(); var e=s.scenarios.get(id);
        return e!=null&&visible(player,e)?Optional.of(view(player,e)):Optional.empty(); }
    private ScenarioView view(ServerPlayer player,Scenario e) { var counts=new EnumMap<Outcome,Long>(Outcome.class);
        for(var outcome:e.terms().outcomes()) counts.put(outcome,e.contributions().values().stream().filter(c->c.outcome()==outcome).count());
        var own=Optional.ofNullable(e.contributions().get(player.getUUID())).map(Contribution::outcome);
        return new ScenarioView(e.id(),e.terms().title(),e.terms().trigger(),e.settlement(),e.kingdom(),e.phase(),e.revision(),e.deadline(),
                e.terms().outcomes(),e.terms().contributions(),Map.copyOf(counts),own,e.cause().detail(),e.result(),e.audience()!=null); }
    private long now(EvolutionState s) { return Math.max(s.clock, server.overworld().getGameTime()); }
    private void actor(ServerPlayer p) {
        if (!server.isSameThread() || p.getServer() != server || p.hasDisconnected()) throw Keyed.refuse("evolution.mcakingdoms.world.connected","You must be connected to this server.");
    }
    private void commit(EvolutionState s) {
        s.clock = now(s); s.revision = Math.addExact(s.revision, 1);
        if (!data.commit(server, s)) throw Keyed.refuse("evolution.mcakingdoms.world.save_unavailable","The opportunity records could not be saved, so nothing changed.");
    }
    public String configure(ServerPlayer player, boolean enabled, boolean drama) {
        actor(player); if (!player.hasPermissions(2)) throw Keyed.refuse("evolution.mcakingdoms.world.operator","Only an operator can do this.");
        var s = data.snapshot(); if (s.enabled != enabled) { s.activeRegions.clear(); occupiedSince.clear(); } s.enabled = enabled; s.drama = drama; commit(s);
        return Keyed.text("evolution.mcakingdoms.world.settings", "World evolution is %s; world events are %s.", enabled ? net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.state.enabled", "on") : net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.state.paused", "paused"), drama ? net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.drama.enabled", "on") : net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.drama.disabled", "off"));
    }
    public String region(ServerPlayer player, UUID settlement, boolean enabled) {
        actor(player); if (!player.hasPermissions(2)) throw Keyed.refuse("evolution.mcakingdoms.world.operator","Only an operator can do this.");
        if (McaKingdomsApi.get(server).getSettlement(settlement).isEmpty()) throw Keyed.refuse("evolution.mcakingdoms.world.settlement_unavailable","That settlement is not available.");
        var s = data.snapshot(); if (s.paused == null) s.paused = new LinkedHashSet<>();
        if (enabled) { s.eligible.add(settlement); s.paused.remove(settlement); } else { s.eligible.remove(settlement); s.paused.add(settlement); s.activeRegions.remove(settlement); } commit(s);
        return enabled ? Keyed.text("evolution.mcakingdoms.world.region.enabled", "This settlement can now have opportunities; they are only considered while a player is there.") : Keyed.text("evolution.mcakingdoms.world.region.paused", "World evolution is paused for this settlement.");
    }
    public String subscribe(ServerPlayer player, boolean enabled) {
        actor(player); var s = data.snapshot();
        if (enabled) { s.subscriptions.add(player.getUUID()); s.digest.putIfAbsent(player.getUUID(), now(s)); }
        else s.subscriptions.remove(player.getUUID());
        commit(s); return enabled ? Keyed.text("evolution.mcakingdoms.world.digest.enabled","Your personal political digest is on.") : Keyed.text("evolution.mcakingdoms.world.digest.disabled","Your personal political digest is off.");
    }
    public String familyIntroduction(ServerPlayer player) {
        actor(player); var s = data.snapshot();
        var marriage = dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence.marriage(server, player.getUUID())
                .orElseThrow(() -> new IllegalArgumentException("A reciprocal native marriage record is required; unavailable family records create no assumptions"));
        net.minecraft.world.entity.Entity spouse = null;
        for (var level : server.getAllLevels()) { spouse = level.getEntity(marriage.second()); if (spouse != null) break; }
        if (spouse == null) throw Keyed.refuse("evolution.mcakingdoms.world.spouse_unloaded","Visit while your spouse is loaded, so their civic introduction can be verified; no area was loaded.");
        var kingdoms = McaKingdomsApi.get(server);
        var own = kingdoms.getSettlementAt(player.serverLevel(), player.blockPosition()).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.world.visit_settlement","Stand in an eligible settlement first."));
        var other = kingdoms.getResidence(spouse).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.world.spouse_no_residence","Your spouse has no verified civic residence."));
        String counterpart = other.kingdomId().toString();
        if (own.kingdomId().equals(other.kingdomId()) || !SettlementKnowledge.get(server).visible(player, other.id())
                || KingdomsPoliticsApi.get(server).government(counterpart).isEmpty()) throw new IllegalArgumentException("A known counterpart government is required");
        if (!s.enabled || !eligible(s, own.id())) throw Keyed.refuse("evolution.mcakingdoms.world.enable_first","Turn on world evolution for this world and this settlement first.");
        String templateId = "mcakingdoms:family_introduction";
        var terms = EvolutionDefinitions.INSTANCE.snapshot().get(templateId); if (terms == null) throw Keyed.refuse("evolution.mcakingdoms.world.family_definition","The family introduction is not defined by the loaded data packs.");
        String key = player.getUUID() + ":family"; long now = now(s);
        if (s.cooldowns.getOrDefault(key, 0L) > now || activeCount(s) >= EvolutionConfig.CONCURRENT.get())
            throw Keyed.refuse("evolution.mcakingdoms.world.family_cooldown","A family introduction happened recently, or the world evolution budget is used up; try again later.");
        UUID id = UUID.randomUUID();
        var e = new Scenario(id, templateId, terms, own.id(), null, own.kingdomId().toString(),
                new Evidence("mca:family", marriage.second() + "|" + counterpart, "RECIPROCAL_MARRIAGE", now,
                        Keyed.text("evolution.mcakingdoms.world.family_evidence","Your MCA marriage offers a private diplomatic introduction.")), player.getUUID(), now, now + terms.duration(), 1, Phase.OPEN, Map.of(), null, "");
        s.scenarios.put(id, e); s.cooldowns.put(key, now + terms.cooldown()); commit(s);
        return Keyed.text("evolution.mcakingdoms.world.family_opened", "Private family introduction: %s. Preview the consequences before contributing; marriage grants no political ownership.", dev.otectus.mcakingdoms.interaction.PlayerWords.component(terms.title()));
    }
    /** The viewer's operational institution at the settlement, wherever it falls in the paged listing. */
    private static dev.otectus.mcakingdoms.api.politics.InstitutionView institutionAt(dev.otectus.mcakingdoms.api.politics.PoliticalService politics, ServerPlayer viewer, UUID settlement) {
        UUID place = McaKingdomsApi.get(viewer.getServer()).resolveSettlementId(settlement);
        for (int offset = 0; offset <= 4096; offset += 32) {
            var page = politics.knownInstitutions(viewer, offset, 32);
            for (var v : page) if (v.settlement().equals(place) && v.operational()) return v;
            if (page.size() < 32) break;
        }
        return null;
    }
    private String familyCounterpart(ServerPlayer player, Scenario e) {
        String[] parts = e.cause().receipt().split("\\|", 2);
        if (!Objects.equals(e.audience(), player.getUUID()) || parts.length != 2
                || dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence.marriage(server, player.getUUID()).filter(m -> m.second().toString().equals(parts[0])).isEmpty())
            throw Keyed.refuse("evolution.mcakingdoms.world.family_changed","The MCA marriage changed or cannot be read; the introduction is paused.");
        return parts[1];
    }
    private boolean visible(ServerPlayer player, Scenario e) {
        return (e.audience() == null || e.audience().equals(player.getUUID()))
                && SettlementKnowledge.get(server).visible(player, e.settlement());
    }
    private Scenario require(ServerPlayer player, EvolutionState s, UUID id) {
        var event = s.scenarios.get(id);
        if (event == null || !visible(player, event)) throw Keyed.refuse("evolution.mcakingdoms.world.scenario_unavailable","That opportunity is not available.");
        return event;
    }
    private void available(EvolutionState s, Scenario e) {
        if (!data.writable() || !s.enabled || !eligible(s, e.settlement()) || e.terms().dramatic() && !s.drama)
            throw Keyed.refuse("evolution.mcakingdoms.world.paused_policy","World policy has paused this opportunity.");
        if (!EvolutionDefinitions.INSTANCE.snapshot().containsKey(e.templateId())) throw Keyed.refuse("evolution.mcakingdoms.world.definition_unavailable","This opportunity's definition is no longer loaded; its saved terms are kept.");
        if (McaKingdomsApi.get(server).getSettlement(e.settlement()).filter(v -> v.kingdomId().toString().equals(e.kingdom())).isEmpty())
            throw Keyed.refuse("evolution.mcakingdoms.world.identity_changed","The settlement's identity changed; this opportunity is kept as history.");
        if (e.institution() != null && KingdomsPoliticsApi.get(server).institution(e.institution()).filter(InstitutionView::operational).isEmpty())
            throw Keyed.refuse("evolution.mcakingdoms.world.institution_unavailable","The institution is unavailable; an unloaded building is never assumed destroyed.");
    }
    public List<String> page(ServerPlayer player, int offset) {
        actor(player); if (offset < 0 || offset > CAPACITY || offset % 10 != 0) throw Keyed.refuse("evolution.mcakingdoms.world.invalid_page","That page is not valid.");
        var s = data.snapshot(); var lines = new ArrayList<String>();
        lines.add(Keyed.text("evolution.mcakingdoms.world.page.head", "World evolution: %s · revision %s", !data.writable() ? net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.state.read_only", "read-only, recovery needed") : s.enabled ? net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.state.enabled", "on") : net.minecraft.network.chat.Component.translatableWithFallback("evolution.mcakingdoms.world.state.paused", "paused"), s.revision));
        s.scenarios.values().stream().filter(e -> visible(player, e)).sorted(Comparator.comparingLong(Scenario::created).reversed().thenComparing(Scenario::id))
                .skip(offset).limit(10).forEach(e -> lines.add(Keyed.text("evolution.mcakingdoms.world.page.row", "#%s · %s · %s · revision %s", e.id().toString().substring(0, 4), dev.otectus.mcakingdoms.interaction.PlayerWords.component(e.terms().title()), e.phase(), e.revision())));
        return List.copyOf(lines);
    }
    public List<String> inspect(ServerPlayer player, UUID id) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id);
        var lines = new ArrayList<String>();
        lines.add(Keyed.text("evolution.mcakingdoms.world.inspect.head", "%s · %s · revision %s", dev.otectus.mcakingdoms.interaction.PlayerWords.component(e.terms().title()), e.phase(), e.revision()));
        lines.add(Keyed.text("evolution.mcakingdoms.world.inspect.cause", "Cause: %s (%s, %s)", Keyed.component(e.cause().detail()), e.cause().provider(), net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.day(e.cause().at()))));
        var choices = net.minecraft.network.chat.Component.empty(); boolean first = true;
        for (var choice : e.terms().outcomes()) { if (!first) choices.append(", "); first = false; choices.append(dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(choice)); }
        lines.add(Keyed.text("evolution.mcakingdoms.world.inspect.choices", "Choices: %s · contributions needed: %s · closes in %s", choices, e.terms().contributions(), dev.otectus.mcakingdoms.interaction.Names.remainingText(server, e.deadline())));
        for (var choice : e.terms().outcomes()) lines.add(Keyed.text("evolution.mcakingdoms.world.inspect.tally", "%s: %s verified contribution(s)", choice, e.contributions().values().stream().filter(c -> c.outcome() == choice).count()));
        var own = e.contributions().get(player.getUUID()); if (own != null) lines.add(Keyed.text("evolution.mcakingdoms.world.inspect.yours", "Your choice: %s", own.outcome()));
        try { available(s, e); } catch (IllegalArgumentException unavailable) { lines.add(Keyed.text(unavailable)); }
        if (!e.result().isBlank()) lines.add(e.result());
        lines.add(Keyed.text("evolution.mcakingdoms.world.inspect.note","Contributing grants no automatic treaty, succession, payment or troop transfer. Declining keeps peaceful play."));
        return List.copyOf(lines);
    }
    /** Only receipt-backed service or current public authority qualifies; client-supplied proof is never trusted. */
    public String contribute(ServerPlayer player, UUID id, long revision, Outcome outcome) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id); available(s, e);
        if (!e.terms().outcomes().contains(outcome) || outcome == Outcome.DECLINE) throw Keyed.refuse("evolution.mcakingdoms.world.outcome_unavailable","That choice is not available.");
        Evidence proof;
        if (outcome == Outcome.AID || outcome == Outcome.MEDIATE || outcome == Outcome.NEGOTIATE) {
            var kind = EvolutionContracts.kind(outcome);
            var service = CivilianContractService.scopedProof(server, player.getUUID(), e.settlement(), kind, e.id())
                    .filter(p -> p.completedAt() >= e.created()).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.world.contract_needed","Complete the matching civilian contract after this opportunity began."));
            proof = new Evidence("mcaquests", service.providerEpoch() + ":" + service.receipt(), kind.name(), service.completedAt(), Keyed.text("evolution.mcakingdoms.world.service_verified", "Verified %s service", kind));
        } else if (outcome == Outcome.INTRODUCE && e.cause().provider().equals("mca:family")) {
            familyCounterpart(player, e);
            proof = new Evidence("mca:family", e.id() + ":" + player.getUUID(), "INTRODUCTION", now(s), Keyed.text("evolution.mcakingdoms.world.family_revalidated","The MCA marriage was checked again."));
        } else {
            var politics = KingdomsPoliticsApi.get(server);
            var government = politics.government(e.kingdom()).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.world.government_unavailable","That government is not available."));
            if (outcome == Outcome.SUCCEED) {
                if (government.state() != Politics.State.INTERREGNUM || government.successor() == null
                        || government.successor().kind() != Politics.Kind.PLAYER || !government.successor().id().equals(player.getUUID()))
                    throw Keyed.refuse("evolution.mcakingdoms.world.successor_only","Only the named player successor can accept this opportunity.");
            } else if (!politics.authorized(player, e.kingdom(), Politics.Permission.PROPOSE))
                throw Keyed.refuse("evolution.mcakingdoms.world.mandate_required","This needs the Propose agreements responsibility in this kingdom.");
            proof = new Evidence("mcakingdoms:government", e.id() + ":" + player.getUUID() + ":" + government.revision(),
                    outcome.name(), now(s), Keyed.text("evolution.mcakingdoms.world.authority_verified","Current government authority verified"));
        }
        if (s.consumed.contains(proof.key())) throw Keyed.refuse("evolution.mcakingdoms.world.receipt_used","That service receipt already supports another choice.");
        s.scenarios.put(id, e.contribute(new Contribution(player.getUUID(), outcome, proof), revision, now(s)));
        s.consumed.add(proof.key()); commit(s); return Keyed.text("evolution.mcakingdoms.world.contributed","Your verified contribution is recorded. No shared outcome has been decided yet.");
    }
    public String withdraw(ServerPlayer player, UUID id, long revision) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id);
        var contribution = e.contributions().get(player.getUUID());
        boolean receipt = contribution != null && contribution.evidence().provider().equals("mcaquests");
        if (contribution != null && !receipt) s.consumed.remove(contribution.evidence().key()); // authority and family proofs are re-verified on the next contribution
        s.scenarios.put(id, e.withdraw(player.getUUID(), revision, now(s))); commit(s);
        return receipt ? Keyed.text("evolution.mcakingdoms.world.withdrawn.receipt","Choice withdrawn; its service receipt stays recorded so it cannot be reused.") : Keyed.text("evolution.mcakingdoms.world.withdrawn","Choice withdrawn; you may contribute again while the opportunity is open.");
    }
    public String resolve(ServerPlayer player, UUID id, long revision, Outcome outcome, String counterpart) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id); available(s, e);
        var politics = KingdomsPoliticsApi.get(server);
        if (outcome == Outcome.INTRODUCE && e.cause().provider().equals("mca:family")) {
            String expected = familyCounterpart(player, e);
            if (!counterpart.equals(expected)) throw Keyed.refuse("evolution.mcakingdoms.world.counterpart_fixed", "The introduction can only be with %s, the kingdom your verified family ties point to.", dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, expected));
        }
        if (outcome != Outcome.SUCCEED && !Objects.equals(e.audience(), player.getUUID())
                && !politics.authorized(player, e.kingdom(), Politics.Permission.REVIEW))
            throw Keyed.refuse("evolution.mcakingdoms.world.review_authority","Resolving a shared opportunity needs the Review petitions responsibility in this kingdom.");
        if (outcome == Outcome.SUCCEED && (!e.contributions().containsKey(player.getUUID())
                || e.contributions().get(player.getUUID()).outcome() != outcome)) throw Keyed.refuse("evolution.mcakingdoms.world.successor_contribution","The named successor must have contributed this choice.");
        if (outcome == Outcome.INTRODUCE && (politics.government(counterpart).isEmpty() || counterpart.equals(e.kingdom())))
            throw Keyed.refuse("evolution.mcakingdoms.world.other_government","Choose another constituted government.");
        var intent = new Intent(player.getUUID(), outcome, UUID.randomUUID(), politics.revision(), counterpart);
        s.scenarios.put(id, e.reserve(intent, revision, now(s))); commit(s);
        return retry(player, id);
    }
    /** The same actor/request/frozen revision survives the provider/local-commit crash window. */
    public String retry(ServerPlayer player, UUID id) {
        actor(player); var s = data.snapshot(); var e = s.scenarios.get(id);
        if (e == null || e.phase() != Phase.RESOLVING || !e.intent().actor().equals(player.getUUID())) throw Keyed.refuse("evolution.mcakingdoms.world.resume_actor","Only the player who started this resolution can resume it.");
        var intent = e.intent(); String result;
        var receipt = KingdomsPoliticsApi.get(server).receipt(player, intent.request());
        if (receipt.filter(Politics.Result::success).isPresent()) {
            result = Keyed.text("evolution.mcakingdoms.world.filed","The government accepted this resolution and filed its record.");
            s.scenarios.put(id, e.finish(result)); commit(s); return result;
        }
        require(player, s, id); available(s, e);
        if (intent.outcome() == Outcome.INTRODUCE || intent.outcome() == Outcome.SUCCEED) {
            if (e.cause().provider().equals("mca:family") && KingdomsPoliticsApi.get(server).receipt(player, intent.request()).isEmpty()) familyCounterpart(player, e);
            var action = intent.outcome() == Outcome.SUCCEED ? Politics.Action.SUCCEED : Politics.Action.PETITION;
            var request = new Politics.Request(intent.request(), intent.politicalRevision(), action, e.kingdom(),
                    action == Politics.Action.PETITION ? "mcakingdoms:introduction_petition" : "", e.settlement().toString(),
                    null, intent.counterpart(), Keyed.text("evolution.mcakingdoms.world.scenario_text", "Opportunity: %s", dev.otectus.mcakingdoms.interaction.PlayerWords.component(e.terms().title())), "", 0, 0, 0);
            var response = KingdomsPoliticsApi.get(server).execute(player, request);
            if (!response.success()) return Keyed.text("evolution.mcakingdoms.world.still_pending", "The resolution is still pending: %s", Keyed.component(response.message()));
            result = Keyed.text("evolution.mcakingdoms.world.filed","The government accepted this resolution and filed its record.");
        } else result = intent.outcome() == Outcome.DECLINE ? Keyed.text("evolution.mcakingdoms.world.declined","Opportunity declined peacefully.") : Keyed.text("evolution.mcakingdoms.world.service_acknowledged", "Verified %s service acknowledged; any further political terms need consent.", intent.outcome());
        s.scenarios.put(id, e.finish(result)); commit(s); return result;
    }
    public String releaseRejected(ServerPlayer player, UUID id) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id);
        if (e.phase() != Phase.RESOLVING || !e.intent().actor().equals(player.getUUID())) throw Keyed.refuse("evolution.mcakingdoms.world.original_actor","Only the player who started this can do that.");
        if (KingdomsPoliticsApi.get(server).government(e.kingdom()).isEmpty()) throw Keyed.refuse("evolution.mcakingdoms.world.owner_unavailable","The government's state cannot be read; the pending request is kept for recovery.");
        if (KingdomsPoliticsApi.get(server).receipt(player, e.intent().request()).isPresent()) throw Keyed.refuse("evolution.mcakingdoms.world.owner_committed","The government already recorded this; retry the acknowledgment instead.");
        s.scenarios.put(id, e.reopen()); commit(s); return Keyed.text("evolution.mcakingdoms.world.released","The undecided outcome was released; refresh before choosing again.");
    }
    /**
     * Records which eligible regions hold at least one online player now. Cheap (one indexed lookup per player, no chunk
     * loads) and run every 200 ticks while evolution is on; a region left empty at an observation starts a new run of
     * occupation when someone returns.
     */
    public void observePresence() {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        if (!data.writable()) { occupiedSince.clear(); return; }
        var s = data.snapshot();
        if (!s.enabled) { occupiedSince.clear(); return; }
        long now = now(s);
        var present = presentRegions(s).keySet();
        occupiedSince.keySet().retainAll(present);
        for (UUID region : present) occupiedSince.putIfAbsent(region, now);
    }

    /** Eligible regions with an online player in them, each with its online players in UUID order. */
    private Map<UUID, List<ServerPlayer>> presentRegions(EvolutionState s) {
        var kingdoms = McaKingdomsApi.get(server);
        var present = new TreeMap<UUID, List<ServerPlayer>>();
        for (var player : server.getPlayerList().getPlayers().stream().sorted(Comparator.comparing(ServerPlayer::getUUID)).toList()) {
            kingdoms.getSettlementAt(player.serverLevel(), player.blockPosition())
                    .filter(place -> eligible(s, place.id()))
                    .ifPresent(place -> present.computeIfAbsent(place.id(), ignored -> new ArrayList<>()).add(player));
        }
        return present;
    }

    /**
     * The part of an evaluation window [{@code windowStart}, {@code now}] its region spent occupied, given when the current
     * run of occupation was first observed (null: unoccupied now). The rest of the window extends the deadline.
     */
    static long creditedTicks(Long occupiedSince, long windowStart, long now) {
        if (occupiedSince == null || now <= windowStart) return 0;
        return Math.max(0, now - Math.max(windowStart, occupiedSince));
    }

    /**
     * One scenario at an evaluation: the unoccupied part of the window [max(last evaluation, created), now] extends its
     * deadline (unoccupied time is frozen; returning to a region applies no offline catch-up), and an occupied region's
     * scenario expires once its extended deadline has passed. {@code occupiedSince} is null when the region is empty now.
     */
    static EvolutionState.Scenario account(EvolutionState.Scenario e, Long occupiedSince, long lastEvaluation, long now) {
        long windowStart = Math.max(lastEvaluation, e.created());
        long credited = creditedTicks(occupiedSince, windowStart, now);
        var paused = e.pause(Math.max(0, now - windowStart) - credited);
        return occupiedSince != null ? paused.expire(now) : paused;
    }

    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        if (!data.evaluationDue(server.overworld().getGameTime())) return;
        var before = data.snapshot();
        var s = data.snapshot();
        long now = now(s); s.nextEvaluation = Math.addExact(now, EvolutionConfig.INTERVAL.get());
        var players = server.getPlayerList().getPlayers().stream().sorted(Comparator.comparing(ServerPlayer::getUUID)).toList();
        // Activity covers every online player; only the expensive generation below is bounded by the region budget.
        var present = presentRegions(s);
        occupiedSince.keySet().retainAll(present.keySet());
        for (UUID region : present.keySet()) occupiedSince.putIfAbsent(region, now);
        var kingdoms = McaKingdomsApi.get(server);
        s.scenarios.replaceAll((id, e) -> account(e, occupiedSince.get(kingdoms.resolveSettlementId(e.settlement())), s.lastEvaluation, now));
        s.activeRegions = new LinkedHashSet<>(present.keySet()); s.lastEvaluation = now;
        var regions = new ArrayList<>(present.keySet());
        int count = Math.min(regions.size(), EvolutionConfig.BUDGET.get());
        int start = regions.isEmpty() ? 0 : Math.floorMod(s.cursor, regions.size());
        s.cursor = s.cursor > 1_000_000 ? 0 : s.cursor + count;
        var announcements = new ArrayList<Runnable>();
        for (int i = 0; i < count; i++) {
            UUID region = regions.get((start + i) % regions.size());
            generate(s, present.get(region).get(0), region, now, announcements);
        }
        // Digests: the due subscribers who have waited longest go first, so a large server serves everyone in turn.
        var deliveries = players.stream()
                .filter(player -> s.subscriptions.contains(player.getUUID())
                        && now - s.digest.getOrDefault(player.getUUID(), now) >= EvolutionConfig.DIGEST_INTERVAL.get())
                .sorted(Comparator.comparingLong((ServerPlayer player) -> s.digest.getOrDefault(player.getUUID(), now))
                        .thenComparing(ServerPlayer::getUUID))
                .limit(EvolutionConfig.BUDGET.get()).toList();
        for (var player : deliveries) s.digest.put(player.getUUID(), now);
        if (deliveries.isEmpty() && announcements.isEmpty() && EvolutionSavedData.sameApartFromSchedule(before, s)) {
            // Only the schedule and sampling cursor moved: keep them in memory and let the next world save
            // write them. A crash before then repeats one evaluation, which changes nothing.
            s.clock = now;
            data.advanceSchedule(s);
            return;
        }
        // Durable before anything is announced: a failed save announces nothing and a restart repeats no digest.
        commitThenAnnounce(() -> commit(s), announcements);
        for (var player : deliveries) {
            long open = s.scenarios.values().stream().filter(e -> e.pending() && visible(player, e)).count();
            if (open > 0) dev.otectus.mcakingdoms.interaction.Notify.player(server, player.getUUID(), null,
                    net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.opportunity_digest", "%s known political opportunities.", open),
                    "evolution.overview");
        }
    }
    /**
     * Commits, then announces (F09). A commit that throws announces nothing, so no notice or actionable link ever names
     * an opportunity that was not saved; the exception reaches the caller unchanged.
     */
    static void commitThenAnnounce(Runnable commit, List<Runnable> announcements) {
        commit.run();
        announcements.forEach(Runnable::run);
    }

    /** Explicitly eligible, or automatically eligible when its kingdom is governed and no operator paused it. */
    private boolean eligible(EvolutionState s, UUID settlement) {
        if (s.eligible.contains(settlement)) return true;
        if (!EvolutionConfig.AUTO_ELIGIBLE.get() || s.paused != null && s.paused.contains(settlement)) return false;
        return McaKingdomsApi.get(server).getSettlement(settlement).flatMap(place -> KingdomsPoliticsApi.get(server).government(place.kingdomId().toString())).isPresent();
    }
    /** Prepares at most one opportunity for the region; its announcement is queued until the evaluation commits (F09). */
    private void generate(EvolutionState s, ServerPlayer viewer, UUID settlement, long now, List<Runnable> announcements) {
        // Stop before a bound rather than at it: a state past a bound fails validation and would refuse every save (F12).
        if (s.scenarios.size() >= CAPACITY || s.cooldowns.size() >= CAPACITY - 1 || s.consumed.size() >= CAPACITY * 16 - 1
                || activeCount(s) >= EvolutionConfig.CONCURRENT.get()) return;
        var place = McaKingdomsApi.get(server).getSettlement(settlement).orElse(null); if (place == null) return;
        var politics = KingdomsPoliticsApi.get(server); var government = politics.government(place.kingdomId().toString()).orElse(null);
        if (government == null) return;
        var institution = institutionAt(politics, viewer, settlement);
        for (var item : EvolutionDefinitions.INSTANCE.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var terms = item.getValue(); if (terms.dramatic() && !s.drama) continue;
            String key = settlement + ":" + item.getKey();
            if (s.cooldowns.getOrDefault(key, 0L) > now || s.scenarios.values().stream().anyMatch(e -> e.pending()
                    && McaKingdomsApi.get(server).resolveSettlementId(e.settlement()).equals(settlement) && e.templateId().equals(item.getKey()))) continue;
            Evidence cause = null;
            if (terms.trigger() == Trigger.INSTITUTION && institution != null && government.state() == Politics.State.ACTIVE)
                cause = new Evidence("mcakingdoms:institution", institution.id() + ":" + institution.revision(), "OPERATIONAL_INSTITUTION", now, Keyed.text("evolution.mcakingdoms.world.cause.institution","A recognized local institution is open to diplomatic introductions."));
            if (terms.trigger() == Trigger.INTERREGNUM && government.state() == Politics.State.INTERREGNUM && government.successor() != null)
                cause = new Evidence("mcakingdoms:government", government.kingdom() + ":" + government.revision(), "NAMED_SUCCESSION", now, Keyed.text("evolution.mcakingdoms.world.cause.succession","The leadership is vacant and a successor has been lawfully named."));
            if (terms.trigger() == Trigger.OCCUPATION) {
                var control = WarfareApi.get(server).flatMap(p -> p.control(viewer, settlement)).orElse(null);
                boolean occupied = dev.otectus.mcakingdoms.warfare.WarfareRuntime.get(server).verifiedBinding(settlement)
                        .filter(b -> b.condition() == dev.otectus.mcakingdoms.warfare.ControlState.Condition.OCCUPIED).isPresent();
                if (control != null && "available".equals(control.availability()) && (control.contested() || occupied))
                    cause = new Evidence("mcakingdoms:control", settlement + ":" + control.sequence(), "CONTROL_TRANSITION", now, Keyed.text("evolution.mcakingdoms.world.cause.control","Confirmed local control conditions support relief and autonomy talks."));
            }
            if (terms.trigger() == Trigger.DEMAND) {
                var demand = new ProtectionService(server).openDemand(settlement).stream().findFirst().orElse(null);
                if (demand != null) cause = new Evidence("mcakingdoms:protection", demand.id().toString(), "VOLUNTARY_DEMAND", demand.created(),
                        Keyed.text("evolution.mcakingdoms.world.cause.demand", "A signed protectorate has requested voluntary %s.", demand.duty()));
            }
            if (terms.trigger() == Trigger.GRIEVANCE) {
                var dispute = new ProtectionService(server).disputes(settlement).stream().findFirst().orElse(null);
                if (dispute != null) cause = new Evidence("mcakingdoms:protection", dispute.id().toString(), "REFUSED_VOLUNTARY_OBLIGATION", dispute.created(),
                        "A signed voluntary obligation was refused; mediation or an alternative civilian contribution can reopen talks");
            }
            if (cause == null) continue;
            String causeKey = "cause:" + dev.otectus.mcakingdoms.politics.GovernmentService.hash(item.getKey() + ":" + cause.key());
            if (s.consumed.contains(causeKey)) continue;
            String seed = key + ":" + now; UUID id = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
            var event = new Scenario(id, item.getKey(), terms, settlement, terms.trigger() == Trigger.INTERREGNUM || institution == null ? null : institution.id(),
                    place.kingdomId().toString(), cause, null, now, Math.addExact(now, terms.duration()), 1, Phase.OPEN, Map.of(), null, "");
            s.scenarios.put(id, event); s.consumed.add(causeKey); s.cooldowns.put(key, Math.addExact(now, terms.cooldown()));
            String kingdom = place.kingdomId().toString(), name = place.displayName(), title = terms.title();
            announcements.add(() -> dev.otectus.mcakingdoms.interaction.Notify.government(server, kingdom, null,
                    net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.opportunity_opened", "A new opportunity opened at %s: %s.", name, title),
                    "evolution.inspect"));
            return;
        }
    }
    private long activeCount(EvolutionState s) {
        return s.scenarios.values().stream().filter(Scenario::pending).count() + dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.get(server).activeCount();
    }
}
