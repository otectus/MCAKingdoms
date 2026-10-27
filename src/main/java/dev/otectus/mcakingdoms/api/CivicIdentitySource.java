package dev.otectus.mcakingdoms.api;

public enum CivicIdentitySource {
    AUTOMATIC,
    MCA,
    ADDON,
    COMMAND,
    MIGRATION,
    /**
     * Declared by the player and verified by the server (0.3.0): their bed-set respawn point lies in the settlement and
     * they stood there when they registered. A record, not a land claim. Builds before 0.3.0 read it as MIGRATION.
     */
    REGISTERED
}
