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

    /** Recruits: Living Villages integration. Every switch here shapes Kingdoms' side; the provider's own config stays its own. */
    public static final class LivingVillages {
        public enum PlayerTargetPolicy { PROVIDER_AND_KINGDOMS, PROVIDER_ONLY }
        public enum CapitalPolicy { ALLOW_UNLESS_PROTECTED, PROTECT_ALL }
        public static ForgeConfigSpec.BooleanValue ENABLED, AUTO_BIND, AUTO_REALMS, PRESERVE_NAMES, AUTO_ADOPT, AI_WARS,
                RESPECT_CAPITALS, AI_TREATIES, ARBITRATION, PERSISTENT_RESIDENTS, RECRUIT_PROTECTED, ADOPTED_CONSTRUCTION,
                PROTECT_BUILDINGS, COMPANION_CONTEXT, DEDUPLICATE, MCA_RESIDENTS, ALLOW_UNPROTECTED;
        public static ForgeConfigSpec.EnumValue<PlayerTargetPolicy> PLAYER_TARGETS;
        public static ForgeConfigSpec.EnumValue<CapitalPolicy> CAPITALS;
        public static ForgeConfigSpec.IntValue TRUCE_TICKS, BATCH, BATCH_INTERVAL, RECONCILE_INTERVAL, MATCH_DISTANCE;
        private LivingVillages() { }
    }

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
        builder.comment("Recruits: Living Villages integration. Active only with Recruits 1.15.2 and the audited Living Villages 1.1.0 jar;",
                "any other tuple keeps saved bridge records read-only and makes no new bindings. Changing a switch never erases",
                "bindings, wars, treaties or residents: turning one off stops new operations of that kind.").push("livingVillages");
        LivingVillages.ENABLED = builder.comment("Use the integration when the supported tuple is installed.").define("enabled", true);
        LivingVillages.AUTO_BIND = builder.comment("Give every unambiguous Living Villages village one MCA: Kingdoms settlement, importing existing worlds",
                "in bounded batches. Ambiguous matches are kept for an operator to decide.").define("autoBindNativeVillages", true);
        LivingVillages.AUTO_REALMS = builder.comment("Keep a realm record for every native faction, including a player faction whose Ledger is not placed yet",
                "(a realm only: no settlement is made for it).").define("autoCreateRealmRecords", true);
        LivingVillages.PRESERVE_NAMES = builder.comment("An existing settlement bound to a Living Villages village keeps its name; the Living Villages name is kept",
                "on the binding. Off: an unlocked settlement takes the Living Villages name when no other settlement holds it, and keeps",
                "its own as an alias. A locked name is always kept.").define("preserveExistingNames", true);
        LivingVillages.AUTO_ADOPT = builder.comment("Place eligible generated villages under Living Villages management on their own, at most one per",
                "reconciliation interval, by the same preview the operator task shows (no blockers, not a capital seat). Off: only through",
                "the \"Enable Living Villages management here\" task.").define("adoptOrdinaryVillagesAutomatically", false);
        LivingVillages.AI_WARS = builder.comment("Let verified AI realms fight each other where Living Villages' own rules and MCA: Kingdoms' warfare switches allow.")
                .define("allowAiVsAiWars", true);
        LivingVillages.PLAYER_TARGETS = builder.comment("PROVIDER_AND_KINGDOMS: Living Villages' own exclusions plus MCA: Kingdoms' offline-defender protection",
                "for player-led targets (political_warfare.requireDefenderOnline). PROVIDER_ONLY: Living Villages' exclusions alone.")
                .defineEnum("playerTargetPolicy", LivingVillages.PlayerTargetPolicy.PROVIDER_AND_KINGDOMS);
        LivingVillages.CAPITALS = builder.comment("ALLOW_UNLESS_PROTECTED: an ordinary AI capital can be attacked; PROTECT_ALL: no realm capital can be.")
                .defineEnum("aiRealmCapitalPolicy", LivingVillages.CapitalPolicy.ALLOW_UNLESS_PROTECTED);
        LivingVillages.RESPECT_CAPITALS = builder.comment("Honour political_warfare.protectCapitals for a kingdom's capital seat that is also a Living Villages village.")
                .define("respectExistingCapitalProtection", true);
        LivingVillages.AI_TREATIES = builder.comment("Let AI realms accept or refuse proposed agreements on their own, by the recorded policy. Off: only ratifying",
                "officials of a kingdom associated with an AI realm for diplomacy can sign for it.").define("aiTreatyDecisions", true);
        LivingVillages.ARBITRATION = builder.comment("Keep one effective military relation between realms bound by an agreement: Living Villages' periodic opinions",
                "cannot silently overturn a peace or alliance in force.").define("nativeRelationArbitration", true);
        LivingVillages.TRUCE_TICKS = builder.comment("Game ticks after a peace or ceasefire before the same realms may fight again (24000 = one Minecraft day).")
                .defineInRange("minimumTruceTicks", 24000, 0, 2_400_000);
        LivingVillages.PERSISTENT_RESIDENTS = builder.comment("Keep the residents of integrated villages as the same persistent people: Living Villages may not discard",
                "or respawn them when players leave, at promotion, at cleanup or to balance its counts.").define("persistentResidents", true);
        LivingVillages.MCA_RESIDENTS = builder.comment("With MCA Reborn installed, new residents of integrated villages are created through MCA's own villager factory",
                "(either sex, MCA genetics and names) instead of vanilla villagers.").define("mcaResidents", true);
        LivingVillages.RECRUIT_PROTECTED = builder.comment("Allow Living Villages to turn a protected resident (married, with family, holding an office or named by a",
                "player) into a soldier or worker automatically. Off keeps them in their role.").define("automaticRecruitmentOfProtectedResidents", false);
        LivingVillages.ADOPTED_CONSTRUCTION = builder.comment("Let Living Villages construct, flatten, dig or restock livestock inside a village placed under its management",
                "deliberately. Off keeps every existing building, field and animal as it is.").define("constructionInAdoptedVillages", false);
        LivingVillages.PROTECT_BUILDINGS = builder.comment("Living Villages may not found a natural village, or choose a spot for a new building, on another settlement's",
                "ground, a player's protected home (development) or where a block entity of someone else's already stands (a bed, chest,",
                "barrel, furnace, lectern or sign); it tries another spot. Its existing buildings, upgrades and roads are its own.")
                .define("protectExternalBuildings", true);
        LivingVillages.COMPANION_CONTEXT = builder.comment("Give installed MCA companions (Quests, Conversations, Crime) the verified village, realm and war context, and",
                "grant the kingdom standing a supply delivery earns (which MCA: Reputation mirrors where it synchronizes).").define("companionContext", true);
        LivingVillages.DEDUPLICATE = builder.comment("Living Villages announces its wars, sieges, battles and conquests in chat itself; on, MCA: Kingdoms does not",
                "repeat them and announces only agreements, breaches, cessions, capital changes, defeats and adoptions. Off: it announces",
                "those too. Either way each announcement reaches a player once, after the save that made it durable.").define("deduplicateNotifications", true);
        LivingVillages.BATCH = builder.comment("Changed village records reconciled per batch.").defineInRange("changedRecordsPerBatch", 8, 1, 64);
        LivingVillages.BATCH_INTERVAL = builder.comment("Ticks between change batches.").defineInRange("batchIntervalTicks", 20, 1, 1200);
        LivingVillages.RECONCILE_INTERVAL = builder.comment("Ticks between bounded checks of known bindings for missed changes.")
                .defineInRange("reconciliationIntervalTicks", 200, 20, 24000);
        LivingVillages.MATCH_DISTANCE = builder.comment("How far (blocks) from a village centre an existing settlement's anchor may be and still be taken for the same place,",
                "when the settlement's bounds do not already contain the centre.").defineInRange("matchDistance", 48, 0, 256);
        LivingVillages.ALLOW_UNPROTECTED = builder.comment("Operator acknowledgement: start a world that has integrated residents even though the resident protection",
                "hooks are not active (an unaudited Living Villages build). Living Villages may then discard those residents.")
                .define("allowUnprotectedResidents", false);
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
