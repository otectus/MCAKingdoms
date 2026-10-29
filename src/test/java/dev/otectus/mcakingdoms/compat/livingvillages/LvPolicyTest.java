package dev.otectus.mcakingdoms.compat.livingvillages;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The bridge's decisions as pure functions: war preflight, agreement precedence, AI answers, coalitions and realm identity. */
class LvPolicyTest {
    private static final LvPolicy.Rules RULES = new LvPolicy.Rules(true, true, true, true, LvPolicy.PlayerTargets.PROVIDER_AND_KINGDOMS,
            LvPolicy.Capitals.ALLOW_UNLESS_PROTECTED, true, true);
    private static final LvPolicy.Side AI_A = new LvPolicy.Side(UUID.randomUUID(), false, false);
    private static final LvPolicy.Side AI_B = new LvPolicy.Side(UUID.randomUUID(), false, false);

    private static LvPolicy.Attack attack(LvPolicy.Side attacker, LvPolicy.Side defender) {
        return new LvPolicy.Attack(attacker, defender, true, false, false, false, false, false, false, true, false, true);
    }

    @Test
    void aiAgainstAiNeedsNoPlayerAndNoFakeLeader() {
        assertTrue(LvPolicy.war(RULES, attack(AI_A, AI_B)).allowed(), "T13: an ordinary AI war needs no connected human leader");
    }

    @Test
    void anOrdinaryAiCapitalCanBeAttackedButAProtectedSeatCannot() {
        var capital = new LvPolicy.Attack(AI_A, AI_B, true, false, true, false, false, false, false, true, false, true);
        assertTrue(LvPolicy.war(RULES, capital).allowed(), "imported AI capitals are not invulnerable by default");
        var seat = new LvPolicy.Attack(AI_A, AI_B, true, false, true, true, false, false, false, true, false, true);
        var refused = LvPolicy.war(RULES, seat);
        assertFalse(refused.allowed());
        assertEquals("kingdom_capital", refused.code());
        assertEquals(LvPolicy.Source.KINGDOMS, refused.source(), "the refusal says Kingdoms refused it");
        var protectAll = new LvPolicy.Rules(true, true, true, true, LvPolicy.PlayerTargets.PROVIDER_AND_KINGDOMS, LvPolicy.Capitals.PROTECT_ALL, true, true);
        assertEquals("realm_capital", LvPolicy.war(protectAll, capital).code());
    }

    @Test
    void playerLedTargetsKeepOfflineProtectionUnlessTheProviderAloneDecides() {
        var offline = new LvPolicy.Side(UUID.randomUUID(), true, false);
        assertEquals("defender_offline", LvPolicy.war(RULES, attack(AI_A, offline)).code(), "T12: offline player-led target");
        assertTrue(LvPolicy.war(RULES, attack(AI_A, new LvPolicy.Side(offline.realm(), true, true))).allowed());
        var providerOnly = new LvPolicy.Rules(true, true, true, true, LvPolicy.PlayerTargets.PROVIDER_ONLY, LvPolicy.Capitals.ALLOW_UNLESS_PROTECTED, true, true);
        assertTrue(LvPolicy.war(providerOnly, attack(AI_A, offline)).allowed());
    }

    @Test
    void agreementsTrucesSwitchesAndVirtualCapitalsRefuse() {
        var treaty = new LvPolicy.Attack(AI_A, AI_B, true, false, false, false, true, false, false, true, false, true);
        assertEquals("treaty", LvPolicy.war(RULES, treaty).code());
        var truce = new LvPolicy.Attack(AI_A, AI_B, true, false, false, false, false, true, false, true, false, true);
        assertEquals("truce", LvPolicy.war(RULES, truce).code());
        var virtual = new LvPolicy.Attack(AI_A, AI_B, true, true, false, false, false, false, false, true, false, true);
        assertEquals("virtual_capital", LvPolicy.war(RULES, virtual).code(), "the provider's own exclusion stays");
        var off = new LvPolicy.Rules(true, false, true, true, LvPolicy.PlayerTargets.PROVIDER_AND_KINGDOMS, LvPolicy.Capitals.ALLOW_UNLESS_PROTECTED, true, true);
        assertEquals("warfare_off", LvPolicy.war(off, attack(AI_A, AI_B)).code());
        var noAi = new LvPolicy.Rules(true, true, true, false, LvPolicy.PlayerTargets.PROVIDER_AND_KINGDOMS, LvPolicy.Capitals.ALLOW_UNLESS_PROTECTED, true, true);
        assertEquals("ai_wars_off", LvPolicy.war(noAi, attack(AI_A, AI_B)).code());
        var readOnly = new LvPolicy.Rules(false, true, true, true, LvPolicy.PlayerTargets.PROVIDER_AND_KINGDOMS, LvPolicy.Capitals.ALLOW_UNLESS_PROTECTED, true, true);
        assertEquals("store_read_only", LvPolicy.war(readOnly, attack(AI_A, AI_B)).code(), "fails closed when records cannot be kept");
    }

    @Test
    void unboundTargetsKeepNativeBehaviour() {
        var unbound = new LvPolicy.Attack(AI_A, AI_B, false, false, true, true, true, true, false, true, false, true);
        assertTrue(LvPolicy.war(RULES, unbound).allowed(), "claims outside the integration keep the provider's behaviour");
    }

    @Test
    void aPlayerRequestNeedsCommandAndAnyMandate() {
        var member = new LvPolicy.Attack(AI_A, AI_B, true, false, false, false, false, false, true, false, false, true);
        assertEquals("not_commander", LvPolicy.war(RULES, member).code(), "T18: a member is not the commander");
        var noMandate = new LvPolicy.Attack(AI_A, AI_B, true, false, false, false, false, false, true, true, true, false);
        assertEquals("mandate", LvPolicy.war(RULES, noMandate).code());
        var both = new LvPolicy.Attack(AI_A, AI_B, true, false, false, false, false, false, true, true, true, true);
        assertTrue(LvPolicy.war(RULES, both).allowed());
    }

    @Test
    void peaceForbidsEnmityAndAnAllianceRequiresAlliance() {
        assertFalse(LvPolicy.permits(List.of(LvState.Term.PEACE), LvPolicy.ENEMY), "T16: periodic recomputation cannot overturn a peace");
        assertTrue(LvPolicy.permits(List.of(LvState.Term.PEACE), LvPolicy.NEUTRAL));
        assertTrue(LvPolicy.permits(List.of(LvState.Term.PEACE), LvPolicy.ALLY));
        assertFalse(LvPolicy.permits(List.of(LvState.Term.ALLIANCE), LvPolicy.NEUTRAL));
        assertTrue(LvPolicy.permits(List.of(), LvPolicy.ENEMY), "no agreement, no constraint");
        assertEquals(OptionalInt.of(LvPolicy.NEUTRAL), LvPolicy.required(List.of(LvState.Term.CEASEFIRE), LvPolicy.ENEMY));
        assertEquals(OptionalInt.empty(), LvPolicy.required(List.of(LvState.Term.CEASEFIRE), LvPolicy.NEUTRAL));
        assertEquals(OptionalInt.of(LvPolicy.ALLY), LvPolicy.required(List.of(LvState.Term.ALLIANCE), LvPolicy.NEUTRAL));
    }

    @Test
    void anAiAnswerIsDeterministicFromItsFacts() {
        var losing = new LvPolicy.Proposal(LvState.Term.PEACE, true, -30, 0.5, 0, false, 60, -60);
        assertTrue(LvPolicy.consider(losing).accept());
        assertEquals(LvPolicy.consider(losing), LvPolicy.consider(losing), "asking again with the same facts gives the same answer");
        var winning = new LvPolicy.Proposal(LvState.Term.PEACE, true, -30, 2.0, 0, false, 60, -60);
        assertEquals(new LvPolicy.Answer(false, "winning"), LvPolicy.consider(winning));
        var bloodied = new LvPolicy.Proposal(LvState.Term.CEASEFIRE, true, -10, 1.0, 2, false, 60, -60);
        assertEquals("losses", LvPolicy.consider(bloodied).code());
        assertFalse(LvPolicy.consider(new LvPolicy.Proposal(LvState.Term.ALLIANCE, false, 10, 1.0, 0, false, 60, -60)).accept());
        assertTrue(LvPolicy.consider(new LvPolicy.Proposal(LvState.Term.ALLIANCE, false, 70, 1.0, 0, false, 60, -60)).accept());
        assertTrue(LvPolicy.consider(new LvPolicy.Proposal(LvState.Term.CEDE, false, 0, 1.0, 0, true, 60, -60)).accept(), "a realm accepts recognition of what it holds");
        assertFalse(LvPolicy.consider(new LvPolicy.Proposal(LvState.Term.CEDE, false, 0, 1.0, 0, false, 60, -60)).accept());
    }

    @Test
    void aVillageAlliedToBothSidesSitsTheWarOut() {
        UUID attacker = UUID.randomUUID(), defender = UUID.randomUUID(), both = UUID.randomUUID(), ally = UUID.randomUUID(), friend = UUID.randomUUID();
        var sides = LvPolicy.reconcile(Set.of(attacker, both, ally), Set.of(defender, both, friend, attacker), attacker, defender);
        assertEquals(Set.of(attacker, ally), sides.attackers(), "T11: the principal attacker keeps its side");
        assertEquals(Set.of(defender, friend), sides.defenders());
        assertEquals(Set.of(both), sides.neutral());
    }

    private static LvState.Realm realm(UUID id, UUID capital, List<UUID> members, String team) {
        return new LvState.Realm(id, "R", capital, members, List.of(team), LvState.Leadership.AI, null, LvState.RealmStatus.ACTIVE, 0, 0, List.of());
    }

    @Test
    void aRealmKeepsItsIdentityWhenItsCapitalFalls() {
        UUID realmId = UUID.randomUUID(), oldCapital = UUID.randomUUID(), survivor = UUID.randomUUID(), other = UUID.randomUUID();
        var before = List.of(realm(realmId, oldCapital, List.of(oldCapital, survivor, other), "lunari_a"));
        // The old capital was conquered; the survivors keep the team and pick a new capital (T20).
        var now = List.of(new LvPolicy.Capital(survivor, "lunari_a", Set.of(survivor, other), false));
        var assigned = LvPolicy.assign(before, now, UUID::randomUUID);
        assertEquals(realmId, assigned.get(survivor), "the surviving realm keeps its id under its new capital");
    }

    @Test
    void sameCultureRealmsStaySeparateAndNewFactionsGetNewIds() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), capitalA = UUID.randomUUID(), capitalB = UUID.randomUUID(), fresh = UUID.randomUUID();
        var before = List.of(realm(a, capitalA, List.of(capitalA), "lunari_a"), realm(b, capitalB, List.of(capitalB), "lunari_b"));
        var now = List.of(new LvPolicy.Capital(capitalA, "lunari_a", Set.of(capitalA), false), new LvPolicy.Capital(capitalB, "lunari_b", Set.of(capitalB), false),
                new LvPolicy.Capital(fresh, "serenum_c", Set.of(fresh), false));
        var ids = new ArrayDeque<>(List.of(UUID.randomUUID()));
        var assigned = LvPolicy.assign(before, now, ids::pop);
        assertEquals(a, assigned.get(capitalA));
        assertEquals(b, assigned.get(capitalB), "T04: two independent factions are two realms, whatever culture they share");
        assertNotEquals(a, assigned.get(fresh));
        assertNotEquals(b, assigned.get(fresh));
    }

    @Test
    void aConqueredCapitalJoinsTheWinnerWithoutTakingItsOldRealmAlong() {
        UUID winner = UUID.randomUUID(), loser = UUID.randomUUID(), winnerCapital = UUID.randomUUID(), loserCapital = UUID.randomUUID();
        var before = List.of(realm(winner, winnerCapital, List.of(winnerCapital), "a"), realm(loser, loserCapital, List.of(loserCapital), "b"));
        var now = List.of(new LvPolicy.Capital(winnerCapital, "a", Set.of(winnerCapital, loserCapital), false));
        var assigned = LvPolicy.assign(before, now, UUID::randomUUID);
        assertEquals(Map.of(winnerCapital, winner), assigned, "the defeated realm is not reassigned; it is retired by the caller");
    }
}
