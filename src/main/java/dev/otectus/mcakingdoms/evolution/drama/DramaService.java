package dev.otectus.mcakingdoms.evolution.drama;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.factions.organization.*;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsMilitary;
import dev.otectus.mcakingdoms.evolution.EvolutionRuntime;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.warfare.*;
import dev.otectus.mcakingdoms.warfare.CampaignState.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Opt-in orchestration over existing authority-checked campaign and organization operations. */
public final class DramaService {
    public record TemplateView(String id,String title,DramaState.Kind kind,String objective,Set<DramaState.Outcome> outcomes) {}
    public record DramaView(UUID id,String title,DramaState.Kind kind,UUID settlement,DramaState.Phase phase,long revision,long remainingTicks,
                            String objective,Set<DramaState.Outcome> outcomes,String subject,String sourceKingdom,String targetKingdom,String result,boolean participant,boolean canConsent) {}
    private final MinecraftServer server;
    private final DramaSavedData data;
    private final DramaDefinitions definitions;
    DramaService(MinecraftServer server, DramaDefinitions definitions) { this.server = server; this.definitions = definitions; this.data = DramaSavedData.get(server); }
    public long revision() { thread(); return data.revision(); }
    public long activeCount() { thread(); return data.snapshot().dramas.values().stream().filter(d -> !d.terminal()).count(); }
    public Optional<DramaState.Drama> drama(UUID id) { thread(); return Optional.ofNullable(data.snapshot().dramas.get(id)); }
    public List<TemplateView> templates() { thread(); return definitions.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e->new TemplateView(e.getKey(),e.getValue().title(),e.getValue().kind(),e.getValue().objective(),e.getValue().outcomes())).toList(); }
    public List<DramaView> dramas(ServerPlayer viewer) { viewer(viewer); return data.snapshot().dramas.values().stream().filter(d->participates(viewer,d)||eligibleConsent(viewer,d))
            .sorted(Comparator.comparingLong(DramaState.Drama::created).reversed()).map(d->new DramaView(d.id(),d.terms().title(),d.terms().kind(),d.settlement(),d.phase(),d.revision(),d.remainingTicks(),
                    d.terms().objective(),d.terms().outcomes(),d.subject(),d.sourceKingdom(),d.targetKingdom(),d.result(),participates(viewer,d),eligibleConsent(viewer,d))).toList(); }
    private boolean eligibleConsent(ServerPlayer viewer,DramaState.Drama drama) { if(drama.phase()!=DramaState.Phase.PROPOSED||viewer.getUUID().equals(drama.proposer()))return false;
        if(drama.terms().kind()==DramaState.Kind.SCHISM){var source=ResourceLocation.tryParse(drama.subject());return source!=null&&activeMember(viewer,source);}
        try{return RecruitsMilitary.commandedFaction(viewer).equals(drama.targetFaction());}catch(IllegalArgumentException failure){return false;} }

    public String propose(ServerPlayer actor, UUID request, long expectedRevision, String templateId, UUID settlement, String subject) {
        actor(actor); require(EvolutionRuntime.dramaEnabled(server), Keyed.text("evolution.mcakingdoms.drama.disabled","World events are not turned on for this world."));
        require(request != null && settlement != null, "A request and settlement are required.");
        var template = definitions.snapshot().get(templateId); require(template != null, Keyed.text("evolution.mcakingdoms.drama.unknown_template","That world event is not defined by the loaded data packs."));
        require(SettlementKnowledge.get(server).visible(actor, settlement), Keyed.text("evolution.mcakingdoms.drama.unknown_settlement","You have not discovered that settlement."));
        var civic = McaKingdomsApi.get(server).getSettlement(settlement).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.settlement_unavailable","That settlement is not available."));
        String source, target, sourceKingdom, targetKingdom; var evidence = new ArrayList<DramaState.Evidence>();
        String dynamic = ""; subject = subject == null ? "" : subject;
        if (template.kind() == DramaState.Kind.SCHISM) {
            var original = ResourceLocation.tryParse(subject); require(original != null, Keyed.text("evolution.mcakingdoms.drama.schism_id","A schism needs an existing organization."));
            var organizations = OrganizationApi.get(server); require(organizations.definition(original).isPresent(), Keyed.text("evolution.mcakingdoms.drama.schism_defined","A schism needs a defined source organization."));
            var membership = organizations.ownSnapshot(actor).memberships().stream().filter(m -> m.organizationId().equals(original)
                    && m.status() == OrganizationMembershipSnapshot.Status.ACTIVE).findFirst();
            require(membership.isPresent(), Keyed.text("evolution.mcakingdoms.drama.schism_members","Everyone in a schism must be an active member of the source organization."));
            sourceKingdom = civic.kingdomId().toString(); targetKingdom = sourceKingdom;
            require(KingdomsPoliticsApi.get(server).authorized(actor, sourceKingdom, Politics.Permission.RECOGNIZE), Keyed.text("evolution.mcakingdoms.drama.schism_authority","Founding a schism needs the Recognize institutions responsibility in the source kingdom."));
            source = "schism_founder"; target = "schism_consenter";
            dynamic = "mcakingdoms:schism_" + request.toString().replace("-", "");
            evidence.add(new DramaState.Evidence("organization", "ACTIVE_MEMBERSHIP", original.toString(), membership.get().revision(), Keyed.text("evolution.mcakingdoms.drama.evidence.membership","The saved membership in the source organization and the current charter authority were confirmed.")));
        } else {
            var controls = WarfareRuntime.get(server); var binding = controls.verifiedBinding(settlement)
                    .orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.control_unavailable","The saved Recruits control record is unavailable."));
            source = RecruitsMilitary.commandedFaction(actor);
            var sourceMap = controls.mapping(source).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.faction_unmapped","Your Recruits faction is not linked to a kingdom."));
            target = binding.owner(); sourceKingdom = sourceMap.kingdom(); targetKingdom = sourceKingdom;
            var targetMap = controls.mapping(target).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.other_unmapped","The other Recruits faction is not linked to a kingdom."));
            require(!source.equals(target), Keyed.text("evolution.mcakingdoms.drama.distinct","The two sides must be different.")); targetKingdom = targetMap.kingdom();
            require(Set.of(DramaState.Kind.CAMPAIGN, DramaState.Kind.REBELLION, DramaState.Kind.INVASION).contains(template.kind()), Keyed.text("evolution.mcakingdoms.drama.no_adapter","This world event has no installed way to carry out its outcome."));
            evidence.add(new DramaState.Evidence("warfare_control", binding.condition().name(), binding.claim().toString(), binding.sequence(),
                    Keyed.text("evolution.mcakingdoms.drama.evidence.binding","Saved control record; the settlement's civic identity and buildings stay unchanged.")));
            if (template.kind() == DramaState.Kind.REBELLION) {
                require(binding.condition() == ControlState.Condition.OCCUPIED, Keyed.text("evolution.mcakingdoms.drama.rebellion_grievance","A rebellion needs a current, saved occupation of this settlement."));
                evidence.add(new DramaState.Evidence("warfare_control", "OCCUPATION_GRIEVANCE", settlement.toString(), binding.sequence(), Keyed.text("evolution.mcakingdoms.drama.evidence.grievance","The occupation supports the proposal; it gives no authority to erase the settlement's civic identity.")));
            }
        }
        String fingerprint = templateId + "|" + settlement + "|" + subject;
        var current = data.snapshot();
        if (current.receipts.containsKey(request)) { require(current.receipts.get(request).equals(fingerprint), Keyed.text("evolution.mcakingdoms.drama.request_reused","That request id belongs to another proposal.")); return Keyed.text("evolution.mcakingdoms.drama.already_recorded","This proposal is already recorded."); }
        require(current.revision == expectedRevision, Keyed.text("evolution.mcakingdoms.drama.state_changed","World events changed; refresh before proposing."));
        require(current.dramas.values().stream().filter(d -> !d.terminal()).count() + EvolutionRuntime.activeScenarioCount(server)
                < dev.otectus.mcakingdoms.evolution.EvolutionConfig.CONCURRENT.get(), Keyed.text("evolution.mcakingdoms.drama.budget","The world evolution budget for simultaneous events is full."));
        require(current.dramas.size() < DramaState.CAPACITY, Keyed.text("evolution.mcakingdoms.drama.capacity","No more world events can be recorded."));
        var kingdoms = McaKingdomsApi.get(server); UUID place = kingdoms.resolveSettlementId(settlement);
        require(current.dramas.values().stream().noneMatch(d -> kingdoms.resolveSettlementId(d.settlement()).equals(place) && !d.terminal()), Keyed.text("evolution.mcakingdoms.drama.settlement_busy","A world event already involves this settlement."));
        var participants = new LinkedHashMap<String, UUID>(); participants.put(source, actor.getUUID());
        var frozen = new DramaState.Drama(request, templateId, template, settlement, actor.getUUID(), source, target, sourceKingdom, targetKingdom,
                subject, dynamic, participants, Set.of(source), evidence, now(), template.durationTicks(), now(), 1, 0, DramaState.Phase.PROPOSED,
                DramaState.Operation.NONE, DramaState.operationId(request, "provider"), Keyed.text("evolution.mcakingdoms.drama.awaiting_consent","Waiting for a participant from the other side to consent."));
        current.dramas.put(request, frozen); current.receipts.put(request, fingerprint); save(current);
        dev.otectus.mcakingdoms.interaction.Notify.players(server, frozen.participants().values(), actor.getUUID(), net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.world_event_proposed", "The world event \"%s\" was proposed at %s; your consent is requested.", dev.otectus.mcakingdoms.interaction.PlayerWords.component(frozen.terms().title()), dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement)), "drama.inspect");
        return Keyed.text("evolution.mcakingdoms.drama.proposed", "World event \"%s\" proposed with its fixed terms; the other participant must consent.", dev.otectus.mcakingdoms.interaction.PlayerWords.component(frozen.terms().title()));
    }

    public String consent(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id);
        require(drama.phase() == DramaState.Phase.PROPOSED, Keyed.text("evolution.mcakingdoms.drama.not_awaiting","This world event is not waiting for consent."));
        String side;
        if (drama.terms().kind() == DramaState.Kind.SCHISM) {
            var source = ResourceLocation.tryParse(drama.subject()); require(source != null && activeMember(actor, source), Keyed.text("evolution.mcakingdoms.drama.source_member","Only another active member of the source organization may consent."));
            require(!actor.getUUID().equals(drama.proposer()), Keyed.text("evolution.mcakingdoms.drama.another_player","Consent must come from a different player.")); side = drama.targetFaction();
        } else {
            side = RecruitsMilitary.commandedFaction(actor); require(side.equals(drama.targetFaction()), Keyed.text("evolution.mcakingdoms.drama.opposing_leader","Only the leader of the other Recruits faction may consent."));
        }
        current.dramas.put(id, drama.consent(side, actor.getUUID(), expectedRevision)); save(current);
        return Keyed.text("evolution.mcakingdoms.drama.consented","Consent recorded. The world event is ready; nothing has happened in Recruits yet.");
    }

    public String execute(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id);
        require(drama.terms().outcomes().contains(drama.terms().kind() == DramaState.Kind.SCHISM ? DramaState.Outcome.FOUND_SCHISM : DramaState.Outcome.DECLARE), Keyed.text("evolution.mcakingdoms.drama.outcome_not_allowed","This world event does not allow that outcome."));
        require(actor.getUUID().equals(drama.proposer()), Keyed.text("evolution.mcakingdoms.drama.proposer_only","Only the player who proposed it may start this."));
        if (drama.terms().kind() != DramaState.Kind.SCHISM)
            require(RecruitsMilitary.commands(actor, drama.sourceFaction()), Keyed.text("evolution.mcakingdoms.drama.proposer_lost_command","You no longer command the recorded Recruits faction."));
        DramaState.Operation operation = drama.terms().kind() == DramaState.Kind.SCHISM ? DramaState.Operation.SCHISM_FOUNDING : DramaState.Operation.DECLARATION;
        current.dramas.put(id, drama.begin(operation, expectedRevision)); save(current); // durable fence before provider call
        return resume(actor, id);
    }

    public String negotiate(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id);
        require(drama.terms().outcomes().contains(DramaState.Outcome.NEGOTIATE), Keyed.text("evolution.mcakingdoms.drama.no_negotiation","This world event does not allow negotiation."));
        require(drama.terms().kind() != DramaState.Kind.SCHISM, "A schism exits peacefully by leaving or independently choosing membership.");
        require(RecruitsMilitary.commands(actor, drama.sourceFaction()), Keyed.text("evolution.mcakingdoms.drama.signatory_terms","Only the recorded Recruits commander may propose terms."));
        current.dramas.put(id, drama.begin(DramaState.Operation.ACCORD, expectedRevision)); save(current);
        return resume(actor, id);
    }

    public String exit(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); var current = data.snapshot(); var drama = requireDrama(current, id); require(participates(actor, drama), Keyed.text("evolution.mcakingdoms.drama.participant_exit","Only a participant may leave."));
        require(drama.terms().outcomes().contains(DramaState.Outcome.EXIT), Keyed.text("evolution.mcakingdoms.drama.no_exit","This world event does not allow leaving."));
        current.dramas.put(id, drama.exit(expectedRevision, Keyed.text("evolution.mcakingdoms.drama.exit_detail","A participant chose a peaceful exit; civic identity, buildings, property and standing were kept."))); save(current);
        return Keyed.text("evolution.mcakingdoms.drama.exited","You left the world event. Control, civic identity, buildings, property and organization standing are unchanged.");
    }

    public String recover(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id); require(participates(actor, drama), Keyed.text("evolution.mcakingdoms.drama.participant_recover","Only a participant may recover this world event."));
        if (drama.phase() == DramaState.Phase.ACTIVE) {
            require(drama.terms().outcomes().contains(DramaState.Outcome.RECOVER), Keyed.text("evolution.mcakingdoms.drama.no_recovery","This world event does not allow withdrawing."));
            require(RecruitsMilitary.commands(actor, drama.sourceFaction()), Keyed.text("evolution.mcakingdoms.drama.commander_withdraw","Only the recorded campaign commander may withdraw."));
            current.dramas.put(id, drama.begin(DramaState.Operation.WITHDRAWAL, expectedRevision)); save(current);
        } else {
            require(drama.revision() == expectedRevision && Set.of(DramaState.Phase.APPLYING, DramaState.Phase.SUSPENDED, DramaState.Phase.NEGOTIATING, DramaState.Phase.RECOVERING).contains(drama.phase()), Keyed.text("evolution.mcakingdoms.drama.nothing_pending","Nothing is waiting for recovery."));
            if (drama.operation() != DramaState.Operation.NONE) { current.dramas.put(id, drama.retry(expectedRevision)); save(current); }
        }
        return resume(actor, id);
    }

    private String resume(ServerPlayer actor, UUID id) {
        var current = data.snapshot(); var drama = requireDrama(current, id);
        try {
            if (drama.operation() == DramaState.Operation.SCHISM_FOUNDING || drama.phase() == DramaState.Phase.APPLYING && drama.terms().kind() == DramaState.Kind.SCHISM) {
                var organizations = OrganizationApi.get(server); var dynamic = new ResourceLocation(drama.dynamicOrganization());
                if (organizations.lifecycle(dynamic).isEmpty()) {
                    var result = organizations.found(actor, dynamic, new ResourceLocation(drama.terms().organizationTemplate()), drama.sourceKingdom(), drama.terms().organizationName(), organizations.revision());
                    require(result.status() == OrganizationLifecycleResult.Status.APPLIED, Keyed.text("evolution.mcakingdoms.drama.charter_failed", "The charter was not applied: %s", Keyed.component(result.reason())));
                }
                current = data.snapshot(); drama = requireDrama(current, id);
                current.dramas.put(id, drama.applied(DramaState.Phase.RESOLVED, Keyed.text("evolution.mcakingdoms.drama.schism_detail","Schism charter founded; each participant keeps their standing and chooses membership independently."))); save(current);
                return Keyed.text("evolution.mcakingdoms.drama.schism_founded","Schism charter founded. No member was moved and no standing was copied.");
            }
            var campaigns = CampaignService.get(server);
            if (drama.operation() == DramaState.Operation.WITHDRAWAL || drama.phase() == DramaState.Phase.RECOVERING) {
                campaigns.withdraw(actor, drama.providerRequest());
                var confirmed = campaigns.campaign(drama.providerRequest()).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.campaign_receipt_missing","The campaign record is missing after the withdrawal."));
                require(confirmed.phase() == CampaignState.Phase.RESOLVED, Keyed.text("evolution.mcakingdoms.drama.withdrawal_pending","The withdrawal is still waiting for Recruits."));
                current = data.snapshot(); drama = requireDrama(current, id); current.dramas.put(id, drama.applied(DramaState.Phase.RESOLVED, Keyed.text("evolution.mcakingdoms.drama.withdrawal_detail","The campaign withdrawal is confirmed."))); save(current);
                return Keyed.text("evolution.mcakingdoms.drama.withdrawal_confirmed","Withdrawal confirmed; civic identity and property stay intact.");
            }
            if (drama.operation() == DramaState.Operation.ACCORD || drama.phase() == DramaState.Phase.NEGOTIATING) {
                UUID accordId = DramaState.operationId(id, "accord"); var accord = campaigns.accord(accordId).orElse(null);
                if (accord == null) {
                    var control = campaigns.control(drama.settlement()).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.control_for_negotiation","The current control record is unavailable for negotiation."));
                    campaigns.propose(actor, accordId, campaigns.revision(), drama.settlement(), drama.targetFaction(), Relation.NEUTRAL,
                            Sovereignty.RECOGNIZED, control.recognizedKingdom(), drama.terms().objective());
                    accord = campaigns.accord(accordId).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.negotiation_receipt_missing","The negotiation record is missing after the proposal."));
                }
                if (accord.phase() != TreatyPhase.ACTIVE) {
                    if (drama.operation() == DramaState.Operation.ACCORD) {
                        current = data.snapshot(); drama = requireDrama(current, id);
                        current.dramas.put(id, drama.applied(DramaState.Phase.NEGOTIATING, Keyed.text("evolution.mcakingdoms.drama.accord_proposed_detail","Accord proposed; both leaders must ratify it through campaign authority."))); save(current);
                    }
                    return Keyed.text("evolution.mcakingdoms.drama.negotiation_pending","The negotiation is waiting for both sides to ratify.");
                }
                current = data.snapshot(); drama = requireDrama(current, id); current.dramas.put(id, drama.applied(DramaState.Phase.RESOLVED, Keyed.text("evolution.mcakingdoms.drama.accord_detail","Both sides ratified the accord."))); save(current);
                return Keyed.text("evolution.mcakingdoms.drama.accord_confirmed","Both sides ratified the accord.");
            }
            if (campaigns.campaign(drama.providerRequest()).isEmpty()) {
                campaigns.declare(actor, drama.providerRequest(), campaigns.revision(), drama.settlement(), drama.terms().goal(), drama.terms().objective());
            } else campaigns.applyDeclaration(actor, drama.providerRequest());
            var confirmed = campaigns.campaign(drama.providerRequest()).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.drama.provider_receipt_missing","The campaign record is missing after the operation."));
            DramaState.Phase next = confirmed.phase() == CampaignState.Phase.SUSPENDED ? DramaState.Phase.SUSPENDED
                    : confirmed.phase() == CampaignState.Phase.RESOLVED || confirmed.phase() == CampaignState.Phase.EXPIRED ? DramaState.Phase.RESOLVED : DramaState.Phase.ACTIVE;
            current = data.snapshot(); drama = requireDrama(current, id); current.dramas.put(id, drama.applied(next,
                    Keyed.text("evolution.mcakingdoms.drama.campaign_state_detail", "Recruits confirmed the campaign as %s; battle and control stay with Recruits.", confirmed.phase()))); save(current);
            return Keyed.text("evolution.mcakingdoms.drama.campaign_state", "Campaign confirmed by Recruits: %s. No siege or transfer was made up.", confirmed.phase());
        } catch (RuntimeException failure) { return suspend(id, failure); }
    }

    private String suspend(UUID id, RuntimeException failure) {
        var current = data.snapshot(); var drama = requireDrama(current, id);
        var detail = failure.getMessage() == null ? net.minecraft.network.chat.Component.literal(failure.getClass().getSimpleName()) : Keyed.component(failure);
        if (detail.getString().length() > 800) detail = net.minecraft.network.chat.Component.literal(detail.getString().substring(0, 800));
        current.dramas.put(id, drama.suspended(Keyed.text("evolution.mcakingdoms.drama.suspended_detail", "The operation waits for recovery: %s", detail)));
        if (!saveIfPossible(current)) return Keyed.text("evolution.mcakingdoms.drama.suspension_unsaved","The operation failed and its suspension could not be saved; inspect before retrying.");
        return Keyed.text("evolution.mcakingdoms.drama.suspended", "The operation was not confirmed. The world event is suspended until someone recovers it: %s", detail);
    }

    public List<String> page(ServerPlayer viewer, int offset) {
        actor(viewer); if (offset < 0) throw Keyed.refuse("evolution.mcakingdoms.drama.invalid_offset","That page is not valid.");
        return data.snapshot().dramas.values().stream().filter(d -> participates(viewer, d)).sorted(Comparator.comparingLong(DramaState.Drama::created).reversed())
                .skip(offset).limit(20).map(d -> Keyed.text("evolution.mcakingdoms.drama.page.row", "#%s · %s · %s · revision %s · %s of online time left", d.id().toString().substring(0, 4), dev.otectus.mcakingdoms.interaction.PlayerWords.component(d.terms().title()), d.phase(), d.revision(), dev.otectus.mcakingdoms.interaction.Names.durationText(d.remainingTicks()))).toList();
    }
    public List<String> inspect(ServerPlayer viewer, UUID id) {
        actor(viewer); var drama = data.snapshot().dramas.get(id); if (drama == null || !participates(viewer, drama)) return List.of(Keyed.text("evolution.mcakingdoms.drama.unavailable", "That world event is not available."));
        var lines = new ArrayList<String>(); lines.add(Keyed.text("evolution.mcakingdoms.drama.inspect.head", "%s · %s", dev.otectus.mcakingdoms.interaction.PlayerWords.component(drama.terms().title()), drama.phase()));
        lines.add(Keyed.text("evolution.mcakingdoms.drama.inspect.objective", "Objective: %s", dev.otectus.mcakingdoms.interaction.PlayerWords.component(drama.terms().objective())));
        lines.add(Keyed.text("evolution.mcakingdoms.drama.inspect.time", "Online time left: %s; consents %s of 2", dev.otectus.mcakingdoms.interaction.Names.durationText(drama.remainingTicks()), drama.consents().size()));
        drama.evidence().forEach(e -> lines.add(Keyed.text("evolution.mcakingdoms.drama.inspect.evidence", "Evidence %s/%s #%s: %s", e.source(), e.kind(), e.revision(), Keyed.component(e.detail()))));
        lines.add(Keyed.text("evolution.mcakingdoms.drama.inspect.status", "Status: %s", Keyed.component(drama.result()))); return List.copyOf(lines);
    }

    void tick() {
        thread(); if (!EvolutionRuntime.dramaEnabled(server) || !data.writable()) return; var current = data.snapshot();
        if (current.dramas.isEmpty()) return; var open = current.dramas.values().stream().filter(d -> !d.terminal()).toList(); if (open.isEmpty()) return;
        boolean changed = false; long now = now(); int count = Math.min(8, open.size());
        for (int i = 0; i < count; i++) {
            var drama = open.get(Math.floorMod(current.cursor++, open.size())); boolean online = drama.participants().values().stream().allMatch(id -> server.getPlayerList().getPlayer(id) != null);
            var evaluated = drama.evaluate(now, online); if (!evaluated.equals(drama)) { current.dramas.put(drama.id(), evaluated); changed = true; }
        }
        if (changed) saveIfPossible(current);
    }
    private boolean activeMember(ServerPlayer player, ResourceLocation organization) {
        return OrganizationApi.get(server).ownSnapshot(player).memberships().stream().anyMatch(m -> m.organizationId().equals(organization) && m.status() == OrganizationMembershipSnapshot.Status.ACTIVE);
    }
    private boolean participates(ServerPlayer player, DramaState.Drama drama) { return drama.participants().containsValue(player.getUUID()); }
    private DramaState.Drama requireDrama(DramaState state, UUID id) { var drama = state.dramas.get(id); if (drama == null) throw Keyed.refuse("evolution.mcakingdoms.drama.unavailable", "That world event is not available."); return drama; }
    private void viewer(ServerPlayer actor) { thread(); require(actor != null && actor.getServer() == server && !actor.hasDisconnected(), Keyed.text("evolution.mcakingdoms.drama.connected","You must be connected to this server.")); }
    private void actor(ServerPlayer actor) { viewer(actor); require(data.writable(), Keyed.text("evolution.mcakingdoms.drama.read_only","World event records are kept read-only.")); }
    private void requireEnabled() { require(EvolutionRuntime.dramaEnabled(server), Keyed.text("evolution.mcakingdoms.drama.disabled","World events are not turned on for this world.")); }
    private long now() { return server.overworld().getGameTime(); }
    private void save(DramaState next) { next.revision = Math.addExact(next.revision, 1); require(data.commit(server, next), Keyed.text("evolution.mcakingdoms.drama.save_failed","The world event could not be saved, so nothing changed.")); }
    private boolean saveIfPossible(DramaState next) { next.revision = Math.addExact(next.revision, 1); return data.commit(server, next); }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("Drama requires server thread"); }
    private static void require(boolean value, String message) { if (!value) throw Keyed.of(message); }
}
