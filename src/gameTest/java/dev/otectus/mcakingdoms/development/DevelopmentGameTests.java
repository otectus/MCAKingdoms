package dev.otectus.mcakingdoms.development;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.persistence.FaultPoints;
import dev.otectus.mcakingdoms.persistence.PlayerEvidence;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;
import java.util.function.Consumer;

/**
 * GameTests for settlement development over the shipped templates and real blocks.
 *
 * <p>D7 (0.3.0): the default mode changes nothing; a survey refuses a plot beside a bed; lowering the mode pauses
 * construction. 0.3.1 regression tests, each reproducing a failure path of the 9 November 2026 review:
 * <ul>
 *   <li>AUD-02: a delivery is confirmed only by the player's save; a crash before it rolls the delivery back; a request is
 *   never taken twice; a refund claim takes exactly what fits and leaves the rest owed; an unknown item stays owed.</li>
 *   <li>AUD-03: a crash after a placement and before its record counts the block once; a failed journal write after a
 *   placement counts it once; a placement the world refuses is not counted; a block removed after placement stops the
 *   work instead of being placed again; completion waits for two world saves.</li>
 *   <li>AUD-04: opting out, a protected home, a lost approver, a disallowed dimension and a lowered mode each stop the next
 *   block; re-enabling does not resume; resuming surveys the rest and lists what is in the way.</li>
 *   <li>AUD-05: a player's block, even one matching the work's, stays a blocker on resurvey; rollback removes only the
 *   work's blocks and closes once the removals are saved.</li>
 *   <li>AUD-06: a merge carries works, assets, points, goods (with overflow) and refunds once, with conservative consent;
 *   a kingdom change pauses construction for the new kingdom's review.</li>
 *   <li>AUD-07: a request older than the receipts kept is refused, never replayed.</li>
 *   <li>AUD-08: with development off, pausing, cancelling, rolling back and claiming refunds still work; new work does not.</li>
 * </ul>
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class DevelopmentGameTests {
    private static final ResourceLocation LAMP = new ResourceLocation("mcakingdoms", "lamp_post");

    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    private static FakePlayer operator(GameTestHelper helper, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name)) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
    }

    /** Lists or unlists test players as online: an operator's approval lasts only while they are an operator now (AUD-04). */
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

    private static UUID req(MinecraftServer server) { return DevelopmentService.request(server); }

    /** A settlement around the plot, on a stone floor, with the official standing on the plot. */
    private static SettlementView setup(GameTestHelper helper, FakePlayer official, String key) {
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        var plot = helper.absolutePos(new BlockPos(4, 2, 4));
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var made = api.registerCandidate(helper.getLevel(), SettlementCandidate.structure(helper.getLevel().dimension(), plot, 6,
                new ResourceLocation("mcakingdoms:development_test"), key + plot, null));
        official.moveTo(plot.getX() + 0.5, plot.getY(), plot.getZ() + 0.5);
        return api.setKingdom(made.id(), new ResourceLocation("mcakingdoms:madera"));
    }

    private static UUID proposed(DevelopmentService service, FakePlayer official, SettlementView settlement) {
        return UUID.fromString(service.propose(official, req(service.server()), settlement.id(), LAMP).operation());
    }

    private static int count(FakePlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) total += stack.getCount();
        return total;
    }

    private static Project work(DevelopmentService service, UUID id) { return service.data().records().project(id).orElseThrow(); }

    /** The player's data was saved: their markers are on disk, so their pending operations are confirmed. */
    private static void playerSaved(DevelopmentService service, FakePlayer player) {
        service.settleFromPlayerData(player.getUUID(), PlayerEvidence.markers(player), player, true);
    }

    /** A funded lamp post in a settlement that allows construction, with its delivery confirmed. Returns the work. */
    private static UUID fundedAndAllowed(GameTestHelper helper, DevelopmentService service, FakePlayer official, SettlementView settlement) {
        UUID work = proposed(service, official, settlement);
        service.approve(official, req(service.server()), work);
        official.getInventory().add(new ItemStack(Items.OAK_FENCE, 2));
        official.getInventory().add(new ItemStack(Items.LANTERN, 1));
        service.contribute(official, req(service.server()), work);
        playerSaved(service, official);
        service.optIn(official, req(service.server()), settlement.id());
        official.moveTo(official.getX() + 20, official.getY(), official.getZ());
        return work;
    }

    /** A restart: the records reload from their saved form in a new server run. */
    private static DevelopmentService restart(DevelopmentService service) {
        var reloaded = DevelopmentSavedData.load(service.data().save(new CompoundTag()));
        DevelopmentService.newSession();
        return new DevelopmentService(service.server(), reloaded);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theDefaultModeChangesNothing(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        check(DevelopmentMode.current() == DevelopmentMode.OFF, "Development must be off by default");
        var official = operator(helper, "DevelopmentOff");
        var settlement = setup(helper, official, "development-off");
        var service = new DevelopmentService(server, new DevelopmentSavedData());
        try { service.propose(official, req(server), settlement.id(), LAMP); throw new GameTestAssertException("A work was proposed while development is off"); }
        catch (IllegalArgumentException expected) { }
        service.maintain();
        check(service.data().records().projects().isEmpty() && service.data().records().revision() == 0, "Development wrote records while off");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "development")
    public static void aFundedLampPostIsBuiltOnceAcrossARestart(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentBuilder");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-build");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            var surveyed = work(service, work);
            check(surveyed.state() == State.SURVEYED && surveyed.journal().size() == 3 && surveyed.construction(), "The plot was not surveyed clear: " + surveyed.state() + " " + surveyed.blockers());
            service.approve(official, req(server), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 5));
            official.getInventory().add(new ItemStack(Items.LANTERN, 2));
            var named = new ItemStack(Items.LANTERN);
            named.setHoverName(net.minecraft.network.chat.Component.literal("Heirloom"));
            official.getInventory().add(named);
            UUID delivery = req(server);
            var reply = service.contribute(official, delivery, work);
            check(reply.pending(), "A delivery was reported final before the player's save");
            check(count(official, Items.OAK_FENCE) == 3 && count(official, Items.LANTERN) == 2,
                    "The delivery took the wrong items: fences " + count(official, Items.OAK_FENCE) + ", lanterns " + count(official, Items.LANTERN));
            service.contribute(official, delivery, work);
            check(count(official, Items.OAK_FENCE) == 3 && count(official, Items.LANTERN) == 2, "A repeated request took items twice");
            check(service.data().records().operations().size() == 1, "A repeated request recorded a second delivery");
            check(work(service, work).state() == State.FUNDED, "The delivery did not count toward the work");
            service.optIn(official, req(server), settlement.id());
            official.moveTo(official.getX() + 20, official.getY(), official.getZ());
            service.maintain();
            check(work(service, work).state() == State.FUNDED, "An unconfirmed delivery let the work start: " + work(service, work).state());
            playerSaved(service, official);
            var op = service.data().records().operations().iterator().next();
            check(op.stage() == OpStage.CONFIRMED, "The player's save did not confirm the delivery: " + op.stage());
            check(PlayerEvidence.markers(official).isEmpty(), "A settled delivery kept its marker in the player's data");
            service.maintain(); // FUNDED -> READY
            service.maintain(); // READY -> BUILDING: every cell announced, nothing placed yet
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            check(work(service, work).journal().stream().allMatch(x -> x.stage() == Stage.INTENT), "The cells were not announced first: " + work(service, work).journal());
            check(helper.getLevel().getBlockState(plot).isAir(), "A block was placed before its announcement was saved");
            // A crash after the first block was placed and before the journal was written: it must count once, not twice.
            helper.setBlock(new BlockPos(4, 2, 4), Blocks.OAK_FENCE.defaultBlockState());
            var restarted = restart(service);
            restarted.maintain(); // settles the previous run's cells from the world, then announces the rest
            check(work(restarted, work).journal().get(0).stage() == Stage.VERIFIED, "The block placed before the crash was not recognised: " + work(restarted, work).journal());
            restarted.maintain(); // places the rest
            check(helper.getLevel().getBlockState(plot.above()).is(Blocks.OAK_FENCE) && helper.getLevel().getBlockState(plot.above(2)).is(Blocks.LANTERN),
                    "The lamp post is incomplete: " + work(restarted, work).journal());
            restarted.worldSaved(helper.getLevel());
            restarted.maintain();
            check(work(restarted, work).state() == State.BUILDING, "The work completed before its blocks were known to be on disk");
            restarted.worldSaved(helper.getLevel());
            restarted.maintain();
            var done = work(restarted, work);
            check(done.state() == State.COMPLETED, "The work did not complete: " + done.state() + " " + done.reasonKey());
            check(done.journal().stream().allMatch(x -> x.stage() == Stage.VERIFIED), "The journal lost a block: " + done.journal());
            check(restarted.data().records().economy(settlement.id()).points() == 1, "Completion did not count once");
            check(restarted.data().records().assets().stream().filter(a -> a.work().equals(work)).count() == 1, "Completion did not record one asset");
            restarted.maintain();
            restarted.worldSaved(helper.getLevel());
            check(restarted.data().records().economy(settlement.id()).points() == 1, "Completion counted twice");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aSurveyRefusesAPlotBesideABed(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        try {
            var official = operator(helper, "DevelopmentBed");
            var settlement = setup(helper, official, "development-bed");
            helper.setBlock(new BlockPos(5, 2, 4), Blocks.RED_BED.defaultBlockState());
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            var p = work(service, work);
            boolean refused = p.blockers().stream().anyMatch(b -> b.cause() == Cause.BED || b.cause() == Cause.BLOCK_ENTITY || b.cause() == Cause.POI);
            check(p.state() == State.BLOCKED && refused, "A plot beside a bed was not refused: " + p.state() + " " + p.blockers());
            check(p.blockers().stream().allMatch(b -> b.pos() != null || b.cause() == Cause.DIMENSION), "A blocker lost its position: " + p.blockers());
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "development")
    public static void aChangedBlockStopsConstructionAndRollbackRemovesOnlyOurs(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentRepair");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-repair");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            service.maintain(); // READY
            service.maintain(); // announced
            // Someone builds on the top of the plot after the announcement.
            helper.setBlock(new BlockPos(4, 4, 4), Blocks.GLASS.defaultBlockState());
            service.maintain(); // places the two fences, then meets the glass
            var stopped = work(service, work);
            check(stopped.state() == State.REPAIR_NEEDED && stopped.blockers().get(0).cause() == Cause.CHANGED, "A changed block did not stop construction: " + stopped.state() + " " + stopped.blockers());
            check(helper.getLevel().getBlockState(plot).is(Blocks.OAK_FENCE) && helper.getLevel().getBlockState(plot.above()).is(Blocks.OAK_FENCE), "The first blocks were not built");
            check(helper.getLevel().getBlockState(plot.above(2)).is(Blocks.GLASS), "Construction replaced a player's block");
            var rolled = service.repair(official, req(server), work, DevelopmentService.Repair.ROLLBACK);
            check(rolled.pending(), "A rollback reported itself closed before its removals were saved");
            check(helper.getLevel().getBlockState(plot).isAir() && helper.getLevel().getBlockState(plot.above()).isAir(), "Rollback left the work's blocks");
            check(helper.getLevel().getBlockState(plot.above(2)).is(Blocks.GLASS), "Rollback removed a block the work did not place");
            check(work(service, work).state() == State.ROLLING_BACK, "The rollback did not wait for its removals to be saved");
            service.worldSaved(helper.getLevel());
            service.worldSaved(helper.getLevel());
            service.maintain();
            check(work(service, work).state() == State.CANCELLED, "A finished rollback stayed open: " + work(service, work).state());
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aCancelledWorkRefundsItsDeliveriesOnce(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        try {
            var official = operator(helper, "DevelopmentRefund");
            var settlement = setup(helper, official, "development-refund");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            service.approve(official, req(server), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
            service.contribute(official, req(server), work);
            check(count(official, Items.OAK_FENCE) == 0, "The delivery was not taken");
            try { service.cancel(official, req(server), work); throw new GameTestAssertException("A work was cancelled while a delivery to it was unconfirmed"); }
            catch (IllegalArgumentException expected) { }
            playerSaved(service, official);
            service.cancel(official, req(server), work);
            UUID claim = req(server);
            service.claimRefunds(official, claim);
            check(count(official, Items.OAK_FENCE) == 1, "The refund did not return the delivery");
            service.claimRefunds(official, claim);
            check(count(official, Items.OAK_FENCE) == 1, "A repeated claim refunded twice");
            try { service.claimRefunds(official, req(server)); throw new GameTestAssertException("A second claim was accepted while the first was unconfirmed"); }
            catch (IllegalArgumentException expected) { }
            playerSaved(service, official);
            check(service.data().records().refunds().isEmpty(), "A confirmed claim left a refund owed");
            try { service.claimRefunds(official, req(server)); throw new GameTestAssertException("A second claim found refunds"); }
            catch (IllegalArgumentException expected) { }
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    /** AUD-02: a crash before the player's save rolls the delivery back from their saved data; nothing is counted or taken twice. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aDeliveryLostInACrashIsRolledBack(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        try {
            var official = operator(helper, "DevelopmentCrash");
            var settlement = setup(helper, official, "development-crash");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            service.approve(official, req(server), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 2));
            official.getInventory().add(new ItemStack(Items.LANTERN, 1));
            var savedBefore = PlayerEvidence.markers(official); // the player's file as last saved: no marker yet
            service.contribute(official, req(server), work);
            check(work(service, work).state() == State.FUNDED, "The delivery did not fund the work provisionally");
            // The server stops before the player's data is saved; at the next login their file is the one saved before.
            var restarted = restart(service);
            restarted.settleFromPlayerData(official.getUUID(), savedBefore, null, false);
            var after = work(restarted, work);
            check(after.state() == State.APPROVED && after.demand().stream().allMatch(r -> r.delivered() == 0), "A lost delivery still counted: " + after.state() + " " + after.demand());
            check(after.contributions().isEmpty(), "A lost delivery kept its contribution record");
            check(restarted.data().records().operations().iterator().next().stage() == OpStage.ROLLED_BACK, "The lost delivery was not rolled back");
            // The same evidence again changes nothing.
            long revision = restarted.data().records().revision();
            restarted.settleFromPlayerData(official.getUUID(), savedBefore, null, false);
            check(restarted.data().records().revision() == revision, "Settling twice changed the records");
            // A delivery whose marker reached the saved file is confirmed after the crash.
            official.getInventory().clearContent();
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 2));
            official.getInventory().add(new ItemStack(Items.LANTERN, 1));
            restarted.contribute(official, req(server), work);
            var savedAfter = PlayerEvidence.markers(official);
            var again = restart(restarted);
            again.settleFromPlayerData(official.getUUID(), savedAfter, null, false);
            check(again.data().records().operations().stream().filter(o -> o.stage() == OpStage.CONFIRMED).count() == 1, "A saved delivery was not confirmed after the crash");
            check(work(again, work).demand().stream().allMatch(r -> r.remaining() == 0), "A confirmed delivery did not count once");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    /** AUD-02: a claim takes exactly what fits; the rest stays owed (never dropped); items this server lacks stay owed. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aRefundIntoAFullInventoryKeepsTheRestOwed(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.PROJECTS);
        try {
            var official = operator(helper, "DevelopmentFull");
            setup(helper, official, "development-full");
            var data = new DevelopmentSavedData();
            var service = new DevelopmentService(server, data);
            var next = data.transaction();
            UUID work = UUID.randomUUID();
            next.refunds.add(new Refund(official.getUUID(), "minecraft:oak_fence", 3, work));
            next.refunds.add(new Refund(official.getUUID(), "missingmod:widget", 2, work));
            check(data.commit(server, next), "The test refunds were not written");
            var inventory = official.getInventory();
            for (int slot = 0; slot < inventory.items.size(); slot++) inventory.items.set(slot, new ItemStack(Items.STONE, 64));
            inventory.items.set(0, new ItemStack(Items.OAK_FENCE, 63));
            int entities = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, official.getBoundingBox().inflate(8)).size();
            service.claimRefunds(official, req(server));
            check(count(official, Items.OAK_FENCE) == 64, "The claim did not fill the room there was: " + count(official, Items.OAK_FENCE));
            check(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, official.getBoundingBox().inflate(8)).size() == entities, "A refund was dropped on the ground");
            playerSaved(service, official);
            var owed = data.records().refunds();
            check(owed.stream().filter(r -> r.item().equals("minecraft:oak_fence")).mapToInt(Refund::count).sum() == 2, "What did not fit is not owed: " + owed);
            check(owed.stream().filter(r -> r.item().equals("missingmod:widget")).mapToInt(Refund::count).sum() == 2, "An unknown item's refund was lost: " + owed);
            try { service.claimRefunds(official, req(server)); throw new GameTestAssertException("A claim into a full inventory was accepted"); }
            catch (IllegalArgumentException expected) { }
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    /** AUD-03: a placement the world refuses is not counted or announced as built. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aRefusedPlacementIsNotCounted(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentRefused");
        online(helper, true, official);
        var plot = helper.absolutePos(new BlockPos(4, 2, 4));
        boolean[] reverting = {false};
        Consumer<BlockEvent.NeighborNotifyEvent> reverter = event -> {
            if (!reverting[0] && event.getPos().equals(plot) && event.getLevel() instanceof ServerLevel level && level.getBlockState(plot).is(Blocks.OAK_FENCE)) {
                reverting[0] = true;
                level.setBlock(plot, Blocks.AIR.defaultBlockState(), 3);
                reverting[0] = false;
            }
        };
        MinecraftForge.EVENT_BUS.addListener(reverter);
        try {
            var settlement = setup(helper, official, "development-refused");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            service.maintain(); service.maintain(); service.maintain();
            var p = work(service, work);
            check(p.state() == State.REPAIR_NEEDED && p.blockers().get(0).cause() == Cause.REFUSED, "A refused placement was not reported: " + p.state() + " " + p.blockers());
            check(p.journal().stream().noneMatch(Placement::placed), "A refused placement counted as built: " + p.journal());
            check(service.data().records().economy(settlement.id()).points() == 0, "A refused placement earned points");
            helper.succeed();
        } finally { MinecraftForge.EVENT_BUS.unregister(reverter); DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    /** AUD-03: the journal write after a placement fails; the next pass recognises the announced block as the work's, once. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aFailedJournalWriteAfterPlacementCountsOnce(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentWrite");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-write");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            service.maintain(); service.maintain();
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            FaultPoints.failForTests("development.commit");
            try { service.maintain(); } finally { FaultPoints.failForTests(); }
            check(helper.getLevel().getBlockState(plot).is(Blocks.OAK_FENCE), "The failing pass placed nothing");
            check(work(service, work).journal().stream().allMatch(x -> x.stage() == Stage.INTENT), "A failed write changed the journal");
            service.maintain();
            var p = work(service, work);
            check(p.state() == State.BUILDING && p.journal().stream().allMatch(Placement::placed), "The announced blocks were not recognised: " + p.state() + " " + p.blockers() + " " + p.journal());
            check(p.blockers().isEmpty(), "The work's own block was taken for someone else's: " + p.blockers());
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    /** AUD-03/05: a block removed after it was placed stops the work at the next save; it is not placed again. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void aBlockRemovedAfterPlacementIsNotRebuilt(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentRemoved");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-removed");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            service.maintain(); service.maintain(); service.maintain();
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            check(helper.getLevel().getBlockState(plot.above(2)).is(Blocks.LANTERN), "The lamp post was not built");
            helper.setBlock(new BlockPos(4, 4, 4), Blocks.AIR.defaultBlockState());
            service.worldSaved(helper.getLevel());
            var p = work(service, work);
            check(p.state() == State.REPAIR_NEEDED && p.blockers().get(0).cause() == Cause.CHANGED, "A removed block did not stop the work: " + p.state() + " " + p.blockers());
            service.maintain();
            check(helper.getLevel().getBlockState(plot.above(2)).isAir(), "A removed block was built again without review");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "development_mode")
    public static void loweringTheModePausesAndReenablingDoesNotResume(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentLower");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-lower");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            service.maintain();
            check(work(service, work).state() == State.READY, "The funded work is not ready to build");
            DevelopmentMode.overrideForTests(DevelopmentMode.PROJECTS);
            service.maintain();
            var paused = work(service, work);
            check(paused.state() == State.PAUSED && paused.blockers().get(0).cause() == Cause.MODE, "Lowering the mode did not pause construction: " + paused.state() + " " + paused.blockers());
            check(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(4, 2, 4))).isAir(), "A block was placed after the mode was lowered");
            check(paused.construction(), "A paused construction work became a milestone");
            DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
            service.maintain();
            check(work(service, work).state() == State.PAUSED, "Re-enabling the mode resumed construction without review");
            // Construction resumes only where the mode allows it, and a lower mode never completes it as a milestone.
            DevelopmentMode.overrideForTests(DevelopmentMode.PROJECTS);
            try { service.resume(official, req(server), work); throw new GameTestAssertException("Construction resumed in a mode that does not build"); }
            catch (IllegalArgumentException expected) { }
            official.moveTo(helper.absolutePos(new BlockPos(6, 2, 6)).getX() + 0.5, helper.absolutePos(new BlockPos(6, 2, 6)).getY(), helper.absolutePos(new BlockPos(6, 2, 6)).getZ() + 0.5);
            DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
            UUID second = proposed(service, official, settlement);
            service.approve(official, req(server), second);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 2));
            official.getInventory().add(new ItemStack(Items.LANTERN, 1));
            service.contribute(official, req(server), second);
            playerSaved(service, official);
            DevelopmentMode.overrideForTests(DevelopmentMode.PROJECTS);
            for (int i = 0; i < 3; i++) service.maintain();
            check(work(service, second).state() == State.FUNDED, "A funded construction work changed in a mode that does not build: " + work(service, second).state());
            check(service.data().records().economy(settlement.id()).points() == 0, "A construction work was completed as a milestone in a lower mode");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    /** AUD-04: opting out, a protected home, a lost approver and a disallowed dimension each stop the next block; resume re-surveys. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "development_policy")
    public static void currentPolicyStopsTheNextBlock(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentPolicy");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-policy");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            service.maintain(); service.maintain(); // READY, then announced
            // 1. The settlement withdraws its consent: the announced blocks are not placed.
            service.optOut(official, req(server), settlement.id());
            service.maintain();
            check(work(service, work).state() == State.PAUSED && work(service, work).blockers().get(0).cause() == Cause.CONSENT, "Opting out did not stop construction: " + work(service, work).blockers());
            check(helper.getLevel().getBlockState(plot).isAir(), "A block was placed after the settlement opted out");
            // Resuming without consent is refused; with consent, the rest is surveyed again.
            try { service.resume(official, req(server), work); throw new GameTestAssertException("A work resumed without the settlement's consent"); }
            catch (IllegalArgumentException expected) { }
            service.optIn(official, req(server), settlement.id());
            // 2. Someone builds into a remaining cell meanwhile: resume stays paused and names it.
            helper.setBlock(new BlockPos(4, 3, 4), Blocks.GLASS.defaultBlockState());
            service.resume(official, req(server), work);
            var held = work(service, work);
            check(held.state() == State.PAUSED && held.blockers().stream().anyMatch(b -> b.cause() == Cause.CHANGED && helper.absolutePos(new BlockPos(4, 3, 4)).equals(b.pos())),
                    "Resume did not survey the rest of the plot: " + held.state() + " " + held.blockers());
            helper.setBlock(new BlockPos(4, 3, 4), Blocks.AIR.defaultBlockState());
            service.resume(official, req(server), work);
            check(work(service, work).state() == State.READY, "Resume did not continue a clear plot: " + work(service, work).state() + " " + work(service, work).blockers());
            // 3. A protected home now overlaps the plot.
            var next = service.data().transaction();
            UUID neighbour = UUID.randomUUID();
            next.homes.put(neighbour, new Home(neighbour, helper.getLevel().dimension().location().toString(), plot.offset(2, 0, 0), 3));
            check(service.data().commit(server, next), "The test home was not written");
            service.maintain(); service.maintain();
            check(work(service, work).state() == State.BLOCKED && work(service, work).blockers().get(0).cause() == Cause.HOME, "A protected home did not stop construction: " + work(service, work).blockers());
            check(helper.getLevel().getBlockState(plot).isAir(), "A block was placed in a protected home");
            next = service.data().transaction(); next.homes.remove(neighbour); service.data().commit(server, next);
            service.repair(official, req(server), work, DevelopmentService.Repair.RESURVEY);
            // 4. The approver is no longer an operator.
            online(helper, false, official);
            service.maintain();
            check(work(service, work).state() == State.PAUSED && work(service, work).blockers().get(0).cause() == Cause.AUTHORITY, "A lost approver did not stop construction: " + work(service, work).blockers());
            online(helper, true, official);
            service.resume(official, req(server), work);
            // 5. The dimension is no longer allowed.
            DevelopmentMode.overrideDimensionsForTests(List.of("minecraft:the_nether"));
            service.maintain();
            check(work(service, work).state() == State.PAUSED && work(service, work).blockers().get(0).cause() == Cause.DIMENSION, "A disallowed dimension did not stop construction: " + work(service, work).blockers());
            check(helper.getLevel().getBlockState(plot).isAir(), "A block was placed in a disallowed dimension");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); DevelopmentMode.overrideDimensionsForTests(null); online(helper, false, official); }
    }

    /** AUD-05: a block like the work's that a player placed in a planned cell is never adopted; a resurvey keeps it a blocker. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development_policy")
    public static void aPlayersBlockIsNeverAdopted(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentForeign");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-foreign");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = fundedAndAllowed(helper, service, official, settlement);
            // A player places a fence where the work's second fence goes, before construction announces it.
            helper.setBlock(new BlockPos(4, 3, 4), Blocks.OAK_FENCE.defaultBlockState());
            service.maintain(); service.maintain();
            var p = work(service, work);
            check(p.state() == State.REPAIR_NEEDED && p.blockers().get(0).cause() == Cause.FOREIGN_MATCH, "A player's matching block was adopted: " + p.state() + " " + p.blockers());
            service.repair(official, req(server), work, DevelopmentService.Repair.RESURVEY);
            p = work(service, work);
            check(p.state() != State.FUNDED && p.blockers().stream().anyMatch(b -> b.cause() == Cause.FOREIGN_MATCH), "A resurvey adopted the player's block: " + p.state() + " " + p.blockers());
            // A different state of the same block is a change too: a hanging lantern where a standing one goes.
            helper.setBlock(new BlockPos(4, 3, 4), Blocks.AIR.defaultBlockState());
            helper.setBlock(new BlockPos(4, 5, 4), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(4, 4, 4), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            service.repair(official, req(server), work, DevelopmentService.Repair.RESURVEY);
            p = work(service, work);
            check(p.blockers().stream().anyMatch(b -> b.cause() == Cause.CHANGED || b.cause() == Cause.SUPPORT), "A different state of the work's block was adopted: " + p.blockers());
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    /** AUD-08: with development off, an official still pauses, cancels and rolls back works and a player still claims refunds. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development_off")
    public static void recoveryStaysAvailableWhileDevelopmentIsOff(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        var official = operator(helper, "DevelopmentDisabled");
        online(helper, true, official);
        try {
            var settlement = setup(helper, official, "development-disabled");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID built = fundedAndAllowed(helper, service, official, settlement);
            service.maintain(); service.maintain(); service.maintain();
            check(work(service, built).journal().stream().anyMatch(Placement::placed), "Nothing was built before the feature was turned off");
            var spot = helper.absolutePos(new BlockPos(6, 2, 6));
            official.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            UUID open = proposed(service, official, settlement);
            service.approve(official, req(server), open);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
            service.contribute(official, req(server), open);
            playerSaved(service, official);
            DevelopmentMode.overrideForTests(DevelopmentMode.OFF);
            service.maintain();
            check(work(service, built).state() == State.PAUSED, "Turning development off left construction running: " + work(service, built).state());
            try { service.propose(official, req(server), settlement.id(), LAMP); throw new GameTestAssertException("New work was accepted while development is off"); }
            catch (IllegalArgumentException expected) { }
            service.cancel(official, req(server), open);
            check(work(service, open).state() == State.CANCELLED, "A work could not be cancelled while development is off");
            service.claimRefunds(official, req(server));
            playerSaved(service, official);
            check(service.data().records().refunds().isEmpty(), "A refund could not be claimed while development is off");
            service.repair(official, req(server), built, DevelopmentService.Repair.ROLLBACK);
            check(work(service, built).state() == State.ROLLING_BACK, "A work could not be rolled back while development is off: " + work(service, built).state());
            service.repair(official, req(server), built, DevelopmentService.Repair.FINALIZE);
            check(work(service, built).state() == State.CANCELLED, "A rollback could not be finalized while development is off");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); online(helper, false, official); }
    }

    /** AUD-07: a request older than the receipts the records keep is refused, never treated as new. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development")
    public static void anExpiredRequestIsRefused(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.PROJECTS);
        try {
            var official = operator(helper, "DevelopmentExpired");
            var settlement = setup(helper, official, "development-expired");
            var data = new DevelopmentSavedData();
            var service = new DevelopmentService(server, data);
            UUID old = DevelopmentService.request(server);
            UUID work = proposed(service, official, settlement);
            // Receipts beyond the bound are dropped oldest first; the watermark remembers the newest dropped issue time.
            var next = data.transaction();
            next.receiptWatermark = dev.otectus.mcakingdoms.persistence.RequestIds.issuedAt(old);
            check(data.commit(server, next), "The watermark was not written");
            try { service.approve(official, old, work); throw new GameTestAssertException("An expired request was applied"); }
            catch (IllegalArgumentException expected) { check(dev.otectus.mcakingdoms.interaction.Keyed.component(expected).getString().contains("older"), "The refusal did not explain the expiry: " + expected.getMessage()); }
            try { service.approve(official, UUID.randomUUID(), work); throw new GameTestAssertException("A request the server did not issue was applied"); }
            catch (IllegalArgumentException expected) { }
            check(work(service, work).state() == State.SURVEYED, "A refused request changed the work");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    /**
     * AUD-06: a merge carries every development record of the retired settlement once (works, assets, points, goods with
     * overflow) with conservative consent and pauses inherited construction; a kingdom change pauses construction too.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "development_merge")
    public static void aMergeCarriesDevelopmentOnce(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var level = helper.getLevel();
        var a = api.setKingdom(api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(2, 2, 2)).offset(0, 0, 63_000 + 30 * 48), 6,
                new ResourceLocation("mcakingdoms:development_test"), "merge-a" + helper.absolutePos(BlockPos.ZERO), "Merge Survivor " + helper.absolutePos(BlockPos.ZERO).getX())).id(),
                new ResourceLocation("mcakingdoms:madera"));
        var b = api.setKingdom(api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(2, 2, 2)).offset(0, 0, 63_000 + 30 * 48 + 100), 6,
                new ResourceLocation("mcakingdoms:development_test"), "merge-b" + helper.absolutePos(BlockPos.ZERO), "Merge Retired " + helper.absolutePos(BlockPos.ZERO).getX())).id(),
                new ResourceLocation("mcakingdoms:madera"));
        var data = DevelopmentSavedData.get(server);
        var service = DevelopmentService.get(server);
        UUID player = UUID.randomUUID();
        UUID assetA = UUID.randomUUID(), assetB = UUID.randomUUID(), inherited = UUID.randomUUID(), waiting = UUID.randomUUID();
        var next = data.transaction();
        next.assets.put(assetA, new Asset(assetA, a.id(), LAMP, "lighting", 3, Map.of(), 0));
        next.assets.put(assetB, new Asset(assetB, b.id(), LAMP, "lighting", 2, Map.of("goods", 1), 0));
        next.economies.put(a.id(), new Economy(a.id(), 3, Map.of("lighting", 1), true, Map.of("goods", 9_000L), 0));
        next.economies.put(b.id(), new Economy(b.id(), 2, Map.of("lighting", 1), false, Map.of("goods", 3_000L), 0));
        var origin = helper.absolutePos(new BlockPos(2, 2, 2)).offset(0, 0, 63_000 + 30 * 48 + 100);
        String dim = level.dimension().location().toString();
        next.projects.put(inherited, new Project(inherited, b.id(), "mcakingdoms:madera", LAMP, State.FUNDED, "", player, player, true, dim, origin,
                List.of(), List.of(), List.of(), null, 0, 0, next.revision, WorkKind.CONSTRUCTION, List.of(), null));
        next.projects.put(waiting, new Project(waiting, a.id(), "mcakingdoms:madera", LAMP, State.APPROVED, "", player, player, true, dim, origin.offset(0, 0, -20),
                List.of(), List.of(), List.of(), null, 0, 0, next.revision, WorkKind.CONSTRUCTION, List.of(), null));
        next.refunds.add(new Refund(player, "minecraft:oak_fence", 2, inherited));
        check(data.commit(server, next), "The merge fixture was not written");
        try {
            api.merge(b.id(), a.id());
            var r = data.records();
            check(r.assets().stream().filter(x -> x.settlement().equals(a.id())).count() == 2, "The retired settlement's completed work was not carried over: " + r.assets());
            var e = r.economy(a.id());
            check(e.points() == 5 && e.roles().getOrDefault("lighting", 0) == 2, "Points and roles were not added once: " + e);
            check(e.abstractGoods().getOrDefault("goods", 0L) == Development.ABSTRACT_CAP && e.overflow().getOrDefault("goods", 0L) == 2_000L, "Goods above the cap were lost: " + e);
            check(!e.optedIn(), "The merge opted the retired settlement's land into construction");
            check(!r.economies.containsKey(b.id()), "The retired settlement kept an economy record");
            var moved = r.project(inherited).orElseThrow();
            check(moved.settlement().equals(a.id()) && moved.state() == State.PAUSED && moved.blockers().get(0).cause() == Cause.INHERITED, "An inherited work was not paused for review: " + moved);
            check(r.refunds().size() >= 1 && r.refunds().stream().anyMatch(x -> x.player().equals(player)), "A refund was lost in the merge");
            long revision = r.revision();
            check(DevelopmentService.settlementMerged(server, b.id(), a.id(), "again"), "Repeating the merge consumer failed");
            check(data.records().revision() == revision, "Repeating the merge consumer changed the records");
            DevelopmentService.reconcile(server);
            check(data.records().economy(a.id()).points() == 5, "Reconciliation changed points carried by the merge: " + data.records().economy(a.id()));
            // A kingdom change without a merge: construction waits for the new kingdom's officials.
            api.setKingdom(a.id(), new ResourceLocation("mcakingdoms:lunari"));
            var paused = data.records().project(waiting).orElseThrow();
            check(paused.state() == State.PAUSED && paused.blockers().get(0).cause() == Cause.KINGDOM_CHANGED, "A kingdom change did not pause construction: " + paused);
            check(service.authority(paused).equals("mcakingdoms:lunari"), "Authority still follows the proposing kingdom");
            helper.succeed();
        } finally {
            var clean = data.transaction();
            clean.assets.remove(assetA); clean.assets.remove(assetB); clean.projects.remove(inherited); clean.projects.remove(waiting);
            clean.economies.remove(a.id()); clean.economies.remove(b.id()); clean.refunds.removeIf(x -> x.player().equals(player));
            data.commit(server, clean);
        }
    }
}
