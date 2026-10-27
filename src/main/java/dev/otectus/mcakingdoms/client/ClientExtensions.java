package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.client.guide.GuideLibrary;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only registrations whose lambdas name client classes. Kept out of {@link ClientBootstrap}, which a dedicated
 * server still loads (and verifies) when Forge checks its safe referent; verifying these lambdas there would load client
 * screens and fail.
 */
final class ClientExtensions {
    private ClientExtensions() {
    }

    static void register() {
        // A resource pack or language change can replace Book topics; they are read again on next use.
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) manager -> GuideLibrary.invalidate()));
        // Mods › MCA: Kingdoms › Config opens the same settings screen as Kingdom Tasks › Settings.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new KingdomsSettingsScreen(parent)));
    }
}
