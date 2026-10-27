package dev.otectus.mcakingdoms.warfare;

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
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
    private void actor(ServerPlayer actor) {
        require(server.isSameThread() && actor.getServer() == server && !actor.hasDisconnected(), "Connected server player required.");
        require(WarfareConfig.ENABLED.get() && WarfareConfig.MILITARY.get(), "Military actions disabled by this server.");
        require(data.writable(), "Campaign records are unavailable and retained read-only.");
    }
    private String authority(ServerPlayer actor, Politics.Permission permission) {
        actor(actor); String nativeId = RecruitsMilitary.commandedFaction(actor);
        var mapping = controls().mapping(nativeId).orElseThrow(() -> new IllegalArgumentException("Native faction is not explicitly mapped."));
        require(politics().authorized(actor, mapping.kingdom(), permission), "A live " + permission + " political mandate is also required.");
        return nativeId;
    }
    private void known(ServerPlayer player, UUID settlement) {
        require(SettlementKnowledge.get(server).visible(player, settlement), "Settlement is not known to you.");
    }
    private ControlState.Binding bound(UUID id) {
        return controls().verifiedBinding(id).orElseThrow(() -> new IllegalArgumentException("Current native control is awaiting verified persistence or recovery."));
    }
    private void save(CampaignState next) {
        next.revision = Math.addExact(next.revision, 1);
        require(data.commit(server, next), "Campaign transaction could not be saved.");
    }
    private void notice(CampaignState next, UUID settlement, String action, UUID actor, String detail) {
        next.history.add(new Notice(UUID.randomUUID(), settlement, action, actor, now(), detail));
        if (next.history.size() > CampaignState.CAPACITY) next.history.remove(0);
    }
    private Optional<String> replay(ServerPlayer actor, UUID request, String fingerprint) {
        var receipt = data.snapshot().receipts.get(request);
        if (receipt == null) return Optional.empty();
        require(receipt.player().equals(actor.getUUID()) && receipt.fingerprint().equals(fingerprint), "Request identifier already belongs to another action.");
        return Optional.of(receipt.result());
    }
    private void receipt(CampaignState next, ServerPlayer actor, UUID request, String fingerprint, String result) {
        require(next.receipts.size() < CampaignState.CAPACITY * 4, "Campaign receipt capacity reached.");
        next.receipts.put(request, new Receipt(actor.getUUID(), fingerprint, result));
    }
    public String declare(ServerPlayer actor, UUID request, long expectedRevision, UUID settlement, Goal goal, String reason) {
        actor(actor); CampaignState.text(reason, 512); require(!reason.isBlank(), "A public campaign objective is required.");
        settlement = resolve(settlement);
        String fingerprint = dev.otectus.mcakingdoms.politics.GovernmentService.hash("declare|" + expectedRevision + "|" + settlement + "|" + goal + "|" + reason);
        var prior = replay(actor, request, fingerprint); if (prior.isPresent()) return prior.get();
        String attacker = authority(actor, Politics.Permission.PROPOSE); known(actor, settlement);
        var binding = bound(settlement); String defender = binding.owner(); require(!attacker.equals(defender), "Your faction already controls this claim.");
        require(controls().mapping(defender).isPresent(), "Defending faction must be explicitly mapped.");
        require(!WarfareConfig.PROTECT_CAPITALS.get() || !politics().isCapital(settlement), "This server protects capitals from campaigns.");
        var next = data.snapshot(); require(next.revision == expectedRevision, "Campaign state changed; refresh before declaring.");
        require(next.campaigns.size() < CampaignState.CAPACITY, "Campaign capacity reached.");
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
        receipt(next, actor, request, fingerprint, "Campaign to " + dev.otectus.mcakingdoms.interaction.Names.lower(dev.otectus.mcakingdoms.interaction.Names.words(goal)) + " at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement) + " recorded; inspect its native application status."); save(next);
        try { applyDeclaration(actor, request); }
        catch (IllegalArgumentException | IllegalStateException pending) { return "Campaign to " + dev.otectus.mcakingdoms.interaction.Names.lower(dev.otectus.mcakingdoms.interaction.Names.words(goal)) + " at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement) + " is recorded, but its native declaration is still pending: " + pending.getMessage() + " Retry it from the campaign's apply task."; }
        return "Campaign to " + dev.otectus.mcakingdoms.interaction.Names.lower(dev.otectus.mcakingdoms.interaction.Names.words(goal)) + " at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement) + ": " + dev.otectus.mcakingdoms.interaction.Names.words(data.snapshot().campaigns.get(request).phase()) + ". Native siege detection still governs the battle.";
    }
    public String applyDeclaration(ServerPlayer actor, UUID campaignId) {
        String faction = authority(actor, Politics.Permission.PROPOSE);
        var next = data.snapshot(); var campaign = next.campaigns.get(campaignId); require(campaign != null, "Campaign unavailable.");
        require(campaign.commander().equals(actor.getUUID()) && campaign.attacker().equals(faction), "This is another commander's campaign.");
        require(now() < campaign.expires() && campaign.phase() != Phase.RESOLVED && campaign.phase() != Phase.EXPIRED, "Campaign closed.");
        var binding = bound(campaign.settlement()); require(binding.claim().equals(campaign.claim()) && binding.owner().equals(campaign.defender()), "Claim control changed.");
        boolean applied = RecruitsMilitary.apply(actor, campaign.attacker(), campaign.defender(), campaign.before(), Relation.ENEMY);
        next = data.snapshot();
        next.campaigns.put(campaignId, campaign.phase(applied ? now() < campaign.noticeUntil() ? Phase.NOTICE : Phase.ACTIVE : Phase.SUSPENDED,
                applied ? "Declaration confirmed by native save; objective " + campaign.goal() : "Native declaration vetoed or not yet saved; explicit retry required."));
        save(next); return next.campaigns.get(campaignId).reason();
    }
    public String propose(ServerPlayer actor, UUID request, long expectedRevision, UUID settlement, String counterpart,
                          Relation relation, Sovereignty status, String recognizedKingdom, String terms) {
        actor(actor); CampaignState.text(terms, 512); require(!terms.isBlank(), "Describe the settlement terms.");
        require(relation != Relation.ENEMY, "War uses an explicit campaign declaration.");
        settlement = resolve(settlement);
        String fingerprint = dev.otectus.mcakingdoms.politics.GovernmentService.hash("accord|" + expectedRevision + "|" + settlement + "|" + counterpart + "|" + relation + "|" + status + "|" + recognizedKingdom + "|" + terms);
        var prior = replay(actor, request, fingerprint); if (prior.isPresent()) return prior.get();
        String first = authority(actor, Politics.Permission.RATIFY); known(actor, settlement); var binding = bound(settlement);
        require(!first.equals(counterpart), "An accord needs two separate native factions.");
        var a = controls().mapping(first).orElseThrow(); var b = controls().mapping(counterpart).orElseThrow(() -> new IllegalArgumentException("Counterpart is not mapped."));
        require(RecruitsMilitary.faction(server, counterpart).isPresent(), "Counterpart native faction unavailable.");
        String currentSovereign = control(settlement).orElseThrow().recognizedKingdom();
        require((first.equals(binding.owner()) || counterpart.equals(binding.owner()))
                && (a.kingdom().equals(currentSovereign) || b.kingdom().equals(currentSovereign)), "Both current control and recognized sovereignty must be represented.");
        require(recognizedKingdom.equals(a.kingdom()) || recognizedKingdom.equals(b.kingdom()), "The recognized sovereign must be a signing government.");
        require(!WarfareConfig.PROTECT_CAPITALS.get() || !politics().isCapital(settlement)
                || status == Sovereignty.RECOGNIZED && recognizedKingdom.equals(currentSovereign), "Protected capital sovereignty cannot be changed.");
        var next = data.snapshot(); require(next.revision == expectedRevision, "Campaign state changed; refresh before proposing.");
        require(next.accords.size() < CampaignState.CAPACITY, "Accord capacity reached.");
        UUID place = settlement;
        require(next.accords.values().stream().noneMatch(t -> resolve(t.settlement()).equals(place)
                && t.phase() != TreatyPhase.EXPIRED && t.phase() != TreatyPhase.REJECTED && t.phase() != TreatyPhase.BREACHED && t.expires() > now()), "An accord already applies or is pending for this settlement.");
        var accord = new Accord(request, settlement, first, counterpart, a.kingdom(), b.kingdom(), relation,
                RecruitsMilitary.relation(server, first, counterpart), RecruitsMilitary.relation(server, counterpart, first), Map.of(first, actor.getUUID()),
                TreatyPhase.PROPOSED, status, recognizedKingdom, now() + WarfareConfig.CAMPAIGN_DURATION.get(), binding.sequence(), binding.claim(), binding.owner(), terms);
        next.accords.put(request, accord); notice(next, settlement, "accord_proposed", actor.getUUID(), terms);
        dev.otectus.mcakingdoms.interaction.Notify.government(server, kingdoms().getSettlement(settlement).map(s -> s.kingdomId().toString()).orElse(""), actor.getUUID(), net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.accord_proposed", "An accord was proposed at %s; it awaits the other native leader and political ratifier.", dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement)), "warfare.campaign_overview");
        receipt(next, actor, request, fingerprint, "Accord at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, settlement) + " awaits the other native leader and political ratifier."); save(next);
        return next.receipts.get(request).result();
    }
    public String sign(ServerPlayer actor, UUID accordId, long expectedRevision) {
        String side = authority(actor, Politics.Permission.RATIFY); var next = data.snapshot();
        require(next.revision == expectedRevision, "Accord state changed; inspect before signing.");
        var accord = next.accords.get(accordId); require(accord != null && accord.phase() == TreatyPhase.PROPOSED && now() < accord.expires(), "Accord is not open for signature.");
        known(actor, accord.settlement()); require(side.equals(accord.first()) || side.equals(accord.second()), "You do not represent either signing faction.");
        accordControl(accord);
        require(!accord.signatures().containsKey(side), "This faction already signed.");
        accord = accord.sign(side, actor.getUUID()); next.accords.put(accordId, accord); save(next);
        if (accord.phase() == TreatyPhase.SIGNED) for (var signature : accord.signatures().entrySet()) {
            String counterpartKingdom = signature.getKey().equals(accord.first()) ? accord.secondKingdom() : accord.firstKingdom();
            dev.otectus.mcakingdoms.factions.Consequences.standing(server, signature.getValue(), counterpartKingdom, dev.otectus.mcakingdoms.factions.Consequences.SIGNED_ACCORD, UUID.nameUUIDFromBytes((accordId + ":" + signature.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Signed an accord at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, accord.settlement()));
        }
        if (accord.phase() != TreatyPhase.SIGNED) return "Signature recorded.";
        try { return applyAccord(actor, accordId); }
        catch (IllegalArgumentException | IllegalStateException pending) { return "Signature recorded; the accord's native application is still pending: " + pending.getMessage() + " Apply it again once the condition clears."; }
    }
    public String applyAccord(ServerPlayer requester, UUID accordId) {
        actor(requester); var next = data.snapshot(); var accord = next.accords.get(accordId);
        require(accord != null && (accord.phase() == TreatyPhase.SIGNED || accord.phase() == TreatyPhase.PENDING) && now() < accord.expires(), "Accord is not ready for application.");
        require(accord.signatures().containsValue(requester.getUUID()), "Only an accord signatory may request application.");
        accordControl(accord);
        ServerPlayer first = signatory(accord, accord.first(), accord.firstKingdom());
        ServerPlayer second = signatory(accord, accord.second(), accord.secondKingdom());
        var firstLive = RecruitsMilitary.relation(server, accord.first(), accord.second());
        var secondLive = RecruitsMilitary.relation(server, accord.second(), accord.first());
        require((firstLive == accord.firstBefore() || firstLive == accord.desired()) && (secondLive == accord.secondBefore() || secondLive == accord.desired()),
                "Native diplomacy diverged; negotiate fresh terms instead of overwriting it.");
        next.accords.put(accordId, accord.phase(TreatyPhase.PENDING, "Native application pending; peace is not yet operational.")); save(next);
        boolean applied = RecruitsMilitary.apply(first, accord.first(), accord.second(), accord.firstBefore(), accord.desired())
                && RecruitsMilitary.apply(signatory(accord, accord.second(), accord.secondKingdom()), accord.second(), accord.first(), accord.secondBefore(), accord.desired());
        next = data.snapshot();
        if (!applied) {
            next.accords.put(accordId, accord.phase(TreatyPhase.PENDING, "Native application is incomplete or vetoed; inspect and explicitly retry.")); save(next);
            return "Accord pending; military peace is not operational.";
        }
        signatory(accord, accord.first(), accord.firstKingdom()); signatory(accord, accord.second(), accord.secondKingdom()); accordControl(accord);
        next.accords.put(accordId, accord.phase(TreatyPhase.ACTIVE, "Both native directions confirmed in the provider save."));
        next.decisions.put(accord.settlement(), new Decision(accord.settlement(), accord.id(), accord.sovereignty(), accord.recognizedKingdom(), now(), accord.controlSequence()));
        UUID accordSettlement = resolve(accord.settlement());
        next.campaigns.replaceAll((id, c) -> resolve(c.settlement()).equals(accordSettlement) && c.phase() != Phase.EXPIRED
                ? c.phase(Phase.RESOLVED, "Settlement decision ratified by both governments.") : c);
        notice(next, accord.settlement(), "accord_operational", requester.getUUID(), accord.sovereignty() + " | " + accord.recognizedKingdom()); save(next);
        return "Accord operational. Recognized sovereignty is " + accord.sovereignty() + "; civic identity and native ownership are preserved.";
    }
    private void accordControl(Accord accord) {
        var current = bound(accord.settlement());
        require(current.claim().equals(accord.claim()) && current.owner().equals(accord.controller()), "Physical control changed; negotiate fresh settlement terms.");
    }
    public String withdraw(ServerPlayer actor, UUID campaignId) {
        String faction = authority(actor, Politics.Permission.PROPOSE); var next = data.snapshot(); var c = next.campaigns.get(campaignId);
        require(c != null && c.attacker().equals(faction) && c.commander().equals(actor.getUUID()), "Only this campaign's commander may withdraw.");
        require(c.phase() != Phase.RESOLVED, "Campaign already closed.");
        next.campaigns.put(campaignId, c.phase(Phase.SUSPENDED, "Withdrawal requested; native confirmation pending.")); save(next);
        boolean confirmed = RecruitsMilitary.apply(actor, c.attacker(), c.defender(), Relation.ENEMY, c.before()); // restore what stood before the declaration
        next = data.snapshot();
        next.campaigns.put(campaignId, c.phase(confirmed ? Phase.RESOLVED : Phase.SUSPENDED,
                confirmed ? "Own military declaration withdrawn; counterpart diplomacy remains native-owned." : "Native withdrawal pending; explicit retry required.")); save(next);
        return next.campaigns.get(campaignId).reason();
    }
    public String reject(ServerPlayer actor, UUID accordId, long expectedRevision) {
        String side = authority(actor, Politics.Permission.RATIFY); var next = data.snapshot(); var a = next.accords.get(accordId);
        require(next.revision == expectedRevision && a != null && (side.equals(a.first()) || side.equals(a.second())), "Accord changed or you lack signing authority.");
        require(a.phase() != TreatyPhase.ACTIVE, "An operational agreement must be replaced by a new negotiated decision or native diplomacy change.");
        next.accords.put(accordId, a.phase(TreatyPhase.REJECTED, "Negotiation rejected; any already applied native direction is retained for explicit reconciliation.")); save(next);
        return "Negotiation rejected. Native relations were not rewritten.";
    }
    private ServerPlayer signatory(Accord accord, String side, String kingdom) {
        var player = server.getPlayerList().getPlayer(accord.signatures().get(side));
        require(player != null && RecruitsMilitary.commands(player, side) && politics().authorized(player, kingdom, Politics.Permission.RATIFY),
                "Both native leaders must be online and retain their ratification mandates."); return player;
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
        String availability = !data.writable() ? "read_only" : !WarfareConfig.ENABLED.get() ? "disabled"
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
        return WarfareConfig.ENABLED.get() && WarfareConfig.CONTRACTS.get() && player.getServer() == server && data.writable()
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
        var binding = controls().bindings().stream().filter(b -> b.claim().equals(claim)).findFirst();
        if (binding.isEmpty() || !WarfareConfig.ENABLED.get() || !WarfareConfig.MILITARY.get()) return true;
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
        if (!WarfareConfig.ENABLED.get() || !data.writable()) return;
        var next = data.snapshot(); boolean changed = false;
        var campaigns = new ArrayList<>(next.campaigns.entrySet());
        var accords = new ArrayList<>(next.accords.entrySet());
        for (int i = 0; i < Math.min(WarfareConfig.BUDGET.get(), campaigns.size()); i++) {
            var entry = campaigns.get(Math.floorMod(campaignCursor++, campaigns.size())); var c = entry.getValue();
            if (c.phase() == Phase.RESOLVED || c.phase() == Phase.EXPIRED) continue;
            Phase phase = c.phase(); String reason = c.reason();
            if (now() >= c.expires()) { phase = Phase.EXPIRED; reason = "Campaign expired; native forces retain their native orders."; }
            else {
                var control = controls().binding(c.settlement());
                if (control.isPresent() && control.get().owner().equals(c.attacker()) && controls().verifiedBinding(c.settlement()).isPresent()) {
                    phase = Phase.OCCUPIED; reason = "Native control confirmed; occupation awaits a separate settlement decision.";
                } else if (phase == Phase.NOTICE && now() >= c.noticeUntil()) {
                    try { if (RecruitsMilitary.relation(server, c.attacker(), c.defender()) == Relation.ENEMY && RecruitsMilitary.saved(server, c.attacker(), c.defender(), Relation.ENEMY))
                        { phase = Phase.ACTIVE; reason = "Notice elapsed; native troop detection may begin a siege."; } }
                    catch (IllegalArgumentException unavailable) { phase = Phase.SUSPENDED; reason = "Native provider unavailable; campaign retained."; }
                }
            }
            if (phase != c.phase()) { next.campaigns.put(entry.getKey(), c.phase(phase, reason)); changed = true; }
        }
        for (int i = 0; i < Math.min(WarfareConfig.BUDGET.get(), accords.size()); i++) {
            var entry = accords.get(Math.floorMod(accordCursor++, accords.size()));
            var a = entry.getValue();
            if (a.phase() == TreatyPhase.ACTIVE) {
                try {
                    if (now() >= a.expires()) { next.accords.put(a.id(), a.phase(TreatyPhase.EXPIRED, "Agreement term elapsed; native relations are not rewritten.")); changed = true; }
                    else if (RecruitsMilitary.relation(server, a.first(), a.second()) != a.desired() || RecruitsMilitary.relation(server, a.second(), a.first()) != a.desired()) {
                        next.accords.put(a.id(), a.phase(TreatyPhase.BREACHED, "Native diplomacy changed; no automatic relation write-back.")); changed = true;
                    }
                } catch (IllegalArgumentException unavailable) { /* Native absence suspends operational queries without discarding terms. */ }
            }
        }
        if (changed) save(next);
    }
    private boolean viewable(ServerPlayer viewer,UUID settlement) {
        require(server.isSameThread()&&viewer.getServer()==server&&!viewer.hasDisconnected(),"Connected server player required.");
        UUID current=resolve(settlement);
        return SettlementKnowledge.get(server).visible(viewer,current)&&(viewer.hasPermissions(2)||kingdoms().getSettlementAt(viewer.serverLevel(),viewer.blockPosition()).filter(s->s.id().equals(current)).isPresent());
    }
    public List<Campaign> campaigns(ServerPlayer viewer,int offset,int limit) {
        require(offset>=0&&offset<=4096&&limit>0&&limit<=64,"Invalid campaign page.");return data.snapshot().campaigns.values().stream().filter(c->viewable(viewer,c.settlement())).sorted(Comparator.comparing(Campaign::id)).skip(offset).limit(limit).toList();
    }
    public List<Accord> accords(ServerPlayer viewer,int offset,int limit) {
        require(offset>=0&&offset<=4096&&limit>0&&limit<=64,"Invalid accord page.");return data.snapshot().accords.values().stream().filter(a->viewable(viewer,a.settlement())).sorted(Comparator.comparing(Accord::id)).skip(offset).limit(limit).toList();
    }
    public List<String> page(ServerPlayer viewer, UUID settlement) {
        var shown = pageLines(viewer, settlement);
        if (notPresent(viewer, resolve(settlement))) return shown.stream().map(net.minecraft.network.chat.Component::getString).toList();
        var lines = new ArrayList<String>(); lines.add("Campaign revision: " + revision());
        shown.forEach(line -> lines.add(line.getString()));
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
        require(viewer.getServer() == server, "Wrong server."); settlement = resolve(settlement); known(viewer, settlement);
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
            if (!c.reason().isBlank()) campaigns.add(w("campaign.reason", "Stated reason: %s", c.reason()));
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
            if (!a.reason().isBlank()) campaigns.add(w("campaign.reason", "Stated reason: %s", a.reason()));
        }
        if (campaigns.isEmpty()) campaigns.add(w("quiet", "No campaign, control agreement or native control record is known here."));
        if (now >= 0) sections.put("campaigns", campaigns);
        return sections;
    }
    /** The War Room's campaign lines, translated on the reader's client; reasons and terms stay as their authors wrote them. */
    public List<net.minecraft.network.chat.Component> pageLines(ServerPlayer viewer, UUID settlement) {
        require(viewer.getServer() == server, "Wrong server."); settlement = resolve(settlement); known(viewer, settlement);
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
