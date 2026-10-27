package dev.otectus.mcakingdoms.warfare;

import net.minecraftforge.common.ForgeConfigSpec;

/** Warfare features default on; provider capability and actor authorization still govern each operation. */
public final class WarfareConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue MILITARY, CONTRACTS, TARGET_POLICY, WORLD_CONTEXT, PROTECT_CAPITALS, REQUIRE_DEFENDER_ONLINE;
    public static final ForgeConfigSpec.IntValue INTERVAL, BUDGET;
    public static final ForgeConfigSpec.IntValue NOTICE, CAMPAIGN_DURATION, MOBILIZATION_TICKS, UNIT_BUDGET;
    static {
        var builder = new ForgeConfigSpec.Builder().push("political_warfare");
        ENABLED = builder.comment("Enable the warfare features: territory, campaigns, accords, mobilization, civilian recovery and world context.",
                        "Disabling preserves every record; the switches below turn off one feature each.")
                .define("enabled", true);
        MILITARY = builder.comment("Campaigns, settlement control agreements and temporary recruit deployments. They also need Recruits 1.15.2.",
                        "Off refuses new military actions; existing records stay readable.")
                .define("militaryActions", true);
        CONTRACTS = builder.comment("Civilian recovery contracts offered by settlement contacts. Contracts that belong to an evolving-world opportunity or",
                        "protection obligation follow that system instead.")
                .define("civilianContracts", true);
        TARGET_POLICY = builder.comment("Limit what recruits and mapped native armies may attack: not their own faction or allies, and villagers, players under",
                        "safe conduct and neutral companions only under local enforcement rules.")
                .define("targetProtection", true);
        WORLD_CONTEXT = builder.comment("World sites, institution routes, route travel and encounters.").define("worldContext", true);
        PROTECT_CAPITALS = builder.comment("Refuse campaigns and control changes against a kingdom's capital seat.").define("protectCapitals", true);
        REQUIRE_DEFENDER_ONLINE = builder.comment("Bound settlement sieges require the native defending leader online. Does not change unbound native claims.")
                .define("requireDefenderOnline", true);
        NOTICE = builder.comment("Game ticks between declaring a campaign and its start (default 1200 = 1 minute at 20 ticks per second).")
                .defineInRange("campaignNoticeTicks", 1200, 20, 168000);
        CAMPAIGN_DURATION = builder.comment("Game ticks a campaign, and a proposed control agreement, stays open before it expires (default 168000 = 2 hours 20 minutes).")
                .defineInRange("campaignDurationTicks", 168000, 1200, 2400000);
        MOBILIZATION_TICKS = builder.comment("Game ticks a temporary recruit deployment lasts before the recruit's original orders are restored (default 6000 = 5 minutes).")
                .defineInRange("mobilizationTicks", 6000, 100, 24000);
        UNIT_BUDGET = builder.comment("Most recruits one player may have deployed at the same time.").defineInRange("unitsPerCommander", 16, 1, 64);
        INTERVAL = builder.comment("Ticks between bounded native snapshot reconciliation batches.").defineInRange("reconcileInterval", 100, 20, 1200);
        BUDGET = builder.comment("Settlement control records and campaigns examined per reconciliation batch.").defineInRange("claimsPerBatch", 8, 1, 32);
        builder.pop();
        SPEC = builder.build();
    }
    private WarfareConfig() { }
}
