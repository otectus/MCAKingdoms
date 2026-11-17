package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.civic.CivicConfig;
import dev.otectus.mcakingdoms.warfare.WarfareConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.util.*;

/**
 * Stable presentation metadata for Kingdom Tasks: the category a task belongs to and the server features it needs.
 *
 * <p>Categories are keyed by stable ids, never by translated labels, so the client can filter in any language. A
 * feature check reads only configuration, installed mods and capability probes the owning services already expose: it
 * never loads chunks, scans entities or changes anything. It explains why a task cannot work on this server; the task's
 * own service still decides every request.
 */
public final class TaskMeta {
    private TaskMeta() {
    }

    /** The categories shown as filters in Kingdom Tasks, in display order. */
    public enum Group {
        PLACES("Places"), GUILDS("Guilds and jobs"), GOVERNMENT("Government"), EXPLORATION("Exploration"),
        CONFLICT("Conflict"), WORLD_EVENTS("World events"), HELP("Help"), OPERATOR("Operator tools");

        public final String english;

        Group(String english) {
            this.english = english;
        }

        public String key() {
            return "interaction.mcakingdoms.group." + name().toLowerCase(Locale.ROOT);
        }

        public Component label() {
            return Component.translatableWithFallback(key(), english);
        }
    }

    /**
     * Groups chosen by subject rather than by provider (AUD-15): Townstead's village pages are about places, its
     * government page about government, its career check about one's guild and job progression.
     */
    private static final Map<String, Group> EXPLICIT = Map.of(
            "townstead.life", Group.PLACES, "townstead.work", Group.PLACES, "townstead.news", Group.PLACES,
            "townstead.government", Group.GOVERNMENT, "townstead.qualify", Group.GUILDS);

    public static Group group(ActionRegistry.Task task) {
        if (task.operator()) return Group.OPERATOR;
        var explicit = EXPLICIT.get(task.id());
        if (explicit != null) return explicit;
        String prefix = task.id().substring(0, task.id().indexOf('.'));
        return switch (prefix) {
            case "settlement", "kingdom", "citizen", "development", "villages", "waystones" -> Group.PLACES;
            case "civic", "organization" -> Group.GUILDS;
            case "politics", "dynasty", "documents", "heraldry" -> Group.GOVERNMENT;
            case "world" -> Group.EXPLORATION;
            case "warfare", "transfer", "protection" -> Group.CONFLICT;
            case "evolution", "drama" -> Group.WORLD_EVENTS;
            default -> Group.HELP;
        };
    }

    /** A server feature a task depends on; {@link #learnMore} is the Book topic that explains it. */
    public enum Feature {
        CHAPTER_SERVICES("guild chapter services", "first_guild_steps"),
        INSTITUTIONAL_SERVICES("institutional guild services", "workshop_commissions"),
        GUILD_QUESTS("MCA Quests", "optional_features"),
        INSTITUTIONAL_QUESTS("MCA Quests paid workshop jobs", "workshop_commissions"),
        WORKSHOP_LEGALITY("MCA: Crime", "workshop_commissions"),
        GOVERNMENT("MCA: Kingdoms governments", "optional_features"),
        WARFARE("warfare", "warfare_ownership_and_setup"),
        MILITARY("military actions", "warfare_ownership_and_setup"),
        CONTRACTS("civilian recovery contracts", "civilian_contracts"),
        WORLD_CONTEXT("world sites, routes and encounters", "discover_world_sites"),
        EVOLUTION("world evolution", "evolution_world_opt_in"),
        DRAMA("world events", "authored_drama_workflow"),
        RECRUITS("Recruits", "recruit_transfer_workflow"),
        DYNASTY("dynastic succession", "dynasty_succession"),
        HERALDRY("physical heraldry", "heraldry_and_charters"),
        DEVELOPMENT("settlement development", "settlement_development"),
        DEVELOPMENT_PROJECTS("public works (development mode Projects or above)", "settlement_development"),
        DEVELOPMENT_CONSTRUCTION("construction of public works (development mode Managed growth or above)", "settlement_development"),
        DEVELOPMENT_DELIVERIES("deliveries to public works", "settlement_development"),
        LIVING_VILLAGES("Recruits: Living Villages", "living_villages"),
        TOWNSTEAD("Townstead 0.8.0 village life", "townstead_village_life"),
        WAYSTONES("settlement names for waystones", "waystone_names"),
        TOWNSTEAD_LIFE("Townstead village life", "townstead_village_life"),
        TOWNSTEAD_WORK("Townstead work orders", "townstead_village_life"),
        TOWNSTEAD_NEWS("Townstead village news", "townstead_village_life"),
        TOWNSTEAD_CAREERS("Townstead careers", "townstead_village_life");

        public final String english, learnMore;

        Feature(String english, String learnMore) {
            this.english = english;
            this.learnMore = learnMore;
        }

        Component subject() {
            return Component.translatableWithFallback("interaction.mcakingdoms.feature." + name().toLowerCase(Locale.ROOT), english);
        }
    }

    /**
     * Whether a task can be used here, and if not, the one cause the server can establish. {@code UNKNOWN} means a check
     * failed and nothing could be established (AUD-14): the task stays usable, is never presented as available, and its
     * own service decides.
     */
    public enum State { AVAILABLE, DISABLED, PROVIDER_MISSING, PROVIDER_UNSUPPORTED, UNKNOWN }

    public record Availability(State state, Component reason, String learnMore) {
        public static final Availability AVAILABLE = new Availability(State.AVAILABLE, Component.empty(), "");
        public boolean available() { return state == State.AVAILABLE; }
        /** Available, or not determinable now: the task may be opened and its service decides. */
        public boolean usable() { return state == State.AVAILABLE || state == State.UNKNOWN; }
    }

    private static final Set<String> MILITARY_TASKS = Set.of("warfare.map_here", "warfare.bind_here", "warfare.declare", "warfare.retry_campaign",
            "warfare.withdraw", "warfare.accord", "warfare.accord_sign", "warfare.accord_apply", "warfare.accord_reject", "warfare.muster",
            "warfare.muster_nearby", "warfare.deployment_dismiss", "warfare.deployment_recover");
    private static final Set<String> CHAPTER_TASKS = Set.of("civic.introduction", "civic.commissions", "civic.charter", "civic.appoint", "civic.dismiss");
    private static final Set<String> EVOLUTION_TASKS = Set.of("evolution.contribute", "evolution.withdraw", "evolution.resolve", "evolution.retry",
            "evolution.release", "evolution.contract", "evolution.family");
    private static final Set<String> DRAMA_TASKS = Set.of("drama.propose", "drama.consent", "drama.negotiate", "drama.execute", "drama.exit", "drama.recover");
    /** Pages that read Townstead's public api.v1; the government page and the operator tasks explain absence themselves. */
    private static final Set<String> TOWNSTEAD_TASKS = Set.of("townstead.life", "townstead.work", "townstead.news", "townstead.qualify");
    private static final Set<String> RECRUIT_TASKS = Set.of("transfer.propose", "transfer.share_destination", "transfer.consent", "transfer.apply",
            "transfer.confirm", "transfer.restore");

    /**
     * What a task does to an operation (F08), which decides the switches it needs. Starting or expanding needs the
     * feature turned on. Inspecting retained records, cancelling before any effect, verifying an effect that already
     * happened and restoring or reconciling one stay available while the feature is off, so turning a feature off never
     * strands an obligation; the native provider is still required where the step touches native state. The owning
     * services apply the same rules to commands, and still decide every request.
     */
    public enum Intent { START, INSPECT, CANCEL, VERIFY, RESTORE }

    private static final Map<String, Intent> INTENTS = Map.ofEntries(
            Map.entry("warfare.deployments", Intent.INSPECT), Map.entry("warfare.campaigns", Intent.INSPECT),
            Map.entry("warfare.campaign_overview", Intent.INSPECT), Map.entry("transfer.overview", Intent.INSPECT),
            Map.entry("transfer.inspect", Intent.INSPECT), Map.entry("drama.overview", Intent.INSPECT),
            Map.entry("drama.inspect", Intent.INSPECT), Map.entry("evolution.overview", Intent.INSPECT),
            Map.entry("evolution.inspect", Intent.INSPECT),
            Map.entry("transfer.cancel", Intent.CANCEL), Map.entry("evolution.withdraw", Intent.CANCEL),
            Map.entry("evolution.release", Intent.CANCEL), Map.entry("drama.exit", Intent.CANCEL),
            Map.entry("transfer.confirm", Intent.VERIFY), Map.entry("evolution.retry", Intent.VERIFY),
            Map.entry("transfer.restore", Intent.RESTORE), Map.entry("warfare.deployment_dismiss", Intent.RESTORE),
            Map.entry("warfare.deployment_recover", Intent.RESTORE),
            // AUD-08: stopping, withdrawing and recovering development, documents and court records need no creation switch.
            Map.entry("development.read", Intent.INSPECT), Map.entry("development.status", Intent.INSPECT),
            Map.entry("development.pause", Intent.CANCEL), Map.entry("development.cancel", Intent.CANCEL), Map.entry("development.opt_out", Intent.CANCEL),
            Map.entry("development.rollback", Intent.RESTORE), Map.entry("development.refunds", Intent.RESTORE),
            Map.entry("documents.inspect", Intent.INSPECT), Map.entry("documents.browse", Intent.INSPECT),
            Map.entry("documents.revoke", Intent.CANCEL), Map.entry("documents.recover", Intent.RESTORE),
            Map.entry("dynasty.court", Intent.INSPECT), Map.entry("dynasty.withdraw", Intent.CANCEL),
            Map.entry("dynasty.resign_title", Intent.CANCEL), Map.entry("dynasty.revoke_title", Intent.CANCEL));

    /** The task's intent; anything not listed starts or expands an operation. */
    public static Intent intent(ActionRegistry.Task task) {
        return INTENTS.getOrDefault(task.id(), Intent.START);
    }

    /** The features {@code task} needs, in the order they are checked. */
    public static List<Feature> features(ActionRegistry.Task task) {
        String id = task.id();
        List<Feature> out = new ArrayList<>();
        switch (intent(task)) {
            case INSPECT, CANCEL -> { return out; }
            case VERIFY, RESTORE -> {
                // A native step needs the provider that holds the state; never the switch that allows new operations.
                if (RECRUIT_TASKS.contains(id) || id.startsWith("warfare.deployment_")) out.add(Feature.RECRUITS);
                return out;
            }
            case START -> { }
        }
        if (CHAPTER_TASKS.contains(id)) out.add(Feature.CHAPTER_SERVICES);
        if (id.equals("civic.hospitality") || id.equals("civic.workshop")) out.add(Feature.INSTITUTIONAL_SERVICES);
        if (id.equals("civic.commissions") || id.equals("civic.workshop")) out.add(Feature.GUILD_QUESTS);
        if (id.equals("civic.workshop")) out.add(Feature.INSTITUTIONAL_QUESTS);
        if (id.equals("civic.workshop")) out.add(Feature.WORKSHOP_LEGALITY);
        if (id.startsWith("politics.") && task.consequential()) out.add(Feature.GOVERNMENT);
        if (id.startsWith("dynasty.") && task.consequential()) { out.add(Feature.DYNASTY); out.add(Feature.GOVERNMENT); }
        if (id.equals("documents.seal") || id.equals("heraldry.colours")) out.add(Feature.HERALDRY);
        if (id.startsWith("development.") && task.consequential()) {
            // Each decision names the mode its service needs, so the list never offers what the service would refuse (AUD-14).
            switch (id) {
                case "development.propose", "development.approve", "development.resume" -> out.add(Feature.DEVELOPMENT_PROJECTS);
                case "development.contribute" -> { out.add(Feature.DEVELOPMENT_PROJECTS); out.add(Feature.DEVELOPMENT_DELIVERIES); }
                case "development.repair" -> out.add(Feature.DEVELOPMENT_CONSTRUCTION);
                default -> out.add(Feature.DEVELOPMENT);
            }
        }
        if (id.startsWith("warfare.")) out.add(Feature.WARFARE);
        if (MILITARY_TASKS.contains(id)) out.add(Feature.MILITARY);
        if (id.equals("warfare.contract")) out.add(Feature.CONTRACTS);
        if (id.startsWith("world.")) out.add(Feature.WORLD_CONTEXT);
        if (EVOLUTION_TASKS.contains(id)) out.add(Feature.EVOLUTION);
        if (DRAMA_TASKS.contains(id)) out.add(Feature.DRAMA);
        if (RECRUIT_TASKS.contains(id)) out.add(Feature.RECRUITS);
        if (id.startsWith("villages.") || id.startsWith("warfare.realm_")) out.add(Feature.LIVING_VILLAGES);
        if (TOWNSTEAD_TASKS.contains(id)) {
            out.add(Feature.TOWNSTEAD);
            // AUD-14: each page also needs its own switch and the capability it reads, not just a v1 backend.
            switch (id) {
                case "townstead.life" -> out.add(Feature.TOWNSTEAD_LIFE);
                case "townstead.work" -> out.add(Feature.TOWNSTEAD_WORK);
                case "townstead.news" -> out.add(Feature.TOWNSTEAD_NEWS);
                case "townstead.qualify" -> out.add(Feature.TOWNSTEAD_CAREERS);
                default -> { }
            }
        }
        if (id.startsWith("waystones.") && task.consequential()) out.add(Feature.WAYSTONES);
        return out;
    }

    private static boolean recruits() {
        return ModList.get().getModContainerById("recruits").map(m -> m.getModInfo().getVersion().toString().equals("1.15.2")).orElse(false);
    }

    /** One feature's current state on this server. */
    public static Availability check(Feature feature, MinecraftServer server) {
        return switch (feature) {
            case CHAPTER_SERVICES -> CivicConfig.ENABLED.get() ? Availability.AVAILABLE : disabled(feature);
            case INSTITUTIONAL_SERVICES -> CivicConfig.ENABLED.get() && CivicConfig.INSTITUTIONAL_SERVICES.get() ? Availability.AVAILABLE : disabled(feature);
            case GUILD_QUESTS -> switch (dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.capability()) {
                case "available" -> Availability.AVAILABLE;
                case "absent" -> missing(feature);
                default -> unsupported(feature);
            };
            case INSTITUTIONAL_QUESTS -> switch (dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.institutionalCapability()) {
                case "available" -> Availability.AVAILABLE;
                case "absent" -> missing(Feature.GUILD_QUESTS);
                default -> unsupported(feature);
            };
            case WORKSHOP_LEGALITY -> !ModList.get().isLoaded("mcacrime") ? missing(feature)
                    : dev.otectus.mcakingdoms.compat.crime.InstitutionalCrimeBridge.providerPresent() ? Availability.AVAILABLE : unsupported(feature);
            // With MCA Capitals installed, government tasks stay open: the service refuses only the crown actions of the kingdoms
            // Capitals governs (C2). They close only when every crown is read-only (unreadable Capitals or ALL_READ_ONLY).
            case GOVERNMENT -> !dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.installed()
                    || dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.capabilities().readable()
                    && dev.otectus.mcakingdoms.config.McaKingdomsConfig.CAPITALS_INTEROP.get() == dev.otectus.mcakingdoms.config.McaKingdomsConfig.CapitalsInterop.PER_KINGDOM
                    ? Availability.AVAILABLE
                    : new Availability(State.DISABLED, Component.translatableWithFallback("politics.mcakingdoms.sovereignty.capitals_unsupported",
                    "MCA Capitals is installed but cannot be read safely, so every MCA: Kingdoms crown action is read-only."), feature.learnMore);
            case WARFARE -> WarfareConfig.enabled() ? Availability.AVAILABLE : disabled(feature);
            case MILITARY -> !WarfareConfig.enabled() || !WarfareConfig.MILITARY.get() ? disabled(feature)
                    : ModList.get().isLoaded("recruits") ? recruits() ? Availability.AVAILABLE : unsupported(Feature.RECRUITS) : missing(Feature.RECRUITS);
            case CONTRACTS -> WarfareConfig.enabled() && WarfareConfig.CONTRACTS.get() ? Availability.AVAILABLE : disabled(feature);
            case WORLD_CONTEXT -> WarfareConfig.enabled() && WarfareConfig.WORLD_CONTEXT.get() ? Availability.AVAILABLE : disabled(feature);
            case EVOLUTION -> dev.otectus.mcakingdoms.evolution.EvolutionRuntime.get(server).worldSettings().enabled() ? Availability.AVAILABLE : disabled(feature);
            case DRAMA -> dev.otectus.mcakingdoms.evolution.EvolutionRuntime.dramaEnabled(server) ? Availability.AVAILABLE : disabled(feature);
            case RECRUITS -> !ModList.get().isLoaded("recruits") ? missing(feature) : recruits() ? Availability.AVAILABLE : unsupported(feature);
            case DYNASTY -> dev.otectus.mcakingdoms.politics.dynasty.DynastyConfig.enabled() ? Availability.AVAILABLE : disabled(feature);
            case HERALDRY -> dev.otectus.mcakingdoms.heraldry.HeraldryConfig.enabled() ? Availability.AVAILABLE : disabled(feature);
            case DEVELOPMENT -> dev.otectus.mcakingdoms.development.DevelopmentMode.current() != dev.otectus.mcakingdoms.development.DevelopmentMode.OFF ? Availability.AVAILABLE : disabled(feature);
            case DEVELOPMENT_PROJECTS -> dev.otectus.mcakingdoms.development.DevelopmentMode.current().atLeast(dev.otectus.mcakingdoms.development.DevelopmentMode.PROJECTS) ? Availability.AVAILABLE : disabled(feature);
            case DEVELOPMENT_CONSTRUCTION -> dev.otectus.mcakingdoms.development.DevelopmentMode.current().atLeast(dev.otectus.mcakingdoms.development.DevelopmentMode.MANAGED_GROWTH) ? Availability.AVAILABLE : disabled(feature);
            case DEVELOPMENT_DELIVERIES -> !dev.otectus.mcakingdoms.development.DevelopmentMode.economyPaused() ? Availability.AVAILABLE : disabled(feature);
            case TOWNSTEAD -> !ModList.get().isLoaded("townstead") ? missing(feature)
                    : dev.otectus.mcakingdoms.compat.townstead.TownsteadRuntime.session(server).map(s -> s.backend().equals("v1")).orElse(false)
                    ? Availability.AVAILABLE : !dev.otectus.mcakingdoms.compat.townstead.TownsteadIntegrationConfig.ENABLED.get() ? disabled(feature) : unsupported(feature);
            case TOWNSTEAD_LIFE -> townsteadPage(feature, server, dev.otectus.mcakingdoms.compat.townstead.TownsteadIntegrationConfig.ENABLE_VILLAGE_LIFE.get(),
                    dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_VILLAGE);
            case TOWNSTEAD_WORK -> townsteadPage(feature, server, dev.otectus.mcakingdoms.compat.townstead.TownsteadIntegrationConfig.ENABLE_VILLAGE_LIFE.get(),
                    dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_WORK_ORDERS);
            case TOWNSTEAD_NEWS -> townsteadPage(feature, server, dev.otectus.mcakingdoms.compat.townstead.TownsteadIntegrationConfig.ENABLE_VILLAGE_LIFE.get(),
                    dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_CHRONICLES);
            case TOWNSTEAD_CAREERS -> townsteadPage(feature, server, true, dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_CAREERS);
            case WAYSTONES -> switch (dev.otectus.mcakingdoms.compat.waystones.WaystonesIntegration.status().readiness()) {
                case READY -> Availability.AVAILABLE;
                case ABSENT -> missing(feature);
                case DISABLED -> disabled(feature);
                case UNSUPPORTED -> unsupported(feature);
            };
            // One shared capability snapshot decides the tasks, the Book and the services alike.
            case LIVING_VILLAGES -> {
                if (!dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.installed()) yield missing(feature);
                var state = dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.snapshot(server).of(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.Capability.STABLE_BINDING);
                yield switch (state.readiness()) {
                    case READY, PARTIAL -> dev.otectus.mcakingdoms.compat.livingvillages.LvRuntime.get(server).isPresent() ? Availability.AVAILABLE : disabled(feature);
                    case UNAVAILABLE -> disabled(feature);
                    default -> unsupported(feature);
                };
            }
        };
    }

    /** One Townstead page: its switch, then the capability it reads from the installed Townstead. */
    private static Availability townsteadPage(Feature feature, MinecraftServer server, boolean switchOn, dev.otectus.mcakingdoms.api.townstead.TownsteadCapability capability) {
        if (!switchOn) return disabled(feature);
        return dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi.get(server).capabilities().contains(capability) ? Availability.AVAILABLE : unsupported(feature);
    }

    private static Availability disabled(Feature feature) {
        return new Availability(State.DISABLED, Unavailable.DISABLED.explain(feature.subject()), feature.learnMore);
    }

    private static Availability missing(Feature feature) {
        return new Availability(State.PROVIDER_MISSING, Unavailable.PROVIDER_MISSING.explain(feature.subject()), feature.learnMore);
    }

    private static Availability unsupported(Feature feature) {
        return new Availability(State.PROVIDER_UNSUPPORTED, Unavailable.PROVIDER_UNSUPPORTED.explain(feature.subject()), feature.learnMore);
    }

    /**
     * The first unavailable feature of {@code task}, or {@link Availability#AVAILABLE}. A probe that fails is "could not
     * determine", never proof of availability (AUD-14); a feature that is certainly off still wins over an unknown one.
     */
    public static Availability availability(ActionRegistry.Task task, MinecraftServer server) {
        Availability unknown = null;
        for (Feature feature : features(task)) {
            Availability state;
            try {
                state = check(feature, server);
            } catch (RuntimeException | LinkageError failure) {
                if (unknown == null) unknown = unknown(feature);
                continue;
            }
            if (!state.available()) return state;
        }
        return unknown == null ? Availability.AVAILABLE : unknown;
    }

    static Availability unknown(Feature feature) {
        return new Availability(State.UNKNOWN, Component.translatableWithFallback("interaction.mcakingdoms.unavailable.unknown",
                "Whether %s works here could not be checked just now; the task checks again when you use it.", feature.subject()), feature.learnMore);
    }

    /** One feature's state, with a failing probe reported as {@code UNKNOWN} (for summaries and the Book's status). */
    public static Availability safeCheck(Feature feature, MinecraftServer server) {
        try { return check(feature, server); }
        catch (RuntimeException | LinkageError failure) { return unknown(feature); }
    }

    /** Translation key of a task's goal phrases ("paid work, earn money"), searched after its title. */
    public static String aliasesKey(ActionRegistry.Task task) {
        return "task.mcakingdoms." + task.id() + ".aliases";
    }
}
