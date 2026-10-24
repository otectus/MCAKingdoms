package dev.otectus.mcakingdoms.api.townstead;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class KingdomsTownsteadApi {
    private static final Map<MinecraftServer, TownsteadService> SERVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private KingdomsTownsteadApi() {
    }

    public static TownsteadService get(MinecraftServer server) {
        TownsteadService service = SERVICES.get(Objects.requireNonNull(server, "server"));
        if (service == null) throw new IllegalStateException("MCA: Kingdoms Townstead integration is not ready for this server");
        return service;
    }

    public static void attach(MinecraftServer server, TownsteadService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        TownsteadService previous = SERVICES.putIfAbsent(server, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("MCA: Kingdoms Townstead integration is already attached to this server");
        }
    }

    public static void detach(MinecraftServer server, TownsteadService service) {
        SERVICES.remove(Objects.requireNonNull(server, "server"), Objects.requireNonNull(service, "service"));
    }
}
