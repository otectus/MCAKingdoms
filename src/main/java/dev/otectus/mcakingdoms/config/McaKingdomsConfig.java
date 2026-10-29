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
    public static final ForgeConfigSpec.BooleanValue PLAYER_REGISTRATION;
    public static final ForgeConfigSpec.BooleanValue POLITICAL_NOTICES;
    public static final ForgeConfigSpec.BooleanValue OFFICE_BADGES;
    public static final ForgeConfigSpec.IntValue RESIDENCE_MOVE_COOLDOWN;
    public static final ForgeConfigSpec.BooleanValue LEGACY_PROGRESS;
    public static final ForgeConfigSpec.EnumValue<CapitalsInterop> CAPITALS_INTEROP;
    public static final ForgeConfigSpec.BooleanValue PHYSICAL_HERALDRY;
    public static final ForgeConfigSpec.BooleanValue DYNASTY, DYNASTY_ANNOUNCEMENTS;
    public static final ForgeConfigSpec.EnumValue<dev.otectus.mcakingdoms.development.DevelopmentMode> DEVELOPMENT_MODE;
    public static final ForgeConfigSpec.BooleanValue DEVELOPMENT_CONSTRUCTION_PAUSED, DEVELOPMENT_ECONOMY_PAUSED;
    public static final ForgeConfigSpec.IntValue DEVELOPMENT_BLOCKS_PER_PASS, DEVELOPMENT_MAX_PROJECTS, DEVELOPMENT_PROJECTS_PER_SETTLEMENT,
            DEVELOPMENT_TRADE_CAP, DEVELOPMENT_HOME_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DEVELOPMENT_DIMENSIONS;
    public static final ForgeConfigSpec.IntValue DYNASTY_ACCEPTANCE, DYNASTY_MOURNING, DYNASTY_CLAIM_COOLDOWN;
    public static final ForgeConfigSpec.IntValue LEGITIMACY_LAW, LEGITIMACY_ELECTION, LEGITIMACY_COUNCIL, LEGITIMACY_LINEAGE,
            LEGITIMACY_APPOINTMENT, LEGITIMACY_RECOGNITION, LEGITIMACY_GOVERNANCE, LEGITIMACY_CONTEST;
    /** How MCA Capitals and MCA: Kingdoms share kingdoms (C2). */
    public enum CapitalsInterop { PER_KINGDOM, ALL_READ_ONLY }

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
        POLITICAL_NOTICES = builder
                .comment("Send committed foundings, accessions, vacancies and memorials to players who turned political notices on",
                        "in their own Kingdoms settings (off by default on each client). Only public facts are announced, once.")
                .define("politicalNotices", true);
        OFFICE_BADGES = builder
                .comment("Show public office holders' roles above them to nearby players (each player can hide badges in their settings).")
                .define("officeBadges", true);
        builder.pop();
        builder.push("civicIdentity");
        CIVIC_EVIDENCE_INTERVAL = builder
                .comment("Ticks between positive home-evidence checks for each loaded NPC.")
                .defineInRange("evidenceInterval", 200, 20, 24_000);
        PLAYER_REGISTRATION = builder
                .comment("Let players register or move their own civic residence from verified evidence: their bed-set respawn point",
                        "inside a recognized settlement, while they stand in it. It records a civic home only (no land, office or NPC home).")
                .define("playerRegistration", true);
        RESIDENCE_MOVE_COOLDOWN = builder
                .comment("Game ticks after any residence change before a player may move their residence again (24000 = one",
                        "Minecraft day of server ticks, not wall time). Operators can override it. 0 turns the wait off.")
                .defineInRange("residenceMoveCooldown", 24_000, 0, 2_400_000);
        builder.pop();
        builder.push("migration");
        LEGACY_PROGRESS = builder
                .comment("Opt-in, once per world: before players load, move advancement and statistic progress recorded under the",
                        "pre-rename namespace ultima_kingdoms: to mcakingdoms:. Each changed player file is kept as <file>.ultima-bak first.",
                        "Recipe-book entries are not rewritten; those recipes unlock again when their ingredients are held. Off by default.")
                .define("legacyProgress", false);
        builder.pop();
        builder.push("capitals");
        CAPITALS_INTEROP = builder
                .comment("With MCA Capitals installed: PER_KINGDOM lets Capitals govern the crown of each kingdom whose village is one of its",
                        "governing capitals (MCA: Kingdoms then refuses crown actions there and keeps its civic layer), while kingdoms without a",
                        "Capitals capital keep MCA: Kingdoms governments. ALL_READ_ONLY keeps every MCA: Kingdoms crown action read-only, as",
                        "before 0.3.0. Capitals is only read, never written. Ignored when Capitals is not installed.")
                .defineEnum("interop", CapitalsInterop.PER_KINGDOM);
        builder.pop();
        builder.push("heraldry");
        PHYSICAL_HERALDRY = builder
                .comment("Physical heraldry: kingdom banners, shields and noticeboards bind to a kingdom when placed, and authorized officials",
                        "seal charters at a seal press. Off: new placements stand for no kingdom and no charter is sealed or revoked; existing",
                        "records stay readable. The items exist either way, so worlds keep loading.")
                .define("enabled", true);
        builder.pop();
        builder.push("dynasty");
        DYNASTY = builder
                .comment("Dynastic succession and the court. Every government starts with the law Manual only, which changes nothing: until",
                        "its leader or an operator adopts another law, a vacant leadership waits for officials exactly as before 0.3.0. Off: no",
                        "law is adopted, no succession case opens or advances and no title is granted; existing records stay readable.")
                .define("enabled", true);
        DYNASTY_ACCEPTANCE = builder
                .comment("Game ticks a player heir has to accept or decline the crown before the succession waits for review (72000 = three",
                        "Minecraft days).")
                .defineInRange("acceptanceTicks", 72_000, 1_200, 2_400_000);
        DYNASTY_MOURNING = builder
                .comment("Game ticks a kingdom mourns a ruler whose death was observed: its pages say so and its banners darken. Shown only;",
                        "no villager or player is changed. 0 turns mourning off.")
                .defineInRange("mourningTicks", 24_000, 0, 2_400_000);
        DYNASTY_CLAIM_COOLDOWN = builder
                .comment("Game ticks after a claim is decided before the same person may file the same kind of claim again (an appeal).",
                        "0 turns the wait off.")
                .defineInRange("claimCooldownTicks", 24_000, 0, 2_400_000);
        DYNASTY_ANNOUNCEMENTS = builder
                .comment("Herald announcements: tell a kingdom's connected officials when its succession law changes, a succession opens under it,",
                        "someone renounces or declines, and a succession waits for review. The death or abdication itself is already a",
                        "political notice; the request to an heir or a council is sent either way.")
                .define("announcements", true);
        builder.comment("How much each fact counts in a ruler's legitimacy reading. Legitimacy only explains a claim; it changes no authority.")
                .push("legitimacy");
        LEGITIMACY_LAW = builder.comment("Seated under the kingdom's law of succession.").defineInRange("lawWeight", 2, 0, 10);
        LEGITIMACY_ELECTION = builder.comment("Chosen by the government's election.").defineInRange("electionWeight", 2, 0, 10);
        LEGITIMACY_COUNCIL = builder.comment("Confirmed by the council.").defineInRange("councilWeight", 1, 0, 10);
        LEGITIMACY_LINEAGE = builder.comment("Heir by a family relation MCA records.").defineInRange("lineageWeight", 1, 0, 10);
        LEGITIMACY_APPOINTMENT = builder.comment("Seated by the officials or as the founder.").defineInRange("appointmentWeight", 1, 0, 10);
        LEGITIMACY_RECOGNITION = builder.comment("Party to an agreement in force with another kingdom.").defineInRange("recognitionWeight", 1, 0, 10);
        LEGITIMACY_GOVERNANCE = builder.comment("Governs with at least two other officials seated.").defineInRange("governanceWeight", 1, 0, 10);
        LEGITIMACY_CONTEST = builder.comment("Subtracted for each open contest of the succession, at most two.").defineInRange("contestWeight", 2, 0, 10);
        builder.pop();
        builder.pop();
        builder.push("development");
        DEVELOPMENT_MODE = builder
                .comment("Settlement development and economy. OFF (the default, also for upgraded worlds): nothing is planned, saved or built.",
                        "OBSERVE: settlement pages explain development from verified buildings and completed works; nothing is debited or built.",
                        "PROJECTS: officials post public works that players fund with real deliveries; finishing one is a recorded milestone,",
                        "and no block is placed. MANAGED_GROWTH: as PROJECTS, and a funded work with a small template is built on its surveyed",
                        "plot in settlements that opted in, a few blocks at a time, in loaded chunks only. FULL_ECONOMY: as MANAGED_GROWTH, plus",
                        "an abstract, clearly labelled goods ledger and capped abstract trade between settlements of kingdoms with an",
                        "agreement in force; no real item or currency is ever created by it. Lowering the mode pauses work and keeps every record.")
                .defineEnum("mode", dev.otectus.mcakingdoms.development.DevelopmentMode.OFF);
        DEVELOPMENT_CONSTRUCTION_PAUSED = builder.comment("Pause all construction; funding and records continue.").define("constructionPaused", false);
        DEVELOPMENT_ECONOMY_PAUSED = builder.comment("Pause deliveries, abstract production and abstract trade; construction continues.").define("economyPaused", false);
        DEVELOPMENT_BLOCKS_PER_PASS = builder.comment("Most blocks construction places each second, across the whole server.").defineInRange("blocksPerPass", 4, 1, 32);
        DEVELOPMENT_MAX_PROJECTS = builder.comment("Most open works on the server at once.").defineInRange("maxProjects", 64, 1, 512);
        DEVELOPMENT_PROJECTS_PER_SETTLEMENT = builder.comment("Most open works in one settlement at once.").defineInRange("projectsPerSettlement", 4, 1, 16);
        DEVELOPMENT_TRADE_CAP = builder.comment("Most abstract units of one commodity two settlements trade per Minecraft day (FULL_ECONOMY). 0 turns trade off.")
                .defineInRange("tradeCap", 8, 0, 64);
        DEVELOPMENT_HOME_RADIUS = builder.comment("Blocks around a player's protected home (their bed) that no construction touches.").defineInRange("homeProtectionRadius", 8, 0, 32);
        DEVELOPMENT_DIMENSIONS = builder.comment("Dimensions where construction may happen. Other dimensions are never built in.")
                .defineListAllowEmpty(List.of("dimensions"), () -> List.of("minecraft:overworld"), value -> value instanceof String text && ResourceLocation.tryParse(text) != null);
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
