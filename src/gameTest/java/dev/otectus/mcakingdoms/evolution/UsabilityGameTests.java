package dev.otectus.mcakingdoms.evolution;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import dev.otectus.mcakingdoms.interaction.*;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.*;

import java.util.*;

/**
 * Usability plan acceptance on the dedicated test server: resident founding reaches the service rule and nothing more,
 * search is token- and alias-aware, typed entries keep their drafts, review editing clears only dependent answers, and
 * the catalogue index lists exactly the authorized tasks.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public final class UsabilityGameTests {
    private static final UUID NONE = InteractionNetwork.NONE;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void residentFoundingReachesOnlyTheServiceRule(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var politics = KingdomsPoliticsApi.get(server);
        if (!McaKingdomsConfig.OPEN_FOUNDING.get()) throw new GameTestAssertException("Resident founding must default to enabled");
        // Use a kingdom no other test governs, so a reused world still has one to found.
        String kingdom = api.getKingdoms().stream().map(k -> k.id().toString()).sorted().filter(k -> politics.government(k).isEmpty()).findFirst().orElse(null);
        if (kingdom == null) { helper.succeed(); return; }
        var capital = settlement(helper, api, 71000, kingdom, "Founding Reach");
        var elsewhere = settlement(helper, api, 71160, kingdom, "Other Reach");
        var resident = player(helper, "FoundingResident", false);
        var stranger = player(helper, "FoundingStranger", false);
        var operator = player(helper, "FoundingOperator", true);
        // The live political service accepts only players it knows; the test server has no profile cache, so these
        // stand-ins are listed as online for the duration of the test, then removed.
        online(helper, true, resident, stranger, operator);
        try {

        // No recorded residence: nothing to choose, and the reason says why without leaking anything private.
        var empty = start(resident, "politics.bootstrap");
        check(empty.mode().equals("choice") && empty.options().isEmpty(), "A non-resident was offered a kingdom to found");
        check(empty.detailText().contains("no recorded civic residence"), "Missing residence explanation: " + empty.detailText());
        var status = start(resident, "politics.founding_status");
        check(status.mode().equals("result") && status.detailText().contains("You have no recorded civic residence"), "Founding status omitted the residence: " + status.detailText());

        // Standing in the capital does not record a residence.
        resident.setPos(capital.anchor().getX(), capital.anchor().getY(), capital.anchor().getZ());
        ((dev.otectus.mcakingdoms.core.KingdomsServiceImpl) api).observeEntity(resident);
        check(api.getCivicIdentity(resident).flatMap(CivicIdentityView::residenceSettlement).isEmpty(), "Presence alone recorded a residence");

        api.setResidence(resident, capital.id());
        var kingdoms = start(resident, "politics.bootstrap");
        check(kingdoms.options().size() == 1, "A resident must be offered exactly their residence kingdom: " + labels(kingdoms));
        var capitals = call(resident, kingdoms, "PICK", kingdoms.options().get(0).key());
        check(capitals.mode().equals("choice") && capitals.options().size() == 1 && capitals.options().get(0).labelText().equals("Founding Reach"),
                "A resident must be offered exactly their residence as capital: " + labels(capitals));
        var constitutions = call(resident, capitals, "PICK", capitals.options().get(0).key());
        check(constitutions.mode().equals("choice") && !constitutions.options().isEmpty(), "No constitution offered");
        var leaders = call(resident, constitutions, "PICK", constitutions.options().get(0).key());
        check(leaders.options().size() == 1 && leaders.options().get(0).labelText().equals("FoundingResident"), "A resident may name only themselves: " + labels(leaders));
        var review = call(resident, leaders, "PICK", leaders.options().get(0).key());
        check(review.mode().equals("review") && review.step() == review.steps() && review.steps() == 5, "Founding review step count: " + review.step() + "/" + review.steps());
        long before = politics.revision();
        var applied = call(resident, review, "APPLY", "");
        check(applied.mode().equals("result") && !applied.detailText().contains("Could not complete"), "Resident founding failed: " + applied.detailText());
        var government = politics.government(kingdom).orElseThrow(() -> new GameTestAssertException("No government was founded"));
        check(government.capital().equals(capital.id()), "The capital is not the residence");
        check(government.offices().values().stream().anyMatch(o -> o.holder().id().equals(resident.getUUID())), "The founder is not the leader");
        long after = politics.revision();
        var repeated = request(resident, review.session(), review.state(), "APPLY", "", "", 0);
        check(repeated.mode().equals("result") && repeated.detail().equals(applied.detail()) && politics.revision() == after && after > before,
                "Repeated Apply changed political state");
        var again = start(resident, "politics.bootstrap");
        check(again.options().isEmpty() && again.detailText().contains("already has a government"), "A founded kingdom was offered again: " + again.detailText());

        // The service rule itself still refuses a wrong capital or another person as leader, whatever a client sends.
        String other = api.getKingdoms().stream().map(k -> k.id().toString()).sorted().filter(k -> !k.equals(kingdom) && politics.government(k).isEmpty()).findFirst().orElse(null);
        if (other != null) {
            api.setKingdom(elsewhere.id(), new ResourceLocation(other));
            api.setResidence(stranger, elsewhere.id());
            var wrongCapital = politics.execute(stranger, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.BOOTSTRAP, other,
                    other + "_charter", capital.id().toString(), new Politics.Person(stranger.getUUID(), Politics.Kind.PLAYER), "", "", "", 0, 0, 0));
            check(!wrongCapital.success(), "A resident founded with another settlement as capital");
            var otherLeader = politics.execute(stranger, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.BOOTSTRAP, other,
                    other + "_charter", elsewhere.id().toString(), new Politics.Person(resident.getUUID(), Politics.Kind.PLAYER), "", "", "", 0, 0, 0));
            check(!otherLeader.success(), "A resident named another player as leader");
            // Eligibility lost after review: Apply re-checks and refuses.
            var stale = start(stranger, "politics.bootstrap");
            var s1 = call(stranger, stale, "PICK", stale.options().get(0).key());
            var s2 = call(stranger, s1, "PICK", s1.options().get(0).key());
            var s3 = call(stranger, s2, "PICK", s2.options().get(0).key());
            var staleReview = call(stranger, s3, "PICK", s3.options().get(0).key());
            check(staleReview.mode().equals("review"), "Second founding did not reach review: " + staleReview.detailText());
            api.setResidence(stranger, capital.id());
            var refused = call(stranger, staleReview, "APPLY", "");
            check(politics.government(other).isEmpty(), "Founding applied after the residence moved away");
            check(refused.mode().equals("error") || refused.detailText().contains("Could not complete"), "Lost eligibility was not refused: " + refused.detailText());
            // The disabled setting is covered by PoliticalGameTests.residentFoundingFollowsTheSetting with a fixed value:
            // ConfigValue.set rewrites the file, and Forge's watcher reloads it on another thread, so reads here could race.
        }

        // Operators keep their setup path: every ungoverned kingdom, never a governed one.
        var operatorKingdoms = start(operator, "politics.bootstrap");
        check(operatorKingdoms.options().stream().noneMatch(o -> o.labelText().equals(api.getKingdom(new ResourceLocation(kingdom)).map(k -> PlayerWords.text(k.translationKey())).orElse("?"))),
                "A governed kingdom was offered to an operator");
        } finally {
            online(helper, false, resident, stranger, operator);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void catalogueSearchUsesWordsAliasesAndOrder(GameTestHelper helper) {
        InteractionNetwork.init();
        var player = player(helper, "SearchPlayer", false);
        var operator = player(helper, "SearchOperator", true);
        for (String query : List.of("join guild", "guild join", "  JOIN   Guild  ")) {
            var found = list(player, query);
            check(!found.options().isEmpty() && found.options().get(0).key().equals("civic.join"), "\"" + query + "\" should find Join a guild first: " + labels(found));
        }
        var election = list(player, "read election");
        check(!election.options().isEmpty() && election.options().get(0).key().equals("politics.election_inspect"), "read election: " + labels(election));
        check(list(operator, "rename village").options().stream().anyMatch(o -> o.key().equals("settlement.rename")), "village should find settlement tasks for operators");
        check(list(player, "rename village").options().stream().noneMatch(o -> o.key().equals("settlement.rename")), "An operator task matched for a player");
        var none = list(player, "zzzz quixotic");
        check(none.options().isEmpty() && none.detailText().contains("No tasks match"), "Zero-match search must say so: " + none.detailText());
        check(list(player, "unirse gremio").options().stream().anyMatch(o -> o.key().equals("civic.join")), "Spanish task words should match");
        check(list(player, "加入行会").options().stream().anyMatch(o -> o.key().equals("civic.join")), "Chinese task words should match");

        var playerIndex = request(player, NONE, NONE, "INDEX", "", "", 0);
        var operatorIndex = request(operator, NONE, NONE, "INDEX", "", "", 0);
        check(playerIndex.mode().equals("index") && !playerIndex.value().contains("\"settlement.rename\"") && playerIndex.value().contains("\"civic.join\""),
                "The player index must list exactly authorized tasks");
        check(operatorIndex.value().contains("\"settlement.rename\"") && operatorIndex.value().contains("\"operator\":true"), "The operator index omitted administrative tasks");
        check(InteractionNetwork.encodeReply(operatorIndex).length() < 40000, "The catalogue index is not comfortably bounded");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void choiceSearchAndEmptyStatesAreDistinct(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var player = player(helper, "ChoiceSearcher", false);
        var alder = settlement(helper, api, 71320, null, "Alder Crossing");
        var birch = settlement(helper, api, 71480, null, "Birch Hollow");
        var knowledge = SettlementKnowledge.get(server);
        knowledge.discover(player.getUUID(), alder.id());
        knowledge.discover(player.getUUID(), birch.id());
        var form = start(player, "settlement.inspect");
        var filtered = request(player, form.session(), form.state(), "FILTER", "", "hollow BIRCH", 0);
        check(filtered.options().size() == 1 && filtered.options().get(0).labelText().equals("Birch Hollow"), "Token search over choices: " + labels(filtered));
        String id = filtered.options().get(0).id();
        var refiltered = request(player, filtered.session(), filtered.state(), "FILTER", "", "birch", 0);
        check(refiltered.options().stream().anyMatch(o -> o.id().equals(id)), "Option identity is not stable across searches");
        var none = request(player, refiltered.session(), refiltered.state(), "FILTER", "", "no such place", 0);
        check(none.options().isEmpty() && none.detailText().contains("No choices match"), "A search with no match must not read as no choices: " + none.detailText());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void typedEntriesAndReviewEditsKeepUnaffectedAnswers(GameTestHelper helper) {
        InteractionNetwork.init();
        var operator = player(helper, "EditingOperator", true);
        var pos = helper.absolutePos(new BlockPos(71640, 2, 71640));
        operator.setPos(pos.getX(), pos.getY(), pos.getZ());
        var radius = start(operator, "settlement.create");
        check(radius.mode().equals("text") && radius.step() == 1 && radius.steps() == 3, "Step counting: " + radius.step() + "/" + radius.steps());
        check(radius.detailText().contains("16") && radius.detailText().contains("512"), "Radius bounds are not shown before submission: " + radius.detailText());
        var tooSmall = call(operator, radius, "NEXT", "5");
        check(tooSmall.mode().equals("text") && tooSmall.value().equals("5") && tooSmall.detailText().contains("from 16 to 512"), "Out-of-range entry lost its draft or reason: " + tooSmall.detailText());
        var notNumber = call(operator, tooSmall, "NEXT", "lots");
        check(notNumber.mode().equals("text") && notNumber.value().equals("lots") && notNumber.detailText().contains("whole number"), "Non-number entry: " + notNumber.detailText());
        var name = call(operator, notNumber, "NEXT", "32");
        check(name.mode().equals("text") && name.step() == 2, "Valid radius did not advance");
        var back = call(operator, name, "BACK", "");
        check(back.mode().equals("text") && back.value().equals("32"), "Back erased the earlier answer: " + back.value());
        var name2 = call(operator, back, "NEXT", "32");
        var review = call(operator, name2, "NEXT", "Edited Reach");
        check(review.mode().equals("review") && review.options().size() == 2, "Review must offer each answer for editing");
        var edit = call(operator, review, "EDIT", "0");
        check(edit.mode().equals("text") && edit.value().equals("32"), "Editing the radius lost its value: " + edit.value());
        var stale = request(operator, review.session(), review.state(), "APPLY", "", "", 0);
        check(stale.mode().equals("error"), "The review before an edit could still be applied");
        var keptName = call(operator, edit, "NEXT", "40");
        check(keptName.mode().equals("text") && keptName.value().equals("Edited Reach"), "Editing an earlier answer erased an unaffected later one: " + keptName.value());
        var newReview = call(operator, keptName, "NEXT", "Edited Reach");
        var applied = call(operator, newReview, "APPLY", "");
        var created = McaKingdomsApi.get(helper.getLevel().getServer()).getSettlementAt(helper.getLevel(), operator.blockPosition()).orElseThrow();
        check(applied.mode().equals("result") && created.displayName().equals("Edited Reach") && created.radius() == 40, "Edited review did not apply the new answer: " + applied.detailText());

        // A dependent answer is asked again after its source changes; an independent one is kept.
        var server = helper.getLevel().getServer();
        String kingdom = McaKingdomsApi.get(server).getKingdoms().stream().map(k -> k.id().toString()).sorted()
                .filter(k -> KingdomsPoliticsApi.get(server).government(k).isEmpty()).findFirst().orElse(null);
        if (kingdom != null) {
            var capital = settlement(helper, McaKingdomsApi.get(server), 71800, kingdom, "Dependent Reach");
            SettlementKnowledge.get(server).discover(operator.getUUID(), capital.id());
            var kingdoms = start(operator, "politics.bootstrap");
            var k = kingdoms.options().stream().filter(o -> o.labelText().equals(McaKingdomsApi.get(server).getKingdom(new ResourceLocation(kingdom)).map(v -> PlayerWords.text(v.translationKey())).orElse(""))).findFirst().orElseThrow();
            var capitals = call(operator, kingdoms, "PICK", k.key());
            var chosenCapital = capitals.options().stream().filter(o -> o.labelText().equals("Dependent Reach")).findFirst().orElseThrow(() -> new GameTestAssertException("Capital not offered: " + labels(capitals)));
            var constitutions = call(operator, capitals, "PICK", chosenCapital.key());
            var leaders = call(operator, constitutions, "PICK", constitutions.options().get(0).key());
            var self = leaders.options().stream().filter(o -> o.labelText().equals("EditingOperator")).findFirst().orElseThrow();
            var foundingReview = call(operator, leaders, "PICK", self.key());
            var editKingdom = call(operator, foundingReview, "EDIT", "0");
            check(editKingdom.mode().equals("choice") && editKingdom.options().stream().anyMatch(InteractionNetwork.Option::selected), "The current choice is not marked when editing");
            var askedAgain = call(operator, editKingdom, "PICK", editKingdom.options().stream().filter(InteractionNetwork.Option::selected).findFirst().orElseThrow().key());
            check(askedAgain.mode().equals("choice") && askedAgain.titleText().contains("Capital"), "A dependent answer was kept after its source was edited: " + askedAgain.titleText());
        }
        helper.succeed();
    }

    private static SettlementView settlement(GameTestHelper helper, KingdomsService api, int offset, String kingdom, String name) {
        var level = helper.getLevel();
        var s = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(offset, 2, offset)), 32,
                new ResourceLocation("mcakingdoms", "manual"), "usability-" + UUID.randomUUID(), name));
        if (kingdom != null && !s.kingdomId().toString().equals(kingdom)) s = api.setKingdom(s.id(), new ResourceLocation(kingdom));
        return s;
    }
    private static String labels(InteractionNetwork.Reply reply) { return reply.options().stream().map(InteractionNetwork.Option::labelText).toList() + " / " + reply.detailText(); }
    private static InteractionNetwork.Reply start(FakePlayer player, String task) { return request(player, NONE, NONE, "TASK", task, "", 0); }
    private static InteractionNetwork.Reply list(FakePlayer player, String search) { return request(player, NONE, NONE, "LIST", "", search, 0); }
    private static InteractionNetwork.Reply call(FakePlayer player, InteractionNetwork.Reply prior, String operation, String value) { return request(player, prior.session(), prior.state(), operation, value, "", 0); }
    private static InteractionNetwork.Reply request(FakePlayer player, UUID session, UUID state, String operation, String value, String search, int page) {
        return InteractionNetwork.handle(player, new InteractionNetwork.Request(UUID.randomUUID(), session, state, operation, value, search, page));
    }
    private static FakePlayer player(GameTestHelper helper, String name, boolean operator) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        // Known to the server like a player who has joined, so the political service accepts them as a person.
        if (helper.getLevel().getServer().getProfileCache() != null) helper.getLevel().getServer().getProfileCache().add(profile);
        var player = new FakePlayer(helper.getLevel(), profile) { @Override public boolean hasPermissions(int level) { return operator; } };
        var pos = helper.absolutePos(new BlockPos(50000, 2, 50000)); player.setPos(pos.getX(), pos.getY(), pos.getZ()); return player;
    }
    /** Lists (or unlists) test stand-ins in the server's player map, which is what the political service's known-player check reads. */
    @SuppressWarnings("unchecked")
    private static void online(GameTestHelper helper, boolean listed, FakePlayer... players) {
        try {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            var byId = (Map<UUID, net.minecraft.server.level.ServerPlayer>) field.get(helper.getLevel().getServer().getPlayerList());
            for (var player : players) { if (listed) byId.put(player.getUUID(), player); else byId.remove(player.getUUID()); }
        } catch (ReflectiveOperationException unavailable) {
            throw new GameTestAssertException("The test server's player map is unavailable: " + unavailable);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new GameTestAssertException(message); }
    private UsabilityGameTests() { }
}
