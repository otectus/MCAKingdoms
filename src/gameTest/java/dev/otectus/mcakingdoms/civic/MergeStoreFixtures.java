package dev.otectus.mcakingdoms.civic;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** Seeds and reads a pending civic appointment for the settlement-merge GameTest; no such NPC exists, so it is inert. */
public final class MergeStoreFixtures {
    private MergeStoreFixtures() {
    }

    public static UUID seedAppointment(MinecraftServer server, UUID settlement) {
        var data = CivicSavedData.get(server);
        var next = data.copy();
        UUID npc = UUID.randomUUID();
        next.seeds.put(npc, new CivicSavedData.Seed(npc, "mcakingdoms:lamplighters", settlement, null, null, UUID.randomUUID(),
                ++next.revision));
        if (!data.commit(server, next)) throw new IllegalStateException("Civic fixture was not saved");
        return npc;
    }

    public static UUID appointmentSettlement(MinecraftServer server, UUID npc) {
        return CivicSavedData.get(server).state().seeds.get(npc).settlement();
    }

    public static void removeAppointment(MinecraftServer server, UUID npc) {
        var data = CivicSavedData.get(server);
        var next = data.copy();
        if (next.seeds.remove(npc) == null) return;
        next.revision++;
        data.commit(server, next);
    }
}
