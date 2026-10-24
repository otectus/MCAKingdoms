package dev.otectus.mcakingdoms.evolution;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.otectus.mcakingdoms.evolution.EvolutionState.*;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionStateTest {
    /** An evaluation that only moved the schedule is kept in memory instead of written durably (K-11). */
    @Test void onlyScheduleFieldsAreIgnoredWhenDecidingWhetherToCommit() {
        var before = new EvolutionState(); before.enabled = true; before.nextEvaluation = 100; before.cursor = 3;
        var after = EvolutionSavedData.JSON.fromJson(EvolutionSavedData.JSON.toJson(before), EvolutionState.class);
        after.nextEvaluation = 1300; after.lastEvaluation = 100; after.cursor = 4; after.clock = 100;
        assertTrue(EvolutionSavedData.sameApartFromSchedule(before, after));
        after.activeRegions.add(UUID.randomUUID());
        assertFalse(EvolutionSavedData.sameApartFromSchedule(before, after), "a region entering the sample is a change");
        after.activeRegions.clear(); after.digest.put(UUID.randomUUID(), 100L);
        assertFalse(EvolutionSavedData.sameApartFromSchedule(before, after), "a digest delivery is a change");
    }
    private Scenario scenario() {
        return new Scenario(UUID.randomUUID(), "mcakingdoms:test", new Template(1, "Aid", Trigger.DEMAND,
                List.of(Outcome.AID, Outcome.MEDIATE, Outcome.DECLINE), 1200, 2400, 1, false), UUID.randomUUID(),
                null, "mcakingdoms:a", new Evidence("owner", "receipt", "DEMAND", 10, "Verified demand"), null,
                10, 1210, 1, Phase.OPEN, Map.of(), null, "");
    }
    @Test void opposingChoicesHaveOneTerminalResolution() {
        var first = UUID.randomUUID(); var second = UUID.randomUUID(); var s = scenario();
        s = s.contribute(new Contribution(first, Outcome.AID, new Evidence("quests", "a", "RELIEF", 20, "Aid")), 1, 20);
        s = s.contribute(new Contribution(second, Outcome.MEDIATE, new Evidence("quests", "b", "MEDIATION", 20, "Mediation")), 2, 20);
        var pending = s.reserve(new Intent(first, Outcome.AID, UUID.randomUUID(), 4, ""), 3, 30);
        assertThrows(IllegalArgumentException.class, () -> pending.reserve(new Intent(second, Outcome.MEDIATE, UUID.randomUUID(), 4, ""), 3, 30));
        var done = pending.finish("owner-receipt");
        assertEquals(Outcome.AID, done.intent().outcome()); assertEquals(Phase.RESOLVED, done.phase());
        assertThrows(IllegalArgumentException.class, () -> done.finish("other-receipt"));
    }
    @Test void withdrawalAndNeutralDeclinePreserveContributionProvenance() {
        UUID player = UUID.randomUUID(); var e = scenario();
        var contributed = e.contribute(new Contribution(player, Outcome.AID, e.cause()), 1, 20);
        var withdrawn = contributed.withdraw(player, 2, 21);
        assertTrue(withdrawn.contributions().isEmpty());
        var declined = withdrawn.reserve(new Intent(player, Outcome.DECLINE, UUID.randomUUID(), 0, ""), 3, 22).finish("Declined");
        assertEquals(Phase.DECLINED, declined.phase());
        assertThrows(IllegalArgumentException.class, () -> e.reserve(new Intent(player, Outcome.AID, UUID.randomUUID(), 0, ""), 1, 22));
    }
    @Test void pendingResolutionSurvivesDeadlineAndRestartWithoutReroll() {
        var e = scenario(); var player = UUID.randomUUID();
        e = e.contribute(new Contribution(player, Outcome.AID, e.cause()), 1, 20)
                .reserve(new Intent(player, Outcome.AID, UUID.randomUUID(), 9, ""), 2, 21);
        var s = new EvolutionState(); s.scenarios.put(e.id(), e); s.enabled = true;
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", EvolutionSavedData.JSON.toJson(s));
        var data = EvolutionSavedData.load(tag); assertTrue(data.writable());
        var restored = data.snapshot().scenarios.get(e.id()); assertEquals(e, restored);
        assertEquals(restored, restored.expire(2000));
        assertEquals(Phase.EXPIRED, scenario().expire(2000).phase());
    }
    @Test void unknownAndMalformedSavesStayReadOnlyAndIntact() {
        for (String payload : List.of("{}", "null", "{\"revision\":0}", "not-json")) {
            var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload);
            var data = EvolutionSavedData.load(tag); assertFalse(data.writable()); assertEquals(tag, data.save(new CompoundTag()));
        }
        var tag = new CompoundTag(); tag.putInt("Schema", 7); tag.putString("Payload", "future");
        assertEquals(tag, EvolutionSavedData.load(tag).save(new CompoundTag()));
    }
    @Test void invalidTermsCannotRemoveNeutralExitOrExceedBudget() {
        assertThrows(IllegalArgumentException.class, () -> new Template(1, "Bad", Trigger.GRIEVANCE, List.of(Outcome.AID, Outcome.MEDIATE), 1200, 1200, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Template(1, "Bad", Trigger.GRIEVANCE, List.of(Outcome.AID, Outcome.DECLINE), 1200, 1200, 65, true));
    }
    @Test void inactiveTimeExtendsOnlyOpenDeadlinesAndRetainsEvidence() {
        var original=scenario();var paused=original.pause(24000);
        assertEquals(original.deadline()+24000,paused.deadline());
        assertEquals(original.cause(),paused.cause());
        assertEquals(Phase.OPEN,paused.expire(24010).phase());
        var done=original.reserve(new Intent(UUID.randomUUID(),Outcome.DECLINE,UUID.randomUUID(),0,""),1,20).finish("Declined");
        assertEquals(done,done.pause(24000));
    }
    @Test void mergeMovesRegionsScenariosAndKeepsTheLaterCooldown() {
        UUID retired = UUID.randomUUID(), survivor = UUID.randomUUID();
        var e = scenario(); var at = new Scenario(e.id(), e.templateId(), e.terms(), retired, e.institution(), e.kingdom(), e.cause(),
                e.audience(), e.created(), e.deadline(), e.revision(), e.phase(), e.contributions(), e.intent(), e.result());
        var s = new EvolutionState(); s.scenarios.put(at.id(), at);
        s.eligible.add(retired); s.activeRegions.add(retired); s.paused.add(survivor);
        s.cooldowns.put(retired + ":mcakingdoms:aid", 100L); s.cooldowns.put(survivor + ":mcakingdoms:aid", 300L);
        s.cooldowns.put(retired + ":mcakingdoms:grievance", 50L); s.cooldowns.put("someone:family", 70L);
        assertTrue(s.redirectSettlement(retired, survivor));
        s.validate();
        assertEquals(survivor, s.scenarios.get(at.id()).settlement());
        assertEquals(at.revision(), s.scenarios.get(at.id()).revision());
        assertEquals(Set.of(survivor), s.eligible); assertEquals(Set.of(survivor), s.activeRegions); assertEquals(Set.of(survivor), s.paused);
        assertEquals(300L, s.cooldowns.get(survivor + ":mcakingdoms:aid"));
        assertEquals(50L, s.cooldowns.get(survivor + ":mcakingdoms:grievance"));
        assertEquals(70L, s.cooldowns.get("someone:family"));
        assertTrue(s.cooldowns.keySet().stream().noneMatch(key -> key.startsWith(retired.toString())));
    }
}
