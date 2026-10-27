package dev.otectus.mcakingdoms.factions.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.concurrent.atomic.AtomicBoolean;

public final class FactionConfig {
    public enum SyncMode { OFF, SHADOW, MCA_TO_FACTION, BIDIRECTIONAL_SEMANTIC }
    public enum LegacyAggregation { MEAN, MEDIAN, MAX, MIN, SUM }
    public enum ReassignmentPolicy { FREEZE_HISTORY }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<SyncMode> SYNC_MODE;
    public static final ForgeConfigSpec.DoubleValue LOCAL_DELTA_CONTRIBUTION;
    public static final ForgeConfigSpec.BooleanValue PROPAGATE_DECAY;
    public static final ForgeConfigSpec.BooleanValue PROPAGATE_ADMIN;
    public static final ForgeConfigSpec.IntValue FACTION_OVERLAY_MAX;
    public static final ForgeConfigSpec.LongValue RECEIPT_RETENTION_TICKS;
    public static final ForgeConfigSpec.IntValue MISSING_MAPPING_CAPACITY;
    public static final ForgeConfigSpec.EnumValue<ReassignmentPolicy> REASSIGNMENT_POLICY;
    public static final ForgeConfigSpec.EnumValue<LegacyAggregation> LEGACY_AGGREGATION;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("reputationSync");
        SYNC_MODE = builder.comment("How MCA Reputation and kingdom standing interact. Needs MCA Reputation; live modes need 0.6.1 or later.",
                        "OFF: MCA Reputation changes are not read.",
                        "SHADOW (observe only, the default): changes are read and the standing they would cause is recorded; standing does not change.",
                        "MCA_TO_FACTION: eligible MCA reputation changes in a settlement also change the player's standing with its kingdom.",
                        "BIDIRECTIONAL_SEMANTIC: as MCA_TO_FACTION, and scoped kingdom-side effects are also delivered to MCA communities.")
                .defineEnum("mode", SyncMode.SHADOW);
        LOCAL_DELTA_CONTRIBUTION = builder.comment("Share of an eligible MCA reputation change that becomes kingdom standing (0.5 = half, rounded). 0 ignores every change.")
                .defineInRange("localDeltaContribution", 0.50D, 0D, 4D);
        PROPAGATE_DECAY = builder.comment("Also count MCA reputation decay. Deeds, resolutions and superseded records always count.")
                .define("propagateDecay", false);
        PROPAGATE_ADMIN = builder.comment("Also count MCA reputation changes made by operators or commands.")
                .define("propagateAdminChanges", false);
        FACTION_OVERLAY_MAX = builder.comment("Largest amount, up or down, that kingdom standing adds to a player's local MCA standing when the two are combined.")
                .defineInRange("factionOverlayMax", 50, 0, 1_000);
        RECEIPT_RETENTION_TICKS = builder.comment("Game ticks a processed reputation change is remembered so it is never applied twice (default 12096000 = 168 hours at 20 ticks per second).")
                .defineInRange("receiptRetentionTicks", 12_096_000L, 1L, Long.MAX_VALUE);
        MISSING_MAPPING_CAPACITY = builder.comment("Most reputation changes held for review when their settlement has no kingdom mapping yet; operators assign them with Assign an unmapped standing event.")
                .defineInRange("missingMappingCapacity", 2_048, 1, 100_000);
        REASSIGNMENT_POLICY = builder.comment("Informational: FREEZE_HISTORY is the only policy. Standing already earned stays with the kingdom it was earned under when a settlement changes kingdom.")
                .defineEnum("settlementReassignmentPolicy", ReassignmentPolicy.FREEZE_HISTORY);
        builder.pop();
        builder.push("migration");
        LEGACY_AGGREGATION = builder.comment("How a player's several legacy standing values for one kingdom are combined when they are imported: MEAN, MEDIAN, MAX, MIN or SUM.",
                        "Preview the import with Preview legacy reputation migration before applying it.")
                .defineEnum("legacyAggregation", LegacyAggregation.MEAN);
        builder.pop();
        SPEC = builder.build();
    }

    private FactionConfig() {
    }

    @SuppressWarnings("removal")
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC,
                    "mcakingdoms-factions-common.toml");
        }
    }
}
