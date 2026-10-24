package dev.otectus.mcakingdoms.api;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class McaKingdomsApi {
    public static final String MOD_ID = "mcakingdoms";

    private static final Map<MinecraftServer, KingdomsService> SERVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private McaKingdomsApi() {
    }

    public static KingdomsService get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        KingdomsService service = SERVICES.get(server);
        if (service == null) {
            throw new IllegalStateException("MCA: Kingdoms is not ready for this server");
        }
        return service;
    }

    static void attach(MinecraftServer server, KingdomsService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        KingdomsService previous = SERVICES.putIfAbsent(server, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("MCA: Kingdoms service is already attached to this server");
        }
    }

    static void detach(MinecraftServer server, KingdomsService service) {
        SERVICES.remove(Objects.requireNonNull(server, "server"), Objects.requireNonNull(service, "service"));
    }
}
