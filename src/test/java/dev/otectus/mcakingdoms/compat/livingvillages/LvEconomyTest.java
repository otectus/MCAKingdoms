package dev.otectus.mcakingdoms.compat.livingvillages;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A supply delivery's standing is granted from what its journaled, acknowledged state says was credited, and only for deliveries. */
class LvEconomyTest {
    private static LvState.Operation op(LvState.OperationKind kind, String after) {
        return new LvState.Operation(UUID.randomUUID(), kind, UUID.randomUUID(), null, LvState.OperationState.ACKNOWLEDGED, "currency:10",
                after, 1, 2, 3, false, "supply", LvState.ActorKind.PLAYER, UUID.randomUUID());
    }

    @Test
    void theCreditIsReadFromTheAppliedState() {
        assertEquals(32, LvEconomy.credited(op(LvState.OperationKind.RELIEF, "currency:42 (+32)")));
        assertEquals(5, LvEconomy.credited(op(LvState.OperationKind.RELIEF, "minecraft:oak_log:12 (+5)")));
    }

    @Test
    void nothingIsCreditedWithoutARecordedDelivery() {
        assertEquals(0, LvEconomy.credited(op(LvState.OperationKind.RELIEF, "currency:42")));
        assertEquals(0, LvEconomy.credited(op(LvState.OperationKind.RELIEF, "currency:42 (+0)")));
        assertEquals(0, LvEconomy.credited(op(LvState.OperationKind.CONQUEST, "team (+5)")), "only supply deliveries earn standing");
    }
}
