package dev.otectus.mcakingdoms.api.factions;

@FunctionalInterface
public interface FactionStandingMirror {
    void standingChanged(FactionStandingResult result, FactionStandingRequest request);
}
