package dev.otectus.mcakingdoms.core;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration.Check;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration.Intent;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * A01: a player registers their own residence only from server-owned evidence (a bed-set respawn point in a loaded
 * chunk, inside a recognized settlement, while standing there), moves it subject to a cooldown that only operators may
 * override, and can then found a government without operator help. Runs in its own batch: placing beds generates
 * terrain, which must not slow the default batch's discovery tests.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class ResidenceRegistrationGameTests {
    private static final String BATCH = "residence";
    private static final UUID NONE = InteractionNetwork.NONE;

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    /** Far row shared by these tests, 96 blocks apart so bounds never overlap. */
    private static BlockPos spot(GameTestHelper helper, int index) {
        return helper.absolutePos(new BlockPos(76_000 + index * 96, 2, 76_000)).atY(64);
    }

    private static SettlementView settlement(GameTestHelper helper, KingdomsService api, BlockPos at, String name) {
        var level = helper.getLevel();
        return api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), at, 24,
                new ResourceLocation("mcakingdoms", "manual"), "residence-" + UUID.randomUUID(), name));
    }

    private static BlockPos bed(GameTestHelper helper, BlockPos foot) {
        var level = helper.getLevel();
        var head = foot.relative(Direction.NORTH);
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), 3);
        return head;
    }

    private static FakePlayer player(GameTestHelper helper, String name, boolean operator) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        if (helper.getLevel().getServer().getProfileCache() != null) helper.getLevel().getServer().getProfileCache().add(profile);
        return new FakePlayer(helper.getLevel(), profile) { @Override public boolean hasPermissions(int level) { return operator; } };
    }

    private static void stand(FakePlayer player, BlockPos at) {
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static void sleptIn(FakePlayer player, BlockPos bed) {
        player.setRespawnPosition(Level.OVERWORLD, bed, 0, false, false);
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = BATCH)
    public static void evidenceIsVerifiedByTheServer(GameTestHelper helper) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var home = settlement(helper, api, spot(helper, 0), "Bedford Reach");
        var player = player(helper, "BedTester", false);
        stand(player, home.anchor());

        check(ResidenceRegistration.evidence(player).check() == Check.NO_BED, "no bed must be refused");
        BlockPos bed = bed(helper, home.anchor().east(3));
        player.setRespawnPosition(Level.OVERWORLD, bed, 0, true, false);
        check(ResidenceRegistration.evidence(player).check() == Check.COMMAND_SPAWN, "a command-set spawn is not evidence");
        BlockPos empty = home.anchor().west(4);
        sleptIn(player, empty);
        check(ResidenceRegistration.evidence(player).check() == Check.BED_MISSING, "a respawn point without a bed is not evidence");
        BlockPos outside = bed(helper, home.anchor().east(60));
        sleptIn(player, outside);
        check(ResidenceRegistration.evidence(player).check() == Check.BED_OUTSIDE_SETTLEMENT, "a bed outside every settlement is not evidence");
        BlockPos unloaded = helper.absolutePos(new BlockPos(140_000, 64, 140_000));
        check(!helper.getLevel().hasChunkAt(unloaded), "the remote fixture chunk must be unloaded");
        sleptIn(player, unloaded);
        check(ResidenceRegistration.evidence(player).check() == Check.BED_UNLOADED, "an unloaded bed must be refused without loading it");
        check(!helper.getLevel().hasChunkAt(unloaded), "verification loaded a chunk");

        sleptIn(player, bed);
        stand(player, home.anchor().east(40));
        check(ResidenceRegistration.evidence(player).check() == Check.NOT_PRESENT, "registering from elsewhere must be refused");
        stand(player, home.anchor());
        var evidence = ResidenceRegistration.evidence(player);
        check(evidence.found() && evidence.settlement().orElseThrow().id().equals(home.id()), "valid evidence was not recognized: " + evidence.check());
        check(api.getCivicIdentity(player).isEmpty(), "verifying must not record anything");

        // Breaking the bed after a review invalidates it: the apply re-checks the evidence.
        String reviewed = ResidenceRegistration.fingerprint(player, Intent.REGISTER);
        helper.getLevel().removeBlock(bed, false);
        check(!ResidenceRegistration.fingerprint(player, Intent.REGISTER).equals(reviewed), "a broken bed must void the review");
        check(ResidenceRegistration.apply(player, Intent.REGISTER, false).decision() == Check.BED_MISSING, "stale evidence was accepted");
        check(api.getCivicIdentity(player).isEmpty(), "stale evidence recorded a residence");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = BATCH)
    public static void registerMoveCooldownAndOverride(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var first = settlement(helper, api, spot(helper, 2), "First Rest");
        var second = settlement(helper, api, spot(helper, 4), "Second Rest");
        var resident = player(helper, "MovingResident", false);
        BlockPos firstBed = bed(helper, first.anchor().east(2));
        sleptIn(resident, firstBed);
        stand(resident, first.anchor());

        check(ResidenceRegistration.review(resident, Intent.MOVE, false).decision() == Check.NOT_REGISTERED, "a move needs a residence");
        var registered = ResidenceRegistration.apply(resident, Intent.REGISTER, false);
        check(registered.decision() == Check.READY, "registration refused: " + registered.decision());
        var identity = api.getCivicIdentity(resident).orElseThrow();
        check(identity.residenceSettlement().orElseThrow().equals(first.id()), "residence not recorded");
        check(identity.originSettlement().orElseThrow().equals(first.id()), "a first registration sets the origin");
        check(identity.source() == CivicIdentitySource.REGISTERED, "source must be REGISTERED, was " + identity.source());
        check(ResidenceRegistration.apply(resident, Intent.REGISTER, false).decision() == Check.ALREADY_REGISTERED, "registering twice must be refused");
        long changed = identity.lastResidenceChange();
        check(ResidenceRegistration.apply(resident, Intent.MOVE, false).decision() == Check.SAME, "the same residence must be a no-op");
        check(api.getCivicIdentity(resident).orElseThrow().lastResidenceChange() == changed, "a no-op rewrote the record");

        BlockPos secondBed = bed(helper, second.anchor().east(2));
        sleptIn(resident, secondBed);
        stand(resident, second.anchor());
        var waiting = ResidenceRegistration.apply(resident, Intent.MOVE, true);
        check(waiting.decision() == Check.COOLDOWN && waiting.cooldownRemaining() > 0, "a non-operator bypassed the cooldown: " + waiting.decision());
        check(api.getResidence(resident).orElseThrow().id().equals(first.id()), "a refused move changed the residence");

        // A respawned player keeps the record and its cooldown (F01 with A01).
        var respawned = new FakePlayer(helper.getLevel(), resident.getGameProfile()) { @Override public boolean hasPermissions(int level) { return false; } };
        MinecraftForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, resident, true));
        sleptIn(respawned, secondBed);
        stand(respawned, second.anchor());
        check(ResidenceRegistration.apply(respawned, Intent.MOVE, false).decision() == Check.COOLDOWN, "respawning reset the cooldown");

        // The cooldown runs on the shared server clock: once it has passed, the move is allowed and keeps the origin.
        var later = ResidenceRegistration.class;
        try {
            var review = later.getDeclaredMethod("review", net.minecraft.server.level.ServerPlayer.class, Intent.class, boolean.class, java.util.function.LongSupplier.class, long.class);
            review.setAccessible(true);
            var after = (ResidenceRegistration.Review) review.invoke(null, respawned, Intent.MOVE, false, (java.util.function.LongSupplier) () -> changed + 24_000L, 24_000L);
            check(after.decision() == Check.READY, "the move must be allowed after the cooldown: " + after.decision());
        } catch (ReflectiveOperationException unavailable) {
            throw new GameTestAssertException("review seam unavailable: " + unavailable);
        }

        var operator = player(helper, "ResidenceOperator", true);
        BlockPos operatorBed = bed(helper, first.anchor().west(3));
        sleptIn(operator, operatorBed);
        stand(operator, first.anchor());
        check(ResidenceRegistration.apply(operator, Intent.REGISTER, false).decision() == Check.READY, "operator registration refused");
        sleptIn(operator, secondBed);
        stand(operator, second.anchor());
        check(ResidenceRegistration.apply(operator, Intent.MOVE, false).decision() == Check.COOLDOWN, "operators also wait without the override");
        var moved = ResidenceRegistration.apply(operator, Intent.MOVE, true);
        check(moved.decision() == Check.READY && moved.override(), "the operator override did not apply");
        var moverIdentity = api.getCivicIdentity(operator).orElseThrow();
        check(moverIdentity.residenceSettlement().orElseThrow().equals(second.id()), "the override move was not recorded");
        check(moverIdentity.originSettlement().orElseThrow().equals(first.id()), "a move must keep the origin");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = BATCH)
    public static void operatorAssignedAndMergedResidencesFollowTheRules(GameTestHelper helper) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var assigned = settlement(helper, api, spot(helper, 6), "Assigned Rest");
        var merged = settlement(helper, api, spot(helper, 8), "Merged Rest");
        var resident = player(helper, "AssignedResident", false);
        api.setResidence(resident, assigned.id());
        BlockPos bed = bed(helper, merged.anchor().east(2));
        sleptIn(resident, bed);
        stand(resident, merged.anchor());
        check(ResidenceRegistration.apply(resident, Intent.MOVE, false).decision() == Check.COOLDOWN,
                "an operator-assigned residence starts the same move cooldown");

        api.merge(assigned.id(), merged.id());
        var review = ResidenceRegistration.review(resident, Intent.MOVE, false);
        check(review.currentResidence().orElseThrow().id().equals(merged.id()), "the residence must follow the merge");
        check(review.decision() == Check.SAME, "the merged survivor is already the residence: " + review.decision());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = BATCH)
    public static void aFreshPlayerRegistersThenFoundsWithoutAnOperator(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var politics = KingdomsPoliticsApi.get(server);
        String kingdom = api.getKingdoms().stream().map(k -> k.id().toString()).sorted().filter(k -> politics.government(k).isEmpty()).findFirst().orElse(null);
        if (kingdom == null) { helper.succeed(); return; }
        var capital = settlement(helper, api, spot(helper, 10), "Founders Rest");
        if (!capital.kingdomId().toString().equals(kingdom)) capital = api.setKingdom(capital.id(), new ResourceLocation(kingdom));
        var founder = player(helper, "RegisteredFounder", false);
        var rival = player(helper, "RivalFounder", false);
        online(helper, true, founder, rival);
        try {
            for (var p : new FakePlayer[]{founder, rival}) {
                BlockPos bed = bed(helper, capital.anchor().east(p == founder ? 2 : 5));
                sleptIn(p, bed);
                stand(p, capital.anchor());
                var start = InteractionNetwork.handle(p, new InteractionNetwork.Request(UUID.randomUUID(), NONE, NONE, "TASK", "citizen.register_residence", "", 0));
                check(start.mode().equals("review"), "registration review expected, got " + start.mode() + ": " + start.detailText());
                check(start.detailText().contains("Founders Rest"), "the review must name the settlement: " + start.detailText());
                var done = InteractionNetwork.handle(p, new InteractionNetwork.Request(UUID.randomUUID(), start.session(), start.state(), "APPLY", "", "", 0));
                check(done.statusOrEmpty().equals("done"), "registration did not apply: " + done.detailText());
            }
            // Both found at once: one government results, the other review is refused rather than overwriting it.
            var founderReview = review(founder, kingdom);
            var rivalReview = review(rival, kingdom);
            var founded = apply(founder, founderReview);
            var refused = apply(rival, rivalReview);
            check(founded.statusOrEmpty().equals("done"), "the registered founder could not found: " + founded.detailText());
            check(!refused.statusOrEmpty().equals("done"), "a second simultaneous founding also succeeded");
            var government = politics.government(kingdom).orElseThrow(() -> new GameTestAssertException("no government"));
            check(government.capital().equals(capital.id()), "the capital is not the registered residence");
            check(government.offices().values().stream().anyMatch(o -> o.holder().id().equals(founder.getUUID())), "the founder does not lead");
        } finally {
            online(helper, false, founder, rival);
        }
        helper.succeed();
    }

    private static InteractionNetwork.Reply review(FakePlayer player, String kingdom) {
        var reply = InteractionNetwork.handle(player, new InteractionNetwork.Request(UUID.randomUUID(), NONE, NONE, "TASK", "politics.bootstrap", "", 0));
        for (int step = 0; step < 6 && reply.mode().equals("choice"); step++) {
            if (reply.options().isEmpty()) throw new GameTestAssertException("no choice offered: " + reply.detailText());
            var pick = reply.options().get(0);
            reply = InteractionNetwork.handle(player, new InteractionNetwork.Request(UUID.randomUUID(), reply.session(), reply.state(), "PICK", pick.key(), "", 0));
        }
        check(reply.mode().equals("review"), "founding review expected, got " + reply.mode() + ": " + reply.detailText());
        return reply;
    }

    private static InteractionNetwork.Reply apply(FakePlayer player, InteractionNetwork.Reply review) {
        return InteractionNetwork.handle(player, new InteractionNetwork.Request(UUID.randomUUID(), review.session(), review.state(), "APPLY", "", "", 0));
    }

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
}
