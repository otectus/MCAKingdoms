package dev.otectus.mcakingdoms.acceptance;

import dev.otectus.mcakingdoms.politics.PoliticalSavedData;

import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Legacy recognition fixture, confined to the opt-in acceptance jar. */
public final class CivicPrivacyFixture {
    public static void assertMerge(MinecraftServer server, ServerPlayer known, ServerPlayer stranger, SettlementView source) throws Exception {
        var data=PoliticalSavedData.get(server);
        var transaction=PoliticalSavedData.class.getDeclaredMethod("transaction"); transaction.setAccessible(true);
        var next=transaction.invoke(data);
        var recognitionsField=next.getClass().getDeclaredField("recognitions"); recognitionsField.setAccessible(true);
        @SuppressWarnings("unchecked") var recognitions=(java.util.Map<UUID,Recognition>)recognitionsField.get(next);
        var revisionField=next.getClass().getDeclaredField("revision"); revisionField.setAccessible(true);
        long revision=revisionField.getLong(next)+1; revisionField.setLong(next,revision);
        UUID record=UUID.randomUUID(); String kingdom=source.kingdomId().toString();
        var definition=McaKingdoms.POLITICS.get("mcakingdoms:guild","institution");
        var pos=source.anchor();
        recognitions.put(record,new Recognition(record,kingdom,"mcakingdoms:guild",definition,
                new Building("fixture","minecraft:overworld",711,7,source.id(),"workshop",pos.getX(),pos.getY(),pos.getZ()),known.getUUID(),true,revision));
        var commit=PoliticalSavedData.class.getDeclaredMethod("commit",next.getClass()); commit.setAccessible(true); commit.invoke(data,next);
        assertVisible(server,known,stranger,kingdom);
        var kingdoms=McaKingdomsApi.get(server);
        var target=kingdoms.registerCandidate(server.overworld(),SettlementCandidate.manual(server.overworld().dimension(),pos.offset(200,0,0),32,
                new ResourceLocation("mcakingdoms_acceptance:r1"),"civic-merge-target","Civic Receiving Village"));
        kingdoms.setKingdom(target.id(),source.kingdomId());
        kingdoms.merge(source.id(),target.id());
        assertVisible(server,known,stranger,kingdom);
        System.out.println("PASS integration: institution visibility resolves settlement redirects after merge without exposing coordinates to stranger");
    }
    private static void assertVisible(MinecraftServer server,ServerPlayer known,ServerPlayer stranger,String kingdom) {
        var service=KingdomsPoliticsApi.get(server);
        if(service.page(known,UUID.randomUUID(),kingdom,"institutions",0).rows().size()!=1
                ||!service.page(stranger,UUID.randomUUID(),kingdom,"institutions",0).rows().isEmpty())
            throw new AssertionError("Recognition location visibility disagrees with settlement discovery");
    }
}
