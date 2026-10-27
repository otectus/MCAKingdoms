package dev.otectus.mcakingdoms.evolution;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static dev.otectus.mcakingdoms.evolution.EvolutionState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F12: capacity relief only ever touches records proven finished. Unfinished, diverged and reconciled transfers stay; open
 * or resolving opportunities stay; used evidence is never part of a compaction, so no receipt or cause can count twice.
 */
class CompactionTest {
    private static RecruitTransferData.Transfer transfer(RecruitTransferData.Phase phase, long deadline, boolean diverged) {
        var t = new RecruitTransferData.Transfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "c".repeat(64), false, deadline - 100, deadline, 1, phase, null, "fixture", diverged);
        return t;
    }

    @Test
    void onlyFinishedUndivergedTransfersPastRetentionAreArchivable() {
        long deadline = 10_000;
        var complete = transfer(RecruitTransferData.Phase.CANCELLED, deadline, false);
        var expired = transfer(RecruitTransferData.Phase.EXPIRED, deadline, false);
        var recent = transfer(RecruitTransferData.Phase.CANCELLED, deadline + 50_000, false);
        var open = transfer(RecruitTransferData.Phase.OFFERED, deadline, false);
        var tag = new CompoundTag();
        tag.putInt("Schema", 1);
        tag.putString("Transfers", RecruitTransferData.JSON.toJson(List.of(complete, expired, recent, open)));
        var data = RecruitTransferData.load(tag);
        assertTrue(data.writable());
        long now = deadline + RecruitTransferData.ARCHIVE_AFTER;
        var archivable = data.archivable(now).stream().map(RecruitTransferData.Transfer::id).toList();
        assertEquals(Set.of(complete.id(), expired.id()), Set.copyOf(archivable));
        assertFalse(archivable.contains(open.id()), "an open offer keeps blocking its recruit until it expires");
        assertFalse(archivable.contains(recent.id()), "finished transfers are kept for the retention period");
        assertTrue(data.archivable(now - 1).isEmpty(), "nothing is archived before the retention period");
    }

    private static Scenario scenario(Phase phase, long deadline) {
        var player = UUID.randomUUID();
        var e = new Scenario(UUID.randomUUID(), "mcakingdoms:test", new Template(1, "Aid", Trigger.DEMAND, List.of(Outcome.AID, Outcome.DECLINE),
                1200, 2400, 1, false), UUID.randomUUID(), null, "mcakingdoms:a", new Evidence("owner", "r" + UUID.randomUUID(), "DEMAND", 1, "Demand"),
                null, deadline - 1200, deadline, 1, Phase.OPEN, Map.of(), null, "");
        if (phase == Phase.OPEN) return e;
        var resolving = e.contribute(new Contribution(player, Outcome.AID, new Evidence("q", "x" + UUID.randomUUID(), "AID", 1, "Aid")), 1, deadline - 1100)
                .reserve(new Intent(player, Outcome.AID, UUID.randomUUID(), 0, ""), 2, deadline - 1000);
        return phase == Phase.RESOLVING ? resolving : resolving.finish("Resolved");
    }

    @Test
    void onlyFinishedOpportunitiesLapsedCooldownsAndIdleDigestsAreCompacted() {
        long deadline = 50_000;
        var state = new EvolutionState();
        var finished = scenario(Phase.RESOLVED, deadline);
        var resolving = scenario(Phase.RESOLVING, deadline);
        var open = scenario(Phase.OPEN, deadline);
        for (var e : List.of(finished, resolving, open)) state.scenarios.put(e.id(), e);
        state.cooldowns.put("lapsed", deadline);
        state.cooldowns.put("running", deadline + 1_000_000);
        UUID subscriber = UUID.randomUUID(), former = UUID.randomUUID();
        state.subscriptions.add(subscriber);
        state.digest.put(subscriber, 1L);
        state.digest.put(former, 1L);
        state.consumed.add("cause:abc");
        var tag = new CompoundTag();
        tag.putInt("Schema", 1);
        tag.putString("Payload", EvolutionSavedData.JSON.toJson(state));
        var data = EvolutionSavedData.load(tag);
        assertTrue(data.writable());

        var plan = data.compactable(deadline + EvolutionSavedData.ARCHIVE_AFTER);
        assertEquals(List.of(finished.id()), plan.scenarios().stream().map(Scenario::id).toList(), "only the finished opportunity");
        assertEquals(List.of("lapsed"), plan.lapsedCooldowns());
        assertEquals(List.of(former), plan.idleDigests(), "a subscriber's digest schedule is kept");
        assertTrue(data.compactable(deadline).scenarios().isEmpty(), "finished opportunities are kept for the retention period");
    }
}
