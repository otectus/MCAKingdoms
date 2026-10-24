package dev.otectus.mcakingdoms.factions;

import dev.otectus.mcakingdoms.api.McaCommunityRef;

import java.util.OptionalInt;
import java.util.UUID;

@FunctionalInterface
public interface LocalStandingProvider {
    LocalStandingProvider UNAVAILABLE = (player, community) -> OptionalInt.empty();

    OptionalInt score(UUID playerId, McaCommunityRef community);
}
