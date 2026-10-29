package dev.otectus.mcakingdoms.compat.livingvillages;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The bridge document: it round-trips, refuses impossible identities, compacts only finished records and copies safely. */
class LvStateTest {
    private static LvState populated() {
        var state = new LvState();
        state.provenance.world = UUID.randomUUID();
        UUID village = UUID.randomUUID(), settlement = UUID.randomUUID(), realm = UUID.randomUUID();
        state.bindings.put(village, new LvState.Binding(village, settlement, "minecraft:overworld", LvState.BindingStatus.BOUND, "Ashford",
                List.of("Old Ashford"), UUID.randomUUID(), List.of(), realm, realm, false, 1, 2, "imported", List.of()));
        state.realms.put(realm, new LvState.Realm(realm, "Ashford", village, List.of(village), List.of("ashford"), LvState.Leadership.AI, null,
                LvState.RealmStatus.ACTIVE, 1, 2, List.of()));
        var conflict = new LvState.Conflict(UUID.randomUUID(), realm, UUID.randomUUID(), village, realm, settlement, null, List.of(), List.of(), List.of(),
                LvState.Phase.ACTIVE_ABSTRACT, LvState.Path.ABSTRACT, false, null, 1, 1, 5, 4, "ai", null, List.of());
        state.conflicts.put(conflict.id(), conflict);
        var treaty = new LvState.Treaty(UUID.randomUUID(), "realm:" + realm, "kingdom:mcakingdoms:serenum", LvState.Term.PEACE, LvState.TreatyStatus.ACTIVE,
                Map.of("realm:" + realm, new LvState.Signature(LvState.ActorKind.PROVIDER_REALM, realm, 1, "losses")), List.of(village), 1, 1, 100000, 200000,
                "", "", 0, "");
        state.treaties.put(treaty.id(), treaty);
        state.record(LvState.EventKind.BASELINE_CONTROL, 1, settlement, realm, null, null, "Ashford", false);
        return state;
    }

    @Test
    void theDocumentRoundTripsThroughItsJsonForm() {
        var state = populated();
        state.validate();
        var copy = LvBridgeSavedData.JSON.fromJson(LvBridgeSavedData.JSON.toJson(state), LvState.class);
        copy.validate();
        assertEquals(state.bindings, copy.bindings);
        assertEquals(state.realms, copy.realms);
        assertEquals(state.conflicts, copy.conflicts);
        assertEquals(state.treaties, copy.treaties);
        assertEquals(state.history, copy.history);
        assertEquals(state.provenance.world, copy.provenance.world);
    }

    @Test
    void twoVillagesAreNeverBoundToOneSettlement() {
        var state = populated();
        var first = state.bindings.values().iterator().next();
        UUID other = UUID.randomUUID();
        state.bindings.put(other, new LvState.Binding(other, first.settlement(), "minecraft:overworld", LvState.BindingStatus.BOUND, "Twin",
                List.of(), null, List.of(), null, null, false, 1, 1, "", List.of()));
        assertThrows(IllegalArgumentException.class, state::validate, "T01/T05: one settlement per village, never a silent merge");
    }

    @Test
    void anOccupiedVillageKeepsItsRecognizedRealm() {
        UUID village = UUID.randomUUID(), home = UUID.randomUUID(), occupier = UUID.randomUUID();
        var binding = new LvState.Binding(village, UUID.randomUUID(), "minecraft:overworld", LvState.BindingStatus.BOUND, "Vale", List.of(), null, List.of(),
                home, home, false, 1, 1, "", List.of());
        assertFalse(binding.occupied());
        var taken = new LvState.Binding(village, binding.settlement(), binding.dimension(), binding.status(), binding.providerName(), List.of(), null,
                List.of(), occupier, binding.recognizedRealm(), false, 1, 2, "", List.of());
        assertTrue(taken.occupied(), "T19: control differs from the recognized government");
        assertEquals(home, taken.recognizedRealm());
        assertFalse(taken.recognize(occupier).occupied(), "only a cession changes the recognized government");
    }

    @Test
    void observingAVillageKeepsItsEarlierNamesAndClaims() {
        UUID claim = UUID.randomUUID(), next = UUID.randomUUID();
        var binding = new LvState.Binding(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", LvState.BindingStatus.BOUND, "Vale", List.of(), claim,
                List.of(), null, null, false, 1, 1, "", List.of());
        var observed = binding.observed("Vale on the Hill", next, null, 5);
        assertEquals("Vale on the Hill", observed.providerName());
        assertEquals(List.of("Vale"), observed.aliases());
        assertEquals(next, observed.claim());
        assertEquals(List.of(claim), observed.retiredClaims(), "T22: a replaced claim is retired, never reused");
    }

    @Test
    void compactionDropsOnlyFinishedRecords() {
        var state = populated();
        for (int i = 0; i < LvState.CONFLICTS - 10; i++) {
            var c = new LvState.Conflict(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), null, null, null, List.of(), List.of(), List.of(),
                    LvState.Phase.REPELLED, LvState.Path.LIVE, false, null, i, i, 0, 0, "", null, List.of());
            state.conflicts.put(c.id(), c);
        }
        long open = state.conflicts.values().stream().filter(LvState.Conflict::open).count();
        state.compact();
        assertEquals(open, state.conflicts.values().stream().filter(LvState.Conflict::open).count(), "open wars are never compacted away");
        assertTrue(state.conflicts.size() <= LvState.CONFLICTS - 256);
    }

    @Test
    void aCopyCanChangeWithoutTouchingTheCommittedState() {
        var state = populated();
        var copy = state.copy();
        copy.provenance.regressedAt = 99;
        copy.migration.complete = true;
        copy.bindings.clear();
        assertEquals(0, state.provenance.regressedAt);
        assertFalse(state.migration.complete);
        assertFalse(state.bindings.isEmpty());
    }

    @Test
    void anAgreementInForceBindsBothOrderingsOfItsParties() {
        var treaty = populated().treaties.values().iterator().next();
        assertTrue(treaty.binds(treaty.second(), treaty.first()));
        assertTrue(treaty.inForce(50_000));
        assertFalse(treaty.inForce(100_000), "it ends at its expiry");
        assertThrows(IllegalArgumentException.class, () -> new LvState.Treaty(UUID.randomUUID(), "realm:x", "realm:x", LvState.Term.PEACE,
                LvState.TreatyStatus.PROPOSED, Map.of(), List.of(), 0, 0, 0, 0, "", "", 0, ""));
    }

    @Test
    void partyReferencesParseOnlyTheirOwnForms() {
        UUID id = UUID.randomUUID();
        var realm = dev.otectus.mcakingdoms.api.politics.PoliticalPartyRef.parse("realm:" + id).orElseThrow();
        assertEquals("realm:" + id, realm.format());
        assertEquals("kingdom:mcakingdoms:serenum", dev.otectus.mcakingdoms.api.politics.PoliticalPartyRef.parse("kingdom:mcakingdoms:serenum").orElseThrow().format());
        assertTrue(dev.otectus.mcakingdoms.api.politics.PoliticalPartyRef.parse("realm:not-a-uuid").isEmpty());
        assertTrue(dev.otectus.mcakingdoms.api.politics.PoliticalPartyRef.parse("faction:x").isEmpty());
    }
}
