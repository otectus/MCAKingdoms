package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Chronicle projection is at most once per source event and is never retried on an empty answer (spec 10.2). */
class TownsteadChronicleLedgerTest {
    @Test
    void aSourceIsClaimedOnceAndAnEmptyAnswerIsSettledNotRetried() {
        var ledger = new TownsteadChronicleBridge.Ledger(8);
        UUID source = UUID.randomUUID();
        assertTrue(ledger.claim(source, TownsteadChronicleBridge.Outcome.NO_STORY));
        ledger.settle(source, TownsteadChronicleBridge.Outcome.NO_STORY);
        assertFalse(ledger.claim(source, TownsteadChronicleBridge.Outcome.NO_STORY), "a duplicate post of the same commit is ignored");
        assertEquals(Map.of(TownsteadChronicleBridge.Outcome.NO_STORY, 1), ledger.counts());
        UUID other = UUID.randomUUID();
        assertTrue(ledger.claim(other, TownsteadChronicleBridge.Outcome.NO_STORY));
        ledger.settle(other, TownsteadChronicleBridge.Outcome.STORY);
        assertEquals(Map.of(TownsteadChronicleBridge.Outcome.NO_STORY, 1, TownsteadChronicleBridge.Outcome.STORY, 1), ledger.counts());
    }

    @Test
    void theLedgerIsBounded() {
        var ledger = new TownsteadChronicleBridge.Ledger(4);
        UUID first = UUID.randomUUID();
        ledger.claim(first, TownsteadChronicleBridge.Outcome.DISABLED);
        for (int i = 0; i < 4; i++) ledger.claim(UUID.randomUUID(), TownsteadChronicleBridge.Outcome.DISABLED);
        assertEquals(Optional.empty(), ledger.outcome(first));
    }

    @Test
    void anActorCountsAsPresentInsideTheVillagesTownRangeOnly() {
        var village = new TownsteadVillageView(new ResourceLocation("minecraft:overworld"), 3, "Ash", BlockPos.ZERO, new BlockPos(-10, 60, -10),
                new BlockPos(10, 80, 10), 8, 5, 2, 4, Optional.empty(), 0, false);
        assertTrue(TownsteadChronicleBridge.within(village, new BlockPos(17, 64, -17)));
        assertFalse(TownsteadChronicleBridge.within(village, new BlockPos(19, 64, 0)));
    }
}
