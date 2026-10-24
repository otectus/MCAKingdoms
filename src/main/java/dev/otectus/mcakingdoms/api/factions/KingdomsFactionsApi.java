package dev.otectus.mcakingdoms.api.factions;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class KingdomsFactionsApi {
    private static final Map<MinecraftServer, KingdomsFactionsService> SERVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private KingdomsFactionsApi() {
    }

    public static KingdomsFactionsService get(MinecraftServer server) {
        KingdomsFactionsService service = SERVICES.get(Objects.requireNonNull(server, "server"));
        if (service == null) throw new IllegalStateException("MCA: Kingdoms Factions is not ready for this server");
        return service;
    }

    public static void attach(MinecraftServer server, KingdomsFactionsService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        KingdomsFactionsService previous = SERVICES.putIfAbsent(server, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("MCA: Kingdoms Factions is already attached to this server");
        }
    }

    public static void detach(MinecraftServer server, KingdomsFactionsService service) {
        SERVICES.remove(Objects.requireNonNull(server, "server"), Objects.requireNonNull(service, "service"));
    }
}
