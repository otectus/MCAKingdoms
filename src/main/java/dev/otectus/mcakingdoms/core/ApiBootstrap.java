package dev.otectus.mcakingdoms.api;

import net.minecraft.server.MinecraftServer;

public final class ApiBootstrap {
    private ApiBootstrap() {
    }

    public static void attach(MinecraftServer server, KingdomsService service) {
        McaKingdomsApi.attach(server, service);
    }

    public static void detach(MinecraftServer server, KingdomsService service) {
        McaKingdomsApi.detach(server, service);
    }
}
