package dev.otectus.mcakingdoms.evolution;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.warfare.contracts.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import static dev.otectus.mcakingdoms.evolution.ProtectionState.*;

/** Consent-based political hierarchy and native-service obligations with peaceful exit. */
public final class ProtectionService {
    public record PactView(UUID id,String protector,String subordinate,UUID beneficiary,Set<Duty> duties,Phase phase,
                           long revision,long expires,long noticeTicks,long exitAt,String terms,boolean settlementVisible) {}
    public record ObligationView(UUID id,UUID pact,Duty duty,UUID beneficiary,Status status,long revision,long deadline,String explanation) {}
    private final MinecraftServer server;
    private final ProtectionSavedData data;
    public ProtectionService(MinecraftServer server) { this.server = server; data = ProtectionSavedData.get(server); }
    public long revision() { if(!server.isSameThread())throw new IllegalStateException("Protection requires server thread"); return data.revision(); }
    public List<PactView> pacts(ServerPlayer viewer) { actor(viewer); var s=data.snapshot(); return s.pacts.values().stream().filter(p->visible(viewer,p))
            .sorted(Comparator.comparingLong(Pact::created).reversed()).map(p->new PactView(p.id(),p.protector(),p.subordinate(),p.beneficiary(),p.duties(),p.phase(),p.revision(),p.expires(),p.noticeTicks(),p.exitAt(),p.terms(),SettlementKnowledge.get(server).visible(viewer,p.beneficiary()))).toList(); }
    public List<ObligationView> obligations(ServerPlayer viewer) { actor(viewer); var visible=pacts(viewer).stream().filter(PactView::settlementVisible).map(PactView::id).collect(java.util.stream.Collectors.toSet());
        return data.snapshot().obligations.values().stream().filter(o->visible.contains(o.pact())).sorted(Comparator.comparingLong(Obligation::created).reversed())
                .map(o->new ObligationView(o.id(),o.pact(),o.duty(),o.beneficiary(),o.status(),o.revision(),o.deadline(),o.explanation())).toList(); }
    private boolean visible(ServerPlayer viewer,Pact p) { return p.signatures().size()==2||politics().authorized(viewer,p.protector(),Politics.Permission.RATIFY)||politics().authorized(viewer,p.subordinate(),Politics.Permission.RATIFY); }
    private PoliticalService politics() { return KingdomsPoliticsApi.get(server); }
    private long now(ProtectionState s) { return Math.max(s.clock, server.overworld().getGameTime()); }
    private void actor(ServerPlayer player) {
        if (!server.isSameThread() || player.getServer() != server || player.hasDisconnected()) throw Keyed.refuse("evolution.mcakingdoms.protection.connected","You must be connected to this server.");
    }
    private void authority(ServerPlayer player, String kingdom, Politics.Permission permission) {
        actor(player); if (!politics().authorized(player, kingdom, permission)) throw Keyed.refuse("evolution.mcakingdoms.protection.authority", "You need the %s responsibility in %s.", permission, dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, kingdom));
    }
    private void commit(ProtectionState state) {
        state.clock = now(state); state.revision++;
        if (!data.commit(server, state)) throw Keyed.refuse("evolution.mcakingdoms.protection.save_unavailable","The protectorate records could not be saved, so nothing changed.");
    }
    private Pact pact(ProtectionState s, UUID id) {
        var pact = s.pacts.get(id); if (pact == null) throw Keyed.refuse("evolution.mcakingdoms.protection.pact_unavailable","That protectorate is not available."); return pact;
    }
    private void provider() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("mcaquests"))
            throw Keyed.refuse("evolution.mcakingdoms.protection.provider","Protectorate duties need MCA Quests for their service contracts; until it is installed, obligations stay suspended.");
    }
    public String propose(ServerPlayer player, String protector, String subordinate, UUID beneficiary,
                          Set<Duty> duties, long duration, long notice, String terms) {
        authority(player, protector, Politics.Permission.PROPOSE); provider();
        if (duration < 24000 || duration > 24_000_000) throw Keyed.refuse("evolution.mcakingdoms.protection.duration","The duration must be between 24,000 and 24,000,000 game ticks (1 to 1,000 game days).");
        var government = politics().government(subordinate).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.protection.subordinate_missing","The protected kingdom has no government."));
        if (government.state() != Politics.State.ACTIVE) throw Keyed.refuse("evolution.mcakingdoms.protection.subordinate_inactive","The protected kingdom's government is not active.");
        var settlement = McaKingdomsApi.get(server).getSettlement(beneficiary).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.protection.beneficiary_missing","The beneficiary settlement is not available."));
        if (!Set.of(protector, subordinate).contains(settlement.kingdomId().toString())
                || !SettlementKnowledge.get(server).visible(player, beneficiary)) throw Keyed.refuse("evolution.mcakingdoms.protection.beneficiary_rule","Choose a settlement you know that belongs to one of the two signing kingdoms.");
        var s = data.snapshot(); long now = now(s);
        if (s.pacts.values().stream().anyMatch(p -> p.subordinate().equals(subordinate) && (p.effective(now) || p.phase() == Phase.PROPOSED && p.expires() > now)))
            throw Keyed.refuse("evolution.mcakingdoms.protection.already_protected","That kingdom already has a protector or a pending proposal.");
        var p = new Pact(UUID.randomUUID(), protector, subordinate, beneficiary, duties, Map.of(), now, now + duration, notice, 0, 1, Phase.PROPOSED, terms);
        s.pacts.put(p.id(), p); commit(s);
        for (String kingdom : List.of(protector, subordinate)) dev.otectus.mcakingdoms.interaction.Notify.government(server, kingdom, player.getUUID(), net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.protectorate_proposed", "A protectorate was proposed between %s and %s; both governments must sign.", dev.otectus.mcakingdoms.interaction.Names.kingdom(server, protector), dev.otectus.mcakingdoms.interaction.Names.kingdom(server, subordinate)), "protection.inspect");
        return Keyed.text("evolution.mcakingdoms.protection.proposed", "Protectorate proposed by %s for %s. Both governments must sign these exact terms.", dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, protector), dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, subordinate));
    }
    public String sign(ServerPlayer player, UUID id, String kingdom, long revision) {
        authority(player, kingdom, Politics.Permission.RATIFY); provider(); var s = data.snapshot(); var p = pact(s, id);
        var signed = p.sign(kingdom, player.getUUID(), revision, now(s));
        for (var signature : signed.signatures().entrySet()) {
            var actor = server.getPlayerList().getPlayer(signature.getValue());
            if (actor == null || !politics().authorized(actor, signature.getKey(), Politics.Permission.RATIFY))
                throw Keyed.refuse("evolution.mcakingdoms.protection.signers_online","Both signing authorities must be online and still hold the ratification responsibility.");
        }
        s.pacts.put(id, signed); s.hierarchy(now(s)); commit(s);
        return signed.phase() == Phase.ACTIVE ? Keyed.text("evolution.mcakingdoms.protection.active","The protectorate is active. Only the listed voluntary duties apply; offices, laws and ownership stay as they are.") : Keyed.text("evolution.mcakingdoms.protection.signed","Signature recorded; waiting for the other government.");
    }
    public String exit(ServerPlayer player, UUID id, String kingdom, long revision) {
        authority(player, kingdom, Politics.Permission.RATIFY); var s = data.snapshot(); var p = pact(s, id);
        if (!Set.of(p.protector(), p.subordinate()).contains(kingdom)) throw Keyed.refuse("evolution.mcakingdoms.protection.signing_government","Only one of the two signing governments can do this.");
        var next = p.exit(revision, now(s)); s.pacts.put(id, next); commit(s);
        return Keyed.text("evolution.mcakingdoms.protection.exit", "Peaceful exit notice recorded; obligations end in %s. No property or troops are taken.", dev.otectus.mcakingdoms.interaction.Names.remainingText(server, next.exitAt()));
    }
    public String request(ServerPlayer player, UUID id, Duty duty, long revision) {
        var s = data.snapshot(); var p = pact(s, id); authority(player, p.protector(), Politics.Permission.REVIEW); provider();
        if (revision != p.revision() || !p.effective(now(s)) || p.phase() == Phase.EXIT_NOTICE) throw Keyed.refuse("evolution.mcakingdoms.protection.pact_changed","The protectorate changed, is inactive or is being exited.");
        if (!p.duties().contains(duty)) throw Keyed.refuse("evolution.mcakingdoms.protection.out_of_scope","Refused: this protectorate does not create that duty.");
        long deadlineNow = now(s);
        s.obligations.replaceAll((key, value) -> value.status() == Status.OPEN && value.deadline() <= deadlineNow
                ? value.finish(Status.EXPIRED, null, "", Keyed.text("evolution.mcakingdoms.protection.deadline_elapsed","The voluntary service deadline passed."), value.revision()) : value);
        if (s.obligations.values().stream().anyMatch(o -> o.pact().equals(id) && o.duty() == duty && o.status() == Status.OPEN))
            throw Keyed.refuse("evolution.mcakingdoms.protection.duty_open","This duty already has an open request.");
        long now = now(s); var obligation = new Obligation(UUID.randomUUID(), id, duty, p.beneficiary(), player.getUUID(), now,
                Math.min(p.expires(), now + 72000), 1, Status.OPEN, null, "", Keyed.text("evolution.mcakingdoms.protection.obligation_explanation", "Voluntary %s under protectorate #%s", duty, id.toString().substring(0, 4)));
        s.obligations.put(obligation.id(), obligation); commit(s); return Keyed.text("evolution.mcakingdoms.protection.requested", "A request for %s is open under the protectorate. Complete a matching service contract, or refuse it explicitly.", duty);
    }
    public String fulfill(ServerPlayer player, UUID id, long revision) {
        actor(player); provider(); var s = data.snapshot(); var o = s.obligations.get(id);
        if (o == null) throw Keyed.refuse("evolution.mcakingdoms.protection.obligation_unavailable","That obligation is not available."); var p = pact(s, o.pact());
        if (!p.effective(now(s)) || now(s) >= o.deadline()) throw Keyed.refuse("evolution.mcakingdoms.protection.obligation_inactive","That obligation is no longer active.");
        if (!SettlementKnowledge.get(server).visible(player, o.beneficiary())) throw Keyed.refuse("evolution.mcakingdoms.protection.visit_first","Visit the beneficiary settlement first.");
        var kind = EvolutionContracts.kind(o.duty());
        var proof = CivilianContractService.scopedProof(server, player.getUUID(), o.beneficiary(), kind, o.id())
                .filter(r -> r.completedAt() >= o.created() && r.completedAt() < o.deadline())
                .orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.protection.proof_required","A matching completed service contract within the obligation period is required."));
        String key = proof.providerEpoch() + ":" + proof.receipt();
        if (!s.receipts.add(key)) throw Keyed.refuse("evolution.mcakingdoms.protection.receipt_used","That completed contract already satisfies another obligation.");
        s.obligations.put(id, o.finish(Status.SATISFIED, player.getUUID(), key, Keyed.text("evolution.mcakingdoms.protection.verified", "%s service verified", kind), revision)); commit(s);
        dev.otectus.mcakingdoms.factions.Consequences.standing(server, player.getUUID(), p.protector(), dev.otectus.mcakingdoms.factions.Consequences.OBLIGATION_FULFILLED, UUID.nameUUIDFromBytes(("fulfilled:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Fulfilled a protectorate obligation");
        return Keyed.text("evolution.mcakingdoms.protection.satisfied","Obligation satisfied by your completed contract. Payment, if any, stays with the quest provider.");
    }
    public String refuse(ServerPlayer player, UUID id, long revision, String reason) {
        var s = data.snapshot(); var o = s.obligations.get(id); if (o == null) throw Keyed.refuse("evolution.mcakingdoms.protection.obligation_unavailable","That obligation is not available.");
        var p = pact(s, o.pact()); authority(player, p.subordinate(), Politics.Permission.REVIEW);
        if (reason.isBlank()) throw Keyed.refuse("evolution.mcakingdoms.protection.explain_refusal","Explain the refusal.");
        s.obligations.put(id, o.finish(Status.REFUSED, player.getUUID(), "", reason, revision)); commit(s);
        dev.otectus.mcakingdoms.factions.Consequences.standing(server, player.getUUID(), p.protector(), dev.otectus.mcakingdoms.factions.Consequences.OBLIGATION_REFUSED, UUID.nameUUIDFromBytes(("refused:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Refused a protectorate obligation");
        return Keyed.text("evolution.mcakingdoms.protection.refused","Refusal recorded. It may inform negotiation; it declares no war and moves no property.");
    }
    public List<String> page(ServerPlayer viewer, int offset) {
        actor(viewer); if (offset < 0 || offset > 1024 || offset % 10 != 0) throw Keyed.refuse("evolution.mcakingdoms.protection.invalid_page","That page is not valid.");
        var s = data.snapshot(); var lines = new ArrayList<String>();
        lines.add(Keyed.text("evolution.mcakingdoms.protection.page.head", "Protectorates: listed voluntary duties only, at most 4 levels deep · revision %s", s.revision));
        s.pacts.values().stream().filter(p -> p.signatures().size() == 2 || politics().authorized(viewer, p.protector(), Politics.Permission.RATIFY)
                        || politics().authorized(viewer, p.subordinate(), Politics.Permission.RATIFY)).sorted(Comparator.comparing(Pact::id)).skip(offset).limit(10)
                .forEach(p -> lines.add(Keyed.text("evolution.mcakingdoms.protection.page.row", "#%s · %s → %s · %s · %s · revision %s", p.id().toString().substring(0, 4),
                        dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, p.protector()), dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, p.subordinate()), p.refresh(now(s)).phase(), duties(p.duties()), p.revision())));
        return List.copyOf(lines);
    }
    public List<String> inspect(ServerPlayer viewer, UUID id) {
        actor(viewer); var s = data.snapshot(); var p = pact(s, id);
        if (p.signatures().size() < 2 && !politics().authorized(viewer, p.protector(), Politics.Permission.RATIFY)
                && !politics().authorized(viewer, p.subordinate(), Politics.Permission.RATIFY)) throw Keyed.refuse("evolution.mcakingdoms.protection.proposal_unavailable","That proposal is not available.");
        var lines = new ArrayList<String>(); lines.add(p.terms()); lines.add(Keyed.text("evolution.mcakingdoms.protection.inspect.duties", "Duties: %s · revision %s · exit notice %s", duties(p.duties()), p.revision(), dev.otectus.mcakingdoms.interaction.Names.durationText(p.noticeTicks())));
        if (SettlementKnowledge.get(server).visible(viewer, p.beneficiary())) {
            lines.add(Keyed.text("evolution.mcakingdoms.protection.inspect.beneficiary", "Beneficiary settlement: %s", dev.otectus.mcakingdoms.interaction.Names.settlement(server, viewer, p.beneficiary())));
            s.obligations.values().stream().filter(o -> o.pact().equals(id)).limit(16).forEach(o -> lines.add(Keyed.text("evolution.mcakingdoms.protection.inspect.obligation", "#%s · %s · %s · revision %s · deadline in %s",
                    o.id().toString().substring(0, 4), o.duty(), o.status() == Status.OPEN && o.deadline() <= now(s) ? Status.EXPIRED : o.status(), o.revision(), dev.otectus.mcakingdoms.interaction.Names.remainingText(server, o.deadline()))));
        }
        return List.copyOf(lines);
    }
    /** The duties in the reader's language, joined. */
    private static net.minecraft.network.chat.Component duties(Set<Duty> duties) {
        var out = net.minecraft.network.chat.Component.empty(); boolean first = true;
        for (Duty duty : duties.stream().sorted().toList()) { if (!first) out.append(", "); first = false; out.append(dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(duty)); }
        return out;
    }
    /** Bounded factual demand for the director; private refusals are not public narrative evidence. */
    public List<Obligation> openDemand(UUID settlement) {
        var kingdoms = McaKingdomsApi.get(server); UUID place = kingdoms.resolveSettlementId(settlement);
        var s = data.snapshot(); return s.obligations.values().stream().filter(o -> kingdoms.resolveSettlementId(o.beneficiary()).equals(place) && o.status() == Status.OPEN
                && o.deadline() > now(s) && s.pacts.get(o.pact()).effective(now(s))).limit(8).toList();
    }
    public List<Obligation> disputes(UUID settlement) {
        var kingdoms = McaKingdomsApi.get(server); UUID place = kingdoms.resolveSettlementId(settlement);
        var s = data.snapshot(); return s.obligations.values().stream().filter(o -> kingdoms.resolveSettlementId(o.beneficiary()).equals(place)
                && o.status() == Status.REFUSED && s.pacts.get(o.pact()).effective(now(s))).limit(8).toList();
    }
}
