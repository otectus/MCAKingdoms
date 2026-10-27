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

    public static Group group(ActionRegistry.Task task) {
        if (task.operator()) return Group.OPERATOR;
        String prefix = task.id().substring(0, task.id().indexOf('.'));
        return switch (prefix) {
            case "settlement", "kingdom", "citizen" -> Group.PLACES;
            case "civic", "organization" -> Group.GUILDS;
            case "politics" -> Group.GOVERNMENT;
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
        RECRUITS("Recruits", "recruit_transfer_workflow");

        public final String english, learnMore;

        Feature(String english, String learnMore) {
            this.english = english;
            this.learnMore = learnMore;
        }

        Component subject() {
            return Component.translatableWithFallback("interaction.mcakingdoms.feature." + name().toLowerCase(Locale.ROOT), english);
        }
    }

    /** Whether a task can be used here, and if not, the one cause the server can establish. */
    public enum State { AVAILABLE, DISABLED, PROVIDER_MISSING, PROVIDER_UNSUPPORTED }

    public record Availability(State state, Component reason, String learnMore) {
        public static final Availability AVAILABLE = new Availability(State.AVAILABLE, Component.empty(), "");
        public boolean available() { return state == State.AVAILABLE; }
    }

    private static final Set<String> MILITARY_TASKS = Set.of("warfare.map_here", "warfare.bind_here", "warfare.declare", "warfare.retry_campaign",
            "warfare.withdraw", "warfare.accord", "warfare.accord_sign", "warfare.accord_apply", "warfare.accord_reject", "warfare.muster",
            "warfare.muster_nearby", "warfare.deployment_dismiss", "warfare.deployment_recover");
    private static final Set<String> CHAPTER_TASKS = Set.of("civic.introduction", "civic.commissions", "civic.charter", "civic.appoint", "civic.dismiss");
    private static final Set<String> EVOLUTION_TASKS = Set.of("evolution.contribute", "evolution.withdraw", "evolution.resolve", "evolution.retry",
            "evolution.release", "evolution.contract", "evolution.family");
    private static final Set<String> DRAMA_TASKS = Set.of("drama.propose", "drama.consent", "drama.negotiate", "drama.execute", "drama.exit", "drama.recover");
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
            Map.entry("warfare.deployment_recover", Intent.RESTORE));

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
        if (id.startsWith("warfare.")) out.add(Feature.WARFARE);
        if (MILITARY_TASKS.contains(id)) out.add(Feature.MILITARY);
        if (id.equals("warfare.contract")) out.add(Feature.CONTRACTS);
        if (id.startsWith("world.")) out.add(Feature.WORLD_CONTEXT);
        if (EVOLUTION_TASKS.contains(id)) out.add(Feature.EVOLUTION);
        if (DRAMA_TASKS.contains(id)) out.add(Feature.DRAMA);
        if (RECRUIT_TASKS.contains(id)) out.add(Feature.RECRUITS);
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
            case GOVERNMENT -> !ModList.get().isLoaded("mcacapitals") ? Availability.AVAILABLE
                    : new Availability(State.DISABLED, Component.translatableWithFallback("politics.mcakingdoms.founding.capitals",
                    "MCA: Capitals governs kingdoms on this server, so MCA: Kingdoms governments cannot be founded. Use the Capitals government system."), feature.learnMore);
            case WARFARE -> WarfareConfig.ENABLED.get() ? Availability.AVAILABLE : disabled(feature);
            case MILITARY -> !WarfareConfig.ENABLED.get() || !WarfareConfig.MILITARY.get() ? disabled(feature)
                    : ModList.get().isLoaded("recruits") ? recruits() ? Availability.AVAILABLE : unsupported(Feature.RECRUITS) : missing(Feature.RECRUITS);
            case CONTRACTS -> WarfareConfig.ENABLED.get() && WarfareConfig.CONTRACTS.get() ? Availability.AVAILABLE : disabled(feature);
            case WORLD_CONTEXT -> WarfareConfig.ENABLED.get() && WarfareConfig.WORLD_CONTEXT.get() ? Availability.AVAILABLE : disabled(feature);
            case EVOLUTION -> dev.otectus.mcakingdoms.evolution.EvolutionRuntime.get(server).worldSettings().enabled() ? Availability.AVAILABLE : disabled(feature);
            case DRAMA -> dev.otectus.mcakingdoms.evolution.EvolutionRuntime.dramaEnabled(server) ? Availability.AVAILABLE : disabled(feature);
            case RECRUITS -> !ModList.get().isLoaded("recruits") ? missing(feature) : recruits() ? Availability.AVAILABLE : unsupported(feature);
        };
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

    /** The first unavailable feature of {@code task}, or {@link Availability#AVAILABLE}. */
    public static Availability availability(ActionRegistry.Task task, MinecraftServer server) {
        for (Feature feature : features(task)) {
            Availability state;
            try {
                state = check(feature, server);
            } catch (RuntimeException unknown) {
                // A provider probe that fails says nothing certain; the task itself will explain if it cannot run.
                continue;
            }
            if (!state.available()) return state;
        }
        return Availability.AVAILABLE;
    }

    /** Translation key of a task's goal phrases ("paid work, earn money"), searched after its title. */
    public static String aliasesKey(ActionRegistry.Task task) {
        return "task.mcakingdoms." + task.id() + ".aliases";
    }
}
