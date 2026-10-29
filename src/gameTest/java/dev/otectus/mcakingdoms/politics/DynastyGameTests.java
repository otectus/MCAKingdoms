package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.api.politics.PoliticalTransition;
import dev.otectus.mcakingdoms.politics.dynasty.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/**
 * A5 GameTests: the succession lifecycle over real government records, with a fixed family graph standing in for MCA's
 * family tree: abdication, one case per vacancy across repeats and a restart, a player heir's acceptance, an elected
 * successor and an elected ruler that lineage never displaces, a minor heir under a guardian's regency until adulthood,
 * and a crown that is not this mod's to decide.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class DynastyGameTests {
    private static final String SERENUM = "mcakingdoms:serenum";
    private static final ResourceLocation EQUAL = new ResourceLocation("mcakingdoms", "equal_primogeniture");

    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    /** A family graph the test edits; a person missing from it is unknown, as in MCA. */
    private static final class Family implements Lineage.Source {
        final Map<UUID, Lineage.Node> nodes = new HashMap<>();
        void put(UUID id, Set<UUID> parents, Set<UUID> children, boolean player, Lineage.Stage stage, String name) {
            nodes.put(id, new Lineage.Node(id, parents, children, null, false, player, stage, name));
        }
        void stage(UUID id, Lineage.Stage stage) {
            var n = nodes.get(id);
            nodes.put(id, new Lineage.Node(n.id(), n.parents(), n.children(), n.partner(), n.deceased(), n.player(), stage, n.name()));
        }
        @Override public Optional<Lineage.Node> node(UUID id) { return Optional.ofNullable(nodes.get(id)); }
    }

    private static FakePlayer operator(GameTestHelper helper, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name)) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
    }

    /** A settlement in its own lane far from every other test's settlements, so the layout of the run never makes two overlap. */
    private static SettlementView settlement(GameTestHelper helper, KingdomsService service, String key, int lane) {
        BlockPos position = helper.absolutePos(new BlockPos(0, 2, 2)).offset(0, 0, 61_000 + lane * 48);
        var result = service.registerCandidate(helper.getLevel(), SettlementCandidate.structure(helper.getLevel().dimension(), position, 4,
                new ResourceLocation("mcakingdoms:political_test"), key + position, null));
        return service.setKingdom(result.id(), new ResourceLocation(SERENUM));
    }

    private static Request req(GovernmentService service, Action action, String definition, String target, Person person) {
        return new Request(UUID.randomUUID(), service.revision(), action, SERENUM, definition, target, person, "", "", "", 0, 0, 0);
    }

    private static Result run(GovernmentService politics, ServerPlayer actor, Request request) {
        Result result = politics.execute(actor, request);
        check(result.success(), request.action() + " refused: " + result.message());
        return result;
    }

    private static Notice notice(GameTestHelper helper, Request request, UUID actor) {
        return new Notice(request.requestId(), SERENUM, request.action(), actor, SERENUM, helper.getLevel().getGameTime());
    }

    private static Person leader(PoliticalSavedData data) {
        var office = data.records().governments.get(SERENUM).offices().get("mcakingdoms:leader");
        return office == null ? null : office.holder();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lineageSeatsAnAcceptingHeirOnceAcrossRestart(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var identity = McaKingdomsApi.get(server);
        var data = new PoliticalSavedData();
        var politics = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, false, id -> true);
        var ruler = operator(helper, "DynastyRuler");
        var heir = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DynastyHeir"));
        var family = new Family();
        family.put(ruler.getUUID(), Set.of(), Set.of(heir.getUUID()), true, Lineage.Stage.ADULT, "DynastyRuler");
        family.put(heir.getUUID(), Set.of(ruler.getUUID()), Set.of(), true, Lineage.Stage.ADULT, "DynastyHeir");
        var store = new DynastySavedData();
        var dynasty = new DynastyService(server, politics, store, s -> family, (k, id) -> true);
        var seat = settlement(helper, identity, "dynasty-accept", 0);
        run(politics, ruler, req(politics, Action.BOOTSTRAP, SERENUM + "_charter", seat.id().toString(), new Person(ruler.getUUID(), Kind.PLAYER)));
        dynasty.maintain();
        check(store.records().ruler(SERENUM).map(r -> r.person().id().equals(ruler.getUUID())).orElse(false), "The seated ruler was not recorded");
        dynasty.adoptLaw(ruler, UUID.randomUUID(), SERENUM, EQUAL);
        check(dynasty.lawId(SERENUM).equals(EQUAL), "The law was not adopted");
        var abdicate = req(politics, Action.ABDICATE, "", "", null);
        run(politics, ruler, abdicate);
        dynasty.observe(notice(helper, abdicate, ruler.getUUID()));
        dynasty.observe(notice(helper, abdicate, ruler.getUUID()));
        var opened = store.records().current(SERENUM).orElseThrow(() -> new GameTestAssertException("No succession case opened"));
        check(opened.cause() == DynastySavedData.Cause.ABDICATION && opened.status() == DynastySavedData.Status.VACANCY_CONFIRMED, "Wrong opening: " + opened);
        check(store.records().cases().size() == 1, "A repeated notice opened another case");
        check(store.records().memorials().size() == 1, "The abdication was not remembered once");
        dynasty.maintain();
        var offered = store.records().current(SERENUM).orElseThrow();
        check(offered.status() == DynastySavedData.Status.CLAIMS_PENDING && offered.pending() == DynastySavedData.Pending.ACCEPTANCE
                && offered.heir().id().equals(heir.getUUID()), "The heir was not offered the crown: " + offered);
        check(leader(data) == null, "A player heir was seated before accepting");
        var restartedStore = DynastySavedData.load(store.save(new CompoundTag()));
        var restarted = new DynastyService(server, politics, restartedStore, s -> family, (k, id) -> true);
        restarted.maintain();
        check(restartedStore.records().cases().size() == 1 && restartedStore.records().current(SERENUM).orElseThrow().status() == DynastySavedData.Status.CLAIMS_PENDING,
                "A restart changed the pending succession");
        restarted.answerCrown(heir, UUID.randomUUID(), SERENUM, true);
        check(heir.getUUID().equals(Optional.ofNullable(leader(data)).map(Person::id).orElse(null)), "The accepting heir was not seated");
        var closed = restartedStore.records().cases().iterator().next();
        check(closed.status() == DynastySavedData.Status.RATIFIED_SUCCESSION, "The case was not ratified: " + closed.status());
        long revision = data.records().revision;
        try { restarted.answerCrown(heir, UUID.randomUUID(), SERENUM, true); throw new GameTestAssertException("A second acceptance was not refused"); }
        catch (IllegalArgumentException expected) { }
        check(data.records().revision == revision, "A repeated acceptance changed the government");
        var legitimacy = restarted.legitimacy(SERENUM).orElseThrow();
        check(legitimacy.factors().stream().anyMatch(f -> f.key().endsWith(".law")) && legitimacy.factors().stream().anyMatch(f -> f.key().endsWith(".lineage")),
                "Legitimacy did not read the lawful, lineal succession: " + legitimacy.factors());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void electionsAreNeverDisplacedByLineage(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var identity = McaKingdomsApi.get(server);
        var data = new PoliticalSavedData();
        var politics = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, false, id -> true);
        var one = operator(helper, "DynastyElectOne");
        var two = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DynastyElectTwo"));
        var three = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DynastyElectThree"));
        var child = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DynastyElectChild"));
        var family = new Family();
        family.put(one.getUUID(), Set.of(), Set.of(child.getUUID()), true, Lineage.Stage.ADULT, "One");
        family.put(child.getUUID(), Set.of(one.getUUID()), Set.of(), true, Lineage.Stage.ADULT, "Child");
        family.put(two.getUUID(), Set.of(), Set.of(three.getUUID()), true, Lineage.Stage.ADULT, "Two");
        family.put(three.getUUID(), Set.of(two.getUUID()), Set.of(), true, Lineage.Stage.ADULT, "Three");
        var store = new DynastySavedData();
        var dynasty = new DynastyService(server, politics, store, s -> family, (k, id) -> true);
        var seat = settlement(helper, identity, "dynasty-elect", 1);
        run(politics, one, req(politics, Action.BOOTSTRAP, SERENUM + "_charter", seat.id().toString(), new Person(one.getUUID(), Kind.PLAYER)));
        check(politics.adoptTransitionRule(one, UUID.randomUUID(), politics.revision(), SERENUM, new PoliticalTransition.Rule(true, false, 1200, 200, 1200, 4, Set.of())).success(), "Rule refused");
        run(politics, one, req(politics, Action.APPOINT, "mcakingdoms:local_steward", seat.id().toString(), new Person(two.getUUID(), Kind.PLAYER)));
        Result opened = politics.openElection(one, UUID.randomUUID(), politics.revision(), SERENUM, List.of(two.getUUID(), three.getUUID()));
        check(opened.success(), opened.message());
        UUID election = UUID.fromString(opened.recordId());
        check(politics.castBallot(one, UUID.randomUUID(), politics.revision(), election, two.getUUID()).success(), "Ballot refused");
        check(politics.castBallot(two, UUID.randomUUID(), politics.revision(), election, two.getUUID()).success(), "Ballot refused");
        check(politics.closeElection(one, UUID.randomUUID(), politics.revision(), election).success(), "Election not closed");
        dynasty.maintain();
        dynasty.adoptLaw(one, UUID.randomUUID(), SERENUM, EQUAL);
        var abdicate = req(politics, Action.ABDICATE, "", "", null);
        run(politics, one, abdicate);
        dynasty.observe(notice(helper, abdicate, one.getUUID()));
        dynasty.maintain();
        var first = store.records().current(SERENUM).orElseThrow();
        check(first.heir() != null && first.heir().id().equals(two.getUUID()), "The elected successor did not come before the ruler's child: " + first);
        dynasty.answerCrown(two, UUID.randomUUID(), SERENUM, true);
        check(two.getUUID().equals(Optional.ofNullable(leader(data)).map(Person::id).orElse(null)), "The elected successor was not seated");
        dynasty.maintain();
        check(store.records().ruler(SERENUM).map(DynastySavedData.Ruler::elected).orElse(false), "The elected ruler was not recorded as elected");
        var second = req(politics, Action.ABDICATE, "", "", null);
        run(politics, two, second);
        dynasty.observe(notice(helper, second, two.getUUID()));
        dynasty.maintain();
        var next = store.records().current(SERENUM).orElseThrow();
        check(next.status() == DynastySavedData.Status.LEFT_TO_ELECTION, "An elected ruler's office was decided by lineage: " + next.status());
        check(leader(data) == null, "Lineage seated someone in an elected office");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aMinorHeirWaitsUnderAGuardianRegencyUntilAdulthood(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var identity = McaKingdomsApi.get(server);
        var data = new PoliticalSavedData();
        var politics = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, false, id -> true);
        var ruler = operator(helper, "DynastyMinorRuler");
        var guardian = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "DynastyGuardian"));
        var seat = settlement(helper, identity, "dynasty-minor", 2);
        Villager kid = EntityType.VILLAGER.create(helper.getLevel());
        var beside = helper.absolutePos(new BlockPos(1, 2, 1));
        kid.moveTo(beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5);
        helper.getLevel().addFreshEntity(kid);
        identity.setResidence(kid, seat.id());
        try {
            var family = new Family();
            family.put(ruler.getUUID(), Set.of(), Set.of(kid.getUUID()), true, Lineage.Stage.ADULT, "Ruler");
            family.put(guardian.getUUID(), Set.of(), Set.of(kid.getUUID()), true, Lineage.Stage.ADULT, "Guardian");
            family.put(kid.getUUID(), Set.of(ruler.getUUID(), guardian.getUUID()), Set.of(), false, Lineage.Stage.MINOR, "Kid");
            var store = new DynastySavedData();
            var dynasty = new DynastyService(server, politics, store, s -> family, (k, id) -> id.equals(kid.getUUID()));
            run(politics, ruler, req(politics, Action.BOOTSTRAP, SERENUM + "_charter", seat.id().toString(), new Person(ruler.getUUID(), Kind.PLAYER)));
            check(politics.adoptTransitionRule(ruler, UUID.randomUUID(), politics.revision(), SERENUM,
                    new PoliticalTransition.Rule(false, true, 1200, 200, 24000, 4, Set.of(Permission.APPOINT))).success(), "Rule refused");
            dynasty.maintain();
            dynasty.adoptLaw(ruler, UUID.randomUUID(), SERENUM, EQUAL);
            var abdicate = req(politics, Action.ABDICATE, "", "", null);
            run(politics, ruler, abdicate);
            dynasty.observe(notice(helper, abdicate, ruler.getUUID()));
            dynasty.maintain();
            var waiting = store.records().current(SERENUM).orElseThrow();
            check(waiting.status() == DynastySavedData.Status.REGENCY && waiting.heir().id().equals(kid.getUUID()), "The minor heir was not kept under a regency: " + waiting);
            var regency = politics.regency(SERENUM).orElseThrow(() -> new GameTestAssertException("No guardian regency was appointed: "
                    + store.records().current(SERENUM).map(DynastySavedData.Case::reasonKey).orElse("no case")));
            check(regency.active() && regency.regent().id().equals(guardian.getUUID()) && kid.getUUID().equals(regency.preservedSuccessor().id()), "Wrong regency: " + regency);
            check(leader(data) == null, "A minor was seated");
            dynasty.maintain();
            check(data.records().regencies.size() == 1, "The regency was appointed twice");
            family.stage(kid.getUUID(), Lineage.Stage.ADULT);
            dynasty.maintain();
            check(kid.getUUID().equals(Optional.ofNullable(leader(data)).map(Person::id).orElse(null)), "The heir of age was not seated: "
                    + store.records().cases().iterator().next().reasonKey());
            check(!politics.regency(SERENUM).orElseThrow().active(), "The regency did not end when the heir was seated");
            check(store.records().cases().iterator().next().status() == DynastySavedData.Status.RATIFIED_SUCCESSION, "The case was not ratified");
            helper.succeed();
        } finally { kid.discard(); }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void anObservedDeathOpensOneCaseWithMemorialAndMourning(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var identity = McaKingdomsApi.get(server);
        var data = new PoliticalSavedData();
        var politics = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, false, id -> true);
        var operator = operator(helper, "DynastyMourner");
        var seat = settlement(helper, identity, "dynasty-mourning", 4);
        Villager npc = EntityType.VILLAGER.create(helper.getLevel());
        var beside = helper.absolutePos(new BlockPos(1, 2, 1));
        npc.moveTo(beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5);
        helper.getLevel().addFreshEntity(npc);
        identity.setResidence(npc, seat.id());
        run(politics, operator, req(politics, Action.BOOTSTRAP, SERENUM + "_charter", seat.id().toString(), new Person(npc.getUUID(), Kind.NPC)));
        // The server's own store: banners read mourning from it.
        var store = DynastySavedData.get(server);
        var dynasty = new DynastyService(server, politics, store, s -> id -> Optional.empty(), (k, id) -> true);
        dynasty.maintain();
        int memorials = store.records().memorials().size();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(politics);
        npc.hurt(npc.damageSources().genericKill(), Float.MAX_VALUE);
        politics.deathObserved(new net.minecraftforge.event.entity.living.LivingDeathEvent(npc, npc.damageSources().genericKill()));
        helper.runAfterDelay(3, () -> {
            try {
                check(data.records().governments.get(SERENUM).state() == State.INTERREGNUM, "The observed death did not vacate the leadership");
                var death = new Notice(UUID.randomUUID(), SERENUM, Action.REMOVE_OFFICE, npc.getUUID(), npc.getUUID().toString(), helper.getLevel().getGameTime());
                dynasty.observe(death);
                dynasty.observe(death);
                var k = store.records().current(SERENUM).orElseThrow(() -> new GameTestAssertException("No case opened for the death"));
                check(k.cause() == DynastySavedData.Cause.DEATH && k.status() == DynastySavedData.Status.LEFT_TO_OFFICIALS,
                        "Under the default law a death should be left to the officials: " + k.cause() + " " + k.status());
                check(store.records().memorials().size() == memorials + 1, "The ruler was not remembered exactly once");
                check(DynastyService.mourning(server, SERENUM), "The kingdom is not in mourning");
                var arms = dev.otectus.mcakingdoms.heraldry.Heraldry.of(server, SERENUM).orElseThrow();
                check(arms.revision() % 2 == 1, "Mourning did not reach the kingdom's banners");
                helper.succeed();
            } finally {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(politics);
                npc.discard();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aCrownGovernedElsewhereIsNeverDecidedHere(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var identity = McaKingdomsApi.get(server);
        var data = new PoliticalSavedData();
        var native_ = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, false, id -> true);
        var ruler = operator(helper, "DynastyElsewhere");
        var seat = settlement(helper, identity, "dynasty-elsewhere", 3);
        run(native_, ruler, req(native_, Action.BOOTSTRAP, SERENUM + "_charter", seat.id().toString(), new Person(ruler.getUUID(), Kind.PLAYER)));
        var store = new DynastySavedData();
        new DynastyService(server, native_, store, s -> id -> Optional.empty(), (k, id) -> true).maintain();
        // The same records, read by a service for which every crown is read-only (MCA Capitals governs it).
        var elsewhere = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, true, id -> true);
        var dynasty = new DynastyService(server, elsewhere, store, s -> id -> Optional.empty(), (k, id) -> true);
        try { dynasty.adoptLaw(ruler, UUID.randomUUID(), SERENUM, EQUAL); throw new GameTestAssertException("A law was adopted for a crown governed elsewhere"); }
        catch (IllegalArgumentException expected) { }
        var next = data.transaction(); next.revision++;
        var g = next.governments.get(SERENUM);
        next.governments.put(SERENUM, new Government(g.kingdom(), g.capital(), g.profileId(), g.constitution(), State.INTERREGNUM, Map.of(), Map.of(), null, next.revision));
        data.commit(next);
        dynasty.maintain();
        check(store.records().current(SERENUM).isEmpty(), "A succession case opened for a crown governed elsewhere");
        helper.succeed();
    }
}
