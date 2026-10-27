package dev.otectus.mcakingdoms.factions;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.factions.KingdomsFactionsApi;
import dev.otectus.mcakingdoms.compat.reputation.ReputationBridge;
import dev.otectus.mcakingdoms.factions.command.FactionCommands;
import dev.otectus.mcakingdoms.factions.config.FactionConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.WeakHashMap;

public final class FactionsRuntime {
    /** Store health for server diagnostics. */
    public static dev.otectus.mcakingdoms.persistence.StoreStatus storeStatus(net.minecraft.server.MinecraftServer server) { return FactionSavedData.status(server); }

    private static final AtomicBoolean GLOBAL_HOOKS=new AtomicBoolean();
    private static final Map<MinecraftServer,FactionServiceImpl> SERVICES=
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private FactionsRuntime() {}

    public static void registerGlobalHooks() {
        if(GLOBAL_HOOKS.compareAndSet(false,true)) {
            FactionConfig.register();
            MinecraftForge.EVENT_BUS.register(FactionCommands.class);
            MinecraftForge.EVENT_BUS.register(FactionsRuntime.class);
        }
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms) {
        Objects.requireNonNull(server,"server");Objects.requireNonNull(kingdoms,"kingdoms");
        FactionServiceImpl service=new FactionServiceImpl(server,kingdoms);
        SERVICES.put(server,service);
        KingdomsFactionsApi.attach(server,service);
        Registration reputation=ReputationBridge.attach(server,kingdoms,service);
        return ()->{reputation.close();SERVICES.remove(server,service);KingdomsFactionsApi.detach(server,service);};
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = event.getServer().overworld().getGameTime();
        if (Math.floorMod(now, 1200L) != 0L) return;
        FactionServiceImpl service = SERVICES.get(event.getServer());
        if (service != null) service.pruneReceipts(now, FactionConfig.RECEIPT_RETENTION_TICKS.get());
    }
}
