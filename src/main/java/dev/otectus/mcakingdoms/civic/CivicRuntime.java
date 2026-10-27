package dev.otectus.mcakingdoms.civic;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.civic.CivicNetworkApi;
import net.minecraft.server.MinecraftServer;
import java.util.*;

public final class CivicRuntime {
    /** Store health for server diagnostics: the civic network and institutional commission ledgers. */
    public static java.util.List<dev.otectus.mcakingdoms.persistence.StoreStatus> storeStatus(net.minecraft.server.MinecraftServer server) { return java.util.List.of(CivicSavedData.status(server), InstitutionalCommissionData.status(server)); }

    private static final Map<MinecraftServer,CivicService> SERVICES=new WeakHashMap<>();
    /** Capacity readings of the civic network and institutional commission ledgers (F12). */
    public static java.util.List<dev.otectus.mcakingdoms.persistence.Capacity> capacity(MinecraftServer server) {
        var s=CivicSavedData.get(server).state();var out=new ArrayList<dev.otectus.mcakingdoms.persistence.Capacity>();
        out.add(new dev.otectus.mcakingdoms.persistence.Capacity(CivicSavedData.NAME,"guild chapters",s.chapters.size(),4096,dev.otectus.mcakingdoms.persistence.Capacity.Relief.OFFLINE_PROCEDURE));
        out.add(new dev.otectus.mcakingdoms.persistence.Capacity(CivicSavedData.NAME,"chapter contacts",s.contacts.size(),16384,dev.otectus.mcakingdoms.persistence.Capacity.Relief.OFFLINE_PROCEDURE));
        out.add(new dev.otectus.mcakingdoms.persistence.Capacity(CivicSavedData.NAME,"authored roles",s.seeds.size(),16384,dev.otectus.mcakingdoms.persistence.Capacity.Relief.OFFLINE_PROCEDURE));
        out.add(new dev.otectus.mcakingdoms.persistence.Capacity(CivicSavedData.NAME,"dismissed contacts",s.dismissed.size(),16384,dev.otectus.mcakingdoms.persistence.Capacity.Relief.KEPT_AS_EVIDENCE));
        out.add(new dev.otectus.mcakingdoms.persistence.Capacity(CivicSavedData.NAME,"chapter introductions",s.introductions.size(),100000,dev.otectus.mcakingdoms.persistence.Capacity.Relief.KEPT_AS_EVIDENCE));
        out.add(new dev.otectus.mcakingdoms.persistence.Capacity(InstitutionalCommissionData.NAME,"commission contracts",InstitutionalCommissionData.get(server).size(),16384,dev.otectus.mcakingdoms.persistence.Capacity.Relief.OFFLINE_PROCEDURE));
        return out;
    }
    private CivicRuntime() { }
    public static Registration attach(MinecraftServer server,KingdomsService kingdoms) {
        if(!server.isSameThread())throw new IllegalStateException("Civic runtime requires server thread");
        var service=new CivicService(server,kingdoms);
        if(SERVICES.putIfAbsent(server,service)!=null)throw new IllegalStateException("Civic runtime already attached");
        try {
            dev.otectus.mcakingdoms.api.civic.InstitutionalCommissionApi.attach(server,service);
            CivicNetworkApi.attach(server,service::speakerContext);
            CivicNetworkApi.attachActions(server,(player,speaker,introduction)->{
                var result=introduction?service.introduction(player,speaker):service.commissions(player,speaker);
                result.settlement().flatMap(kingdoms::getSettlement).ifPresent(s->player.sendSystemMessage(
                        net.minecraft.network.chat.Component.translatable("civic.introduction_destination",s.displayName(),s.anchor().toShortString())));
                return new dev.otectus.mcakingdoms.api.civic.CivicActionResult(result.success(),result.reason(),result.settlement());
            });
        } catch(RuntimeException failure) { CivicNetworkApi.detach(server);dev.otectus.mcakingdoms.api.civic.InstitutionalCommissionApi.detach(server);SERVICES.remove(server);throw failure; }
        return ()->{CivicNetworkApi.detach(server);dev.otectus.mcakingdoms.api.civic.InstitutionalCommissionApi.detach(server);SERVICES.remove(server);};
    }
    /** Internal replay, bound to a durable authored-contact decision rather than caller-supplied role data. */
    public static boolean applyCommittedContact(MinecraftServer server,UUID epoch,UUID receipt,UUID player,net.minecraft.resources.ResourceLocation organization) {
        var proof=dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.committedContact(server,epoch,receipt,player,organization)
                .orElseThrow(()->new IllegalArgumentException("No durable contact authorization"));
        return get(server).recordCommittedContact(proof.giver(),organization,proof.settlement(),proof.dimension(),proof.village(),proof.receipt());
    }
    public static CivicService get(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("Civic runtime requires server thread");
        var value=SERVICES.get(server);if(value==null)throw new IllegalStateException("Civic runtime unavailable");return value;
    }
}
