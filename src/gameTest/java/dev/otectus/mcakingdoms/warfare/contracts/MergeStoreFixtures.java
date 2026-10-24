package dev.otectus.mcakingdoms.warfare.contracts;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** Seeds and reads a civilian contract for the settlement-merge GameTest. The contract is cancelled, so inert. */
public final class MergeStoreFixtures {
    private MergeStoreFixtures() {
    }

    public static UUID seedContract(MinecraftServer server, UUID settlement) {
        long now = server.overworld().getGameTime();
        var offered = new CivilianContractData.Contract(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), settlement, "mcakingdoms:lamplighters", "relief", "ultima:warfare/relief",
                "mcakingdoms:serenum", "civilian", "RECOGNIZED", 1, 0, 0, now, now + 1_200,
                CivilianContractData.Status.OFFERED, null, null);
        var contract = offered.accepted(UUID.randomUUID()).cancelled();
        if (!CivilianContractData.get(server).put(server, contract)) throw new IllegalStateException("Contract fixture was not saved");
        return contract.id();
    }

    public static UUID contractSettlement(MinecraftServer server, UUID contract) {
        return CivilianContractData.get(server).get(contract).orElseThrow().settlement();
    }
}
