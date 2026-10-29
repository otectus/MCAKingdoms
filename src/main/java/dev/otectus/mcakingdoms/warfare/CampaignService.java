package dev.otectus.mcakingdoms.warfare;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.warfare.WarfareApi;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsMilitary;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.warfare.CampaignState.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Durable intent -> native acknowledgment -> political effect. Recovery never rewrites native divergence. */
public final class CampaignService implements WarfareApi.Provider {
    private static final Map<MinecraftServer, CampaignService> SERVICES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final CampaignSavedData data;
    private int campaignCursor, accordCursor;
    private CampaignService(MinecraftServer server) { this.server = server; data = CampaignSavedData.get(server); }
    public static CampaignService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Campaigns require server thread");
        return SERVICES.computeIfAbsent(server, CampaignService::new);
    }
    public static void attach(MinecraftServer server) { WarfareApi.attach(server, get(server)); }
    public static void clear(MinecraftServer server) { SERVICES.remove(server); RecruitsMilitary.clear(server); WarfareApi.detach(server); }
    private WarfareRuntime controls() { return WarfareRuntime.get(server); }
    private KingdomsService kingdoms() { return McaKingdomsApi.get(server); }
    private PoliticalService politics() { return KingdomsPoliticsApi.get(server); }
    private long now() { return server.overworld().getGameTime(); }
    /** Stored settlement ids may predate a merge; compare them through this. */
    private UUID resolve(UUID settlement) { return kingdoms().resolveSettlementId(settlement); }
    public long revision() { return data.snapshot().revision; }
    public Optional<CampaignState.Campaign> campaign(UUID id) {
        if (!server.isSameThread()) throw new IllegalStateException("Campaign reads require server thread");
        return Optional.ofNullable(data.snapshot().campaigns.get(id));
    }
    public Optional<CampaignState.Accord> accord(UUID id) {
        if (!server.isSameThread()) throw new IllegalStateException("Accord reads require server thread");
        return Optional.ofNullable(data.snapshot().accords.get(id));
    }
    private static void require(boolean value, String reason) { if (!value) throw Keyed.of(reason); }
    private void actor(ServerPlayer actor) {
        require(server.isSameThread() && actor.getServer() == server && !actor.hasDisconnected(), Keyed.text("warfare.mcakingdoms.campaign.connected","You must be connected to this server."));
        require(WarfareConfig.enabled() && WarfareConfig.MILITARY.get(), Keyed.text("warfare.mcakingdoms.campaign.military_off","Military actions are turned off on this server."));
        require(data.writable(), Keyed.text("warfare.mcakingdoms.campaign.read_only","Campaign records are kept read-only."));
    }
    private String authority(ServerPlayer actor, Politics.Permission permission) {
        actor(actor); String nativeId = RecruitsMilitary.commandedFaction(actor);
        var mapping = controls().mapping(nativeId).orElseThrow(() -> Keyed.refuse("warfare.mcakingdoms.campaign.faction_unmapped","Your Recruits faction is not linked to a kingdom."));
        if (!politics().authorized(actor, mapping.kingdom(), permission)) throw Keyed.refuse("warfare.mcakingdoms.campaign.mandate", "You also need the %s responsibility in your faction's kingdom.", permission);
        // A crown MCA Capitals governs declares its own wars; a second declaration here would double the war and its rewards (C1).
        if (politics() instanceof dev.otectus.mcakingdoms.politics.GovernmentService government && !government.sovereignty(mapping.kingdom()).native_())
            throw Keyed.refuse("warfare.mcakingdoms.campaign.capitals", "MCA Capitals governs this kingdom's crown, so its wars are declared through Capitals, not here.");
        return nativeId;
    }
    private void known(ServerPlayer player, UUID settlement) {
        require(SettlementKnowledge.get(server).visible(player, settlement), Keyed.text("warfare.mcakingdoms.campaign.unknown_settlement","You have not discovered that settlement."));
    }
    private ControlState.Binding bound(UUID id) {
        return controls().verifiedBinding(id).orElseThrow(() -> Keyed.refuse("warfare.mcakingdoms.campaign.control_pending","The settlement's current control is still waiting for a confirmed save or recovery."));
    }
    private void save(CampaignState next) {
        next.revision = Math.addExact(next.revision, 1);
        require(data.commit(server, next), Keyed.text("warfare.mcakingdoms.campaign.save_failed","The campaign could not be saved, so nothing changed."));
    }
    private void notice(CampaignState next, UUID settlement, String action, UUID actor, String detail) {
        next.history.add(new Notice(UUID.randomUUID(), settlement, action, actor, now(), detail));
        if (next.history.size() > CampaignState.CAPACITY) next.history.remove(0);
    }
    private Optional<String> replay(ServerPlayer actor, UUID request, String fingerprint) {
        var receipt = data.snapshot().receipts.get(request);
        if (receipt == null) return Optional.empty();
        require(receipt.player().equals(actor.getUUID()) && receipt.fingerprint().equals(fingerprint), Keyed.text("warfare.mcakingdoms.campaign.request_reused","That request id belongs to another action."));
        return Optional.of(receipt.result());
    }
    private void receipt(CampaignState next, ServerPlayer actor, UUID request, String fingerprint, String result) {
        require(next.receipts.size() < CampaignState.CAPACITY * 4, Keyed.text("warfare.mcakingdoms.campaign.receipts_full","Campaign request receipts are full; try again later."));
        next.receipts.put(request, new Receipt(actor.getUUID(), fingerprint, result));
    }
    public String declare(ServerPlayer actor, UUID request, long expectedRevision, UUID settlement, Goal goal, String reason) {
        actor(actor); CampaignState.text(reason, 512); require(!reason.isBlank(), "A public campaign objective is required.");
        settlement = resolve(settlement);
        String fingerprint = dev.otectus.mcakingdoms.politics.GovernmentService.hash("declare|" + expectedRevision + "|" + settlement + "|" + goal + "|" + reason);
        var prior = replay(actor, request, fingerprint); if (prior.isPresent()) return prior.get();
        String attacker = authority(actor, Politics.Permission.PROPOSE); known(actor, settlement);
        var binding = bound(settlement); String defender = binding.owner(); require(!attacker.equals(defender), Keyed.text("warfare.mcakingdoms.campaign.own_claim","Your faction already controls this claim."));
        require(controls().mapping(defender).isPresent(), Keyed.text("warfare.mcakingdoms.campaign.defender_unmapped","The defending faction is not linked to a kingdom."));
        require(!WarfareConfig.PROTECT_CAPITALS.get() || !politics().isCapital(settlement), Keyed.text("warfare.mcakingdoms.campaign.capital_protected","This server protects capitals from campaigns."));
        var next = data.snapshot(); require(next.revision == expectedRevision, Keyed.text("warfare.mcakingdoms.campaign.changed_declare","Campaigns changed; refresh before declaring."));
        require(next.campaigns.size() < CampaignState.CAPACITY, Keyed.text("warfare.mcakingdoms.campaign.capacity","No more campaigns can be recorded."));
        UUID declared = settlement;
        require(next.campaigns.values().stream().noneMatch(c -> resolve(c.settlement()).equals(declared) && c.expires() > now()
                && c.phase() != Phase.EXPIRED && c.phase() != Phase.RESOLVED), "A campaign is already open for this settlement.");
        var before = RecruitsMilitary.relation(server, attacker, defender);
        var campaign = new Campaign(request, settlement, binding.claim(), attacker, defender, controls().mapping(attacker).orElseThrow().kingdom(),
                actor.getUUID(), goal, now() + WarfareConfig.NOTICE.get(), now() + WarfareConfig.NOTICE.get() + WarfareConfig.CAMPAIGN_DURATION.get(),
                Phase.NOTICE, binding.sequence(), before, reason);
        next.campaigns.put(request, campaign); notice(next, settlement, "declaration_intent", actor.getUUID(), reason);
        String defenderKingdom = kingdoms().getSettlement(settlement).map(s -> s.kingdomId().toString()).orElse("");
        dev.otectus.mcakingdoms.factions.Consequences.standing(server, actor.getUUID(), defenderKingdom, dev.otectus.mcakingdoms.factions.Consequences.DECLARED_WAR, request, "Declared a campaign against " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement));
        dev.otectus.mcakingdoms.interaction.Notify.government(server, defenderKingdom, actor.getUUID(), net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.campaign_declared", "A campaign (%s) was declared against %s.", dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(goal), dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement)), "warfare.campaigns");
        receipt(next, actor, request, fingerprint, Keyed.text("warfare.mcakingdoms.campaign.declared_receipt", "Campaign to %s at %s recorded; check whether Recruits has applied it.", goal, dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement))); save(next);
        try { applyDeclaration(actor, request); }
        catch (IllegalArgumentException | IllegalStateException pending) { return "Campaign to " + dev.otectus.mcakingdoms.interaction.Names.lower(dev.otectus.mcakingdoms.interaction.Names.words(goal)) + " at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement) + " is recorded, but its native declaration is still pending: " + pending.getMessage() + " Retry it from the campaign's apply task."; }
        return Keyed.text("warfare.mcakingdoms.campaign.declared", "Campaign to %s at %s: %s. Recruits' own siege detection still decides the battle.", goal, dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement), data.snapshot().campaigns.get(request).phase());
    }
    public String applyDeclaration(ServerPlayer actor, UUID campaignId) {
        String faction = authority(actor, Politics.Permission.PROPOSE);
        var next = data.snapshot(); var campaign = next.campaigns.get(campaignId); require(campaign != null, Keyed.text("warfare.mcakingdoms.campaign.unavailable", "That campaign is not available."));
        require(campaign.commander().equals(actor.getUUID()) && campaign.attacker().equals(faction), Keyed.text("warfare.mcakingdoms.campaign.other_commander","This is another commander's campaign."));
        require(now() < campaign.expires() && campaign.phase() != Phase.RESOLVED && campaign.phase() != Phase.EXPIRED, Keyed.text("warfare.mcakingdoms.campaign.closed", "This campaign is closed."));
        var binding = bound(campaign.settlement()); require(binding.claim().equals(campaign.claim()) && binding.owner().equals(campaign.defender()), Keyed.text("warfare.mcakingdoms.campaign.claim_changed","Control of the claim changed."));
        boolean applied = RecruitsMilitary.apply(actor, campaign.attacker(), campaign.defender(), campaign.before(), Relation.ENEMY);
        next = data.snapshot();
        next.campaigns.put(campaignId, campaign.phase(applied ? now() < campaign.noticeUntil() ? Phase.NOTICE : Phase.ACTIVE : Phase.SUSPENDED,
                applied ? Keyed.text("warfare.mcakingdoms.campaign.declaration_confirmed", "Recruits saved the declaration; objective: %s", campaign.goal()) : Keyed.text("warfare.mcakingdoms.campaign.declaration_pending","Recruits vetoed the declaration or has not saved it; retry it explicitly.")));
        save(next); return next.campaigns.get(campaignId).reason();
    }
    public String propose(ServerPlayer actor, UUID request, long expectedRevision, UUID settlement, String counterpart,
                          Relation relation, Sovereignty status, String recognizedKingdom, String terms) {
        actor(actor); CampaignState.text(terms, 512); require(!terms.isBlank(), Keyed.text("warfare.mcakingdoms.campaign.terms_needed","Describe the settlement terms."));
        require(relation != Relation.ENEMY, Keyed.text("warfare.mcakingdoms.campaign.war_needs_campaign","War needs an explicit campaign declaration."));
        settlement = resolve(settlement);
        String fingerprint = dev.otectus.mcakingdoms.politics.GovernmentService.hash("accord|" + expectedRevision + "|" + settlement + "|" + counterpart + "|" + relation + "|" + status + "|" + recognizedKingdom + "|" + terms);
        var prior = replay(actor, request, fingerprint); if (prior.isPresent()) return prior.get();
        String first = authority(actor, Politics.Permission.RATIFY); known(actor, settlement); var binding = bound(settlement);
        require(!first.equals(counterpart), Keyed.text("warfare.mcakingdoms.campaign.two_factions","An accord needs two separate Recruits factions."));
        var a = controls().mapping(first).orElseThrow(); var b = controls().mapping(counterpart).orElseThrow(() -> Keyed.refuse("warfare.mcakingdoms.campaign.counterpart_unmapped","The other faction is not linked to a kingdom."));
        require(RecruitsMilitary.faction(server, counterpart).isPresent(), Keyed.text("warfare.mcakingdoms.campaign.counterpart_unavailable","The other Recruits faction is unavailable."));
        String currentSovereign = control(settlement).orElseThrow().recognizedKingdom();
        require((first.equals(binding.owner()) || counterpart.equals(binding.owner()))
                && (a.kingdom().equals(currentSovereign) || b.kingdom().equals(currentSovereign)), Keyed.text("warfare.mcakingdoms.campaign.parties_required","Both the current controller and the recognized government must be represented."));
        require(recognizedKingdom.equals(a.kingdom()) || recognizedKingdom.equals(b.kingdom()), Keyed.text("warfare.mcakingdoms.campaign.sovereign_signer","The recognized government must be one of the signing governments."));
        require(!WarfareConfig.PROTECT_CAPITALS.get() || !politics().isCapital(settlement)
                || status == Sovereignty.RECOGNIZED && recognizedKingdom.equals(currentSovereign), Keyed.text("warfare.mcakingdoms.campaign.capital_sovereignty","A protected capital's government cannot be changed."));
        var next = data.snapshot(); require(next.revision == expectedRevision, Keyed.text("warfare.mcakingdoms.campaign.changed_propose","Campaigns changed; refresh before proposing."));
        require(next.accords.size() < CampaignState.CAPACITY, Keyed.text("warfare.mcakingdoms.campaign.accord_capacity","No more accords can be recorded."));
        UUID place = settlement;
        require(next.accords.values().stream().noneMatch(t -> resolve(t.settlement()).equals(place)
                && t.phase() != TreatyPhase.EXPIRED && t.phase() != TreatyPhase.REJECTED && t.phase() != TreatyPhase.BREACHED && t.expires() > now()), Keyed.text("warfare.mcakingdoms.campaign.accord_exists","An accord already applies or is pending for this settlement."));
        var accord = new Accord(request, settlement, first, counterpart, a.kingdom(), b.kingdom(), relation,
                RecruitsMilitary.relation(server, first, counterpart), RecruitsMilitary.relation(server, counterpart, first), Map.of(first, actor.getUUID()),
                TreatyPhase.PROPOSED, status, recognizedKingdom, now() + WarfareConfig.CAMPAIGN_DURATION.get(), binding.sequence(), binding.claim(), binding.owner(), terms);
        next.accords.put(request, accord); notice(next, settlement, "accord_proposed", actor.getUUID(), terms);
        dev.otectus.mcakingdoms.interaction.Notify.government(server, kingdoms().getSettlement(settlement).map(s -> s.kingdomId().toString()).orElse(""), actor.getUUID(), net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.accord_proposed", "An accord was proposed at %s; it awaits the other native leader and political ratifier.", dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement)), "warfare.campaign_overview");
        receipt(next, actor, request, fingerprint, Keyed.text("warfare.mcakingdoms.campaign.accord_receipt", "The accord at %s waits for the other faction's leader and a ratifying official.", dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement))); save(next);
        return next.receipts.get(request).result();
    }
    public String sign(ServerPlayer actor, UUID accordId, long expectedRevision) {
        String side = authority(actor, Politics.Permission.RATIFY); var next = data.snapshot();
        require(next.revision == expectedRevision, Keyed.text("warfare.mcakingdoms.campaign.accord_changed_sign","The accord changed; inspect it before signing."));
        var accord = next.accords.get(accordId); require(accord != null && accord.phase() == TreatyPhase.PROPOSED && now() < accord.expires(), Keyed.text("warfare.mcakingdoms.campaign.accord_not_open","This accord is not open for signature."));
        known(actor, accord.settlement()); require(side.equals(accord.first()) || side.equals(accord.second()), Keyed.text("warfare.mcakingdoms.campaign.not_signatory","You do not represent either signing faction."));
        accordControl(accord);
        require(!accord.signatures().containsKey(side), Keyed.text("warfare.mcakingdoms.campaign.already_signed","This faction already signed."));
        accord = accord.sign(side, actor.getUUID()); next.accords.put(accordId, accord); save(next);
        if (accord.phase() == TreatyPhase.SIGNED) for (var signature : accord.signatures().entrySet()) {
            String counterpartKingdom = signature.getKey().equals(accord.first()) ? accord.secondKingdom() : accord.firstKingdom();
            dev.otectus.mcakingdoms.factions.Consequences.standing(server, signature.getValue(), counterpartKingdom, dev.otectus.mcakingdoms.factions.Consequences.SIGNED_ACCORD, UUID.nameUUIDFromBytes((accordId + ":" + signature.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Signed an accord at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, accord.settlement()));
        }
        if (accord.phase() != TreatyPhase.SIGNED) return Keyed.text("warfare.mcakingdoms.campaign.signed", "Signature recorded.");
        try { return applyAccord(actor, accordId); }
        catch (IllegalArgumentException | IllegalStateException pending) { return "Signature recorded; the accord's native application is still pending: " + pending.getMessage() + " Apply it again once the condition clears."; }
    }
    public String applyAccord(ServerPlayer requester, UUID accordId) {
        actor(requester); var next = data.snapshot(); var accord = next.accords.get(accordId);
        require(accord != null && (accord.phase() == TreatyPhase.SIGNED || accord.phase() == TreatyPhase.PENDING) && now() < accord.expires(), Keyed.text("warfare.mcakingdoms.campaign.not_ready","This accord is not ready to apply."));
        require(accord.signatures().containsValue(requester.getUUID()), Keyed.text("warfare.mcakingdoms.campaign.signatory_only","Only someone who signed the accord may ask to apply it."));
        accordControl(accord);
        ServerPlayer first = signatory(accord, accord.first(), accord.firstKingdom());
        ServerPlayer second = signatory(accord, accord.second(), accord.secondKingdom());
        var firstLive = RecruitsMilitary.relation(server, accord.first(), accord.second());
        var secondLive = RecruitsMilitary.relation(server, accord.second(), accord.first());
        require((firstLive == accord.firstBefore() || firstLive == accord.desired()) && (secondLive == accord.secondBefore() || secondLive == accord.desired()),
                Keyed.text("warfare.mcakingdoms.campaign.diplomacy_diverged","Recruits' diplomacy changed independently; agree fresh terms instead of overwriting it."));
        next.accords.put(accordId, accord.phase(TreatyPhase.PENDING, Keyed.text("warfare.mcakingdoms.campaign.apply_pending","Waiting for Recruits to apply it; peace is not in effect yet."))); save(next);
        boolean applied = RecruitsMilitary.apply(first, accord.first(), accord.second(), accord.firstBefore(), accord.desired())
                && RecruitsMilitary.apply(signatory(accord, accord.second(), accord.secondKingdom()), accord.second(), accord.first(), accord.secondBefore(), accord.desired());
        next = data.snapshot();
        if (!applied) {
            next.accords.put(accordId, accord.phase(TreatyPhase.PENDING, Keyed.text("warfare.mcakingdoms.campaign.apply_incomplete","Recruits applied it only partly or vetoed it; inspect and retry explicitly."))); save(next);
            return Keyed.text("warfare.mcakingdoms.campaign.accord_pending","The accord is pending; military peace is not in effect.");
        }
        signatory(accord, accord.first(), accord.firstKingdom()); signatory(accord, accord.second(), accord.secondKingdom()); accordControl(accord);
        next.accords.put(accordId, accord.phase(TreatyPhase.ACTIVE, Keyed.text("warfare.mcakingdoms.campaign.both_directions","Recruits saved both sides of the change.")));
        next.decisions.put(accord.settlement(), new Decision(accord.settlement(), accord.id(), accord.sovereignty(), accord.recognizedKingdom(), now(), accord.controlSequence()));
        UUID accordSettlement = resolve(accord.settlement());
        next.campaigns.replaceAll((id, c) -> resolve(c.settlement()).equals(accordSettlement) && c.phase() != Phase.EXPIRED
                ? c.phase(Phase.RESOLVED, Keyed.text("warfare.mcakingdoms.campaign.decision_ratified","Both governments ratified the settlement decision.")) : c);
        notice(next, accord.settlement(), "accord_operational", requester.getUUID(), Keyed.text("warfare.mcakingdoms.campaign.accord_notice", "%s · %s", accord.sovereignty(), dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, accord.recognizedKingdom()))); save(next);
        return Keyed.text("warfare.mcakingdoms.campaign.accord_operational", "The accord is in effect. Recognized government: %s; civic identity and ownership are unchanged.", dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, accord.recognizedKingdom()));
    }
    private void accordControl(Accord accord) {
        var current = bound(accord.settlement());
        require(current.claim().equals(accord.claim()) && current.owner().equals(accord.controller()), Keyed.text("warfare.mcakingdoms.campaign.control_changed","Physical control changed; agree fresh settlement terms."));
    }
    public String withdraw(ServerPlayer actor, UUID campaignId) {
        String faction = authority(actor, Politics.Permission.PROPOSE); var next = data.snapshot(); var c = next.campaigns.get(campaignId);
        require(c != null && c.attacker().equals(faction) && c.commander().equals(actor.getUUID()), Keyed.text("warfare.mcakingdoms.campaign.commander_withdraw","Only this campaign's commander may withdraw."));
        require(c.phase() != Phase.RESOLVED, Keyed.text("warfare.mcakingdoms.campaign.already_closed","This campaign is already closed."));
        next.campaigns.put(campaignId, c.phase(Phase.SUSPENDED, Keyed.text("warfare.mcakingdoms.campaign.withdrawal_requested","Withdrawal requested; waiting for Recruits to confirm it."))); save(next);
        boolean confirmed = RecruitsMilitary.apply(actor, c.attacker(), c.defender(), Relation.ENEMY, c.before()); // restore what stood before the declaration
        next = data.snapshot();
        next.campaigns.put(campaignId, c.phase(confirmed ? Phase.RESOLVED : Phase.SUSPENDED,
                confirmed ? Keyed.text("warfare.mcakingdoms.campaign.withdrawn","Your side's declaration is withdrawn; the other side's diplomacy stays with Recruits.") : Keyed.text("warfare.mcakingdoms.campaign.withdrawal_pending","The withdrawal is waiting for Recruits; retry it explicitly."))); save(next);
        return next.campaigns.get(campaignId).reason();
    }
    public String reject(ServerPlayer actor, UUID accordId, long expectedRevision) {
        String side = authority(actor, Politics.Permission.RATIFY); var next = data.snapshot(); var a = next.accords.get(accordId);
        require(next.revision == expectedRevision && a != null && (side.equals(a.first()) || side.equals(a.second())), Keyed.text("warfare.mcakingdoms.campaign.reject_authority","The accord changed, or you cannot sign for either side."));
        require(a.phase() != TreatyPhase.ACTIVE, Keyed.text("warfare.mcakingdoms.campaign.reject_active","An agreement in effect can only be replaced by a new negotiated decision or a change in Recruits' diplomacy."));
        next.accords.put(accordId, a.phase(TreatyPhase.REJECTED, Keyed.text("warfare.mcakingdoms.campaign.rejected_detail","Negotiation rejected; any change already applied in Recruits is kept for explicit reconciliation."))); save(next);
        return Keyed.text("warfare.mcakingdoms.campaign.rejected","Negotiation rejected. Recruits' relations were not rewritten.");
    }
    private ServerPlayer signatory(Accord accord, String side, String kingdom) {
        var player = server.getPlayerList().getPlayer(accord.signatures().get(side));
        require(player != null && RecruitsMilitary.commands(player, side) && politics().authorized(player, kingdom, Politics.Permission.RATIFY),
                Keyed.text("warfare.mcakingdoms.campaign.leaders_online","Both faction leaders must be online and still hold their ratification responsibility.")); return player;
    }
    @Override public Optional<WarfareApi.Control> control(UUID settlement) {
        if (!server.isSameThread()) throw new IllegalStateException("Control query requires server thread");
        settlement = resolve(settlement);
        var binding = controls().binding(settlement); if (binding.isEmpty()) return Optional.empty();
        var value = binding.get(); var decision = data.snapshot().decisions.get(settlement);
        String civic = kingdoms().getSettlement(settlement).map(s -> s.kingdomId().toString()).orElse(value.sovereign());
        String recognized = decision == null ? value.sovereign() : decision.kingdom();
        String status = decision == null ? Sovereignty.RECOGNIZED.name() : decision.status().name();
        boolean siege = value.condition() == ControlState.Condition.UNDER_SIEGE;
        String availability = !data.writable() ? "read_only" : !WarfareConfig.enabled() ? "disabled"
                : controls().verifiedBinding(settlement).isPresent() ? "available" : "provider_pending";
        return Optional.of(new WarfareApi.Control(settlement, civic, recognized, value.owner(), status, siege, value.sequence(), availability));
    }
    @Override public Optional<WarfareApi.Control> control(ServerPlayer viewer, UUID settlement) {
        if (viewer.getServer() != server || !SettlementKnowledge.get(server).visible(viewer, settlement)) return Optional.empty();
        UUID current = resolve(settlement);
        if (!viewer.hasPermissions(2) && kingdoms().getSettlementAt(viewer.serverLevel(), viewer.blockPosition()).filter(s -> s.id().equals(current)).isEmpty()) return Optional.empty();
        return control(current);
    }
    @Override public boolean reliefEligible(ServerPlayer player, UUID settlement) {
        return WarfareConfig.enabled() && WarfareConfig.CONTRACTS.get() && player.getServer() == server && data.writable()
                && SettlementKnowledge.get(server).visible(player, settlement) && control(settlement).filter(c -> c.availability().equals("available")).isPresent();
    }
    @Override public boolean safeConduct(ServerPlayer player, UUID settlement) {
        if (!reliefEligible(player, settlement)) return false;
        String faction = player.getTeam() == null ? "" : player.getTeam().getName();
        UUID current = resolve(settlement);
        return data.snapshot().accords.values().stream().anyMatch(a -> resolve(a.settlement()).equals(current) && operational(a)
                && (faction.isEmpty() || neutralTo(faction, a.first()) && neutralTo(faction, a.second())));
    }
    private boolean neutralTo(String visitor, String side) {
        if (visitor.equals(side)) return true;
        try {
            if (RecruitsMilitary.faction(server, visitor).isEmpty()) return true;
            return RecruitsMilitary.relation(server, visitor, side) != Relation.ENEMY
                    && RecruitsMilitary.relation(server, side, visitor) != Relation.ENEMY;
        } catch (IllegalArgumentException unavailable) { return false; }
    }
    private boolean operational(Accord accord) {
        if (accord.phase() != TreatyPhase.ACTIVE || now() >= accord.expires() || !WarfareConfig.MILITARY.get()) return false;
        try {
            return RecruitsMilitary.relation(server, accord.first(), accord.second()) == accord.desired()
                    && RecruitsMilitary.relation(server, accord.second(), accord.first()) == accord.desired()
                    && RecruitsMilitary.saved(server, accord.first(), accord.second(), accord.desired())
                    && RecruitsMilitary.saved(server, accord.second(), accord.first(), accord.desired());
        } catch (IllegalArgumentException unavailable) { return false; }
    }
    public boolean siegeAllowed(UUID claim, List<String> attackers) {
        // A Living Villages village's claim follows that bridge's realm policy (AI and player wars), not the player-campaign rules.
        var villages = dev.otectus.mcakingdoms.compat.livingvillages.LvWarBridge.siegeAllowed(server, claim, attackers);
        if (villages.isPresent()) return villages.get();
        var binding = controls().bindings().stream().filter(b -> b.claim().equals(claim)).findFirst();
        if (binding.isEmpty() || !WarfareConfig.enabled() || !WarfareConfig.MILITARY.get()) return true;
        if (!data.writable()) return false;
        var b = binding.get();
        if (WarfareConfig.PROTECT_CAPITALS.get() && politics().isCapital(b.settlement())) return false;
        if (WarfareConfig.REQUIRE_DEFENDER_ONLINE.get()) {
            var defender = RecruitsMilitary.faction(server, b.owner());
            if (defender.isEmpty() || server.getPlayerList().getPlayer(defender.get().leader()) == null) return false;
        }
        UUID bound = resolve(b.settlement());
        if (data.snapshot().accords.values().stream().anyMatch(a -> resolve(a.settlement()).equals(bound) && operational(a))) return false;
        return !attackers.isEmpty() && attackers.stream().allMatch(attacker -> data.snapshot().campaigns.values().stream()
                .anyMatch(c -> c.claim().equals(claim) && c.attacker().equals(attacker) && c.defender().equals(b.owner())
                        && now() >= c.noticeUntil() && now() < c.expires() && (c.phase() == Phase.ACTIVE || c.phase() == Phase.NOTICE)
                        && RecruitsMilitary.relation(server, attacker, b.owner()) == Relation.ENEMY
                        && RecruitsMilitary.saved(server, attacker, b.owner(), Relation.ENEMY)));
    }
    public void tick() {
        if (!WarfareConfig.enabled() || !data.writable()) return;
        var next = data.snapshot(); boolean changed = false;
        var campaigns = new ArrayList<>(next.campaigns.entrySet());
        var accords = new ArrayList<>(next.accords.entrySet());
        for (int i = 0; i < Math.min(WarfareConfig.BUDGET.get(), campaigns.size()); i++) {
            var entry = campaigns.get(Math.floorMod(campaignCursor++, campaigns.size())); var c = entry.getValue();
            if (c.phase() == Phase.RESOLVED || c.phase() == Phase.EXPIRED) continue;
            Phase phase = c.phase(); String reason = c.reason();
            if (now() >= c.expires()) { phase = Phase.EXPIRED; reason = Keyed.text("warfare.mcakingdoms.campaign.expired","The campaign ended; Recruits forces keep their own orders."); }
            else {
                var control = controls().binding(c.settlement());
                if (control.isPresent() && control.get().owner().equals(c.attacker()) && controls().verifiedBinding(c.settlement()).isPresent()) {
                    phase = Phase.OCCUPIED; reason = Keyed.text("warfare.mcakingdoms.campaign.occupied","Control is confirmed; occupation waits for a separate settlement decision.");
                } else if (phase == Phase.NOTICE && now() >= c.noticeUntil()) {
                    try { if (RecruitsMilitary.relation(server, c.attacker(), c.defender()) == Relation.ENEMY && RecruitsMilitary.saved(server, c.attacker(), c.defender(), Relation.ENEMY))
                        { phase = Phase.ACTIVE; reason = Keyed.text("warfare.mcakingdoms.campaign.notice_elapsed","The notice period passed; Recruits' troop detection may now begin a siege."); } }
                    catch (IllegalArgumentException unavailable) { phase = Phase.SUSPENDED; reason = Keyed.text("warfare.mcakingdoms.campaign.provider_unavailable","Recruits is unavailable; the campaign is kept."); }
                }
            }
            if (phase != c.phase()) { next.campaigns.put(entry.getKey(), c.phase(phase, reason)); changed = true; }
        }
        for (int i = 0; i < Math.min(WarfareConfig.BUDGET.get(), accords.size()); i++) {
            var entry = accords.get(Math.floorMod(accordCursor++, accords.size()));
            var a = entry.getValue();
            if (a.phase() == TreatyPhase.ACTIVE) {
                try {
                    if (now() >= a.expires()) { next.accords.put(a.id(), a.phase(TreatyPhase.EXPIRED, Keyed.text("warfare.mcakingdoms.campaign.accord_elapsed","The agreement's term ended; Recruits' relations are not rewritten."))); changed = true; }
                    else if (RecruitsMilitary.relation(server, a.first(), a.second()) != a.desired() || RecruitsMilitary.relation(server, a.second(), a.first()) != a.desired()) {
                        next.accords.put(a.id(), a.phase(TreatyPhase.BREACHED, Keyed.text("warfare.mcakingdoms.campaign.accord_breached","Recruits' diplomacy changed; nothing was written back automatically."))); changed = true;
                    }
                } catch (IllegalArgumentException unavailable) { /* Native absence suspends operational queries without discarding terms. */ }
            }
        }
        if (changed) save(next);
    }
    private boolean viewable(ServerPlayer viewer,UUID settlement) {
        require(server.isSameThread()&&viewer.getServer()==server&&!viewer.hasDisconnected(),Keyed.text("warfare.mcakingdoms.campaign.connected","You must be connected to this server."));
        UUID current=resolve(settlement);
        return SettlementKnowledge.get(server).visible(viewer,current)&&(viewer.hasPermissions(2)||kingdoms().getSettlementAt(viewer.serverLevel(),viewer.blockPosition()).filter(s->s.id().equals(current)).isPresent());
    }
    public List<Campaign> campaigns(ServerPlayer viewer,int offset,int limit) {
        require(offset>=0&&offset<=4096&&limit>0&&limit<=64,Keyed.text("warfare.mcakingdoms.campaign.invalid_page","That campaign page is not valid."));return data.snapshot().campaigns.values().stream().filter(c->viewable(viewer,c.settlement())).sorted(Comparator.comparing(Campaign::id)).skip(offset).limit(limit).toList();
    }
    public List<Accord> accords(ServerPlayer viewer,int offset,int limit) {
        require(offset>=0&&offset<=4096&&limit>0&&limit<=64,Keyed.text("warfare.mcakingdoms.campaign.invalid_accord_page","That accord page is not valid."));return data.snapshot().accords.values().stream().filter(a->viewable(viewer,a.settlement())).sorted(Comparator.comparing(Accord::id)).skip(offset).limit(limit).toList();
    }
    public List<String> page(ServerPlayer viewer, UUID settlement) {
        var shown = pageLines(viewer, settlement);
        if (notPresent(viewer, resolve(settlement))) return shown.stream().map(Keyed::encode).toList();
        var lines = new ArrayList<String>(); lines.add(Keyed.text("warfare.mcakingdoms.campaign.page.head", "Campaign records, revision %s", revision()));
        shown.forEach(line -> lines.add(Keyed.encode(line)));
        return List.copyOf(lines);
    }
    private boolean notPresent(ServerPlayer viewer, UUID current) {
        return !viewer.hasPermissions(2) && kingdoms().getSettlementAt(viewer.serverLevel(), viewer.blockPosition()).filter(s -> s.id().equals(current)).isEmpty();
    }
    private static net.minecraft.network.chat.Component w(String key, String fallback, Object... args) {
        return net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room." + key, fallback, args);
    }
    /**
     * The War Room in sections: who holds the settlement (its kingdom, the recognized civilian government and military
     * control, kept apart), then each campaign and control agreement with its stage, time left on the game clock and whose
     * step is next. Reasons and terms stay as their authors wrote them. Only for a viewer standing there (or an operator).
     */
    public Map<String, List<net.minecraft.network.chat.Component>> roomSections(ServerPlayer viewer, UUID settlement) {
        require(viewer.getServer() == server, Keyed.text("warfare.mcakingdoms.campaign.wrong_server", "That request belongs to another server.")); settlement = resolve(settlement); known(viewer, settlement);
        UUID current = settlement;
        var sections = new LinkedHashMap<String, List<net.minecraft.network.chat.Component>>();
        var control = new ArrayList<net.minecraft.network.chat.Component>();
        sections.put("control", control);
        var civic = kingdoms().getSettlement(current).map(s -> s.kingdomId().toString()).orElse("");
        control.add(w("kingdom", "Settlement's kingdom: %s — who its people are; conquest does not change it.", dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, civic)));
        if (notPresent(viewer, current)) {
            control.add(w("visit", "Visit this settlement to inspect its campaigns and negotiations."));
            return sections;
        }
        var held = control(viewer, current);
        if (held.isEmpty()) control.add(w("no_claim", "Military control: no military claim is recorded here."));
        else {
            var c = held.get();
            net.minecraft.network.chat.Component autonomy;
            try { autonomy = dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(Sovereignty.valueOf(c.autonomy())); }
            catch (IllegalArgumentException unknown) { autonomy = net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.words(c.autonomy())); }
            control.add(w("government", "Recognized civilian government: %s (%s)", dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, c.recognizedKingdom()), autonomy));
            control.add(w("controller", "Military control: %s%s", c.nativeController(), c.contested() ? w("contested", " — under siege") : net.minecraft.network.chat.Component.empty()));
            if (!c.availability().equals("available")) control.add(w("availability." + c.availability(), "Control records: %s", c.availability()));
        }
        var state = data.snapshot();
        var campaigns = new ArrayList<net.minecraft.network.chat.Component>();
        long now = now();
        var here = state.campaigns.values().stream().filter(c -> resolve(c.settlement()).equals(current)).toList();
        here.stream().skip(Math.max(0, here.size() - 6)).forEach(c -> {
            campaigns.add(w("campaign.title", "%s campaign — %s", dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(c.goal()), dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(c.phase())));
            campaigns.add(switch (c.phase()) {
                case NOTICE -> w("campaign.notice", "Notice period: %s left before it can begin. The defenders are told; nothing is attacked yet.", dev.otectus.mcakingdoms.interaction.Names.remainingText(server, c.noticeUntil()));
                case ACTIVE, OCCUPIED -> w("campaign.active", "Ends in %s unless resolved earlier. Civilian services keep their own rules; Withdraw from a campaign ends your side's part.", dev.otectus.mcakingdoms.interaction.Names.remainingText(server, c.expires()));
                case SUSPENDED -> w("campaign.suspended", "Paused: the military provider is unavailable. It resumes when the provider returns; nothing is lost.");
                default -> w("campaign.closed", "Finished; kept as history.");
            });
            if (!c.reason().isBlank()) campaigns.add(w("campaign.reason", "Stated reason: %s", Keyed.component(c.reason())));
        });
        var accords = state.accords.values().stream().filter(a -> resolve(a.settlement()).equals(current)).limit(6).toList();
        for (var a : accords) {
            campaigns.add(w("accord.title", "Agreement on who governs: %s — %s", dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(a.sovereignty()), dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(a.phase())));
            campaigns.add(switch (a.phase()) {
                case PROPOSED -> w("accord.proposed", "%s of 2 signatures; expires in %s. Each side's commander signs; then it is applied.", a.signatures().size(), dev.otectus.mcakingdoms.interaction.Names.remainingText(server, a.expires()));
                case SIGNED, PENDING -> w("accord.signed", "Signed by both; waits to be applied through Apply a control agreement.");
                case ACTIVE -> w("accord.active", "In force for %s more. It sets who governs; families, homes and identity are unchanged.", dev.otectus.mcakingdoms.interaction.Names.remainingText(server, a.expires()));
                default -> w("accord.closed", "No longer in force; kept as history.");
            });
            if (!a.reason().isBlank()) campaigns.add(w("campaign.reason", "Stated reason: %s", Keyed.component(a.reason())));
        }
        if (campaigns.isEmpty()) campaigns.add(w("quiet", "No campaign, control agreement or native control record is known here."));
        if (now >= 0) sections.put("campaigns", campaigns);
        return sections;
    }
    /** The War Room's campaign lines, translated on the reader's client; reasons and terms stay as their authors wrote them. */
    public List<net.minecraft.network.chat.Component> pageLines(ServerPlayer viewer, UUID settlement) {
        require(viewer.getServer() == server, Keyed.text("warfare.mcakingdoms.campaign.wrong_server", "That request belongs to another server.")); settlement = resolve(settlement); known(viewer, settlement);
        UUID current = settlement;
        if (notPresent(viewer, current))
            return List.of(net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room.visit", "Visit this settlement to inspect its campaigns and negotiations."));
        var result = new ArrayList<net.minecraft.network.chat.Component>();
        control(viewer, settlement).ifPresent(c -> result.add(net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room.sovereignty", "Sovereignty: %s · %s · %s",
                dev.otectus.mcakingdoms.interaction.Names.words(c.autonomy()), dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, c.recognizedKingdom()), c.availability())));
        var state = data.snapshot();
        state.campaigns.values().stream().filter(c -> resolve(c.settlement()).equals(current)).skip(Math.max(0, state.campaigns.values().stream().filter(c -> resolve(c.settlement()).equals(current)).count() - 6))
                .forEach(c -> result.add(net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room.campaign", "%s campaign · %s · %s",
                        dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(c.goal()), dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(c.phase()), c.reason())));
        state.accords.values().stream().filter(a -> resolve(a.settlement()).equals(current)).limit(6)
                .forEach(a -> result.add(net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room.accord", "%s agreement · %s · %s",
                        dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(a.sovereignty()), dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(a.phase()), a.reason())));
        if (result.isEmpty()) result.add(net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room.quiet", "No campaign, control agreement or native control record is known here."));
        return List.copyOf(result);
    }
}
