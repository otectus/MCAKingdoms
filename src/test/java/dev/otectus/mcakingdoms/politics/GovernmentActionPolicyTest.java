package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.Politics.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Predicate;

import static dev.otectus.mcakingdoms.politics.GovernmentActionPolicy.Target;
import static org.junit.jupiter.api.Assertions.*;

/**
 * H02: the page, the Kingdom Tasks previews and the mutation share one policy. An elected leader's office is protected
 * whatever the number of other offices; every other office stays removable by an appointing official.
 */
class GovernmentActionPolicyTest {
    private static final String LEADER = GovernmentActionPolicy.LEADER;
    private static final UUID RULER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MINISTER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final Definition OFFICE = new Definition(1, "office", "politics.mcakingdoms.office", List.of(),
            Set.of(Permission.APPOINT, Permission.DELEGATE), List.of(), Set.of(), 0, 1, false, "");
    private static final Definition CONSTITUTION = new Definition(1, "government", "politics.mcakingdoms.charter",
            List.of(LEADER, "mcakingdoms:chancellor", "mcakingdoms:envoy", "mcakingdoms:herald", "mcakingdoms:keeper_of_records"),
            Set.of(), List.of(), Set.of(), 0, 4, true, "");

    private static Government government(State state, int offices, Person successor) {
        Map<String, Office> held = new LinkedHashMap<>();
        if (state == State.ACTIVE) held.put(LEADER, new Office(LEADER, OFFICE, new Person(RULER, Kind.PLAYER), null, RULER, 0));
        for (String extra : CONSTITUTION.offices().subList(1, CONSTITUTION.offices().size())) {
            if (held.size() >= offices) break;
            held.put(extra, new Office(extra, OFFICE, new Person(MINISTER, Kind.PLAYER), null, RULER, 0));
        }
        return new Government("mcakingdoms:serenum", UUID.randomUUID(), "mcakingdoms:serenum_charter", CONSTITUTION, state, held, Map.of(), successor, 1);
    }

    private static final Sovereignty CAPITALS = new Sovereignty(Sovereignty.Mode.CAPITALS, "politics.mcakingdoms.sovereignty.capitals_managed");

    private static GovernmentActionPolicy.Facts facts(UUID actor, Government g, UUID elected, Predicate<Permission> holds) {
        return new GovernmentActionPolicy.Facts(actor, false, true, Sovereignty.NATIVE, g, true, elected, false, holds, false, false, false);
    }

    private static Predicate<Permission> appointer() { return permission -> permission == Permission.APPOINT || permission == Permission.DELEGATE; }

    @Test
    void electedLeaderIsProtectedWithOneTwoOrFiveOffices() {
        for (int offices : List.of(1, 2, 5)) {
            Government g = government(State.ACTIVE, offices, null);
            var f = facts(RULER, g, RULER, appointer());
            assertEquals(Optional.of("politics.mcakingdoms.refusal.elected_leader"), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.office(LEADER)), offices + " offices");
            if (offices == 1) assertEquals(Optional.of("politics.mcakingdoms.refusal.elected_leader"), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.ANY));
            else {
                // Generically available, because another office may be vacated; the leader's own office stays refused.
                assertEquals(Optional.empty(), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.ANY), offices + " offices");
                assertEquals(Optional.empty(), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.office("mcakingdoms:chancellor")));
            }
            assertThrows(IllegalArgumentException.class, () -> GovernmentActionPolicy.require(f, Action.REMOVE_OFFICE, Target.office(LEADER)));
        }
    }

    @Test
    void appointedLeaderAndNpcOfficesCanBeRemoved() {
        Government g = government(State.ACTIVE, 3, null);
        var f = facts(RULER, g, null, appointer());
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.office(LEADER)));
        Map<String, Office> offices = new LinkedHashMap<>(g.offices());
        offices.put("mcakingdoms:herald", new Office("mcakingdoms:herald", OFFICE, new Person(UUID.randomUUID(), Kind.NPC), null, RULER, 0));
        Government withNpc = new Government(g.kingdom(), g.capital(), g.profileId(), g.constitution(), g.state(), offices, Map.of(), null, 2);
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(facts(RULER, withNpc, RULER, appointer()), Action.REMOVE_OFFICE, Target.office("mcakingdoms:herald")));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.office_vacant"), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.office("mcakingdoms:envoy@" + UUID.randomUUID())));
    }

    @Test
    void electedLeaderMayStillAbdicateButNobodyElseMay() {
        Government g = government(State.ACTIVE, 2, null);
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(facts(RULER, g, RULER, appointer()), Action.ABDICATE, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.abdicate_holder"), GovernmentActionPolicy.denial(facts(MINISTER, g, RULER, appointer()), Action.ABDICATE, Target.ANY));
    }

    @Test
    void unauthorizedViewerGetsThePermissionKey() {
        Government g = government(State.ACTIVE, 2, null);
        var f = facts(STRANGER, g, null, permission -> false);
        assertEquals(Optional.of("politics.mcakingdoms.refusal.requires.appoint"), GovernmentActionPolicy.denial(f, Action.REMOVE_OFFICE, Target.office("mcakingdoms:chancellor")));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.requires.ratify"), GovernmentActionPolicy.denial(f, Action.SIGN, Target.ANY));
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(f, Action.PETITION, Target.ANY));
    }

    @Test
    void successionRulesAgreeWithTheMutation() {
        Person heir = new Person(MINISTER, Kind.PLAYER);
        Government vacant = government(State.INTERREGNUM, 2, heir);
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(facts(MINISTER, vacant, null, p -> false), Action.SUCCEED, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.succeed_actor"), GovernmentActionPolicy.denial(facts(STRANGER, vacant, null, p -> false), Action.SUCCEED, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.no_succession"), GovernmentActionPolicy.denial(facts(MINISTER, government(State.ACTIVE, 2, heir), null, p -> false), Action.SUCCEED, Target.ANY));
        Government named = government(State.ACTIVE, 2, heir);
        assertEquals(Optional.of("politics.mcakingdoms.refusal.elected_successor"), GovernmentActionPolicy.denial(facts(RULER, named, MINISTER, appointer()), Action.NAME_SUCCESSOR, Target.ANY));
        var regency = new GovernmentActionPolicy.Facts(RULER, false, true, Sovereignty.NATIVE, named, true, null, true, appointer(), false, false, false);
        assertEquals(Optional.of("politics.mcakingdoms.refusal.successor_frozen"), GovernmentActionPolicy.denial(regency, Action.NAME_SUCCESSOR, Target.ANY));
    }

    @Test
    void vacantLeadershipIsFilledByAnOperatorOnly() {
        Government vacant = government(State.INTERREGNUM, 2, null);
        var operator = new GovernmentActionPolicy.Facts(STRANGER, true, true, Sovereignty.NATIVE, vacant, true, null, false, p -> false, false, false, false);
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(operator, Action.APPOINT, Target.office(LEADER)));
        // An operator gains no other appointment power during the vacancy.
        assertEquals(Optional.of("politics.mcakingdoms.refusal.requires.appoint"), GovernmentActionPolicy.denial(operator, Action.APPOINT, Target.office("mcakingdoms:herald")));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.leader_operator"), GovernmentActionPolicy.denial(facts(MINISTER, vacant, null, appointer()), Action.APPOINT, Target.office(LEADER)));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.office_occupied"), GovernmentActionPolicy.denial(facts(RULER, government(State.ACTIVE, 2, null), null, appointer()), Action.APPOINT, Target.office(LEADER)));
    }

    @Test
    void storeStateAndOtherOwnersComeFirst() {
        Government g = government(State.ACTIVE, 2, null);
        var readOnly = new GovernmentActionPolicy.Facts(RULER, true, false, Sovereignty.NATIVE, g, true, null, false, appointer(), false, false, false);
        var elsewhere = new GovernmentActionPolicy.Facts(RULER, true, true, CAPITALS, g, true, null, false, appointer(), false, false, false);
        for (Action action : Action.values()) {
            assertEquals(Optional.of("politics.mcakingdoms.refusal.records_read_only"), GovernmentActionPolicy.denial(readOnly, action, Target.ANY), action.name());
            var denial = GovernmentActionPolicy.denial(elsewhere, action, Target.ANY);
            // C2: only crown actions defer to MCA Capitals; civic actions follow the usual rules.
            if (Sovereignty.crown(action, null)) assertEquals(Optional.of("politics.mcakingdoms.refusal.capitals_managed"), denial, action.name());
            else assertNotEquals(Optional.of("politics.mcakingdoms.refusal.capitals_managed"), denial, action.name());
        }
        assertEquals(Optional.of("politics.mcakingdoms.refusal.capitals_managed"), GovernmentActionPolicy.denial(elsewhere, Action.REMOVE_OFFICE, Target.office(LEADER)));
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(elsewhere, Action.REMOVE_OFFICE, Target.office("mcakingdoms:chancellor")));
        var unmapped = new GovernmentActionPolicy.Facts(RULER, true, true, new Sovereignty(Sovereignty.Mode.UNMAPPED, ""), g, true, null, false, appointer(), false, false, false);
        assertEquals(Optional.of("politics.mcakingdoms.refusal.capitals_unmapped"), GovernmentActionPolicy.denial(unmapped, Action.ABDICATE, Target.ANY));
        var dormant = new GovernmentActionPolicy.Facts(RULER, true, true, Sovereignty.NATIVE, g, false, null, false, appointer(), false, false, false);
        assertEquals(Optional.of("politics.mcakingdoms.refusal.dormant"), GovernmentActionPolicy.denial(dormant, Action.REMOVE_OFFICE, Target.ANY));
    }

    @Test
    void onlyTheLeaderDelegates() {
        Government g = government(State.ACTIVE, 2, null);
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(facts(RULER, g, null, appointer()), Action.DELEGATE, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.delegate_leader"), GovernmentActionPolicy.denial(facts(MINISTER, g, null, appointer()), Action.DELEGATE, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.requires.delegate"), GovernmentActionPolicy.denial(facts(STRANGER, g, null, p -> false), Action.DELEGATE, Target.ANY));
    }

    @Test
    void foundingFollowsTheResidentRule() {
        var open = new GovernmentActionPolicy.Facts(STRANGER, false, true, Sovereignty.NATIVE, null, false, null, false, p -> false, false, true, true);
        var notResident = new GovernmentActionPolicy.Facts(STRANGER, false, true, Sovereignty.NATIVE, null, false, null, false, p -> false, false, true, false);
        var closed = new GovernmentActionPolicy.Facts(STRANGER, false, true, Sovereignty.NATIVE, null, false, null, false, p -> false, false, false, true);
        assertEquals(Optional.empty(), GovernmentActionPolicy.denial(open, Action.BOOTSTRAP, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.founding_resident"), GovernmentActionPolicy.denial(notResident, Action.BOOTSTRAP, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.founding_closed"), GovernmentActionPolicy.denial(closed, Action.BOOTSTRAP, Target.ANY));
        assertEquals(Optional.of("politics.mcakingdoms.refusal.government_exists"),
                GovernmentActionPolicy.denial(new GovernmentActionPolicy.Facts(STRANGER, true, true, Sovereignty.NATIVE, government(State.ACTIVE, 1, null), true, null, false, p -> false, false, true, true), Action.BOOTSTRAP, Target.ANY));
    }
}
