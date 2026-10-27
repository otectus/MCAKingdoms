package dev.otectus.mcakingdoms.integration;

import com.google.gson.JsonObject;
import dev.otectus.mcakingdoms.api.gating.UnknownKingdomPolicy;
import net.minecraftforge.common.ForgeConfigSpec;

/** Common settings for the shared kingdom-gating integration layer. */
public final class IntegrationConfig {
    public static final String FILE_NAME = "mcakingdoms-integrations-common.toml";
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue KINGDOM_GATING_ENABLED;
    public static final ForgeConfigSpec.EnumValue<UnknownKingdomPolicy> DEFAULT_UNKNOWN_POLICY;
    public static final ForgeConfigSpec.BooleanValue CACHE_BY_SETTLEMENT_REVISION;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("kingdomGating");
        KINGDOM_GATING_ENABLED = builder
                .comment("Enable server-owned kingdom gates for optional addon content.")
                .define("enabled", true);
        DEFAULT_UNKNOWN_POLICY = builder
                .comment("What a kingdom gate answers when the kingdom cannot be determined, for MCA: Kingdoms datapack gates and MCA Quests",
                        "conditions that omit when_unknown. A predicate that states when_unknown keeps it. Gates parsed by an addon through",
                        "KingdomGateApi keep that API's documented DENY default. Read when gates are parsed: a change applies at the next data pack reload.")
                .defineEnum("unknownKingdomPolicy", UnknownKingdomPolicy.DENY);
        CACHE_BY_SETTLEMENT_REVISION = builder
                .comment("Reserved: no current integration adapter caches settlement contexts, so this has no effect; every check reads current records.")
                .define("cacheBySettlementRevision", true);
        builder.pop();
        SPEC = builder.build();
    }

    /** The configured answer for predicates without {@code when_unknown}; DENY before the config has loaded (unit tests, early start). */
    public static UnknownKingdomPolicy unknownKingdomPolicy() {
        return SPEC.isLoaded() ? DEFAULT_UNKNOWN_POLICY.get() : UnknownKingdomPolicy.DENY;
    }

    /** {@code predicate} with the configured {@code when_unknown} filled in when its author omitted it; the argument is not modified. */
    public static JsonObject withUnknownPolicy(JsonObject predicate) {
        if (predicate.has("when_unknown")) return predicate;
        JsonObject completed = predicate.deepCopy();
        completed.addProperty("when_unknown", unknownKingdomPolicy().serializedName());
        return completed;
    }

    private IntegrationConfig() {
    }
}
