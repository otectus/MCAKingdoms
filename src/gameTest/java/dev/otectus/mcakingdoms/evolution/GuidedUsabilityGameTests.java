package dev.otectus.mcakingdoms.evolution;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.civic.CivicViews;
import dev.otectus.mcakingdoms.interaction.*;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.*;

import java.util.*;

/**
 * The usability update's server side on the dedicated test server: the start view suggests only what the player may
 * know (A04, A13); unavailable features stay listed with their reason and Book topic (UX-06); goal phrases find their
 * task (A07); guild requirements show the applying route with exact numbers and the review names its action (A06, A11);
 * a result says whether it was done or refused (A10); the Ledger searches only known settlements and counts only those
 * (A08); the Book's kingdom gallery and the Kingdom page header read only public facts and the viewer's own state.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public final class GuidedUsabilityGameTests {
    private static final UUID NONE = InteractionNetwork.NONE;
    private static final String LAMPLIGHTERS = "mcakingdoms:lamplighters";

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void startViewSuggestsOnlyWhatThePlayerMayKnow(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var here = settlement(helper, api, 73100, null, "Startview Here " + suffix());
        var hidden = settlement(helper, api, 73300, null, "Startview Hidden " + suffix());
        var player = player(helper, "StartViewer", false);
        player.setPos(here.anchor().getX(), here.anchor().getY(), here.anchor().getZ());
        var start = request(player, NONE, NONE, "START", "", "", 0);
        check(start.mode().equals("start"), "START answers with the start view: " + start.mode());
        var json = JsonParser.parseString(start.value()).getAsJsonObject();
        check(json.has("here") && json.getAsJsonObject("here").get("name").getAsString().equals(here.displayName()), "The start view names the settlement the player stands in: " + start.value());
        check(!start.value().contains(hidden.displayName()), "An undiscovered settlement reached the start view: " + start.value());
        var targets = new ArrayList<String>();
        json.getAsJsonArray("suggestions").forEach(e -> targets.add(e.getAsJsonObject().get("target").getAsString()));
        check(targets.contains("settlement.here"), "Standing in a settlement suggests reading it: " + targets);
        check(targets.size() <= 3, "At most three suggestions: " + targets);
        for (String target : targets) ActionRegistry.find(target).ifPresent(task -> check(!task.operator(), "An operator task was suggested to a player: " + target));

        var newcomer = player(helper, "StartNewcomer", false);
        var fresh = request(newcomer, NONE, NONE, "START", "", "", 0);
        var freshJson = JsonParser.parseString(fresh.value()).getAsJsonObject();
        check(!freshJson.has("here"), "A player outside every settlement has no current place");
        if (SettlementKnowledge.get(server).all(newcomer, api, Optional.empty(), 1).isEmpty())
            check(fresh.value().contains("\"ledger_recipe\""), "Someone who knows no settlement is pointed at the first-village guide: " + fresh.value());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unavailableFeaturesStayListedWithTheirReason(GameTestHelper helper) {
        InteractionNetwork.init();
        var player = player(helper, "FeatureReader", false);
        var index = request(player, NONE, NONE, "INDEX", "", "", 0);
        var json = JsonParser.parseString(index.value()).getAsJsonObject();
        var groups = Set.of("PLACES", "GUILDS", "GOVERNMENT", "EXPLORATION", "CONFLICT", "WORLD_EVENTS", "HELP", "OPERATOR");
        Map<String, com.google.gson.JsonObject> byId = new HashMap<>();
        for (var element : json.getAsJsonArray("tasks")) {
            var entry = element.getAsJsonObject();
            check(entry.has("p") && groups.contains(entry.get("p").getAsString()), "Every task has a stable category: " + entry);
            byId.put(entry.get("i").getAsString(), entry);
        }
        check(byId.get("civic.join").get("p").getAsString().equals("GUILDS") && byId.get("settlement.here").get("p").getAsString().equals("PLACES"), "Categories follow the task's subject");
        // Recruits is not installed on the test server, so its tasks explain the missing provider.
        if (!net.minecraftforge.fml.ModList.get().isLoaded("recruits")) {
            var transfer = byId.get("transfer.propose");
            check(transfer != null && transfer.has("a") && transfer.get("a").getAsString().equals("PROVIDER_MISSING"), "A missing provider is named in the index: " + transfer);
            check(transfer.get("l").getAsString().equals("recruit_transfer_workflow"), "The index names the Book topic to learn more");
            var opened = start(player, "transfer.propose");
            check(opened.mode().equals("unavailable") && opened.learnOrEmpty().equals("recruit_transfer_workflow"), "Opening an unavailable task explains it instead of a form: " + opened.mode());
            check(opened.detailText().contains("Recruits"), "The explanation names the missing provider: " + opened.detailText());
        }
        check(!byId.get("settlement.here").has("a"), "An available task carries no unavailability");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void goalPhrasesFindTheirTask(GameTestHelper helper) {
        InteractionNetwork.init();
        var player = player(helper, "GoalSearcher", false);
        check(first(player, "where am I").equals("settlement.here"), "\"where am I\": " + labels(list(player, "where am I")));
        check(first(player, "why can't I join").equals("civic.explain"), "\"why can't I join\": " + labels(list(player, "why can't I join")));
        var ruler = list(player, "become ruler").options().stream().limit(2).map(InteractionNetwork.Option::key).toList();
        check(ruler.contains("politics.bootstrap") && ruler.contains("politics.founding_status"), "\"become ruler\" leads to founding and its eligibility check: " + ruler);
        var paid = list(player, "paid work").options().stream().map(InteractionNetwork.Option::key).toList();
        check(paid.contains("civic.commissions") || paid.contains("civic.workshop"), "\"paid work\" finds guild jobs: " + paid);
        check(list(player, "make peace").options().stream().anyMatch(o -> o.key().equals("politics.propose")), "\"make peace\" finds diplomacy");
        check(first(player, "dónde estoy").equals("settlement.here"), "Spanish goal phrase");
        check(first(player, "我在哪里").equals("settlement.here"), "Chinese goal phrase");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void guildRequirementsShowTheRouteAndTheReviewNamesTheAction(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var organizations = dev.otectus.mcakingdoms.api.factions.organization.OrganizationApi.get(server);
        if (organizations.definition(new ResourceLocation(LAMPLIGHTERS)).isEmpty()) { helper.succeed(); return; }
        var player = player(helper, "GuildRouteReader", false);
        var explain = request(player, NONE, NONE, "TASK", "civic.explain", "", 0,
                "{\"organization\":\"" + LAMPLIGHTERS + "\",\"permission\":\"mcakingdoms:route_introductions\"}");
        check(explain.mode().equals("result"), "Both choices prefilled, the read runs at once: " + explain.mode() + " " + explain.detailText());
        String text = explain.detailText();
        check(text.contains("Route: independent contributor"), "A non-member reads the independent route: " + text);
        check(text.contains("Guild standing") && text.contains("0 of 40") && text.contains("0 of 2"), "Exact numbers from the definition: " + text);
        check(text.contains("Or join and use the member route"), "The member route is offered as an alternative, not a second requirement: " + text);
        check(text.contains("blocks and in sight"), "The contact rule is a separate row: " + text);

        var view = CivicViews.own(player, 0).guild();
        check(view != null && !view.member() && view.services().size() >= 2, "The Guilds view has its own service sections: " + view);
        check(view.services().stream().allMatch(s -> !s.rows().isEmpty()), "Every service has its own requirement rows");
        check(view.services().stream().anyMatch(s -> s.task().equals("civic.introduction")), "Services lead to their reviewed task");

        var join = request(player, NONE, NONE, "TASK", "civic.join", "", 0, "{\"organization\":\"" + LAMPLIGHTERS + "\"}");
        check(join.mode().equals("review"), "Joining ends on a review: " + join.mode());
        check(join.actionComponent().getString().equals("Join Lamplighters"), "The confirm button names the action: " + join.actionComponent().getString());
        check(join.detailText().contains("You become an active member"), "The review says what changes: " + join.detailText());
        var joined = call(player, join, "APPLY", "");
        check(joined.mode().equals("result") && joined.statusOrEmpty().equals("done"), "A completed join says done: " + joined.statusOrEmpty() + " " + joined.detailText());
        check(CivicViews.own(player, 0).active(), "The membership is active after the reviewed join");
        var again = request(player, NONE, NONE, "TASK", "civic.join", "", 0, "{\"organization\":\"" + LAMPLIGHTERS + "\"}");
        var refused = call(player, again, "APPLY", "");
        check(refused.mode().equals("result") && refused.statusOrEmpty().equals("refused"), "The service's refusal is reported as refused: " + refused.statusOrEmpty() + " " + refused.detailText());
        var leave = request(player, NONE, NONE, "TASK", "civic.leave", "", 0, "{\"organization\":\"" + LAMPLIGHTERS + "\"}");
        check(leave.mode().equals("review") && leave.actionComponent().getString().equals("Leave Lamplighters"), "Leaving names its action: " + leave.actionComponent().getString());
        check(leave.detailText().contains("accepted jobs stay with you"), "Leaving explains what is kept: " + leave.detailText());
        check(call(player, leave, "APPLY", "").statusOrEmpty().equals("done") && !CivicViews.own(player, 0).active(), "The reviewed leave completes");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void ledgerSearchesOnlyKnownSettlements(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var knowledge = SettlementKnowledge.get(server);
        String tag = suffix();
        String first = "mcakingdoms:serenum", second = "mcakingdoms:lunari";
        var alpha = settlement(helper, api, 73500, first, "Ledgerton Alpha " + tag);
        var beta = settlement(helper, api, 73700, second, "Ledgerton Beta " + tag);
        var gamma = settlement(helper, api, 73900, first, "Ledgerton Gamma Old " + tag);
        gamma = api.rename(gamma.id(), "Ledgerton Gamma " + tag);
        var hidden = settlement(helper, api, 74100, first, "Ledgerton Hidden " + tag);
        var player = player(helper, "LedgerSearcher", false);
        for (var known : List.of(alpha, beta, gamma)) knowledge.discover(player.getUUID(), known.id());
        player.setPos(beta.anchor().getX(), beta.anchor().getY(), beta.anchor().getZ());
        var all = knowledge.search(player, api, Optional.empty(), "ledgerton " + tag, SettlementKnowledge.Sort.NAME, 0, 21);
        check(all.total() == 3 && all.settlements().size() == 3, "Search lists and counts only known settlements: " + names(all));
        check(all.settlements().stream().noneMatch(s -> s.id().equals(hidden.id())), "An undiscovered settlement matched a search");
        check(knowledge.search(player, api, Optional.empty(), "hidden " + tag, SettlementKnowledge.Sort.NAME, 0, 21).total() == 0, "A hidden name is not even counted");
        var alias = knowledge.search(player, api, Optional.empty(), "gamma old " + tag, SettlementKnowledge.Sort.NAME, 0, 21);
        check(alias.total() == 1 && alias.settlements().get(0).id().equals(gamma.id()), "A former name finds the renamed settlement: " + names(alias));
        var byKingdom = knowledge.search(player, api, Optional.of(new ResourceLocation(second)), tag, SettlementKnowledge.Sort.NAME, 0, 21);
        check(byKingdom.total() == 1 && byKingdom.settlements().get(0).id().equals(beta.id()), "The kingdom filter applies: " + names(byKingdom));
        var nearest = knowledge.search(player, api, Optional.empty(), tag, SettlementKnowledge.Sort.DISTANCE, 0, 21);
        check(nearest.settlements().get(0).id().equals(beta.id()), "Nearest first puts the settlement the player stands in first: " + names(nearest));
        var operator = player(helper, "LedgerOperator", true);
        check(knowledge.search(operator, api, Optional.empty(), "hidden " + tag, SettlementKnowledge.Sort.NAME, 0, 21).total() == 1, "Operators see every record");
        var gallery = NetworkHandler.gallery(player, 1);
        check(gallery.kingdoms().size() == api.getKingdoms().stream().filter(KingdomView::defined).count(), "The gallery lists the defined kingdoms");
        int index = -1;
        for (int i = 0; i < gallery.kingdoms().size(); i++) if (gallery.kingdoms().get(i).id().toString().equals(second)) index = i;
        check(index >= 0 && gallery.known().get(index) == knowledge.count(player, api, new ResourceLocation(second)), "The gallery counts only the viewer's own settlements");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void kingdomPagesReadAsLabelledSections(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var politics = KingdomsPoliticsApi.get(server);
        var player = player(helper, "KingdomReader", false);
        String ungoverned = api.getKingdoms().stream().map(k -> k.id().toString()).sorted().filter(k -> politics.government(k).isEmpty()).findFirst().orElse(null);
        if (ungoverned == null || !(politics instanceof dev.otectus.mcakingdoms.politics.GovernmentService government)) { helper.succeed(); return; }
        var header = government.header(player, ungoverned).stream().map(net.minecraft.network.chat.Component::getString).toList();
        check(header.stream().anyMatch(line -> line.contains("No government yet")), "An ungoverned kingdom says so: " + header);
        var page = politics.page(player, UUID.randomUUID(), ungoverned, "overview", 0);
        var rows = government.present(player, page);
        check(!rows.isEmpty() && rows.stream().allMatch(row -> !row.fields().isEmpty()), "Overview rows carry labelled fields");
        check(rows.get(0).fields().stream().anyMatch(f -> f.label().getString().equals("Who can found it")), "The founding path is a labelled fact: "
                + rows.get(0).fields().stream().map(f -> f.label().getString()).toList());
        var bootstrap = start(player, "politics.bootstrap");
        check(bootstrap.detailText().contains("What this needs") && bootstrap.detailText().contains("✗ Needed: A recorded civic residence"),
                "Founding lists its requirements before any choice: " + bootstrap.detailText());
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    private static String suffix() { return Integer.toString(Math.floorMod(UUID.randomUUID().hashCode(), 9000) + 1000); }
    private static String names(SettlementKnowledge.SearchPage page) { return page.total() + " " + page.settlements().stream().map(SettlementView::displayName).toList(); }
    private static String first(FakePlayer player, String search) { var found = list(player, search); return found.options().isEmpty() ? "" : found.options().get(0).key(); }
    private static SettlementView settlement(GameTestHelper helper, KingdomsService api, int offset, String kingdom, String name) {
        var level = helper.getLevel();
        var s = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(offset, 2, offset)), 32,
                new ResourceLocation("mcakingdoms", "manual"), "guided-" + UUID.randomUUID(), name));
        if (kingdom != null && !s.kingdomId().toString().equals(kingdom)) s = api.setKingdom(s.id(), new ResourceLocation(kingdom));
        return s;
    }
    private static String labels(InteractionNetwork.Reply reply) { return reply.options().stream().map(InteractionNetwork.Option::key).toList() + " / " + reply.detailText(); }
    private static InteractionNetwork.Reply start(FakePlayer player, String task) { return request(player, NONE, NONE, "TASK", task, "", 0); }
    private static InteractionNetwork.Reply list(FakePlayer player, String search) { return request(player, NONE, NONE, "LIST", "", search, 0); }
    private static InteractionNetwork.Reply call(FakePlayer player, InteractionNetwork.Reply prior, String operation, String value) { return request(player, prior.session(), prior.state(), operation, value, "", 0); }
    private static InteractionNetwork.Reply request(FakePlayer player, UUID session, UUID state, String operation, String value, String search, int page) {
        return request(player, session, state, operation, value, search, page, "");
    }
    private static InteractionNetwork.Reply request(FakePlayer player, UUID session, UUID state, String operation, String value, String search, int page, String prefill) {
        return InteractionNetwork.handle(player, new InteractionNetwork.Request(UUID.randomUUID(), session, state, operation, value, search, page, prefill));
    }
    private static FakePlayer player(GameTestHelper helper, String name, boolean operator) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        if (helper.getLevel().getServer().getProfileCache() != null) helper.getLevel().getServer().getProfileCache().add(profile);
        var player = new FakePlayer(helper.getLevel(), profile) { @Override public boolean hasPermissions(int level) { return operator; } };
        var pos = helper.absolutePos(new BlockPos(50000, 2, 50000)); player.setPos(pos.getX(), pos.getY(), pos.getZ()); return player;
    }
    private static void check(boolean value, String message) { if (!value) throw new GameTestAssertException(message); }
    private GuidedUsabilityGameTests() { }
}
