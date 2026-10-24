package dev.otectus.mcakingdoms.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class McaKingdomsConfig {
    /** How detectors decide that two candidates are the same village. */
    public enum VillageIdentity { STRUCTURE, SPATIAL }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue DEFAULT_SETTLEMENT_RADIUS;
    public static final ForgeConfigSpec.IntValue POI_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue POI_MINIMUM_COUNT;
    public static final ForgeConfigSpec.IntValue CHUNK_SCANS_PER_TICK;
    public static final ForgeConfigSpec.IntValue CANDIDATE_MERGE_DISTANCE;
    public static final ForgeConfigSpec.EnumValue<VillageIdentity> VILLAGE_IDENTITY;
    public static final ForgeConfigSpec.IntValue STRUCTURE_MERGE_MARGIN;
    public static final ForgeConfigSpec.BooleanValue AUTO_RECONCILE;
    public static final ForgeConfigSpec.BooleanValue RECONCILE_ON_STARTUP;
    public static final ForgeConfigSpec.IntValue RECONCILE_STARTUP_CHUNK_BUDGET;
    public static final ForgeConfigSpec.IntValue RECONCILE_CHUNK_READS_PER_TICK;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> VILLAGE_STRUCTURE_TAGS;
    public static final ForgeConfigSpec.IntValue POI_LINK_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue POI_REQUIRE_HOME_OR_MEETING;
    public static final ForgeConfigSpec.BooleanValue POI_SURFACE_ONLY;
    public static final ForgeConfigSpec.DoubleValue CANDIDATE_OVERLAP_FRACTION;
    public static final ForgeConfigSpec.IntValue CIVIC_EVIDENCE_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue OPEN_FOUNDING;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("settlementDiscovery");
        DEFAULT_SETTLEMENT_RADIUS = builder
                .comment("Default horizontal territory radius for detected settlements.")
                .defineInRange("defaultSettlementRadius", 64, 16, 512);
        POI_SEARCH_RADIUS = builder
                .comment("Horizontal radius searched for village POIs when a loaded chunk is inspected.")
                .defineInRange("poiSearchRadius", 48, 16, 256);
        POI_MINIMUM_COUNT = builder
                .comment("Minimum village POIs needed to recognize a settlement.")
                .defineInRange("poiMinimumCount", 5, 1, 64);
        CHUNK_SCANS_PER_TICK = builder
                .comment("Maximum queued loaded chunks inspected per server tick.")
                .defineInRange("chunkScansPerTick", 2, 1, 64);
        CANDIDATE_MERGE_DISTANCE = builder
                .comment("Spatial fallback: anchor distance at which a candidate matches an existing settlement when no village record or shared",
                        "identity decides. With villageIdentity STRUCTURE, point-of-interest clusters outside generated villages use poiLinkDistance",
                        "instead.")
                .defineInRange("candidateMergeDistance", 32, 0, 256);
        VILLAGE_IDENTITY = builder
                .comment("STRUCTURE: every candidate inside a generated village's structure footprint is that village, and records found to",
                        "be pieces of one village are merged. SPATIAL: only the anchor-distance and overlap matching, without merging; for troubleshooting.")
                .defineEnum("villageIdentity", VillageIdentity.STRUCTURE);
        STRUCTURE_MERGE_MARGIN = builder
                .comment("Blocks beyond a generated village's structure box that still belong to that village.")
                .defineInRange("structureMergeMargin", 48, 0, 128);
        AUTO_RECONCILE = builder
                .comment("Merge settlement records that turn out to be pieces of one generated village.")
                .define("autoReconcile", true);
        RECONCILE_ON_STARTUP = builder
                .comment("Also reconcile every record once when the server starts, instead of only as its chunks are scanned.")
                .define("reconcileOnStartup", true);
        RECONCILE_STARTUP_CHUNK_BUDGET = builder
                .comment("Unloaded chunks the start-up reconciliation may read for structure references; the rest reconcile when their chunks load.")
                .defineInRange("reconcileStartupChunkBudget", 64, 0, 4096);
        RECONCILE_CHUNK_READS_PER_TICK = builder
                .comment("Chunk reads the start-up reconciliation spends per server tick.")
                .defineInRange("reconcileChunkReadsPerTick", 4, 1, 64);
        VILLAGE_STRUCTURE_TAGS = builder
                .comment("Structure tags or structure ids treated as generated villages. A leading # is optional.")
                .defineListAllowEmpty(List.of("villageStructureTags"), () -> List.of("minecraft:village"),
                        McaKingdomsConfig::validStructureSelector);
        POI_LINK_DISTANCE = builder
                .comment("Village POIs closer than this, horizontally, belong to one cluster.")
                .defineInRange("poiLinkDistance", 40, 8, 128);
        POI_REQUIRE_HOME_OR_MEETING = builder
                .comment("A POI cluster needs at least one bed or bell to count as a settlement.")
                .define("poiRequireHomeOrMeeting", true);
        POI_SURFACE_ONLY = builder
                .comment("Ignore POI clusters whose mean height is more than 24 blocks below the surface.")
                .define("poiSurfaceOnly", true);
        CANDIDATE_OVERLAP_FRACTION = builder
                .comment("Spatial fallback: share of the smaller area two bounds must overlap for a candidate to match an existing settlement when",
                        "no village record or shared identity decides. With villageIdentity STRUCTURE, point-of-interest clusters outside generated",
                        "villages use poiLinkDistance instead.")
                .defineInRange("candidateOverlapFraction", 0.5, 0.1, 1.0);
        builder.pop();
        builder.push("politics");
        OPEN_FOUNDING = builder.comment("Let a player whose civic residence is in the chosen capital found that kingdom's first government with themselves as leader; operators can always found.")
                .define("openFounding", true);
        builder.pop();
        builder.push("civicIdentity");
        CIVIC_EVIDENCE_INTERVAL = builder
                .comment("Ticks between positive home-evidence checks for each loaded NPC.")
                .defineInRange("evidenceInterval", 200, 20, 24_000);
        builder.pop();
        SPEC = builder.build();
    }

    private static boolean validStructureSelector(Object value) {
        return value instanceof String text
                && ResourceLocation.tryParse(text.startsWith("#") ? text.substring(1) : text) != null;
    }

    private McaKingdomsConfig() {
    }
}
