package dev.otectus.mcakingdoms.factions;

import dev.otectus.mcakingdoms.api.McaCommunityRef;
import dev.otectus.mcakingdoms.api.factions.LocalStandingEffectResult;

import java.util.UUID;

@FunctionalInterface
public interface LocalEffectProvider {
    LocalEffectProvider UNAVAILABLE = (player, community, delta, correlation, revision, description) ->
            LocalStandingEffectResult.UNAVAILABLE;

    LocalStandingEffectResult deliver(UUID playerId, McaCommunityRef community, int delta,
                                      UUID correlationId, long sourceRevision, String description);
}
