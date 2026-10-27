package dev.otectus.mcakingdoms.compat.townstead;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TownsteadIntegrationConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue ALLOW_INTERNAL_FALLBACK;
    public static final ForgeConfigSpec.IntValue BUILDING_RECONCILE_INTERVAL;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_SETTLEMENTS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_REACTIONS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BLUEPRINT_HEADER;
    public static final ForgeConfigSpec.IntValue BLUEPRINT_REQUEST_RANGE;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("townstead");
        ENABLED = builder.comment("Use Townstead, when installed, for building evidence, NPC life stages, calendar, needs, schedule, spirit and reactions.",
                        "Read when the server starts; restart after changing it.")
                .define("enabled", true);
        ALLOW_INTERNAL_FALLBACK = builder
                .comment("Permit isolated reflection for Townstead spirit and reaction surfaces missing from its public API.")
                .define("allowInternalFallback", true);
        BUILDING_RECONCILE_INTERVAL = builder
                .comment("Ticks between bounded building fingerprint checks for settlements with nearby players.")
                .defineInRange("buildingReconcileIntervalTicks", 200, 20, 24_000);
        MAX_ACTIVE_SETTLEMENTS = builder
                .comment("Most settlements near online players whose buildings are rechecked per interval.")
                .defineInRange("maxActiveSettlements", 32, 1, 256);
        ENABLE_REACTIONS = builder.comment("Let nearby Townstead villagers react when a player's kingdom standing changes.").define("enablePoliticalReactions", true);
        ENABLE_BLUEPRINT_HEADER = builder.comment("Show the settlement's name, kingdom and civic status above Townstead's Blueprint screen.").define("enableBlueprintCivicHeader", true);
        BLUEPRINT_REQUEST_RANGE = builder
                .comment("Maximum distance from a settlement anchor for Blueprint civic-header requests.")
                .defineInRange("blueprintRequestRange", 192, 32, 1_024);
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
