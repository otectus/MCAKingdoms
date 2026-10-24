package dev.otectus.mcakingdoms.factions;

import dev.otectus.mcakingdoms.api.McaCommunityRef;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface LegacyStandingProvider {
    LegacyStandingProvider UNAVAILABLE = Optional::empty;

    Optional<List<LegacyStanding>> standings();

    record LegacyStanding(UUID playerId, McaCommunityRef community, int score, long revision) { }
}
