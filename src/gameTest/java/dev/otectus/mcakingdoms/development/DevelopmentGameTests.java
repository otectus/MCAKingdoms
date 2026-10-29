package dev.otectus.mcakingdoms.development;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.State;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/**
 * D7 GameTests over the shipped templates and real blocks: the default mode changes nothing; a funded lamp post is built
 * once, block by block, and a restart neither repeats nor loses it; a survey refuses a plot beside a bed; a block changed
 * after the survey stops construction, and a rollback removes only what the work placed; a cancelled work refunds its
 * deliveries once; lowering the mode pauses construction.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class DevelopmentGameTests {
    private static final ResourceLocation LAMP = new ResourceLocation("mcakingdoms", "lamp_post");

    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    private static FakePlayer operator(GameTestHelper helper, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name)) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
    }

    /** A settlement around the plot, on a stone floor, with the official standing on the plot. */
    private static SettlementView setup(GameTestHelper helper, FakePlayer official, String key) {
        for (int x = 2; x <= 6; x++) for (int z = 2; z <= 6; z++) {
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
        service.propose(official, UUID.randomUUID(), settlement.id(), LAMP);
        return service.data().records().projects().stream().filter(p -> p.settlement().equals(settlement.id())).findFirst().orElseThrow().id();
    }

    private static int count(FakePlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) total += stack.getCount();
        return total;
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theDefaultModeChangesNothing(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        check(DevelopmentMode.current() == DevelopmentMode.OFF, "Development must be off by default");
        var official = operator(helper, "DevelopmentOff");
        var settlement = setup(helper, official, "development-off");
        var service = new DevelopmentService(server, new DevelopmentSavedData());
        try { service.propose(official, UUID.randomUUID(), settlement.id(), LAMP); throw new GameTestAssertException("A work was proposed while development is off"); }
        catch (IllegalArgumentException expected) { }
        service.maintain();
        check(service.data().records().projects().isEmpty() && service.data().records().revision() == 0, "Development wrote records while off");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "development")
    public static void aFundedLampPostIsBuiltOnceAcrossARestart(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        try {
            var official = operator(helper, "DevelopmentBuilder");
            var settlement = setup(helper, official, "development-build");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            var surveyed = service.data().records().project(work).orElseThrow();
            check(surveyed.state() == State.SURVEYED && surveyed.journal().size() == 3, "The plot was not surveyed clear: " + surveyed.state() + " " + surveyed.reasonKey());
            service.approve(official, UUID.randomUUID(), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 5));
            official.getInventory().add(new ItemStack(Items.LANTERN, 2));
            var named = new ItemStack(Items.LANTERN);
            named.setHoverName(net.minecraft.network.chat.Component.literal("Heirloom"));
            official.getInventory().add(named);
            UUID delivery = UUID.randomUUID();
            service.contribute(official, delivery, work);
            check(count(official, Items.OAK_FENCE) == 3 && count(official, Items.LANTERN) == 2,
                    "The delivery took the wrong items: fences " + count(official, Items.OAK_FENCE) + ", lanterns " + count(official, Items.LANTERN));
            service.contribute(official, delivery, work);
            check(count(official, Items.OAK_FENCE) == 3 && count(official, Items.LANTERN) == 2, "A repeated request took items twice");
            check(service.data().records().project(work).orElseThrow().state() == State.FUNDED, "The work was not funded");
            service.optIn(official, UUID.randomUUID(), settlement.id(), true);
            official.moveTo(official.getX() + 20, official.getY(), official.getZ());
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            // As if a crash came after the first block was placed and before the journal was saved: it must count once, not twice.
            helper.setBlock(new BlockPos(4, 2, 4), Blocks.OAK_FENCE.defaultBlockState());
            service.maintain(); // FUNDED -> READY
            service.maintain(); // READY -> BUILDING, blocks placed
            check(helper.getLevel().getBlockState(plot).is(Blocks.OAK_FENCE), "Construction placed nothing: "
                    + service.data().records().project(work).orElseThrow().state() + " " + service.data().records().project(work).orElseThrow().reasonKey());
            // A restart: the records reload from their saved form; construction continues without repeating a block.
            var reloaded = DevelopmentSavedData.load(service.data().save(new CompoundTag()));
            var restarted = new DevelopmentService(server, reloaded);
            for (int i = 0; i < 4 && restarted.data().records().project(work).orElseThrow().state() != State.COMPLETED; i++) restarted.maintain();
            var done = restarted.data().records().project(work).orElseThrow();
            check(done.state() == State.COMPLETED, "The work did not complete: " + done.state() + " " + done.reasonKey());
            check(helper.getLevel().getBlockState(plot.above()).is(Blocks.OAK_FENCE) && helper.getLevel().getBlockState(plot.above(2)).is(Blocks.LANTERN), "The lamp post is incomplete: "
                    + helper.getLevel().getBlockState(plot) + " / " + helper.getLevel().getBlockState(plot.above()) + " / " + helper.getLevel().getBlockState(plot.above(2)) + "; journal " + done.journal());
            check(done.journal().stream().allMatch(DevelopmentSavedData.Placement::placed), "The journal lost a block");
            check(restarted.data().records().economy(settlement.id()).points() == 1, "Completion did not count once");
            restarted.maintain();
            check(restarted.data().records().economy(settlement.id()).points() == 1, "Completion counted twice");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
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
            var p = service.data().records().project(work).orElseThrow();
            boolean refused = p.reasonKey().contains("blocker.bed") || p.reasonKey().contains("blocker.block_entity") || p.reasonKey().contains("blocker.poi");
            check(p.state() == State.BLOCKED && refused, "A plot beside a bed was not refused: " + p.state() + " " + p.reasonKey());
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "development")
    public static void aChangedBlockStopsConstructionAndRollbackRemovesOnlyOurs(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        try {
            var official = operator(helper, "DevelopmentRepair");
            var settlement = setup(helper, official, "development-repair");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            service.approve(official, UUID.randomUUID(), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 2));
            official.getInventory().add(new ItemStack(Items.LANTERN, 1));
            service.contribute(official, UUID.randomUUID(), work);
            service.optIn(official, UUID.randomUUID(), settlement.id(), true);
            official.moveTo(official.getX() + 20, official.getY(), official.getZ());
            var plot = helper.absolutePos(new BlockPos(4, 2, 4));
            // Someone builds on the top of the plot after the survey.
            helper.setBlock(new BlockPos(4, 4, 4), Blocks.GLASS.defaultBlockState());
            for (int i = 0; i < 4; i++) service.maintain();
            var stopped = service.data().records().project(work).orElseThrow();
            check(stopped.state() == State.REPAIR_NEEDED, "A changed block did not stop construction: " + stopped.state() + " " + stopped.reasonKey());
            check(helper.getLevel().getBlockState(plot.above(2)).is(Blocks.GLASS), "Construction replaced a player's block");
            service.repair(official, UUID.randomUUID(), work, true);
            check(helper.getLevel().getBlockState(plot).isAir() && helper.getLevel().getBlockState(plot.above()).isAir(), "Rollback left the work's blocks");
            check(helper.getLevel().getBlockState(plot.above(2)).is(Blocks.GLASS), "Rollback removed a block the work did not place");
            check(service.data().records().project(work).orElseThrow().state() == State.CANCELLED, "A rolled-back work stayed open");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
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
            service.approve(official, UUID.randomUUID(), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
            service.contribute(official, UUID.randomUUID(), work);
            check(count(official, Items.OAK_FENCE) == 0, "The delivery was not taken");
            service.cancel(official, UUID.randomUUID(), work);
            UUID claim = UUID.randomUUID();
            service.claimRefunds(official, claim);
            check(count(official, Items.OAK_FENCE) == 1, "The refund did not return the delivery");
            service.claimRefunds(official, claim);
            check(count(official, Items.OAK_FENCE) == 1, "A repeated claim refunded twice");
            try { service.claimRefunds(official, UUID.randomUUID()); throw new GameTestAssertException("A second claim found refunds"); }
            catch (IllegalArgumentException expected) { }
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "development_mode")
    public static void loweringTheModePausesConstruction(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        DevelopmentMode.overrideForTests(DevelopmentMode.MANAGED_GROWTH);
        try {
            var official = operator(helper, "DevelopmentLower");
            var settlement = setup(helper, official, "development-lower");
            var service = new DevelopmentService(server, new DevelopmentSavedData());
            UUID work = proposed(service, official, settlement);
            service.approve(official, UUID.randomUUID(), work);
            official.getInventory().add(new ItemStack(Items.OAK_FENCE, 2));
            official.getInventory().add(new ItemStack(Items.LANTERN, 1));
            service.contribute(official, UUID.randomUUID(), work);
            service.optIn(official, UUID.randomUUID(), settlement.id(), true);
            service.maintain();
            check(service.data().records().project(work).orElseThrow().state() == State.READY, "The funded work is not ready to build");
            DevelopmentMode.overrideForTests(DevelopmentMode.PROJECTS);
            service.maintain();
            var paused = service.data().records().project(work).orElseThrow();
            check(paused.state() == State.PAUSED && paused.reasonKey().endsWith("mode_lowered"), "Lowering the mode did not pause construction: " + paused.state());
            check(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(4, 2, 4))).isAir(), "A block was placed after the mode was lowered");
            helper.succeed();
        } finally { DevelopmentMode.overrideForTests(null); }
    }
}
