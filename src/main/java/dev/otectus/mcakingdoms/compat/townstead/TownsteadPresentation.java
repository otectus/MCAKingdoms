package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.InstitutionView;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageLifeView.NeedSummary;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Names;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Words for the Townstead integration's pages and diagnostics (spec 8.1, 9.2, 10.2, 11.2, 11.3, 12.2). Player pages use
 * plain explanations and public summaries only; API revisions, class names and reasons from the provider stay in the
 * operator diagnostics. Every page is filtered by the viewer's settlement knowledge on the server.
 */
public final class TownsteadPresentation {
    private static final int NEWS_PER_PAGE = 8;
    private static final int MAX_NEWS_PAGES = 8;

    private TownsteadPresentation() {
    }

    static Component t(String key, String fallback, Object... args) {
        return TownsteadText.tr(key, fallback, args);
    }

    public static Optional<TownsteadRuntime.Session> session(MinecraftServer server) {
        return TownsteadRuntime.session(server);
    }

    private static TownsteadRuntime.Session require(MinecraftServer server) {
        return session(server).orElseThrow(() -> TownsteadText.refuse("inactive", "The Townstead integration is not attached on this server."));
    }

    private static SettlementView visible(ServerPlayer viewer, UUID settlement) {
        var found = McaKingdomsApi.get(viewer.getServer()).getSettlement(settlement);
        if (found.isEmpty() || !SettlementKnowledge.get(viewer.getServer()).visible(viewer, found.get().id()))
            throw TownsteadText.refuse("unknown_settlement", "That settlement is not available.");
        return found.get();
    }

    /** The plain sentence for a capability a player page cannot use; never an API detail. */
    static Component unavailable(TownsteadCapabilityState state, Component what) {
        return switch (state.status()) {
            case ABSENT -> t("player.absent", "Townstead is not installed on this server, so %s is not available.", what);
            case DISABLED -> t("player.disabled", "Townstead's %s is switched off in this world.", what);
            case RECOVERY_REQUIRED -> t("player.recovery", "Townstead's %s is waiting for an operator to review it.", what);
            default -> t("player.unreadable", "Townstead's %s cannot be read on this server.", what);
        };
    }

    // ---- player pages ----

    public static List<Object> villageLife(ServerPlayer viewer, UUID settlementId) {
        var session = require(viewer.getServer());
        SettlementView settlement = visible(viewer, settlementId);
        if (!TownsteadIntegrationConfig.ENABLE_VILLAGE_LIFE.get())
            throw TownsteadText.refuse("life_off", "Village life pages are switched off on this server.");
        TownsteadServiceImpl townstead = session.service();
        List<Object> lines = new ArrayList<>();
        lines.add(header(viewer.getServer(), settlement, townstead));
        var life = townstead.villageLife(settlement.id());
        TownsteadCapabilityState summary = townstead.states().state(TownsteadCapability.READ_NEEDS_SUMMARY);
        if (life.isEmpty()) {
            lines.add(summary.status().usable() ? t("life.no_record", "Townstead has no record of this village's residents yet.")
                    : unavailable(summary, t("what.needs", "village needs")));
        } else {
            lines.addAll(lifeLines(life.get()));
        }
        ServerLevel level = viewer.getServer().getLevel(settlement.dimension());
        if (level != null) {
            townstead.spirit(level, settlement.id()).ifPresentOrElse(spirit -> lines.add(spirit.primaryId().isPresent()
                    ? t("life.spirit", "Community spirit: %s%s, tier %s (%s points from %s buildings).", words(spirit.primaryId().get()),
                    spirit.secondaryId().map(s -> " / " + words(s)).orElse(""), spirit.tier(), spirit.total(), spirit.contributingBuildings())
                    : t("life.spirit_none", "Community spirit: not yet formed (%s points).", spirit.total())),
                    () -> lines.add(unavailable(townstead.states().state(TownsteadCapability.READ_SPIRIT), t("what.spirit", "village spirit"))));
            lines.addAll(buildingLines(townstead, level, settlement));
        }
        townstead.calendar().ifPresent(calendar -> lines.add(calendar.season().isBlank()
                ? t("life.date", "Today: day %s of month %s, year %s.", calendar.day(), calendar.month(), calendar.year())
                : t("life.date_season", "Today: day %s of month %s, year %s (%s).", calendar.day(), calendar.month(), calendar.year(), words(calendar.season()))));
        if (life.isPresent() && TownsteadIntegrationConfig.WELFARE_SUGGESTIONS.get()) {
            List<TownsteadSuggestions.Shown> shown = suggestions(session, settlement, life.get(), level);
            for (var suggestion : shown) lines.add(Component.translatableWithFallback(suggestion.rule().messageKey(), suggestion.rule().messageKey()));
            if (!shown.isEmpty()) lines.add(t("life.suggestions_note", "Suggestions are optional: they point at existing tasks and commissions and change nothing by themselves."));
        }
        return lines;
    }

    /** The first suggestion's task, for the page's next step; empty without one. */
    public static Optional<String> suggestedTask(ServerPlayer viewer, UUID settlementId) {
        var session = session(viewer.getServer());
        if (session.isEmpty() || !TownsteadIntegrationConfig.WELFARE_SUGGESTIONS.get()) return Optional.empty();
        var settlement = McaKingdomsApi.get(viewer.getServer()).getSettlement(settlementId);
        if (settlement.isEmpty()) return Optional.empty();
        var life = session.get().service().villageLife(settlement.get().id());
        if (life.isEmpty()) return Optional.empty();
        return suggestions(session.get(), settlement.get(), life.get(), viewer.getServer().getLevel(settlement.get().dimension())).stream()
                .flatMap(s -> s.rule().task().stream()).findFirst();
    }

    private static List<TownsteadSuggestions.Shown> suggestions(TownsteadRuntime.Session session, SettlementView settlement, TownsteadVillageLifeView life, ServerLevel level) {
        var townstead = session.service();
        var buildings = level == null ? List.<TownsteadBuildingEvidence>of() : townstead.buildingEvidence(level, settlement.id());
        var spirit = level == null ? Optional.<dev.otectus.mcakingdoms.api.townstead.TownsteadSpiritView>empty() : townstead.spirit(level, settlement.id());
        long reading = life.worldDay() * 31 + life.known() * 7L + life.loaded() + life.needs().hashCode();
        return session.suggestions().step(settlement.id(), reading, new TownsteadSuggestions.Context(life, spirit, buildings,
                TownsteadSuggestions.careersUsable(townstead)), TownsteadMappings.snapshot().suggestions());
    }

    private static Component header(MinecraftServer server, SettlementView settlement, TownsteadServiceImpl townstead) {
        Component kingdom = Names.kingdomName(server, settlement.kingdomId().toString());
        Optional<ResourceLocation> culture = townstead.villages(settlement.id()).stream().flatMap(v -> v.culture().stream()).findFirst();
        return culture.isPresent()
                ? t("header.culture", "%s · kingdom %s · Townstead culture %s", settlement.displayName(), kingdom, words(culture.get().getPath()))
                : t("header", "%s · kingdom %s", settlement.displayName(), kingdom);
    }

    static List<Component> lifeLines(TownsteadVillageLifeView life) {
        List<Component> lines = new ArrayList<>();
        long age = life.oldestSampleAgeDays();
        lines.add(t("life.counts", "%s residents · %s known · %s currently loaded · oldest observation %s day(s) ago.",
                life.residents(), Math.min(life.known(), Math.max(life.residents(), 0)), life.loaded(), age));
        if (life.coverage() == TownsteadVillageLifeView.Coverage.INSUFFICIENT || life.coverage() == TownsteadVillageLifeView.Coverage.NONE)
            lines.add(t("life.insufficient", "Insufficient current information: Townstead knows too few residents, or its readings are too old, to describe this village."));
        if (life.inconsistentCounts())
            lines.add(t("life.inconsistent", "Townstead also counts residents recorded under this village who are not on its current roll; the numbers are shown as Townstead gives them."));
        boolean describe = life.coverage() != TownsteadVillageLifeView.Coverage.INSUFFICIENT && life.coverage() != TownsteadVillageLifeView.Coverage.NONE;
        for (String need : TownsteadVillageLife.STANDARD) {
            NeedSummary summary = life.needs().get(need);
            if (summary == null) continue;
            Component name = needName(need);
            switch (summary.simulation()) {
                case NOT_SIMULATED -> lines.add(t("life.need_off", "%s: not simulated.", name));
                case NO_SAMPLES -> lines.add(t("life.need_none", "%s: no readings yet.", name));
                case SIMULATED -> {
                    if (TownsteadNeedLevelView.TEMPERATURE.equals(need)) {
                        lines.add(summary.inCrisis() > 0
                                ? t("life.thermal_distress", "Temperature: %s of %s sampled residents in thermal distress (body %s to %s °C).", summary.inCrisis(), summary.sampled(),
                                celsius(summary.min()), celsius(summary.max()))
                                : t("life.thermal_ok", "Temperature: no thermal distress among %s sampled residents (body %s to %s °C).", summary.sampled(), celsius(summary.min()), celsius(summary.max())));
                    } else {
                        Component band = summary.band().map(TownsteadPresentation::bandName).orElse(t("band.unknown", "unknown"));
                        lines.add(t("life.need", "%s: %s; %s of %s sampled residents in crisis.", name, band, summary.inCrisis(), summary.sampled()));
                    }
                }
            }
        }
        if (describe) {
            needCrisis(life, TownsteadNeedLevelView.HUNGER).ifPresent(n -> lines.add(t("explain.food", "Food is running low.")));
            needCrisis(life, TownsteadNeedLevelView.THIRST).ifPresent(n -> lines.add(t("explain.water", "Water is short.")));
            if (life.collapsed() > 0 || needCrisis(life, TownsteadNeedLevelView.ENERGY).isPresent()) lines.add(t("explain.exhausted", "Some residents are exhausted."));
            needCrisis(life, TownsteadNeedLevelView.TEMPERATURE).ifPresent(n -> lines.add(t("explain.thermal", "Thermal distress reported.")));
        }
        return lines;
    }

    private static Optional<NeedSummary> needCrisis(TownsteadVillageLifeView life, String need) {
        return life.need(need).filter(s -> s.simulation() == NeedSummary.Simulation.SIMULATED && (s.inCrisis() > 0
                || !TownsteadNeedLevelView.TEMPERATURE.equals(need) && s.band().filter(b -> b.equals("crisis") || b.equals("strained")).isPresent()));
    }

    static String celsius(int tenths) {
        return String.format(Locale.ROOT, "%.1f", tenths / 10.0);
    }

    static Component needName(String need) {
        return switch (need) {
            case TownsteadNeedLevelView.HUNGER -> t("need.hunger", "Food");
            case TownsteadNeedLevelView.THIRST -> t("need.thirst", "Water");
            case TownsteadNeedLevelView.ENERGY -> t("need.energy", "Rest");
            case TownsteadNeedLevelView.TEMPERATURE -> t("need.temperature", "Temperature");
            default -> Component.literal(need);
        };
    }

    static Component bandName(String band) {
        return switch (band) {
            case "thriving" -> t("band.thriving", "thriving");
            case "steady" -> t("band.steady", "steady");
            case "strained" -> t("band.strained", "strained");
            case "crisis" -> t("band.crisis", "in crisis");
            default -> Component.literal(band);
        };
    }

    /** Civic-relevant buildings with their completion: the development panel (spec 7.4). */
    static List<Component> buildingLines(TownsteadServiceImpl townstead, ServerLevel level, SettlementView settlement) {
        List<Component> lines = new ArrayList<>();
        if (!townstead.capabilities().contains(TownsteadCapability.READ_BUILDINGS)) return lines;
        var read = townstead.read(level, settlement.id());
        Map<String, int[]> families = new TreeMap<>();
        for (var evidence : read.buildings()) {
            int[] counts = families.computeIfAbsent(evidence.building().family(), ignored -> new int[3]);
            counts[evidence.completion().ordinal()]++;
        }
        if (families.isEmpty()) {
            lines.add(t("buildings.none", "No buildings are recorded for this village."));
            return lines;
        }
        List<String> parts = new ArrayList<>();
        families.forEach((family, counts) -> {
            String part = (counts[0] + counts[1] + counts[2]) + " " + words(family);
            if (counts[1] > 0) part += " (" + counts[1] + " unfinished)";
            parts.add(part);
        });
        lines.add(t("buildings.list", "Buildings: %s.", String.join(", ", parts.subList(0, Math.min(24, parts.size())))));
        if (read.buildings().stream().anyMatch(b -> b.completion() == TownsteadBuildingEvidence.Completion.UNKNOWN))
            lines.add(t("buildings.unknown_completion", "Whether every building is finished cannot be read here."));
        if (!read.consistent()) lines.add(t("buildings.partial", "Townstead's building list could not be read completely just now; it is shown as read."));
        return lines;
    }

    public static List<Object> work(ServerPlayer viewer, UUID settlementId) {
        var session = require(viewer.getServer());
        SettlementView settlement = visible(viewer, settlementId);
        if (!TownsteadIntegrationConfig.ENABLE_VILLAGE_LIFE.get()) throw TownsteadText.refuse("life_off", "Village life pages are switched off on this server.");
        TownsteadServiceImpl townstead = session.service();
        List<Object> lines = new ArrayList<>();
        lines.add(header(viewer.getServer(), settlement, townstead));
        TownsteadCapabilityState orders = townstead.states().state(TownsteadCapability.READ_WORK_ORDERS);
        if (!orders.status().usable()) lines.add(unavailable(orders, t("what.orders", "work orders")));
        else {
            var sites = session.work().sites(settlement.id());
            if (sites.isEmpty()) lines.add(t("work.none", "No worksites are registered in this village."));
            for (var site : sites) {
                String driver = site.worksite().driver().map(id -> Names.player(viewer.getServer(), id)).orElse("");
                lines.add(driver.isEmpty() || driver.equals("nobody")
                        ? t("work.site", "%s: %s order(s).", site.worksite().name().isBlank() ? words(site.worksite().binding().getPath()) : site.worksite().name(), site.worksite().orderCount())
                        : t("work.site_run", "%s: %s order(s), run by a villager.", site.worksite().name().isBlank() ? words(site.worksite().binding().getPath()) : site.worksite().name(), site.worksite().orderCount()));
                for (var order : site.orders()) {
                    String product = order.productName().isBlank() ? words(order.product().orElse(order.output()).getPath()) : order.productName();
                    Component restriction = order.profession().map(p -> t("work.restricted", ", only %s of rank %s or higher", words(p.getPath()), order.minRank())).orElse(Component.empty());
                    lines.add(t("work.order", "  • %s: target %s (%s, %s)%s%s", product, order.target(), words(order.mode()), words(order.scope()),
                            order.paused() ? t("work.paused", ", paused") : Component.empty(), restriction));
                }
            }
        }
        lines.add(t("work.meaning", "An order is production requested; a storage count is goods on hand now; neither is goods delivered. Only a delivery to a commission pays, once."));
        TownsteadCapabilityState storage = townstead.states().state(TownsteadCapability.READ_STORAGE);
        if (storage.status().usable()) {
            List<String> parts = new ArrayList<>();
            for (String metric : TownsteadIntegrationConfig.ADVISORY_STOCK.get()) {
                boolean tag = metric.startsWith("#");
                ResourceLocation id = ResourceLocation.tryParse(tag ? metric.substring(1) : metric);
                if (id == null) continue;
                var count = session.work().count(settlement.id(), id, tag);
                parts.add(words(id.getPath()) + " " + (count.isPresent() ? String.valueOf(count.getAsInt()) : "?"));
            }
            if (!parts.isEmpty()) lines.add(t("work.stock", "On hand in recognized storage (advisory, reserves nothing): %s.", String.join(", ", parts)));
        }
        int today = session.work().workToday(settlement.id());
        if (today > 0) lines.add(t("work.today", "Its residents finished %s piece(s) of work today.", today));
        return lines;
    }

    public static List<Object> news(ServerPlayer viewer, UUID settlementId, int page) {
        var session = require(viewer.getServer());
        SettlementView settlement = visible(viewer, settlementId);
        TownsteadServiceImpl townstead = session.service();
        List<Object> lines = new ArrayList<>();
        lines.add(header(viewer.getServer(), settlement, townstead));
        lines.add(t("news.official", "MCA: Kingdoms' official records for %s are on its Kingdom page (History).", Names.kingdomName(viewer.getServer(), settlement.kingdomId().toString())));
        TownsteadCapabilityState chronicles = townstead.states().state(TownsteadCapability.READ_CHRONICLES);
        if (!chronicles.status().usable()) {
            lines.add(unavailable(chronicles, t("what.news", "village news")));
            return lines;
        }
        int wanted = Math.max(1, Math.min(MAX_NEWS_PAGES, page));
        long cursor = 0;
        Optional<TownsteadBackend.DigestPage> current = Optional.empty();
        for (int i = 1; i <= wanted; i++) {
            current = session.chronicles().digest(settlement.id(), cursor, NEWS_PER_PAGE);
            if (current.isEmpty() || i == wanted || !current.get().hasMore()) break;
            cursor = current.get().next();
        }
        if (current.isEmpty() || current.get().entries().isEmpty()) {
            lines.add(t("news.none", "Townstead has no village news here yet."));
            return lines;
        }
        lines.add(t("news.townstead", "Townstead village news (what villagers tell each other; a story, not an official record), page %s:", wanted));
        for (var entry : current.get().entries()) lines.add(t("news.entry", "Day %s: %s", entry.worldDay(), headline(entry)));
        if (current.get().hasMore()) lines.add(t("news.more", "More news on the next page."));
        return lines;
    }

    /** The three parameter orders of MCA: Kingdoms' own Chronicle templates, as their {@code display.params} list them. */
    private static final Map<String, List<String>> OWN_HEADLINES = Map.of(
            "chronicle.mcakingdoms.institution_recognized", List.of("player", "institution", "settlement"),
            "chronicle.mcakingdoms.agreement_concluded", List.of("player", "kingdom", "counterpart"),
            "chronicle.mcakingdoms.public_work_completed", List.of("player", "work", "settlement"));

    /**
     * A headline in the reader's language: MCA: Kingdoms' own stories from their key and parameters, Townstead's from the
     * text Townstead resolved (its key is translated by Townstead's own language files when they have it).
     */
    static Component headline(TownsteadBackend.DigestEntry entry) {
        List<String> own = OWN_HEADLINES.get(entry.headlineKey());
        if (own != null) return Component.translatable(entry.headlineKey(), own.stream().map(key -> (Object) entry.params().getOrDefault(key, "?")).toArray());
        if (!entry.headlineKey().isBlank()) return Component.translatableWithFallback(entry.headlineKey(), entry.headline().isBlank() ? entry.templateId() : entry.headline());
        return Component.literal(entry.headline().isBlank() ? entry.templateId() : entry.headline());
    }

    /**
     * What Townstead says a civic contact is doing, to explain why they are not at hand (spec 8.3): a hangout visit, else
     * the activity their schedule has now. Empty when Townstead cannot say. Nothing is changed: no one is moved, woken or
     * called back, and no dialogue is opened.
     */
    public static Optional<Component> whereabouts(MinecraftServer server, Entity npc) {
        var session = TownsteadRuntime.session(server);
        if (session.isEmpty() || npc == null) return Optional.empty();
        var townstead = session.get().service();
        if (townstead.states().state(TownsteadCapability.READ_HANGOUTS).status().usable()) {
            var phase = townstead.backendImpl().hangoutPhase(npc).orElse("");
            if (phase.equals("traveling")) return Optional.of(t("whereabouts.hangout_traveling", "Townstead: on the way to a gathering place, back when the visit ends."));
            if (phase.equals("present")) return Optional.of(t("whereabouts.hangout_present", "Townstead: at a gathering place, back when the visit ends."));
        }
        if (!townstead.states().state(TownsteadCapability.READ_SCHEDULE).status().usable()) return Optional.empty();
        var activity = townstead.villager(npc).map(v -> v.schedule().currentActivity()).filter(a -> !a.isBlank());
        return activity.map(now -> switch (now) {
            case "rest" -> t("whereabouts.rest", "Townstead: resting now, by their schedule.");
            case "work" -> t("whereabouts.work", "Townstead: at work now, by their schedule.");
            case "meet" -> t("whereabouts.meet", "Townstead: meeting others now, by their schedule.");
            case "idle" -> t("whereabouts.idle", "Townstead: free time now, by their schedule.");
            default -> t("whereabouts.activity", "Townstead: %s now, by their schedule.", words(now));
        });
    }

    public static List<Object> government(ServerPlayer viewer, UUID settlementId) {
        var session = require(viewer.getServer());
        SettlementView settlement = visible(viewer, settlementId);
        List<Object> lines = new ArrayList<>();
        lines.add(header(viewer.getServer(), settlement, session.service()));
        for (var resolution : session.authority().resolve(settlement.id())) {
            if (resolution.domain() == TownsteadAuthority.Domain.ORGANIZATION && !ModList.get().isLoaded("townstead")) continue;
            lines.add(t("government.line", "%s: %s. %s", domainName(resolution.domain()), ownerName(resolution.owner()), resolution.reason()));
        }
        var politics = KingdomsPoliticsApi.get(viewer.getServer());
        politics.government(settlement.kingdomId().toString()).ifPresent(g -> g.offices().values().stream()
                .filter(o -> o.settlement() == null || settlement.id().equals(McaKingdomsApi.get(viewer.getServer()).resolveSettlementId(o.settlement())))
                .limit(12).forEach(o -> lines.add(t("government.office", "Office %s: %s%s", words(ResourceLocation.tryParse(o.definitionId()) == null ? o.definitionId()
                        : ResourceLocation.tryParse(o.definitionId()).getPath()), o.holder().kind() == dev.otectus.mcakingdoms.api.politics.Politics.Kind.PLAYER
                        ? Names.player(viewer.getServer(), o.holder().id()) : t("government.npc", "a villager"),
                        o.settlement() == null ? t("government.kingdom_wide", " (kingdom-wide)") : Component.empty()))));
        List<InstitutionView> institutions = politics.knownInstitutions(viewer, 0, 32).stream().filter(i -> i.settlement().equals(settlement.id())).toList();
        for (var institution : institutions)
            lines.add(t("government.institution", "Institution: %s in a %s, %s.", Component.translatable(institution.titleKey()), words(institution.type()),
                    ActionRegistry.enumLabel(institution.status())));
        lines.add(t("government.where", "Civic offices, institutions and petitions are on the Kingdom page; guild services are in Guilds; Townstead's own council keeps its Charter."));
        return lines;
    }

    static Component domainName(TownsteadAuthority.Domain domain) {
        return switch (domain) {
            case CROWN -> t("domain.crown", "Crown");
            case LOCAL_GOVERNMENT -> t("domain.local", "Local government");
            case ORGANIZATION -> t("domain.organization", "Guilds and associations");
            case NAMING -> t("domain.naming", "Names");
            case HERALDRY -> t("domain.heraldry", "Arms");
        };
    }

    static Component ownerName(TownsteadAuthority.Owner owner) {
        return switch (owner) {
            case MCA_KINGDOMS -> t("owner.kingdoms", "MCA: Kingdoms");
            case MCA_CAPITALS -> t("owner.capitals", "MCA Capitals");
            case TOWNSTEAD -> t("owner.townstead", "Townstead");
            case MCA -> t("owner.mca", "MCA");
            case NONE -> t("owner.none", "nobody yet");
        };
    }

    static Component stateName(TownsteadAuthority.State state) {
        return switch (state) {
            case NATIVE -> t("state.native", "governed here");
            case EXTERNAL_ACTIVE -> t("state.external_active", "governed by another mod");
            case EXTERNAL_UNAVAILABLE -> t("state.external_unavailable", "governed by another mod that cannot be read now");
            case CONFLICT -> t("state.conflict", "conflict reported");
            case UNMAPPED -> t("state.unmapped", "not mapped");
            case DISABLED -> t("state.disabled", "resolution switched off");
            case RECOVERY_REQUIRED -> t("state.recovery", "waiting for an operator");
        };
    }

    // ---- operator diagnostics ----

    public static List<Component> status(MinecraftServer server) {
        List<Component> lines = new ArrayList<>();
        String installed = ModList.get().getModContainerById("townstead").map(c -> c.getModInfo().getVersion().toString()).orElse("absent");
        String mca = ModList.get().getModContainerById("mca").map(c -> c.getModInfo().getVersion().toString()).orElse("absent");
        var session = session(server);
        if (session.isEmpty()) {
            lines.add(t("status.inactive", "Townstead %s, MCA %s: the integration is not attached.", installed, mca));
            return lines;
        }
        var townstead = session.get().service();
        var info = townstead.backendImpl().info();
        lines.add(t("status.versions", "Townstead %s, MCA %s; backend %s (%s); buildings from %s.", installed, mca, townstead.backendImpl().id(),
                info.detail().isBlank() ? "—" : info.detail(), townstead.buildingBackend().id()));
        lines.add(t("status.tuple", "Validated tuples: %s. This installation: %s.", String.join("; ", TownsteadTuples.validated()),
                TownsteadTuples.validated(installed, mca) ? t("status.tuple_yes", "validated") : t("status.tuple_no", "not a validated tuple")));
        for (var entry : townstead.capabilityStates().entrySet()) {
            var state = entry.getValue();
            lines.add(t("status.capability", "%s: %s (%s) %s", entry.getKey().serializedName(), state.status().name().toLowerCase(Locale.ROOT), state.source(), state.reason()));
        }
        var systems = townstead.backendImpl().systems();
        if (!systems.isEmpty()) {
            List<String> off = systems.stream().filter(s -> townstead.backendImpl().systemEnabled(s).filter(on -> !on).isPresent()).toList();
            lines.add(t("status.systems", "Townstead systems: %s known; switched off in this world: %s.", systems.size(), off.isEmpty() ? "none" : String.join(", ", off)));
        }
        lines.add(t("status.delegation", "Political delegation: unavailable. Townstead 0.8.0 accepts no external government, so MCA: Kingdoms reports ownership and conflicts and never changes Townstead's politics."));
        var problems = TownsteadMappings.problems();
        lines.add(problems.isEmpty() ? t("status.mappings_ok", "Townstead mappings: all valid.")
                : t("status.mappings_problems", "Townstead mappings: %s skipped or disabled; first: %s", problems.size(), problems.get(0)));
        var data = TownsteadSavedData.get(server);
        lines.add(t("status.decisions", "Authority decisions: %s; baselines: %s; store %s.", data.decisions().size(), data.baselineCount(),
                data.writable() ? "writable" : "read-only"));
        return lines;
    }

    public static List<Component> village(MinecraftServer server, UUID settlementId) {
        var session = require(server);
        KingdomsService kingdoms = McaKingdomsApi.get(server);
        var settlement = kingdoms.getSettlement(settlementId).orElseThrow(() -> TownsteadText.refuse("unknown_settlement", "That settlement is not available."));
        var townstead = session.service();
        List<Component> lines = new ArrayList<>();
        lines.add(t("village.identity", "%s (%s), kingdom %s, dimension %s.", settlement.displayName(), settlement.id().toString(), settlement.kingdomId().toString(),
                settlement.dimension().location().toString()));
        if (!settlement.aliases().isEmpty()) lines.add(t("village.aliases", "Also known as: %s.", String.join(", ", settlement.aliases())));
        var villages = townstead.villageIds(settlement, settlement.dimension().location());
        if (villages.isEmpty()) lines.add(t("village.no_mca", "No MCA village reference: Townstead's data cannot be mapped to this settlement."));
        for (int i = 0; i < villages.size(); i++) {
            int id = villages.get(i);
            lines.add(t(i == 0 ? "village.ref_primary" : "village.ref_merged", i == 0 ? "MCA village %s#%s (its own)." : "MCA village %s#%s (kept from a merged record).",
                    settlement.dimension().location().toString(), id));
            ServerLevel level = server.getLevel(settlement.dimension());
            if (level != null) townstead.backendImpl().village(level, id).ifPresent(v -> lines.add(t("village.townstead",
                    "  Townstead: \"%s\", culture %s, %s residents (%s loaded), %s buildings, range margin %s, established day %s%s.", v.name(),
                    v.culture().map(ResourceLocation::toString).orElse("none"), v.residents(), v.loadedResidents(), v.buildingCount(), v.rangeMargin(),
                    v.establishedWorldDay() == Long.MIN_VALUE ? "?" : String.valueOf(v.establishedWorldDay()), v.playerFounded() ? ", player founded" : "")));
        }
        var fingerprint = session.reconciler().fingerprint(settlement.id());
        var age = session.reconciler().readAge(settlement.id());
        lines.add(fingerprint.isPresent() ? t("village.reading", "Last consistent building reading: %s buildings, %s ticks ago.", fingerprint.get().size(),
                age.isPresent() ? String.valueOf(age.getAsLong()) : "?")
                : t("village.reading_none", "No consistent building reading yet this session."));
        var local = session.authority().resolve(settlement.id(), TownsteadAuthority.Domain.LOCAL_GOVERNMENT);
        lines.add(t("village.ownership", "Local government: %s, %s.", ownerName(local.owner()), stateName(local.state())));
        return lines;
    }

    public static List<Component> buildings(MinecraftServer server, UUID settlementId) {
        var session = require(server);
        var settlement = McaKingdomsApi.get(server).getSettlement(settlementId).orElseThrow(() -> TownsteadText.refuse("unknown_settlement", "That settlement is not available."));
        ServerLevel level = server.getLevel(settlement.dimension());
        if (level == null) throw TownsteadText.refuse("no_level", "That settlement's dimension is not loaded.");
        var read = session.service().read(level, settlement.id());
        List<Component> lines = new ArrayList<>();
        lines.add(read.consistent() ? t("buildings.read_ok", "%s building(s), read consistently.", read.buildings().size())
                : t("buildings.read_partial", "%s building(s); the read was not consistent: %s", read.buildings().size(), read.detail()));
        for (var evidence : read.buildings().stream().limit(64).toList()) {
            var b = evidence.building();
            lines.add(t("buildings.entry", "village %s building %s: %s (family %s, tier %s), %s, from %s; box %s,%s,%s to %s,%s,%s; binding %s",
                    b.villageId(), b.buildingId(), b.type(), b.family(), b.level(), evidence.completion().name().toLowerCase(Locale.ROOT),
                    evidence.source().name().toLowerCase(Locale.ROOT), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.bindingId().toString()));
        }
        return lines;
    }

    public static List<Component> authority(MinecraftServer server, UUID settlementId) {
        var session = require(server);
        var settlement = McaKingdomsApi.get(server).getSettlement(settlementId).orElseThrow(() -> TownsteadText.refuse("unknown_settlement", "That settlement is not available."));
        List<Component> lines = new ArrayList<>();
        for (var resolution : session.authority().resolve(settlement.id())) {
            lines.add(t("authority.domain_line", "%s: owner %s, %s (revision %s). %s", domainName(resolution.domain()), ownerName(resolution.owner()),
                    stateName(resolution.state()), resolution.revision(), resolution.reason()));
            for (Component evidence : resolution.evidence()) lines.add(t("authority.evidence_line", "  evidence: %s", evidence));
            lines.add(t("authority.allowed_line", "  MCA: Kingdoms actions allowed here: %s", resolution.allowedActions().isEmpty() ? "none"
                    : String.join(", ", new java.util.TreeSet<>(resolution.allowedActions()))));
        }
        TownsteadSavedData.get(server).decision(settlement.id()).ifPresent(d -> lines.add(t("authority.decision_line",
                "Recorded decision: %s, by %s on tick %s; Townstead faction then %s. %s", d.owner().name(), d.decidedBy() == null ? "the console" : Names.player(server, d.decidedBy()),
                d.at(), d.faction().isBlank() ? "none" : d.faction(), d.note())));
        TownsteadSavedData.get(server).baseline(settlement.id()).ifPresent(b -> lines.add(t("authority.baseline_line",
                "Baseline: Townstead %s (api revision %s) on Townstead day %s.", b.version(), b.revision(), b.townsteadDay())));
        return lines;
    }

    public static List<Component> queues(MinecraftServer server) {
        var session = require(server);
        var bridge = session.bridge().stats();
        var reconciler = session.reconciler().stats();
        var reactions = session.reactions().stats();
        List<Component> lines = new ArrayList<>();
        lines.add(t("queues.events", "Townstead notices: subscription %s; %s waiting (most %s), %s received, %s coalesced, %s dropped, %s handled, %s handler failures.",
                bridge.open() ? "open" : "closed", bridge.depth(), bridge.maxDepth(), bridge.received(), bridge.coalesced(), bridge.dropped(), bridge.handled(),
                bridge.failures()));
        lines.add(t("queues.buildings", "Building reconciliation: %s settlement(s) tracked, %s in rotation, %s dirty; %s reads, %s inconsistent, %s changes.",
                reconciler.tracked(), reconciler.rotation(), reconciler.dirty(), reconciler.reads(), reconciler.inconsistent(), reconciler.changes()));
        var oldest = session.service().lifeCache().oldestAge(server.getTickCount());
        lines.add(t("queues.caches", "Shared readings: village life %s (oldest %s ticks old), stock counts %s (%s reads), suggestion tracks %s.",
                session.service().lifeCache().size(), oldest.isPresent() ? String.valueOf(oldest.getAsLong()) : "-", session.work().cachedCounts(),
                session.work().stockReads(), session.suggestions().tracked()));
        lines.add(t("queues.reactions", "Reactions: %s villagers inspected, %s reactions dispatched, %s skipped while reactions could not play.",
                reactions.inspected(), reactions.dispatched(), reactions.skipped()));
        lines.add(t("queues.chronicles", "Chronicle projections: %s.", session.chronicles().counts().isEmpty() ? "none yet"
                : session.chronicles().counts().toString().toLowerCase(Locale.ROOT)));
        lines.add(t("queues.heraldry", "Plain shields given a kingdom's design this session: %s.", TownsteadHeraldry.painted(server)));
        return lines;
    }

    /** A bounded re-read of one settlement: no award, no government change, no reset and no replay. */
    public static List<Component> reconcile(MinecraftServer server, UUID settlementId) {
        var session = require(server);
        var settlement = McaKingdomsApi.get(server).getSettlement(settlementId).orElseThrow(() -> TownsteadText.refuse("unknown_settlement", "That settlement is not available."));
        session.service().lifeCache().invalidate(settlement.id());
        session.work().invalidate(settlement.id());
        session.authority().invalidate(settlement.id());
        session.chronicles().digestChanged(settlement.id());
        boolean changed = session.reconciler().reconcile(settlement.id());
        return List.of(t("reconcile.done", "Re-read %s: buildings %s. Nothing was awarded, changed or replayed.", settlement.displayName(),
                changed ? t("reconcile.changed", "changed since the last reading") : t("reconcile.unchanged", "unchanged or not yet read consistently")));
    }

    static String words(String value) {
        return value == null ? "" : value.replace('_', ' ').replace('/', ' ');
    }
}
