package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.civic.CivicConfig;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import dev.otectus.mcakingdoms.data.ReloadReport;
import dev.otectus.mcakingdoms.factions.config.FactionConfig;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.persistence.LegacyRename;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import dev.otectus.mcakingdoms.warfare.WarfareConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What this server enables, from the owning services and adapters themselves. Installed, compatible, enabled, supplied
 * with content and permitted to you are separate facts and are reported separately. The player view shows only public
 * capability facts and the viewer's own state; the operator view adds switches, storage, reload, migration and
 * reconciliation detail.
 */
public final class ServerFacts {
    private ServerFacts() {
    }

    private static Component f(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("facts.mcakingdoms." + key, fallback, args);
    }

    private static Component heading(String key, String fallback) {
        return f(key, fallback).copy().withStyle(ChatFormatting.BOLD);
    }

    private static Component onOff(boolean on) {
        return on ? f("on", "on") : f("off", "off");
    }

    private static Optional<String> version(String mod) {
        return ModList.get().getModContainerById(mod).map(container -> container.getModInfo().getVersion().toString());
    }

    private static Component installed(String mod) {
        return version(mod).<Component>map(v -> f("installed", "installed (%s)", v)).orElse(f("absent", "not installed"));
    }

    /** "What is available here?": public capability facts and the viewer's own state; nothing private or administrative. */
    public static List<Object> forPlayer(ServerPlayer viewer) {
        MinecraftServer server = viewer.getServer();
        List<Object> lines = new ArrayList<>();
        var knowledge = SettlementKnowledge.get(server);
        lines.add(heading("settlements.title", "Settlements and kingdoms"));
        lines.add(f("settlements.player", "Always on. You know %s settlements; the Village Ledger and Read a known settlement list them.",
                knowledge.all(viewer, McaKingdomsApi.get(server), Optional.empty(), 4096).size()));
        if (!knowledge.writable()) lines.add(Unavailable.READ_ONLY.explain(f("settlements.discovery", "new discoveries are not recorded until an operator repairs the discovery file")));
        var mca = dev.otectus.mcakingdoms.compat.mca.McaCompat.status();
        lines.add(mca.installed().isEmpty() ? f("mca.absent", "MCA Reborn: not installed. Settlements and records work without it; villagers' home evidence is unavailable.")
                : mca.evidence() ? f("mca.ok", "MCA Reborn: installed; villagers' confirmed homes record their civic residence.")
                : Unavailable.PROVIDER_UNSUPPORTED.explain(f("mca.name", "MCA Reborn %s", mca.installed().get())));

        lines.add(heading("guilds.title", "Guilds and service"));
        lines.add(guildLine(server));
        lines.add(workshopLine());

        lines.add(heading("government.title", "Governments"));
        if (dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.installed()) lines.add(f("government.capitals_shared", "MCA Capitals is installed: it governs the crown of each kingdom where it has a governing capital, and MCA: Kingdoms keeps every other kingdom and its civic layer. A kingdom's page says who governs it."));
        else lines.add(McaKingdomsConfig.OPEN_FOUNDING.get()
                ? f("government.open", "Available. Resident founding is on: a recorded resident may found their kingdom's first government. Search Check whether you can found a government.")
                : f("government.closed", "Available. Resident founding is off: operators establish governments."));

        lines.add(heading("warfare.title", "Warfare and exploration"));
        lines.add(warfareLine());
        lines.add(WarfareConfig.enabled() && WarfareConfig.CONTRACTS.get() ? f("contracts.on", "Civilian recovery contracts: on.") : Unavailable.DISABLED.explain(f("contracts.name", "civilian recovery contracts")));
        lines.add(WarfareConfig.enabled() && WarfareConfig.WORLD_CONTEXT.get() ? f("world.on", "World sites, routes and encounters: on.") : Unavailable.DISABLED.explain(f("world.name", "world sites, routes and encounters")));

        lines.add(heading("evolution.title", "Evolving world"));
        var evolution = dev.otectus.mcakingdoms.evolution.EvolutionRuntime.get(server).worldSettings();
        lines.add(!evolution.enabled() ? f("evolution.off", "Off for this world. Only an operator can enable it; existing records stay readable.")
                : f("evolution.on", "On for this world; world events (dramatic scenarios) are %s.", onOff(evolution.drama())));

        lines.add(heading("standing.title", "Kingdom standing"));
        lines.add(f("standing.mode", "Reputation synchronization: %s.", syncMode()));

        lines.add(heading("you.title", "You"));
        lines.add(viewer.hasPermissions(2) ? f("you.operator", "You are an operator: Administration tasks and Server health are available to you.")
                : f("you.player", "You see the tasks any player may use; administrative tasks are not listed for you."));
        lines.add(f("more", "Each task still checks its own requirements. A missing feature here is a server setting or an installed mod, not something you did wrong."));
        return lines;
    }

    static Component syncMode() {
        var mode = FactionConfig.SYNC_MODE.get();
        return switch (mode) {
            case SHADOW -> f("sync.shadow", "Observe only (SHADOW)");
            case OFF -> f("sync.off", "Off");
            case MCA_TO_FACTION -> f("sync.mca_to_faction", "MCA reputation feeds kingdom standing (MCA_TO_FACTION)");
            case BIDIRECTIONAL_SEMANTIC -> f("sync.bidirectional", "Both directions, scoped effects only (BIDIRECTIONAL_SEMANTIC)");
        };
    }

    private static Component guildLine(MinecraftServer server) {
        if (!CivicConfig.ENABLED.get()) return Unavailable.DISABLED.explain(f("guilds.name", "guild chapter services"));
        String quests = dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.capability();
        int organizations = dev.otectus.mcakingdoms.api.factions.organization.OrganizationApi.get(server).definitions().size();
        if (organizations == 0) return Unavailable.CONTENT_MISSING.explain(f("guilds.no_definitions", "no guild definitions are loaded from data packs"));
        return switch (quests) {
            case "available" -> f("guilds.ok", "On. %s guild(s) defined; MCA Quests records verified guild service.", organizations);
            case "absent" -> f("guilds.no_quests", "On, without guild credit: MCA Quests is not installed, so quest service cannot be verified.");
            default -> Unavailable.PROVIDER_UNSUPPORTED.explain(f("quests.name", "MCA Quests"));
        };
    }

    private static Component workshopLine() {
        if (!CivicConfig.ENABLED.get() || !CivicConfig.INSTITUTIONAL_SERVICES.get())
            return Unavailable.DISABLED.explain(f("workshop.name", "paid workshop commissions"));
        if (version("mcacrime").isEmpty()) return f("workshop.no_crime", "Paid workshop commissions: need MCA: Crime's workshop-legality provider, which is not installed.");
        if (!dev.otectus.mcakingdoms.compat.crime.InstitutionalCrimeBridge.providerPresent())
            return Unavailable.PROVIDER_UNSUPPORTED.explain(f("crime.name", "MCA: Crime"));
        return f("workshop.ok", "Paid workshop commissions: on, at recognized workshops.");
    }

    private static Component warfareLine() {
        if (!WarfareConfig.enabled()) return Unavailable.DISABLED.explain(f("warfare.name", "warfare"));
        if (!WarfareConfig.MILITARY.get()) return f("warfare.peaceful", "Military actions are off; civilian and exploration services follow their own switches below.");
        var recruits = version("recruits");
        if (recruits.isEmpty()) return f("warfare.no_recruits", "Military actions need Recruits 1.15.2, which is not installed.");
        if (!recruits.get().equals("1.15.2")) return f("warfare.recruits_unsupported", "Military actions are unavailable: Recruits %s is installed, and the integration is audited for 1.15.2 only.", recruits.get());
        return f("warfare.on", "Military actions: on, through Recruits 1.15.2.");
    }

    /** Server health: every fact the player view has plus switches, storage, reload, migration and reconciliation. */
    public static List<Object> forOperator(MinecraftServer server) {
        List<Object> lines = new ArrayList<>();
        lines.add(heading("health.providers", "Installed providers"));
        var mca = dev.otectus.mcakingdoms.compat.mca.McaCompat.status();
        lines.add(f("health.mca", "MCA Reborn: %s; settlement detection %s, home evidence %s.", installed("mca"), onOff(mca.detection()), onOff(mca.evidence())));
        lines.add(f("health.quests", "MCA Quests: %s; completion receipts: %s.", installed("mcaquests"), dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.capability()));
        lines.add(f("health.quests_contract", "MCA Quests contract: receipts are read as %s; paid workshop jobs: %s.",
                dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.consumerId().toString(),
                dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.institutionalCapability()));
        lines.add(f("health.townstead", "Townstead: %s; capabilities bound: %s.", installed("townstead"), townsteadCapabilities(server)));
        lines.add(f("health.political_presentation", "Political notices %s and office badges %s; they present this mod's own committed record, because Townstead offers no political narrative bridge to hand them to.",
                onOff(McaKingdomsConfig.POLITICAL_NOTICES.get()), onOff(McaKingdomsConfig.OFFICE_BADGES.get())));
        lines.add(f("health.recruits", "Recruits: %s; the military bridge is gated to exactly 1.15.2 and is %s.", installed("recruits"),
                version("recruits").filter("1.15.2"::equals).isPresent() ? f("health.bridge_active", "active") : f("health.bridge_inactive", "inactive")));
        lines.add(livingVillagesHealth(server));
        // H07: what the Recruits transfer save check costs on this server, so operators can see it under load.
        var fence = dev.otectus.mcakingdoms.compat.recruits.TransferFenceMetrics.snapshot();
        if (fence.count() > 0) lines.add(f("health.recruits_fence", "Recruits transfer save checks this session: %s; last %s ms, p95 %s ms, p99 %s ms, longest %s ms; the last saved %s entities with %s entity writes already queued. Server tick p95: %s ms.",
                fence.count(), millis(fence.lastNanos()), millis(fence.p95Nanos()), millis(fence.p99Nanos()), millis(fence.maxNanos()), fence.lastEntities(), fence.lastQueued() < 0 ? "?" : fence.lastQueued(), tickP95(server)));
        lines.add(f("health.crime", "MCA: Crime: %s; workshop-legality provider %s.", installed("mcacrime"),
                onOff(dev.otectus.mcakingdoms.compat.crime.InstitutionalCrimeBridge.providerPresent())));
        lines.add(f("health.reputation", "MCA Reputation: %s; synchronization %s.", installed("mcareputation"), syncMode()));
        lines.add(f("health.conversations", "MCA Conversations: %s.", installed("mcaconversations")));
        lines.addAll(capitalsHealth(server));
        var mode = dev.otectus.mcakingdoms.development.DevelopmentMode.current();
        if (mode != dev.otectus.mcakingdoms.development.DevelopmentMode.OFF) {
            var development = dev.otectus.mcakingdoms.development.DevelopmentMetrics.snapshot();
            lines.add(f("health.development", "Development (%s): %s pass(es) this session, last %s µs, longest %s µs; %s work(s) queued to build; %s block(s) placed.",
                    ActionRegistry.enumLabel(mode), development.passes(), development.lastMicros(), development.maxMicros(), development.queue(), development.placed()));
        }

        lines.add(heading("health.switches", "Switches"));
        lines.add(f("health.discovery", "Village identity %s; automatic reconciliation %s; start-up reconciliation %s; resident founding %s.",
                McaKingdomsConfig.VILLAGE_IDENTITY.get().name(), onOff(McaKingdomsConfig.AUTO_RECONCILE.get()),
                onOff(McaKingdomsConfig.RECONCILE_ON_STARTUP.get()), onOff(McaKingdomsConfig.OPEN_FOUNDING.get())));
        lines.add(f("health.civic", "Civic network %s; institutional services %s; contact range %s blocks.", onOff(CivicConfig.ENABLED.get()),
                onOff(CivicConfig.INSTITUTIONAL_SERVICES.get()), CivicConfig.CONTACT_RANGE.get()));
        lines.add(f("health.warfare", "Warfare %s: military actions %s, civilian contracts %s, world context %s, target protection %s.",
                onOff(WarfareConfig.enabled()), onOff(WarfareConfig.MILITARY.get()), onOff(WarfareConfig.CONTRACTS.get()),
                onOff(WarfareConfig.WORLD_CONTEXT.get()), onOff(WarfareConfig.TARGET_POLICY.get())));
        var evolution = dev.otectus.mcakingdoms.evolution.EvolutionRuntime.get(server).worldSettings();
        lines.add(f("health.evolution", "World evolution %s for this world (world events %s); automatic eligible regions %s; %s eligible and %s active regions.",
                onOff(evolution.enabled()), onOff(evolution.drama()), onOff(dev.otectus.mcakingdoms.evolution.EvolutionConfig.AUTO_ELIGIBLE.get()),
                evolution.eligibleRegions().size(), evolution.activeRegions().size()));
        lines.add(f("health.gates", "Kingdom gates for addon content %s.", onOff(dev.otectus.mcakingdoms.integration.IntegrationConfig.KINGDOM_GATING_ENABLED.get())));

        lines.add(heading("health.storage", "Saved data"));
        for (StoreStatus store : StoreStatus.all(server)) {
            lines.add(store.writable() ? f("health.store_ok", "%s: writable.", store.name())
                    : Component.empty().append(f("health.store_read_only", "%s: read-only — %s", store.name(), store.diagnostic())).withStyle(ChatFormatting.RED));
        }
        // H06: a write that could not be committed left the previous file whole; say which store and why.
        for (var failure : dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter.failures())
            lines.add(Component.empty().append(switch (failure.cause()) {
                case NOT_ATOMIC -> f("health.store_not_atomic", "%s could not be saved: this filesystem cannot replace files atomically. The previous file is unchanged; changes to it are refused until the world is on a filesystem that can.", failure.store());
                case UNREADABLE -> f("health.store_unreadable", "%s could not be read when the world loaded, so it is protected from being overwritten and its changes are refused. Stop the server and restore %s.bak (see Save durability in the documentation).", failure.store(), failure.store());
                case IO -> f("health.store_write_failed", "%s could not be saved (%s). Its previous file is unchanged and the save is retried; see Save durability in the documentation.", failure.store(), failure.reason());
            }).withStyle(ChatFormatting.RED));

        lines.add(heading("health.capacity", "Capacity"));
        lines.addAll(capacityLines(server));

        lines.add(heading("health.reload", "Last definition reload"));
        var attempt = ReloadReport.latest();
        if (attempt.isEmpty()) lines.add(f("health.reload_none", "No reload has been observed since this server started."));
        else ReloadReport.describe(attempt, null).forEach(lines::add);

        lines.add(heading("health.migration", "Ultima Kingdoms migration (this start only)"));
        lines.addAll(migrationLines());

        lines.add(heading("health.reconcile", "Village reconciliation and discovery"));
        lines.addAll(reconcileLines(server));
        return lines;
    }

    private static String townsteadCapabilities(MinecraftServer server) {
        try {
            var capabilities = dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi.get(server).capabilities();
            return capabilities.isEmpty() ? "none" : capabilities.stream().map(c -> c.name().toLowerCase(Locale.ROOT)).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
    }

    /**
     * Bounded collections at 80% or more, each with what relieves it (F12); a single line when every one is below that.
     * The full list is {@code /kingdoms maintenance capacity}.
     */
    static List<Component> capacityLines(MinecraftServer server) {
        var all = dev.otectus.mcakingdoms.persistence.Capacity.all(server);
        List<Component> lines = new ArrayList<>();
        all.stream().filter(c -> c.level() != dev.otectus.mcakingdoms.persistence.Capacity.Level.NORMAL)
                .sorted(java.util.Comparator.comparingInt(dev.otectus.mcakingdoms.persistence.Capacity::percent).reversed())
                .forEach(c -> lines.add(Component.empty().append(dev.otectus.mcakingdoms.command.MaintenanceCommands.line(c))
                        .withStyle(c.level() == dev.otectus.mcakingdoms.persistence.Capacity.Level.FULL ? ChatFormatting.RED : ChatFormatting.GOLD)));
        if (lines.isEmpty()) {
            var fullest = all.stream().max(java.util.Comparator.comparingInt(dev.otectus.mcakingdoms.persistence.Capacity::percent));
            lines.add(fullest.map(c -> f("health.capacity_ok", "Every bounded collection is below 80%%; the fullest is %s / %s at %s%%.", c.store(), c.collection(), c.percent()))
                    .orElse(f("health.capacity_unknown", "Capacity could not be read.")));
        }
        return lines;
    }

    static List<Component> migrationLines() {
        List<Component> lines = new ArrayList<>();
        var configs = LegacyRename.configSteps();
        var data = LegacyRename.dataSteps();
        if (configs.isEmpty() && data.isEmpty()) {
            lines.add(LegacyRename.dataChecked() ? f("health.migration_none", "No Ultima Kingdoms files were found at this start. Earlier starts are not recorded.")
                    : f("health.migration_pending", "The world's data folder has not been checked yet."));
            return lines;
        }
        for (var step : configs) lines.add(step(step));
        for (var step : data) lines.add(step(step));
        lines.add(f("health.migration_note", "Skipped files already had a current copy (from an earlier migration or new play); old files are never changed or deleted."));
        return lines;
    }

    private static Component step(LegacyRename.Step step) {
        return switch (step.outcome()) {
            case COPIED -> f("health.migration_copied", "Copied %s to %s.", step.from(), step.to());
            case SKIPPED_DESTINATION_EXISTS -> f("health.migration_skipped", "Skipped %s: %s already exists.", step.from(), step.to());
            case FAILED -> Component.empty().append(f("health.migration_failed", "Failed %s → %s: %s. The old file is unchanged; see the log. A failed saved-data or common config copy stops the world from starting.",
                    step.from(), step.to(), step.detail())).withStyle(ChatFormatting.RED);
        };
    }

    static List<Component> reconcileLines(MinecraftServer server) {
        List<Component> lines = new ArrayList<>();
        if (!(McaKingdomsApi.get(server) instanceof dev.otectus.mcakingdoms.core.KingdomsServiceImpl impl)) {
            lines.add(f("health.reconcile_unknown", "Reconciliation status is unavailable."));
            return lines;
        }
        var status = impl.reconcileStatus();
        lines.add(f("health.reconcile_mode", "%s settlement records. Village identity by structure: %s; automatic reconciliation %s.",
                status.settlements(), onOff(status.structureIdentity()), onOff(status.autoReconcile())));
        if (status.startupRemaining() >= 0) {
            lines.add(f("health.reconcile_running", "Start-up reconciliation is running: %s records examined, %s merged, %s still queued, %s chunk reads, %s ms so far.",
                    status.startupExamined(), status.startupMerged(), status.startupRemaining(), status.startupChunkReads(), status.startupElapsedMillis()));
        } else if (status.savedVersion() >= status.currentVersion()) {
            lines.add(f("health.reconcile_done", "The one-time start-up reconciliation is recorded as done for this world (version %s), so it does not run again.", status.savedVersion()));
            if (status.startupExamined() > 0) lines.add(f("health.reconcile_last", "This start it examined %s records and merged %s in %s ms.",
                    status.startupExamined(), status.startupMerged(), status.startupElapsedMillis()));
        } else {
            lines.add(f("health.reconcile_not_done", "Start-up reconciliation has not completed for this world (saved version %s of %s); it is %s.",
                    status.savedVersion(), status.currentVersion(), status.onStartup() ? f("health.reconcile_will_run", "enabled and runs at the next start") : f("health.reconcile_disabled", "turned off")));
        }
        lines.add(f("health.reconcile_queue", "Discovery queue: %s loaded chunks waiting; %s merges refused this session (run Reconcile nearby village records where you stand to list them).",
                status.queuedChunks(), status.refusals()));
        lines.add(f("health.reconcile_scope", "Only loaded chunks are scanned; the start-up pass may read up to %s unloaded chunks, %s per tick.",
                McaKingdomsConfig.RECONCILE_STARTUP_CHUNK_BUDGET.get(), McaKingdomsConfig.RECONCILE_CHUNK_READS_PER_TICK.get()));
        return lines;
    }

    /** Plain lines for the console. */
    public static List<Component> asComponents(List<Object> lines) {
        List<Component> out = new ArrayList<>();
        for (Object line : lines) out.add(line instanceof Component component ? component : Component.literal(String.valueOf(line)));
        return out;
    }

    static MutableComponent joined(List<Component> parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) { if (i > 0) out.append("\n"); out.append(parts.get(i)); }
        return out;
    }
    private static String millis(long nanos) { return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0); }
    /** The 95th percentile of the server's last 100 tick durations, in milliseconds. */
    private static String tickP95(net.minecraft.server.MinecraftServer server) {
        long[] ticks = server.tickTimes.clone(); java.util.Arrays.sort(ticks);
        return millis(dev.otectus.mcakingdoms.compat.recruits.TransferFenceMetrics.percentileOf(ticks, 95));
    }
    /** C: the bridge's state and every mapping, for operators; the mapping evidence is public (village and kingdom names). */
    /** The Living Villages integration in one line, from the capability snapshot its tasks and services use. */
    private static Component livingVillagesHealth(MinecraftServer server) {
        var snapshot = dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.snapshot(server);
        var binding = snapshot.of(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.Capability.STABLE_BINDING);
        Component state = Component.translatableWithFallback("warfare.mcakingdoms.villages.readiness." + snapshot.overall().name().toLowerCase(java.util.Locale.ROOT),
                snapshot.overall().name());
        return binding.reason().isEmpty()
                ? f("health.living_villages", "Recruits: Living Villages: %s; integration %s. Details: /kingdoms livingvillages status.",
                installed(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.MOD_ID), state)
                : f("health.living_villages_reason", "Recruits: Living Villages: %s; integration %s (%s). Details: /kingdoms livingvillages status.",
                installed(dev.otectus.mcakingdoms.compat.livingvillages.LvCompat.MOD_ID), state, binding.why());
    }

    private static List<Component> capitalsHealth(net.minecraft.server.MinecraftServer server) {
        var bridge = dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.capabilities();
        var lines = new ArrayList<Component>();
        var bindings = dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData.get(server).bindings().values();
        if (bridge.availability() == dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.Availability.ABSENT && bindings.isEmpty()) return lines;
        lines.add(switch (bridge.availability()) {
            case ABSENT -> f("health.capitals.absent", "MCA Capitals is not installed, but %s earlier mapping(s) remain; their kingdoms keep crown actions read-only until an operator releases them.", bindings.stream().filter(b -> b.status() != dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData.Status.RELEASED).count());
            case UNSUPPORTED -> Component.empty().append(f("health.capitals.unsupported", "MCA Capitals %s cannot be read (%s); every MCA: Kingdoms crown action is read-only.", bridge.version(), bridge.diagnostic())).withStyle(ChatFormatting.RED);
            case AVAILABLE -> f("health.capitals.available", "MCA Capitals %s: read-only bridge %s; %s governing capital(s); delegated actions: none (Capitals publishes no action API).",
                    bridge.version(), bridge.tested() ? f("health.capitals.tested", "tested") : f("health.capitals.untested", "untested version, accessors checked"),
                    dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop.get(server).capitals().stream().filter(dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.CapitalView::governing).count());
        });
        for (var binding : bindings.stream().limit(16).toList())
            lines.add(f("health.capitals.binding", "Capital #%s → %s: %s (%s).", binding.capital().toString().substring(0, 4), dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, binding.kingdom()),
                    ActionRegistry.enumLabel(binding.status()), dev.otectus.mcakingdoms.interaction.PlayerWords.component(binding.reason())));
        return lines;
    }
}
