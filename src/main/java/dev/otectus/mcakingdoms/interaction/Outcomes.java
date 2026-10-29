package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.evolution.EvolutionRuntime;
import dev.otectus.mcakingdoms.evolution.EvolutionState;
import dev.otectus.mcakingdoms.evolution.RecruitTransferData;
import dev.otectus.mcakingdoms.evolution.RecruitTransferService;
import dev.otectus.mcakingdoms.evolution.drama.DramaRuntime;
import dev.otectus.mcakingdoms.evolution.drama.DramaState;
import dev.otectus.mcakingdoms.warfare.CampaignService;
import dev.otectus.mcakingdoms.warfare.CampaignState;
import dev.otectus.mcakingdoms.warfare.mobilization.MobilizationEvents;
import dev.otectus.mcakingdoms.warfare.mobilization.MobilizationSavedData;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Supplier;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Typed outcomes for the operations that can be pending, refused or unconfirmed after their service returns (F07).
 * Each reading comes from the owning record's current phase, read again on Check, never from the service's wording. The
 * phase texts are translation keys, so every player reads them in their own language (F14). Records are read only for
 * players already entitled to them: the parties of a transfer, the commander of a lease, a campaign's commander or a
 * participant of an accord or world event, the contributor or resolver of an opportunity, and political records under
 * the political service's own visibility rules.
 */
public final class Outcomes {
    private Outcomes() {
    }

    private static Component t(String key, String english, Object... args) {
        return tr("interaction.mcakingdoms.outcome." + key, english, args);
    }

    private static Map<String, String> prefill(String key, Object value) {
        return Map.of(key, String.valueOf(value));
    }

    // ------------------------------------------------------------------------------------------------ recruit transfers

    private static Optional<RecruitTransferService.TransferView> transfer(Context c, UUID id) {
        var service = new RecruitTransferService(c.server());
        var own = service.transfers(c.player).stream().filter(v -> v.id().equals(id)).findFirst();
        if (own.isPresent() || !c.player.hasPermissions(2)) return own;
        return service.operatorTransfers(c.player).stream().filter(v -> v.id().equals(id)).findFirst();
    }

    /** A transfer after {@code action} (propose, consent, cancel, apply, confirm, restore, reconcile), from its phase. */
    static Optional<Outcome> transfer(Context c, String action, UUID id) {
        return transfer(c, id).map(v -> transfer(action, v));
    }

    static Outcome transfer(String action, RecruitTransferService.TransferView v) {
        String op = v.id().toString();
        var next = prefill("transfer", op);
        return switch (v.phase()) {
            case OFFERED -> Outcome.pending(t("transfer.offered", "Proposal recorded. The recipient must consent; after the notice period the source owner applies it."), op).then("transfer.inspect", next);
            case CONSENTED -> Outcome.pending(t("transfer.consented", "Consent recorded. After the notice period the source owner applies the transfer while both players and the recruit are present."), op).then("transfer.inspect", next);
            case APPLYING, CONFIRMING -> Outcome.pending(t("transfer.confirming", "Ownership is recorded as changing, but the recruit's save has not confirmed it yet. Confirm it while the recruit is loaded."), op).then("transfer.confirm", next);
            case RESTORING -> Outcome.pending(t("transfer.restoring", "The original ownership is being restored and waits for the recruit's save to confirm it. Restore or confirm it with both players present."), op).then("transfer.restore", next);
            case COMPLETE -> Outcome.applied(t("transfer.complete", "The transfer is complete: the recruit, its equipment and its group were saved under the new owner."), op);
            case CANCELLED -> action.equals("cancel")
                    ? Outcome.applied(t("transfer.cancelled", "Cancelled. Native ownership is unchanged."), op)
                    : Outcome.refused(t("transfer.refused", "The transfer did not happen: native ownership and equipment stayed with the source owner."), op);
            case EXPIRED -> action.equals("cancel")
                    ? Outcome.applied(t("transfer.expired", "This offer expired before anything changed; native ownership is unchanged."), op)
                    : Outcome.refused(t("transfer.expired", "This offer expired before anything changed; native ownership is unchanged."), op);
            case RECONCILED -> Outcome.applied(t("transfer.reconciled", "An operator's reconciliation is recorded; the divergence evidence is kept."), op);
        };
    }

    /** Runs a transfer action and reads the transfer it acted on. */
    static Object transferAction(Context c, String action, UUID id, Supplier<String> run) {
        String said = run.get();
        return transfer(c, action, id).orElseGet(() -> Outcome.applied(said, id.toString()));
    }

    /** Proposes, then finds the one transfer this proposal created among the owner's own. */
    static Object transferProposal(Context c, Supplier<String> run) {
        var service = new RecruitTransferService(c.server());
        Set<UUID> before = new HashSet<>();
        service.transfers(c.player).forEach(v -> before.add(v.id()));
        String said = run.get();
        return service.transfers(c.player).stream().filter(v -> !before.contains(v.id())).findFirst()
                .map(v -> transfer("propose", v)).<Object>map(o -> o).orElse(said);
    }

    // ------------------------------------------------------------------------------------------------ mobilization

    private static Optional<MobilizationSavedData.Lease> lease(Context c, UUID id) {
        var service = MobilizationEvents.get(c.server());
        for (int offset = 0; offset <= 8192; offset += 64) {
            var page = service.deployments(c.player, offset, 64);
            for (var lease : page) if (lease.id().equals(id)) return Optional.of(lease);
            if (page.size() < 64) break;
        }
        return Optional.empty();
    }

    private static Set<UUID> leases(Context c) {
        var service = MobilizationEvents.get(c.server());
        Set<UUID> ids = new HashSet<>();
        for (int offset = 0; offset <= 8192; offset += 64) {
            var page = service.deployments(c.player, offset, 64);
            page.forEach(lease -> ids.add(lease.id()));
            if (page.size() < 64) break;
        }
        return ids;
    }

    /** A lease after {@code action} (muster, dismiss, recover), from its phase. */
    static Outcome lease(String action, MobilizationSavedData.Lease lease) {
        String op = lease.id().toString();
        var next = prefill("deployment", op);
        boolean ending = !action.equals("muster");
        return switch (lease.phase()) {
            case PREPARED -> Outcome.pending(t("lease.prepared", "The deployment is recorded, but the recruit's orders have not changed yet."), op).then("warfare.deployments", next);
            case ACTIVE_PENDING_NATIVE_SAVE -> Outcome.pending(t("lease.active_pending", "Deployed. The recruit's new orders wait for its next save to be confirmed."), op).then("warfare.deployments", next);
            case DURABLE_ACTIVE -> Outcome.applied(t("lease.active", "Deployed, and the recruit's saved orders confirm it."), op);
            case RESTORE_PENDING -> Outcome.pending(t("lease.restore_pending", "The deployment has ended; restoring the original orders waits for the recruit to load. No chunk is loaded for it."), op).then("warfare.deployments", next);
            case RESTORE_PENDING_NATIVE_SAVE -> Outcome.pending(t("lease.restore_saving", "The original orders are back in the game and wait for the recruit's save to confirm them."), op).then("warfare.deployments", next);
            case RESTORED -> ending
                    ? Outcome.applied(t("lease.restored", "The recruit's original orders are restored and saved."), op)
                    : Outcome.refused(t("lease.refused", "The order change was refused; the recruit keeps its original orders."), op);
            case DIVERGED -> Outcome.pending(t("lease.diverged", "The recruit's orders were changed separately, so nothing was overwritten. Recover its original orders to restore the saved ones."), op).then("warfare.deployment_recover", next);
            case SUSPENDED -> Outcome.pending(t("lease.suspended", "Recruits did not answer. The deployment is kept for a retry; nothing was assumed."), op).then("warfare.deployments", next);
        };
    }

    static Optional<Outcome> lease(Context c, String action, String operation) {
        List<Outcome> outcomes = new ArrayList<>();
        for (String id : operation.split(",")) {
            try { lease(c, UUID.fromString(id)).map(l -> lease(action, l)).ifPresent(outcomes::add); }
            catch (IllegalArgumentException malformed) { return Optional.empty(); }
        }
        return combine(outcomes, operation);
    }

    /** Several units at once: pending while any is pending, applied when all applied, refused when all were refused. */
    static Optional<Outcome> combine(List<Outcome> outcomes, String operation) {
        if (outcomes.isEmpty()) return Optional.empty();
        if (outcomes.size() == 1) return Optional.of(outcomes.get(0));
        var lines = new ArrayList<Object>();
        outcomes.forEach(o -> lines.add(o.message()));
        Outcome.State state = outcomes.stream().anyMatch(o -> o.state() == Outcome.State.PENDING) ? Outcome.State.PENDING
                : outcomes.stream().allMatch(o -> o.state() == Outcome.State.REFUSED) ? Outcome.State.REFUSED
                : outcomes.stream().anyMatch(o -> o.state() == Outcome.State.UNKNOWN) ? Outcome.State.UNKNOWN : Outcome.State.APPLIED;
        return Optional.of(new Outcome(state, lines, operation, outcomes.stream().filter(o -> o.state() == state).findFirst().flatMap(Outcome::next)));
    }

    /** Runs a muster and reads the leases it created; none created means the service refused before preparing one. */
    static Object muster(Context c, Supplier<Object> run) {
        Set<UUID> before = leases(c);
        Object said = run.get();
        List<String> created = new ArrayList<>();
        for (UUID id : leases(c)) if (!before.contains(id)) created.add(id.toString());
        if (created.isEmpty()) return Outcome.refused(said, "");
        return lease(c, "muster", String.join(",", created)).<Object>map(o -> o).orElse(said);
    }

    static Object leaseAction(Context c, String action, UUID id, Supplier<String> run) {
        String said = run.get();
        return lease(c, id).map(l -> lease(action, l)).<Object>map(o -> o).orElse(said);
    }

    // ------------------------------------------------------------------------------------------------ campaigns and accords

    private static Optional<CampaignState.Campaign> campaign(Context c, UUID id) {
        var service = CampaignService.get(c.server());
        return service.campaign(id).filter(v -> v.commander().equals(c.player.getUUID()) || c.player.hasPermissions(2)
                || service.campaigns(c.player, 0, 64).stream().anyMatch(x -> x.id().equals(id)));
    }

    static Outcome campaign(String action, CampaignState.Campaign v) {
        String op = v.id().toString();
        var next = prefill("campaign", op);
        return switch (v.phase()) {
            case NOTICE -> Outcome.applied(t("campaign.notice", "Declared and confirmed by the native save. The campaign is in its notice period; native siege detection governs any battle."), op);
            case ACTIVE, OCCUPIED -> Outcome.applied(t("campaign.active", "The campaign is active and confirmed by the native save; native siege detection governs any battle."), op);
            case SUSPENDED -> action.equals("withdraw")
                    ? Outcome.pending(t("campaign.withdraw_pending", "Withdrawal requested; the native confirmation is still pending. Withdraw again to retry."), op).then("warfare.withdraw", next)
                    : Outcome.pending(t("campaign.pending", "The campaign is recorded, but its native declaration is not confirmed yet. Resume the pending declaration to retry."), op).then("warfare.retry_campaign", next);
            case RESOLVED -> action.equals("withdraw")
                    ? Outcome.applied(t("campaign.withdrawn", "Withdrawn. Your faction's declaration is withdrawn; the other side's diplomacy stays its own."), op)
                    : Outcome.applied(t("campaign.resolved", "This campaign is closed: its settlement decision was ratified or it was withdrawn."), op);
            case EXPIRED -> Outcome.refused(t("campaign.expired", "This campaign has expired; nothing further happens."), op);
        };
    }

    static Optional<Outcome> campaign(Context c, String action, UUID id) {
        return campaign(c, id).map(v -> campaign(action, v));
    }

    static Object campaignAction(Context c, String action, UUID id, Supplier<String> run) {
        String said = run.get();
        return campaign(c, action, id).<Object>map(o -> o).orElse(said);
    }

    private static Optional<CampaignState.Accord> accord(Context c, UUID id) {
        var service = CampaignService.get(c.server());
        return service.accord(id).filter(a -> a.signatures().containsValue(c.player.getUUID()) || c.player.hasPermissions(2)
                || service.accords(c.player, 0, 64).stream().anyMatch(x -> x.id().equals(id)));
    }

    static Outcome accord(String action, CampaignState.Accord a) {
        String op = a.id().toString();
        var next = prefill("accord", op);
        return switch (a.phase()) {
            case PROPOSED -> Outcome.pending(t("accord.proposed", "Proposed. The other native leader and political ratifier must sign before anything changes (%s of 2 signatures).", a.signatures().size()), op).then("warfare.campaign_overview", prefill("settlement", a.settlement()));
            case SIGNED -> Outcome.pending(t("accord.signed", "Both sides signed; applying it to native diplomacy is still pending. Apply the control agreement."), op).then("warfare.accord_apply", next);
            case PENDING -> Outcome.pending(t("accord.pending", "Signed, but native diplomacy has not confirmed it, so peace is not operational yet. Apply it again once both leaders are online."), op).then("warfare.accord_apply", next);
            case ACTIVE -> Outcome.applied(t("accord.active", "Operational: both native directions are confirmed in the provider's save. Civic identity and ownership are unchanged."), op);
            case REJECTED -> action.equals("reject")
                    ? Outcome.applied(t("accord.rejected", "Rejected. Native relations were not rewritten."), op)
                    : Outcome.refused(t("accord.rejected_by_other", "This agreement was rejected; nothing changed."), op);
            case EXPIRED, BREACHED -> Outcome.refused(t("accord.closed", "This agreement expired or was breached; it is no longer in force."), op);
        };
    }

    static Optional<Outcome> accord(Context c, String action, UUID id) {
        return accord(c, id).map(a -> accord(action, a));
    }

    static Object accordAction(Context c, String action, UUID id, Supplier<String> run) {
        String said = run.get();
        return accord(c, action, id).<Object>map(o -> o).orElse(said);
    }

    // ------------------------------------------------------------------------------------------------ world events

    private static Optional<DramaState.Drama> drama(Context c, UUID id) {
        return DramaRuntime.get(c.server()).drama(id).filter(d -> d.participants().containsValue(c.player.getUUID()) || c.player.hasPermissions(2));
    }

    static Outcome drama(String action, DramaState.Drama d) {
        String op = d.id().toString();
        var next = prefill("drama", op);
        return switch (d.phase()) {
            case PROPOSED -> Outcome.pending(t("drama.proposed", "Proposed. Another participant must consent before anything happens."), op).then("drama.inspect", next);
            case READY -> Outcome.applied(t("drama.ready", "Consent recorded. The proposer may now carry out the agreed outcome."), op);
            case APPLYING, RECOVERING -> Outcome.pending(t("drama.applying", "The operation is recorded and waits for its provider. Recover it to retry."), op).then("drama.recover", next);
            case SUSPENDED -> Outcome.pending(t("drama.suspended", "The provider did not confirm the operation. It is kept for explicit recovery; nothing was assumed."), op).then("drama.recover", next);
            case NEGOTIATING -> Outcome.pending(t("drama.negotiating", "Terms proposed. Both native leaders must ratify them through campaign authority."), op).then("drama.inspect", next);
            case ACTIVE -> Outcome.applied(t("drama.active", "In progress: the provider confirmed the operation. Battle and control stay native-owned."), op);
            case RESOLVED -> Outcome.applied(t("drama.resolved", "Resolved and confirmed by its provider."), op);
            case EXITED -> Outcome.applied(t("drama.exited", "Exited without changing control, civic identity, buildings, assets or standing."), op);
            case EXPIRED -> action.equals("exit")
                    ? Outcome.applied(t("drama.expired", "This world event expired without changes."), op)
                    : Outcome.refused(t("drama.expired", "This world event expired without changes."), op);
        };
    }

    static Optional<Outcome> drama(Context c, String action, UUID id) {
        return drama(c, id).map(d -> drama(action, d));
    }

    static Object dramaAction(Context c, String action, UUID id, Supplier<String> run) {
        String said = run.get();
        return drama(c, action, id).<Object>map(o -> o).orElse(said);
    }

    // ------------------------------------------------------------------------------------------------ opportunities

    private static Optional<dev.otectus.mcakingdoms.evolution.EvolutionService.ScenarioView> scenario(Context c, UUID id) {
        return EvolutionRuntime.get(c.server()).scenario(c.player, id);
    }

    static Outcome scenario(String action, dev.otectus.mcakingdoms.evolution.EvolutionService.ScenarioView v) {
        String op = v.id().toString();
        var next = prefill("scenario", op);
        return switch (v.phase()) {
            case OPEN -> action.equals("release")
                    ? Outcome.applied(t("scenario.released", "Released: the uncommitted request was withdrawn, and the opportunity is open for a fresh choice."), op)
                    : Outcome.applied(t("scenario.open", "Recorded. No shared outcome has been committed yet."), op);
            case RESOLVING -> Outcome.pending(t("scenario.resolving", "The resolution is recorded and waits for the government's acknowledgment. Retry it to resume the same request."), op).then("evolution.retry", next);
            case RESOLVED -> Outcome.applied(t("scenario.resolved", "Resolved: the government accepted it and filed its record."), op);
            case DECLINED -> Outcome.applied(t("scenario.declined", "Declined peacefully."), op);
            case EXPIRED -> Outcome.refused(t("scenario.expired", "This opportunity expired peacefully; nothing further happens."), op);
        };
    }

    static Optional<Outcome> scenario(Context c, String action, UUID id) {
        return scenario(c, id).map(v -> scenario(action, v));
    }

    static Object scenarioAction(Context c, String action, UUID id, Supplier<String> run) {
        String said = run.get();
        return scenario(c, action, id).<Object>map(o -> o).orElse(said);
    }

    // ------------------------------------------------------------------------------------------------ political records

    /**
     * A committed political decision as it stands: a proposal awaiting a signature or a petition awaiting review is
     * recorded but not in effect, and says so; an agreement is in effect only once both governments signed it.
     */
    public static Outcome political(String action, String recordId, Optional<dev.otectus.mcakingdoms.politics.GovernmentService.RecordState> state) {
        if (state.isEmpty()) return Outcome.applied(t("politics.recorded", "Recorded: the political decision is saved and in effect."), recordId);
        var s = state.get();
        String kingdom = "";
        return switch (s.kind()) {
            case "agreement" -> switch (s.state()) {
                case "PROPOSED" -> action.equals("sign")
                        ? Outcome.pending(t("politics.signature_waiting", "Your government's signature is recorded. The agreement takes effect only when the other government signs it."), recordId).then("politics.read_agreements", Map.of())
                        : Outcome.pending(t("politics.proposal_saved", "Proposal saved. It takes effect only when the other government reviews and signs it."), recordId).then("politics.read_agreements", Map.of());
                case "ACTIVE" -> Outcome.applied(t("politics.agreement_active", "Signed by both governments and in effect."), recordId);
                case "DECLINED" -> action.equals("decline") ? Outcome.applied(t("politics.agreement_declined", "Declined; the agreement will not take effect."), recordId)
                        : Outcome.refused(t("politics.agreement_declined_by_other", "The other government declined this agreement; it is not in effect."), recordId);
                case "WITHDRAWN" -> action.equals("withdraw") ? Outcome.applied(t("politics.withdrawn", "Withdrawn; it will not take effect."), recordId)
                        : Outcome.refused(t("politics.agreement_withdrawn", "This agreement was withdrawn before it took effect."), recordId);
                case "EXPIRED" -> Outcome.refused(t("politics.agreement_expired", "This agreement expired and is no longer in effect."), recordId);
                default -> Outcome.applied(t("politics.agreement_ended", "This agreement has ended."), recordId);
            };
            case "petition" -> switch (s.state()) {
                case "SUBMITTED" -> Outcome.pending(t("politics.petition_submitted", "Submitted. It waits for the government's review; nothing is granted until it is approved."), recordId);
                case "UNDER_REVIEW" -> action.equals("review") ? Outcome.applied(t("politics.petition_reviewing", "Marked as under review. Approve or reject it to decide."), recordId)
                        : Outcome.pending(t("politics.petition_under_review", "Under review by the government; nothing is granted until it is approved."), recordId);
                case "APPROVED" -> Outcome.applied(t("politics.petition_approved", "Approved and in effect."), recordId);
                case "DECLINED" -> action.equals("reject") ? Outcome.applied(t("politics.petition_rejected", "Rejected; the requester sees your reason."), recordId)
                        : Outcome.refused(t("politics.petition_declined", "The government rejected this petition: %s", Keyed.component(s.detail())), recordId);
                case "WITHDRAWN" -> action.equals("withdraw") ? Outcome.applied(t("politics.withdrawn", "Withdrawn; it will not take effect."), recordId)
                        : Outcome.refused(t("politics.petition_withdrawn", "This petition was withdrawn."), recordId);
                default -> Outcome.refused(t("politics.petition_expired", "This petition expired before a decision."), recordId);
            };
            case "election" -> switch (s.state()) {
                case "OPEN" -> action.equals("election_vote")
                        ? Outcome.applied(t("politics.vote_recorded", "Your vote is recorded. You may change it while voting is open."), recordId)
                        : Outcome.applied(t("politics.election_open", "The election is open; eligible voters can vote until it closes."), recordId);
                case "GRACE" -> Outcome.pending(t("politics.election_grace", "No unique result yet: the election is in neutral grace, when votes may still change, until it closes."), recordId).then("politics.election_inspect", prefill("election", recordId));
                case "RESOLVED" -> Outcome.applied(t("politics.election_resolved", "Counted: the election has a winner, recorded in the government's history."), recordId);
                case "EXPIRED" -> Outcome.refused(t("politics.election_expired", "The election expired without a unique authorized result."), recordId);
                default -> Outcome.refused(t("politics.election_cancelled", "The election was cancelled."), recordId);
            };
            default -> Outcome.applied(t("politics.recorded", "Recorded: the political decision is saved and in effect."), recordId);
        };
    }

    static Optional<dev.otectus.mcakingdoms.politics.GovernmentService.RecordState> politicalState(Context c, String recordId) {
        var service = dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(c.server());
        return service instanceof dev.otectus.mcakingdoms.politics.GovernmentService government
                ? government.recordState(c.player, recordId) : Optional.empty();
    }
}
