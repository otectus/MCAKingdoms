package dev.otectus.mcakingdoms.presentation;

import dev.otectus.mcakingdoms.client.ClientBootstrap;
import dev.otectus.mcakingdoms.command.CommandArguments;
import dev.otectus.mcakingdoms.command.KingdomsCommands;
import dev.otectus.mcakingdoms.item.ModItems;
import dev.otectus.mcakingdoms.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class Presentation {
    private Presentation() {
    }

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, dev.otectus.mcakingdoms.civic.CivicConfig.SPEC,
                "mcakingdoms-civic-common.toml");
        CommandArguments.register(modBus);
        ModItems.register(modBus);
        NetworkHandler.init();
        dev.otectus.mcakingdoms.civic.CivicNetwork.init();
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.civic.CivicCommands.class);
        MinecraftForge.EVENT_BUS.register(KingdomsCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.command.KingdomsHelpCommands.class);
        MinecraftForge.EVENT_BUS.register(ServerPresentationEvents.class);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PresentationConfig.SPEC,
                "mcakingdoms-client.toml");
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
    }
}
