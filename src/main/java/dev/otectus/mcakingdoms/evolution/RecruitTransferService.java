package dev.otectus.mcakingdoms.evolution;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.compat.recruits.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;
import static dev.otectus.mcakingdoms.evolution.RecruitTransferData.*;

/** Explicit bilateral defection/employment settlement; no ambient owner changes or free recruits. */
public final class RecruitTransferService {
    public record TransferView(UUID id,UUID unit,UUID owner,UUID recipient,UUID recipientGroup,String equipment,boolean family,long noticeUntil,
                               long deadline,long revision,Phase phase,String detail,boolean accountingDiverged) {}
    public record ReconciliationReview(UUID transfer,long revision,String fingerprint,String observation) {}
    public static boolean blocksMobilization(MinecraftServer server, UUID unit) {
        var data = RecruitTransferData.get(server);
        return !data.writable() || data.all().stream().anyMatch(t -> t.unit().equals(unit) && !t.terminal());
    }
    private final MinecraftServer server;
    private final RecruitTransferData data;
    public RecruitTransferService(MinecraftServer server) { this.server = server; data = RecruitTransferData.get(server); }
    public List<TransferView> transfers(ServerPlayer viewer) { actor(viewer); return data.all().stream()
            .filter(t->t.owner().equals(viewer.getUUID())||t.recipient().equals(viewer.getUUID()))
            .sorted(Comparator.comparingLong(Transfer::deadline).reversed()).map(this::view).toList(); }
    public List<TransferView> operatorTransfers(ServerPlayer viewer) { actor(viewer); if(!viewer.hasPermissions(2))throw Keyed.refuse("evolution.mcakingdoms.transfer.operator_required","Only an operator can review or reconcile transfers.");
        return data.all().stream().sorted(Comparator.comparingLong(Transfer::deadline).reversed()).map(this::view).toList(); }
    public ReconciliationReview reconciliationReview(ServerPlayer actor,UUID id) { actor(actor);if(!actor.hasPermissions(2))throw Keyed.refuse("evolution.mcakingdoms.transfer.operator_required","Only an operator can review or reconcile transfers.");
        var t=data.get(id).orElseThrow(()->Keyed.refuse("evolution.mcakingdoms.transfer.unavailable","That transfer is not available."));if(t.terminal()||t.snapshot()==null)throw Keyed.refuse("evolution.mcakingdoms.transfer.nothing_to_reconcile","This transfer has no pending Recruits change to reconcile.");
        var review=RecruitsTransfer.review(loaded(t.unit()),t.snapshot(),false);return new ReconciliationReview(t.id(),t.revision(),review.fingerprint(),review.observation()); }
    public String version(ServerPlayer viewer) { return transfers(viewer).stream().map(v->v.id()+":"+v.revision()+":"+v.phase()).collect(java.util.stream.Collectors.joining("|")); }
    private TransferView view(Transfer t) { return new TransferView(t.id(),t.unit(),t.owner(),t.recipient(),t.recipientGroup(),t.equipment(),t.family(),t.noticeUntil(),t.deadline(),t.revision(),t.phase(),t.detail(),t.accountingDiverged()); }
    private void actor(ServerPlayer actor) { if (actor.getServer() != server || actor.hasDisconnected() || !server.isSameThread()) throw Keyed.refuse("evolution.mcakingdoms.transfer.connected","You must be connected to this server."); }
    private void enabled() { if (!EvolutionRuntime.dramaEnabled(server)) throw Keyed.refuse("evolution.mcakingdoms.transfer.drama_off","Recruit transfers need world events (political drama) turned on for this world."); RecruitsMilitary.ready(server); }
    private Entity loaded(UUID id) {
        for (var level : server.getAllLevels()) { var entity = level.getEntity(id); if (entity != null) return entity; }
        throw Keyed.refuse("evolution.mcakingdoms.transfer.unit_unloaded","The recruit is not loaded. Its area was not loaded and its owner was not guessed; try again near it.");
    }
    private void put(Transfer t) { if (!data.put(server, t)) throw Keyed.refuse("evolution.mcakingdoms.transfer.save_unavailable","The transfer could not be saved, so nothing changed."); }
    private Transfer own(ServerPlayer actor, UUID id) {
        actor(actor); var t = data.get(id).orElseThrow(() -> Keyed.refuse("evolution.mcakingdoms.transfer.unavailable","That transfer is not available."));
        if (!t.owner().equals(actor.getUUID()) && !t.recipient().equals(actor.getUUID())) throw Keyed.refuse("evolution.mcakingdoms.transfer.unavailable","That transfer is not available."); return t;
    }
    private void family(Transfer t) {
        if (t.family() && dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence.marriage(server, t.owner()).filter(m -> m.second().equals(t.recipient())).isEmpty())
            throw Keyed.refuse("evolution.mcakingdoms.transfer.family_unavailable","MCA no longer shows the marriage this transfer relies on, so the agreement is suspended.");
    }
    public String propose(ServerPlayer owner, UUID unitId, UUID recipientId, UUID group, boolean family) {
        actor(owner); enabled(); var recipient = server.getPlayerList().getPlayer(recipientId);
        if (recipient == null) throw Keyed.refuse("evolution.mcakingdoms.transfer.recipient_offline","The recipient must be online to see the offer.");
        var unit = loaded(unitId); var snapshot = RecruitsTransfer.snapshot(unit, owner, recipient, group);
        long now = server.overworld().getGameTime();
        var t = new Transfer(UUID.randomUUID(), unitId, owner.getUUID(), recipientId, group, snapshot.equipment(), family,
                now + 1200, now + 72000, 1, Phase.OFFERED, null, Keyed.text("evolution.mcakingdoms.transfer.terms","Employment transfer: the recruit's equipment travels with it and its Recruits wage timer keeps running. No payment or troop levy is involved."));
        family(t); put(t);
        dev.otectus.mcakingdoms.interaction.Notify.player(owner.getServer(), recipientId, owner.getUUID(), net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.transfer_offered", "%s offers you the recruit %s; your consent is required.", owner.getGameProfile().getName(), dev.otectus.mcakingdoms.interaction.Names.unitText(owner.getServer(), unitId)), "transfer.inspect");
        return Keyed.text("evolution.mcakingdoms.transfer.proposed", "Private transfer offer sent: %s to %s. The recipient must consent, and %s of notice must pass before you can apply it.",
                dev.otectus.mcakingdoms.interaction.Names.unitText(owner.getServer(), unitId), dev.otectus.mcakingdoms.interaction.Names.player(owner.getServer(), recipientId), dev.otectus.mcakingdoms.interaction.Names.durationText(1200));
    }
    public String consent(ServerPlayer recipient, UUID id, long revision) {
        enabled(); var t = own(recipient, id); family(t);
        if (!RecruitsTransfer.equipment(loaded(t.unit())).equals(t.equipment())) throw Keyed.refuse("evolution.mcakingdoms.transfer.equipment_changed_decline","The recruit's equipment changed since the offer; decline it and agree fresh terms.");
        put(t.consent(recipient.getUUID(), revision, server.overworld().getGameTime())); return Keyed.text("evolution.mcakingdoms.transfer.consented","Your consent is recorded. The current owner applies the transfer after the notice period.");
    }
    public String cancel(ServerPlayer actor, UUID id, long revision) {
        var t = own(actor, id);
        if (t.revision() != revision || t.phase() != Phase.OFFERED && t.phase() != Phase.CONSENTED) throw Keyed.refuse("evolution.mcakingdoms.transfer.already_began","This transfer has already begun; use Restore to recover the original state.");
        put(t.phase(Phase.CANCELLED, null, Keyed.text("evolution.mcakingdoms.transfer.declined","A party declined before anything changed in Recruits."))); return Keyed.text("evolution.mcakingdoms.transfer.cancelled","Transfer cancelled; ownership is unchanged.");
    }
    public String apply(ServerPlayer owner, UUID id, long revision) {
        var t = own(owner, id); enabled(); family(t);
        if (!t.owner().equals(owner.getUUID()) || t.revision() != revision || t.phase() != Phase.CONSENTED
                || server.overworld().getGameTime() < t.noticeUntil() || server.overworld().getGameTime() >= t.deadline())
            throw Keyed.refuse("evolution.mcakingdoms.transfer.apply_conditions","Only the current owner can apply this, after the recipient's consent and the notice period, and before the deadline.");
        var recipient = server.getPlayerList().getPlayer(t.recipient()); if (recipient == null) throw Keyed.refuse("evolution.mcakingdoms.transfer.recipient_must_stay","The recipient must stay online.");
        var unit = loaded(t.unit()); var before = RecruitsTransfer.snapshot(unit, owner, recipient, t.recipientGroup());
        if (!before.equipment().equals(t.equipment())) throw Keyed.refuse("evolution.mcakingdoms.transfer.equipment_changed","The recruit's equipment changed; agree fresh terms.");
        if (!RecruitsTransfer.saveAndConfirm(unit, before, false))
            throw Keyed.refuse("evolution.mcakingdoms.transfer.baseline_failed","Recruits could not save and verify the recruit's current state, so ownership is unchanged.");
        t = t.phase(Phase.APPLYING, before, Keyed.text("evolution.mcakingdoms.transfer.intent_saved","The transfer intent is saved before anything changes in Recruits.")); put(t);
        try {
            if (RecruitsTransfer.apply(unit, owner, recipient, before)) {
                t = t.phase(Phase.CONFIRMING, before, Keyed.text("evolution.mcakingdoms.transfer.ownership_changed","Ownership changed in Recruits; waiting for the save to be confirmed.")); put(t);
                if (RecruitsTransfer.saveAndConfirm(unit, before, true)) { put(t.phase(Phase.COMPLETE, before, Keyed.text("evolution.mcakingdoms.transfer.saves_acknowledged","Recruits saved the recruit, its equipment, group, owner counts and team."))); return Keyed.text("evolution.mcakingdoms.transfer.completed","Transfer completed, and the save is confirmed."); }
                return Keyed.text("evolution.mcakingdoms.transfer.pending_confirm","The transfer still waits for Recruits to confirm its save; use Confirm.");
            }
            t = t.phase(Phase.RESTORING, before, Keyed.text("evolution.mcakingdoms.transfer.refused_restoring","Recruits refused the transfer; restoring the original ownership awaits a confirmed save.")); put(t);
            if (RecruitsTransfer.saveAndConfirm(unit, before, false)) put(t.phase(Phase.CANCELLED, before, Keyed.text("evolution.mcakingdoms.transfer.refusal_preserved","Recruits refused the transfer and kept the original owner and equipment.")));
            return Keyed.text("evolution.mcakingdoms.transfer.refused","Recruits refused the transfer; inspect the transfer for its restoration record.");
        } catch (RuntimeException failure) {
            // Durable APPLYING/CONFIRMING survives a failure even if recording this diagnostic also fails.
            var pending = data.get(id).orElseThrow();
            var recovery = pending.phase(Phase.RESTORING, before, Keyed.text("evolution.mcakingdoms.transfer.interrupted_detail","Applying the transfer was interrupted; use Restore with both players present."));
            put(recovery);
            if (RecruitsTransfer.matches(unit, before, false) && RecruitsTransfer.saveAndConfirm(unit, before, false))
                put(recovery.phase(Phase.CANCELLED, before, Keyed.text("evolution.mcakingdoms.transfer.rolled_back","The failure was rolled back and the original ownership saved.")));
            return Keyed.text("evolution.mcakingdoms.transfer.interrupted", "The transfer was interrupted (%s). The record to restore the original state is kept.", Keyed.component(failure));
        }
    }
    /** Disk verification is bounded per transfer so repeated confirm/restore requests cannot flood the server with flushes. */
    static final long VERIFICATION_COOLDOWN = 200L;
    private static final Map<MinecraftServer, Map<UUID, Long>> LAST_VERIFICATION = new WeakHashMap<>();
    private void throttleVerification(UUID id) {
        long now = server.overworld().getGameTime();
        var attempts = LAST_VERIFICATION.computeIfAbsent(server, ignored -> new HashMap<>());
        Long last = attempts.get(id);
        if (last != null && now - last < VERIFICATION_COOLDOWN && now >= last)
            throw Keyed.refuse("evolution.mcakingdoms.transfer.throttled","The save was checked moments ago; wait a few seconds, then inspect or try again.");
        attempts.put(id, now);
    }
    public String confirm(ServerPlayer actor, UUID id) {
        var t = own(actor, id); if (t.terminal()) return t.detail();
        throttleVerification(id);
        return confirmVerified(actor, id);
    }
    private String confirmVerified(ServerPlayer actor, UUID id) {
        var t = own(actor, id); if (t.terminal()) return t.detail();
        if (t.snapshot() == null) throw Keyed.refuse("evolution.mcakingdoms.transfer.nothing_to_confirm","There is no Recruits change to confirm for this transfer.");
        var unit = loaded(t.unit());
        boolean changed = t.phase() != Phase.RESTORING;
        if (!RecruitsTransfer.saveAndConfirm(unit, t.snapshot(), changed)) return Keyed.text("evolution.mcakingdoms.transfer.not_confirmed","Recruits has not confirmed the save or the recruit's identity; the transfer is kept for recovery.");
        put(t.phase(changed ? Phase.COMPLETE : Phase.CANCELLED, t.snapshot(), Keyed.text("evolution.mcakingdoms.transfer.saved_acknowledged","Recruits' saved state is confirmed."))); return Keyed.text("evolution.mcakingdoms.transfer.outcome_confirmed","The transfer outcome is saved and confirmed.");
    }
    public String restore(ServerPlayer actor, UUID id) {
        var t = own(actor, id);
        if (t.terminal() || t.snapshot() == null) throw Keyed.refuse("evolution.mcakingdoms.transfer.nothing_to_restore","There is no pending Recruits change to restore.");
        var owner = server.getPlayerList().getPlayer(t.owner()); var recipient = server.getPlayerList().getPlayer(t.recipient());
        if (owner == null || recipient == null) throw Keyed.refuse("evolution.mcakingdoms.transfer.both_online","Both original players must be online to restore.");
        throttleVerification(id);
        var unit = loaded(t.unit()); put(t.phase(Phase.RESTORING, t.snapshot(), Keyed.text("evolution.mcakingdoms.transfer.restore_requested","Restoring the original state was requested.")));
        RecruitsTransfer.restore(unit, owner, recipient, t.snapshot());
        return confirmVerified(actor, id);
    }
    /** Most overdue offers expired per maintenance pass. */
    static final int EXPIRY_BUDGET = 8;

    /**
     * Expires overdue offers that never reached a native change (F10): offered or consented, past the deadline, with no
     * native snapshot. Oldest deadline first, at most {@link #EXPIRY_BUDGET} per pass, durably, whether or not world
     * events are enabled and whether or not either party is online. A transfer that began applying keeps its recovery
     * fence and is never expired. Returns how many expired.
     */
    public int expireOverdue() {
        if (!server.isSameThread()) throw new IllegalStateException("Transfer requires server thread");
        if (!data.writable()) return 0;
        long now = server.overworld().getGameTime();
        int expired = 0;
        for (var t : data.all().stream().filter(t -> t.expirable(now))
                .sorted(Comparator.comparingLong(Transfer::deadline).thenComparing(Transfer::id)).limit(EXPIRY_BUDGET).toList()) {
            if (!data.put(server, t.phase(Phase.EXPIRED, null, Keyed.text("evolution.mcakingdoms.transfer.expired","The offer expired before anything changed; ownership and equipment are unchanged.")))) break;
            expired++;
            var notice = net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.transfer_expired",
                    "The recruit transfer offer for %s expired before anything changed; native ownership is unchanged.",
                    dev.otectus.mcakingdoms.interaction.Names.unitText(server, t.unit()));
            dev.otectus.mcakingdoms.interaction.Notify.players(server, List.of(t.owner(), t.recipient()), null, notice, "transfer.inspect");
        }
        return expired;
    }

    public List<String> inspect(ServerPlayer actor, UUID id) {
        var t = own(actor, id);
        return List.of(Keyed.text("evolution.mcakingdoms.transfer.inspect.head", "Transfer #%s · %s · revision %s", t.id().toString().substring(0, 4), t.phase(), t.revision()),
                Keyed.text("evolution.mcakingdoms.transfer.inspect.parties", "%s from %s to %s (their group #%s)", dev.otectus.mcakingdoms.interaction.Names.unitText(server, t.unit()),
                        dev.otectus.mcakingdoms.interaction.Names.player(server, t.owner()), dev.otectus.mcakingdoms.interaction.Names.player(server, t.recipient()), t.recipientGroup().toString().substring(0, 4)),
                Keyed.text("evolution.mcakingdoms.transfer.inspect.times", "Notice ends in %s; deadline in %s; family introduction: %s", dev.otectus.mcakingdoms.interaction.Names.remainingText(server, t.noticeUntil()),
                        dev.otectus.mcakingdoms.interaction.Names.remainingText(server, t.deadline()), net.minecraft.network.chat.Component.translatable(t.family() ? "gui.yes" : "gui.no")), t.detail());
    }
    public String reconciliationPreview(ServerPlayer actor,UUID id) {
        var review=reconciliationReview(actor,id);
        return Keyed.text("evolution.mcakingdoms.transfer.preview","Revision %s; Recruits fingerprint %s; %s. Check Recruits' own counts before attesting: this records your review and repairs nothing.",review.revision(),review.fingerprint(),review.observation());
    }
    public String reconcile(ServerPlayer actor,UUID id,long revision,String fingerprint,String reason) {
        actor(actor);if(!actor.hasPermissions(2))throw Keyed.refuse("evolution.mcakingdoms.transfer.operator_required","Only an operator can review or reconcile transfers.");
        var t=data.get(id).orElseThrow(()->Keyed.refuse("evolution.mcakingdoms.transfer.unavailable","That transfer is not available."));
        if(t.terminal()||t.snapshot()==null||t.revision()!=revision)throw Keyed.refuse("evolution.mcakingdoms.transfer.intent_changed","The pending transfer changed; preview it again.");
        throttleVerification(id);
        var unit=loaded(t.unit());var review=RecruitsTransfer.review(unit,t.snapshot(),false);
        if(!review.fingerprint().equals(fingerprint))throw Keyed.refuse("evolution.mcakingdoms.transfer.native_changed","Recruits' state changed; preview and review again.");
        var receipt=new Reconciliation(actor.getUUID(),fingerprint,reason,server.overworld().getGameTime(),review.observation());
        RecruitsTransfer.review(unit,t.snapshot(),true);
        put(new Transfer(t.id(),t.unit(),t.owner(),t.recipient(),t.recipientGroup(),t.equipment(),t.family(),t.noticeUntil(),t.deadline(),t.revision()+1,
                Phase.RECONCILED,t.snapshot(),Keyed.text("evolution.mcakingdoms.transfer.reconciled_detail","An operator acknowledged the reviewed Recruits state; no ownership or count was written."),t.accountingDiverged(),receipt));
        return Keyed.text("evolution.mcakingdoms.transfer.reconciled","Recorded as reconciled. The original transfer and the evidence of the difference are kept.");
    }
}
