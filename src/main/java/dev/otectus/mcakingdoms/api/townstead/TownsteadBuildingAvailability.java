package dev.otectus.mcakingdoms.api.townstead;

/**
 * What can be said about a bound building now. Only {@link #CONFIRMED_REMOVED} is evidence that it is gone; every other
 * non-current answer means "wait", never "rebind" or "destroy".
 */
public enum TownsteadBuildingAvailability {
    /** Read now and present. */
    CURRENT,
    /** Present in the last good reading; the provider's current reading failed or was inconsistent. */
    STALE,
    /** Its dimension or position is not loaded, so nothing is read. */
    UNLOADED,
    /** The settlement or village record is missing, so nothing can be concluded. */
    UNKNOWN,
    /** No building capability is bound on this server. */
    PROVIDER_UNAVAILABLE,
    /** The village was read successfully and consistently, and the building is not in it. */
    CONFIRMED_REMOVED
}
