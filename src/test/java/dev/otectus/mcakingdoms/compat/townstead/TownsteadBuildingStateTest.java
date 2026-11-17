package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingState;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingView;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The detailed fingerprint sees changes the 0.2.x one missed, and its order never depends on the provider's (spec 7.2). */
class TownsteadBuildingStateTest {
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft:overworld");
    private static final UUID SETTLEMENT = UUID.randomUUID();

    private static TownsteadBuildingEvidence building(int village, int id, String type, int maxX, TownsteadBuildingEvidence.Completion completion) {
        var view = new TownsteadBuildingView(OVERWORLD, SETTLEMENT, TownsteadBuildingView.bindingId(OVERWORLD, SETTLEMENT, village, id), village, id, type,
                TownsteadBuildingView.familyOf(type), TownsteadBuildingView.levelOf(type), 40, 0, 64, 0, -5, 60, -5, maxX, 70, 5);
        return new TownsteadBuildingEvidence(view, completion, TownsteadBuildingEvidence.Source.TOWNSTEAD_V1);
    }

    @Test
    void completionAndGeometryChangesAreChangesEvenWithTheSameTypeAndSize() {
        var before = TownsteadBuildingState.of(building(1, 2, "library", 5, TownsteadBuildingEvidence.Completion.COMPLETE));
        var incomplete = TownsteadBuildingState.of(building(1, 2, "library", 5, TownsteadBuildingEvidence.Completion.INCOMPLETE));
        var moved = TownsteadBuildingState.of(building(1, 2, "library", 9, TownsteadBuildingEvidence.Completion.COMPLETE));
        assertEquals(before.legacy(), incomplete.legacy(), "the 0.2.x fingerprint cannot see it");
        assertNotEquals(before, incomplete);
        assertNotEquals(before, moved);
        assertEquals(2, TownsteadBuildingState.of(building(1, 3, "dock_l2", 5, TownsteadBuildingEvidence.Completion.COMPLETE)).tier());
    }

    @Test
    void theSnapshotOrderIsVillageThenBuildingWhateverTheProviderOrder() {
        List<TownsteadBuildingState> states = new ArrayList<>(List.of(
                TownsteadBuildingState.of(building(2, 1, "inn", 5, TownsteadBuildingEvidence.Completion.COMPLETE)),
                TownsteadBuildingState.of(building(1, 9, "inn", 5, TownsteadBuildingEvidence.Completion.COMPLETE)),
                TownsteadBuildingState.of(building(1, 3, "inn", 5, TownsteadBuildingEvidence.Completion.COMPLETE))));
        List<TownsteadBuildingState> shuffled = new ArrayList<>(List.of(states.get(2), states.get(0), states.get(1)));
        states.sort(TownsteadBuildingState.ORDER);
        shuffled.sort(TownsteadBuildingState.ORDER);
        assertEquals(states, shuffled);
        assertEquals(3, states.get(0).buildingId());
        assertEquals(2, states.get(2).villageId());
    }

    @Test
    void theSameBuildingIdInTwoVillagesHasTwoBindings() {
        assertNotEquals(building(1, 4, "inn", 5, TownsteadBuildingEvidence.Completion.COMPLETE).building().bindingId(),
                building(2, 4, "inn", 5, TownsteadBuildingEvidence.Completion.COMPLETE).building().bindingId());
    }

    @Test
    void onlyAnIncompleteBuildingIsRefusedForNewUse() {
        assertTrue(building(1, 1, "inn", 5, TownsteadBuildingEvidence.Completion.UNKNOWN).acceptsNewUse(), "a provider that cannot say keeps the 0.2.x rule");
        assertFalse(building(1, 1, "inn", 5, TownsteadBuildingEvidence.Completion.UNKNOWN).verifiedComplete());
        assertFalse(building(1, 1, "inn", 5, TownsteadBuildingEvidence.Completion.INCOMPLETE).acceptsNewUse());
    }
}
