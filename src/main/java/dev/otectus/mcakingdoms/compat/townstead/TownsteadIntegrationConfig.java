package dev.otectus.mcakingdoms.compat.townstead;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code mcakingdoms-townstead-common.toml}. The 0.2.x keys keep their names and defaults; the 0.3.0 keys (spec 12.1)
 * switch only features that exist. There is deliberately no switch for career XP rewards, production-order writes or
 * storage withdrawals: Townstead offers no receipt-safe contract for them, so they are not implemented
 * ({@code docs/Townstead-0.8.0-Integration.md}).
 */
public final class TownsteadIntegrationConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue ALLOW_INTERNAL_FALLBACK;
    public static final ForgeConfigSpec.IntValue BUILDING_RECONCILE_INTERVAL;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_SETTLEMENTS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_REACTIONS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BLUEPRINT_HEADER;
    public static final ForgeConfigSpec.IntValue BLUEPRINT_REQUEST_RANGE;
    public static final ForgeConfigSpec.BooleanValue USE_PUBLIC_API;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VILLAGE_LIFE;
    public static final ForgeConfigSpec.IntValue VILLAGE_LIFE_REFRESH_TICKS;
    public static final ForgeConfigSpec.DoubleValue WELFARE_MINIMUM_COVERAGE;
    public static final ForgeConfigSpec.IntValue WELFARE_MAXIMUM_SAMPLE_AGE_DAYS;
    public static final ForgeConfigSpec.BooleanValue WELFARE_SUGGESTIONS;
    public static final ForgeConfigSpec.BooleanValue CAREER_REQUIREMENTS;
    public static final ForgeConfigSpec.BooleanValue POLITICAL_OWNERSHIP;
    public static final ForgeConfigSpec.BooleanValue CHRONICLE_PROJECTION;
    public static final ForgeConfigSpec.BooleanValue HERALDRY_FALLBACK;
    public static final ForgeConfigSpec.IntValue EVENT_QUEUE_CAPACITY;
    public static final ForgeConfigSpec.IntValue EVENT_BUDGET_PER_TICK;
    public static final ForgeConfigSpec.IntValue STOCK_REFRESH_TICKS;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> ADVISORY_STOCK;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("townstead");
        ENABLED = builder.comment("Use Townstead, when installed, for building evidence, NPC life stages, calendar, needs, schedule, spirit and reactions.",
                        "Read when the server starts; restart after changing it.")
                .define("enabled", true);
        ALLOW_INTERNAL_FALLBACK = builder
                .comment("Permit isolated reflection for Townstead 0.7.x spirit and reaction surfaces missing from its public API.",
                        "Townstead 0.8.0's public api.v1 has both, so this only affects 0.7.x installations.")
                .define("allowInternalFallback", true);
        BUILDING_RECONCILE_INTERVAL = builder
                .comment("Ticks between bounded building fingerprint checks for settlements with nearby players.",
                        "Townstead 0.8.0's building events refresh a settlement sooner; this poll catches what no event reports.")
                .defineInRange("buildingReconcileIntervalTicks", 200, 20, 24_000);
        MAX_ACTIVE_SETTLEMENTS = builder
                .comment("Most settlements near online players whose buildings are rechecked per interval; the rest wait their turn.")
                .defineInRange("maxActiveSettlements", 32, 1, 256);
        ENABLE_REACTIONS = builder.comment("Let nearby Townstead villagers react when a player's kingdom standing changes.").define("enablePoliticalReactions", true);
        ENABLE_BLUEPRINT_HEADER = builder.comment("Show the settlement's name, kingdom and civic status above Townstead's Blueprint screen.").define("enableBlueprintCivicHeader", true);
        BLUEPRINT_REQUEST_RANGE = builder
                .comment("Maximum distance from a settlement anchor for Blueprint civic-header requests.")
                .defineInRange("blueprintRequestRange", 192, 32, 1_024);
        USE_PUBLIC_API = builder
                .comment("Read Townstead 0.8.0 and later through its public api.v1 (reads, events, spirit, reactions). Off falls back to the 0.7.x facade.",
                        "Read when the server starts. Townstead's own world settings are never changed.")
                .define("usePublicApi", true);
        ENABLE_VILLAGE_LIFE = builder
                .comment("Offer the Village life, Village work and Village news pages (Townstead 0.8.0). Public summaries only; individual records are not shown.")
                .define("enableVillageLife", true);
        VILLAGE_LIFE_REFRESH_TICKS = builder
                .comment("Ticks a settlement's village-life reading is shared by every viewer before it is read again; Townstead's events refresh it sooner.")
                .defineInRange("villageLifeRefreshTicks", 600, 20, 72_000);
        WELFARE_MINIMUM_COVERAGE = builder
                .comment("Share of a village's roll Townstead must know before its welfare is described rather than called insufficient information.")
                .defineInRange("welfareMinimumCoverage", 0.5D, 0.0D, 1.0D);
        WELFARE_MAXIMUM_SAMPLE_AGE_DAYS = builder
                .comment("Oldest last-known reading, in Minecraft days, that still counts as current when no resident is loaded.")
                .defineInRange("welfareMaximumSampleAgeDays", 3, 0, 365);
        WELFARE_SUGGESTIONS = builder
                .comment("Suggest optional civic aid (existing tasks and commissions) when a village shows sustained, well-sampled need.",
                        "Suggestions never penalize anyone, create a mandatory task or change a village.")
                .define("welfareSuggestions", true);
        CAREER_REQUIREMENTS = builder
                .comment("Let qualifications and civic content require a Townstead profession tier or skill. Read only: no career is changed.")
                .define("careerRequirements", true);
        POLITICAL_OWNERSHIP = builder
                .comment("Resolve who governs each civic domain between MCA: Kingdoms, MCA Capitals and Townstead, and report conflicts.",
                        "Townstead 0.8.0 has no external-government contract, so political delegation itself is reported unavailable.",
                        "Operator adoption and release decisions are kept when this is switched off.")
                .define("politicalOwnership", true);
        CHRONICLE_PROJECTION = builder
                .comment("Tell Townstead's Chronicles about a few major committed events (a recognized institution, a concluded agreement, a completed public work).",
                        "Best effort and at most once; never used for any reward or decision; nothing is replayed from history.")
                .define("chronicleProjection", true);
        HERALDRY_FALLBACK = builder
                .comment("Paint plain shields issued to guards of a kingdom's settlements with the kingdom's mapped design when no local or faction arms apply.",
                        "Decorated or personal equipment is never changed. Off by default.")
                .define("heraldryFallback", false);
        EVENT_QUEUE_CAPACITY = builder
                .comment("Most distinct Townstead change notices waiting at once; further notices are counted as dropped and caught by the slow poll.")
                .defineInRange("eventQueueCapacity", 512, 16, 8_192);
        EVENT_BUDGET_PER_TICK = builder
                .comment("Townstead change notices handled per server tick.")
                .defineInRange("eventBudgetPerTick", 8, 1, 256);
        STOCK_REFRESH_TICKS = builder
                .comment("Ticks an advisory storage count is reused before Townstead is asked again (counts never reserve or pay for anything).")
                .defineInRange("stockRefreshTicks", 1_200, 100, 72_000);
        ADVISORY_STOCK = builder
                .comment("Item ids (or #tag ids) whose advisory counts the Village work page shows; at most 8 are read.")
                .defineListAllowEmpty(java.util.List.of("advisoryStock"), () -> java.util.List.of("minecraft:bread", "minecraft:wheat", "#minecraft:logs"),
                        value -> value instanceof String text && net.minecraft.resources.ResourceLocation.tryParse(text.startsWith("#") ? text.substring(1) : text) != null);
        builder.pop();
        SPEC = builder.build();
    }

    private TownsteadIntegrationConfig() {
    }

    @SuppressWarnings("removal")
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC,
                    "mcakingdoms-townstead-common.toml");
        }
    }
}
