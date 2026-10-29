package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Requirement;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** D7 unit tests: deliveries conserve items and never exceed a demand; tiers follow points; the abstract ledger is capped and trade conserves it. */
class DevelopmentLedgerTest {
    private static Development.Offer offer(int slot, String item, int count, String... tags) {
        return new Development.Offer(slot, item, Set.of(tags), count);
    }

    @Test void aDeliveryTakesOnlyWhatRemainsAndConservesItems() {
        var demand = List.of(new Requirement("minecraft:lantern", 1, 0), new Requirement("#minecraft:wooden_fences", 2, 1));
        var offers = List.of(offer(0, "minecraft:lantern", 5), offer(3, "minecraft:oak_fence", 4, "minecraft:wooden_fences", "minecraft:fences"));
        var takes = Development.plan(demand, offers);
        assertEquals(List.of(new Development.Take(0, "minecraft:lantern", 1, 0), new Development.Take(3, "minecraft:oak_fence", 1, 1)), takes);
        var after = Development.apply(demand, takes);
        assertEquals(0, after.get(0).remaining());
        assertEquals(0, after.get(1).remaining());
        int taken = takes.stream().mapToInt(Development.Take::count).sum();
        int delivered = after.stream().mapToInt(Requirement::delivered).sum() - demand.stream().mapToInt(Requirement::delivered).sum();
        assertEquals(taken, delivered, "every item taken is recorded, and nothing more");
        assertTrue(Development.plan(after, offers).isEmpty(), "a funded work takes nothing more");
    }

    @Test void aTagDemandAcceptsAnyItemOfTheTagAcrossStacks() {
        var demand = List.of(new Requirement("#minecraft:planks", 5, 0));
        var offers = List.of(offer(1, "minecraft:oak_planks", 2, "minecraft:planks"), offer(2, "minecraft:stone", 64), offer(4, "minecraft:spruce_planks", 9, "minecraft:planks"));
        var takes = Development.plan(demand, offers);
        assertEquals(2, takes.size());
        assertEquals(2, takes.get(0).count());
        assertEquals(3, takes.get(1).count());
        assertEquals("minecraft:spruce_planks", takes.get(1).item());
    }

    @Test void applyingADeliveryTwiceCannotOverfillADemand() {
        var demand = List.of(new Requirement("minecraft:dirt", 5, 0));
        var takes = Development.plan(demand, List.of(offer(0, "minecraft:dirt", 64)));
        var once = Development.apply(demand, takes);
        assertThrows(IllegalArgumentException.class, () -> Development.apply(once, takes));
        assertThrows(IllegalArgumentException.class, () -> new Requirement("minecraft:dirt", 5, 6));
        assertThrows(IllegalArgumentException.class, () -> new Requirement("minecraft:dirt", 5, -1));
    }

    @Test void tiersFollowCompletedPointsOnly() {
        assertEquals(0, Development.tier(0));
        assertEquals(1, Development.tier(1));
        assertEquals(1, Development.tier(2));
        assertEquals(2, Development.tier(3));
        assertEquals(4, Development.tier(10));
        assertEquals(5, Development.tier(15));
        assertEquals(5, Development.tier(1_000));
    }

    @Test void abstractProductionIsCappedAndTradeConservesGoods() {
        var produced = Development.produce(Map.of("goods", Development.ABSTRACT_CAP - 1), Map.of("goods", 5, "tools", 1));
        assertEquals(Development.ABSTRACT_CAP, produced.get("goods"));
        assertEquals(1L, produced.get("tools"));
        var a = Map.of("goods", 100L); var b = Map.of("goods", 10L, "tools", 3L);
        var transfers = Development.trade(a, b, 8);
        var afterA = Development.after(a, transfers, true); var afterB = Development.after(b, transfers, false);
        assertEquals(110L, afterA.getOrDefault("goods", 0L) + afterB.getOrDefault("goods", 0L), "trade creates and destroys nothing");
        assertEquals(92L, afterA.get("goods"), "at most the cap moves");
        assertEquals(1L, afterA.get("tools"));
        assertEquals(2L, afterB.get("tools"));
        assertTrue(afterA.values().stream().allMatch(v -> v >= 0) && afterB.values().stream().allMatch(v -> v >= 0));
        assertTrue(Development.trade(a, b, 0).isEmpty(), "a cap of 0 turns trade off");
        assertTrue(Development.trade(Map.of("goods", 4L), Map.of("goods", 4L), 8).isEmpty(), "balanced ledgers do not trade");
    }
}
