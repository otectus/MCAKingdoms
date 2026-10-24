package dev.otectus.mcakingdoms.warfare;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.otectus.mcakingdoms.warfare.CampaignState.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignStateTest {
    private Accord proposed() {
        return new Accord(UUID.randomUUID(), UUID.randomUUID(), "a", "b", "mcakingdoms:a", "mcakingdoms:b",
                Relation.NEUTRAL, Relation.ENEMY, Relation.ENEMY, Map.of("a", UUID.randomUUID()),
                TreatyPhase.PROPOSED, Sovereignty.AUTONOMOUS, "mcakingdoms:a", 1000, 3, UUID.randomUUID(), "b", "Relief and autonomy");
    }
    @Test void signaturesAreFrozenAndCannotImpersonateBothSides() {
        var p = proposed();
        assertThrows(IllegalArgumentException.class, () -> p.sign("b", p.signatures().get("a")));
        var signed = p.sign("b", UUID.randomUUID());
        assertEquals(TreatyPhase.SIGNED, signed.phase());
        assertEquals(1, p.signatures().size());
        assertThrows(UnsupportedOperationException.class, () -> signed.signatures().clear());
        assertEquals(p.claim(), signed.claim());
    }
    @Test void SovereigntyRequiresMatchingAcknowledgedAccord() {
        var a = proposed().sign("b", UUID.randomUUID()); var s = new CampaignState();
        s.accords.put(a.id(), a);
        s.decisions.put(a.settlement(), new Decision(a.settlement(), a.id(), a.sovereignty(), a.recognizedKingdom(), 20, a.controlSequence()));
        assertThrows(IllegalArgumentException.class, s::validate);
        s.accords.put(a.id(), a.phase(TreatyPhase.ACTIVE, "acknowledged")); s.validate();
        s.accords.put(a.id(), a.phase(TreatyPhase.BREACHED, "native change")); s.validate();
        s.decisions.put(a.settlement(), new Decision(a.settlement(), a.id(), Sovereignty.INDEPENDENT, a.recognizedKingdom(), 20, a.controlSequence()));
        assertThrows(IllegalArgumentException.class, s::validate);
    }
    @Test void futureMalformedAndTruncatedPayloadsRemainPreserved() {
        for (var payload : List.of("{}", "null", "{\"revision\":0}", "not json")) {
            var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload);
            var data = CampaignSavedData.load(tag); assertFalse(data.writable()); assertEquals(tag, data.save(new CompoundTag()));
        }
        var future = new CompoundTag(); future.putInt("Schema", 99); future.putString("Payload", "opaque");
        assertEquals(future, CampaignSavedData.load(future).save(new CompoundTag()));
    }
    @Test void roundtripKeepsPendingIntentWithoutInventingDecision() {
        var s = new CampaignState(); var a = proposed().sign("b", UUID.randomUUID()).phase(TreatyPhase.PENDING, "native partial write");
        s.accords.put(a.id(), a); var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", CampaignSavedData.JSON.toJson(s));
        var data = CampaignSavedData.load(tag); assertTrue(data.writable());
        assertEquals(a, data.snapshot().accords.get(a.id())); assertTrue(data.snapshot().decisions.isEmpty());
    }
    private static Accord activeAt(UUID settlement) {
        return new Accord(UUID.randomUUID(), settlement, "a", "b", "mcakingdoms:a", "mcakingdoms:b", Relation.NEUTRAL,
                Relation.ENEMY, Relation.ENEMY, Map.of("a", UUID.randomUUID(), "b", UUID.randomUUID()), TreatyPhase.ACTIVE,
                Sovereignty.AUTONOMOUS, "mcakingdoms:a", 1000, 3, UUID.randomUUID(), "b", "Relief and autonomy");
    }
    @Test void mergeMovesEverythingToTheSurvivorAndKeepsItsOwnDecision() {
        UUID retired = UUID.randomUUID(), survivor = UUID.randomUUID();
        var s = new CampaignState();
        var retiredAccord = activeAt(retired); var survivorAccord = activeAt(survivor);
        s.accords.put(retiredAccord.id(), retiredAccord); s.accords.put(survivorAccord.id(), survivorAccord);
        s.decisions.put(retired, new Decision(retired, retiredAccord.id(), retiredAccord.sovereignty(), retiredAccord.recognizedKingdom(), 20, 3));
        s.decisions.put(survivor, new Decision(survivor, survivorAccord.id(), survivorAccord.sovereignty(), survivorAccord.recognizedKingdom(), 30, 3));
        var campaign = new Campaign(UUID.randomUUID(), retired, UUID.randomUUID(), "a", "b", "mcakingdoms:a", UUID.randomUUID(),
                Goal.DEFEND, 10, 20, Phase.NOTICE, 1, Relation.NEUTRAL, "Hold the bridge");
        s.campaigns.put(campaign.id(), campaign);
        var contract = new Contract(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), retired, "ultima:warfare/relief",
                ContractKind.RELIEF, ContractPhase.OFFERED, 1, 0, "fingerprint", 100, null, null, null, null, 0);
        s.contracts.put(contract.id(), contract);
        s.history.add(new Notice(UUID.randomUUID(), retired, "declaration_intent", UUID.randomUUID(), 5, "Hold the bridge"));
        s.validate();
        assertTrue(s.redirectSettlement(retired, survivor));
        s.validate();
        assertEquals(survivor, s.campaigns.get(campaign.id()).settlement());
        assertEquals(survivor, s.accords.get(retiredAccord.id()).settlement());
        assertEquals(Set.of(survivor), s.decisions.keySet());
        assertEquals(survivorAccord.id(), s.decisions.get(survivor).accord(), "the survivor's own sovereign decision stands");
        assertEquals(survivor, s.contracts.get(contract.id()).settlement());
        assertEquals(survivor, s.history.get(0).settlement());
        assertFalse(s.redirectSettlement(retired, survivor), "nothing is left on the retired id");
    }
}
