package dev.otectus.mcakingdoms.compat.townstead;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import dev.otectus.mcakingdoms.compat.townstead.network.TownsteadCivicNetwork;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TownsteadRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TownsteadRuntime() {
    }

    public static void registerGlobalHooks() {
        TownsteadIntegrationConfig.register();
        TownsteadCivicNetwork.init();
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(kingdoms, "kingdoms");
        boolean installed = ModList.get().isLoaded("townstead") && TownsteadIntegrationConfig.ENABLED.get();
        TownsteadBinding binding = TownsteadBinding.resolve(installed,
                installed && TownsteadIntegrationConfig.ALLOW_INTERNAL_FALLBACK.get());
        TownsteadServiceImpl service = new TownsteadServiceImpl(server, kingdoms, binding);
        KingdomsTownsteadApi.attach(server, service);
        TownsteadBuildingReconciler reconciler = new TownsteadBuildingReconciler(server, kingdoms, service);
        TownsteadPoliticalReactions reactions = new TownsteadPoliticalReactions(server, kingdoms, service);
        // Building changes come from MCA's village data, so they are watched whenever it is readable.
        boolean reconcile = service.capabilities().contains(TownsteadCapability.READ_BUILDINGS);
        if (reconcile) MinecraftForge.EVENT_BUS.register(reconciler);
        if (installed) MinecraftForge.EVENT_BUS.register(reactions);
        String version = ModList.get().getModContainerById("townstead")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("absent");
        LOGGER.info("[MCA: Kingdoms] Townstead {} capabilities: {}", version, service.capabilities());
        String validated = dev.otectus.mcakingdoms.politics.PoliticalProviderVersions.TOWNSTEAD;
        if (!"absent".equals(version) && !validated.equals(version)) {
            LOGGER.warn("[MCA: Kingdoms] Townstead {} has not been validated with this release (validated: {}). "
                    + "Each binding is probed member by member and fails closed where it cannot read, so the "
                    + "capabilities above are the ones that work.", version, validated);
        }
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            if (installed) MinecraftForge.EVENT_BUS.unregister(reactions);
            if (reconcile) MinecraftForge.EVENT_BUS.unregister(reconciler);
            reconciler.clear();
            TownsteadCivicNetwork.clearServerState();
            KingdomsTownsteadApi.detach(server, service);
        };
    }
}
