package dev.otectus.mcakingdoms.api.townstead;

/**
 * Independently probed Townstead integration capabilities. Constants are appended, never reordered or removed; the
 * first ten are the 0.7.x set. Whether a capability is bound and whether the world runs the system behind it are two
 * separate answers: {@link TownsteadService#capabilityStates()} gives both.
 */
public enum TownsteadCapability {
    READ_VILLAGER,
    READ_NEEDS,
    READ_SCHEDULE,
    READ_CALENDAR,
    READ_BUILDING,
    READ_BUILDINGS,
    READ_ORIGIN,
    READ_GENE,
    READ_SPIRIT,
    DISPATCH_REACTION,
    /** A village's dimension-qualified identity, roll, culture and range (Townstead api.v1). */
    READ_VILLAGE,
    /** Whether each building is complete, as MCA records it. */
    READ_BUILDING_COMPLETION,
    /** The last state Townstead saw for a resident, loaded or not. */
    READ_RESIDENT_RECORD,
    /** A village's aggregate needs over its known residents. */
    READ_NEEDS_SUMMARY,
    /** Careers, profession progression and learned skills. */
    READ_CAREERS,
    /** Worksites and their production orders. */
    READ_WORK_ORDERS,
    /** Advisory item counts across a village's recognized storage. */
    READ_STORAGE,
    /** The world's Townstead system switches and settings. */
    READ_SETTINGS,
    /** Townstead's committed-change event feed. */
    SUBSCRIBE_EVENTS,
    /** A village's public Chronicle digest. */
    READ_CHRONICLES,
    /** Narrative Chronicle emission for committed MCA: Kingdoms events. */
    EMIT_CHRONICLE,
    /** Townstead factions, governance and Seats, read only. */
    READ_POLITICS,
    /**
     * A supported contract through which Townstead accepts an external government. Townstead 0.8.0 publishes none,
     * so this is never bound by this release; see {@link TownsteadService#capabilityStates()} for the reason.
     */
    POLITICAL_DELEGATION,
    /** A villager's current hangout visit. */
    READ_HANGOUTS;

    public String serializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
