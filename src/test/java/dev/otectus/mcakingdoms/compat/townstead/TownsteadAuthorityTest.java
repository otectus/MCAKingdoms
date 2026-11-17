package dev.otectus.mcakingdoms.compat.townstead;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static dev.otectus.mcakingdoms.compat.townstead.TownsteadAuthority.Owner;
import static dev.otectus.mcakingdoms.compat.townstead.TownsteadAuthority.State;
import static org.junit.jupiter.api.Assertions.*;

/** The local-government matrix (spec 4.2, 15.1 Authority). */
class TownsteadAuthorityTest {
    private static TownsteadAuthority.LocalVerdict decide(Optional<TownsteadSavedData.Owner> decision, String decidedFaction, boolean governed,
                                                          boolean readable, boolean populated, boolean faction, String factionId, boolean restored) {
        return TownsteadAuthority.decideLocal(new TownsteadAuthority.LocalInputs(true, decision, decidedFaction, governed, readable, populated, faction, factionId, restored));
    }

    @Test
    void populatedRecordsOnBothSidesAreAReportedConflictInWhichKingdomsKeepsActing() {
        var verdict = decide(Optional.empty(), "", true, true, true, true, "townstead:council", false);
        assertEquals(State.CONFLICT, verdict.state());
        assertEquals(Owner.MCA_KINGDOMS, verdict.owner());
        assertTrue(verdict.allowed().contains("appoint_local"), "a conflict refuses nothing; existing worlds keep acting");
    }

    @Test
    void anUnmappedTownsteadCouncilIsTownsteads() {
        var verdict = decide(Optional.empty(), "", false, true, true, true, "townstead:council", false);
        assertEquals(State.EXTERNAL_ACTIVE, verdict.state());
        assertEquals(Owner.TOWNSTEAD, verdict.owner());
    }

    @Test
    void aReleaseRefusesLocalActionsAndSurvivesTheProvidersRemoval() {
        var released = decide(Optional.of(TownsteadSavedData.Owner.TOWNSTEAD), "townstead:council", true, true, true, true, "townstead:council", false);
        assertEquals(State.EXTERNAL_ACTIVE, released.state());
        assertTrue(released.allowed().isEmpty());
        var removed = decide(Optional.of(TownsteadSavedData.Owner.TOWNSTEAD), "townstead:council", true, false, false, false, "", false);
        assertEquals(State.EXTERNAL_UNAVAILABLE, removed.state(), "no native council silently takes over");
        assertEquals(Owner.TOWNSTEAD, removed.owner());
        assertTrue(removed.allowed().isEmpty());
    }

    @Test
    void anAdoptionAcknowledgesTheConflictAndAChangedFactionOrRestoredBackupAsksAnOperator() {
        assertEquals(State.NATIVE, decide(Optional.of(TownsteadSavedData.Owner.KINGDOMS), "townstead:council", true, true, true, true, "townstead:council", false).state());
        var changed = decide(Optional.of(TownsteadSavedData.Owner.KINGDOMS), "townstead:council", true, true, true, true, "townstead:other", false);
        assertEquals(State.RECOVERY_REQUIRED, changed.state());
        assertTrue(changed.allowed().contains("appoint_local"), "MCA: Kingdoms keeps acting on an adopted settlement under review");
        var restored = decide(Optional.of(TownsteadSavedData.Owner.TOWNSTEAD), "townstead:council", true, true, true, true, "townstead:council", true);
        assertEquals(State.RECOVERY_REQUIRED, restored.state());
        assertTrue(restored.allowed().isEmpty(), "a released settlement stays released while it is reviewed");
    }

    @Test
    void withoutTownsteadPoliticsKingdomsGovernsAndNothingIsGuessed() {
        assertEquals(State.NATIVE, decide(Optional.empty(), "", true, false, false, false, "", false).state());
        assertEquals(State.UNMAPPED, decide(Optional.empty(), "", false, false, false, false, "", false).state());
        var off = TownsteadAuthority.decideLocal(new TownsteadAuthority.LocalInputs(false, Optional.of(TownsteadSavedData.Owner.TOWNSTEAD), "x", true, true, true, true, "x", false));
        assertEquals(State.DISABLED, off.state(), "switched off: the decision is kept but not applied");
    }
}
