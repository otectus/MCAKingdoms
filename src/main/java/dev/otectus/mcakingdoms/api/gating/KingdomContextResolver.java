package dev.otectus.mcakingdoms.api.gating;

import dev.otectus.mcakingdoms.api.KingdomsService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/** Resolves authored gate subjects without discovering or mutating settlements. */
@FunctionalInterface
public interface KingdomContextResolver {
    Optional<KingdomContext> resolve(
            KingdomsService service,
            ServerPlayer player,
            Entity giver,
            KingdomSubject subject,
            Optional<UUID> explicitSettlementId
    );
}
