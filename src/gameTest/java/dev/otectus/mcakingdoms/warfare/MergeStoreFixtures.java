package dev.otectus.mcakingdoms.warfare;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** Seeds and reads campaign records for the settlement-merge GameTest. The seeded campaign is closed, so inert. */
public final class MergeStoreFixtures {
    private MergeStoreFixtures() {
    }

    public static UUID seedCampaign(MinecraftServer server, UUID settlement) {
        var data = CampaignSavedData.get(server);
        var next = data.snapshot();
        UUID id = UUID.randomUUID();
        next.campaigns.put(id, new CampaignState.Campaign(id, settlement, UUID.randomUUID(), "fixture:attacker",
                "fixture:defender", "mcakingdoms:serenum", UUID.randomUUID(), CampaignState.Goal.DEFEND, 10, 20,
                CampaignState.Phase.EXPIRED, 1, CampaignState.Relation.NEUTRAL, "Merge fixture"));
        next.revision++;
        if (!data.commit(server, next)) throw new IllegalStateException("Campaign fixture was not saved");
        return id;
    }

    public static UUID campaignSettlement(MinecraftServer server, UUID campaign) {
        return CampaignSavedData.get(server).snapshot().campaigns.get(campaign).settlement();
    }

    public static void removeCampaign(MinecraftServer server, UUID campaign) {
        var data = CampaignSavedData.get(server);
        var next = data.snapshot();
        if (next.campaigns.remove(campaign) == null) return;
        next.revision++;
        data.commit(server, next);
    }
}
