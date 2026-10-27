package dev.otectus.mcakingdoms.core;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.event.ResidentJoinedEvent;
import dev.otectus.mcakingdoms.api.event.ResidentLeftEvent;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * F01: respawning and returning from the End replace the player entity. The civic identity must survive both through
 * the real {@link PlayerEvent.Clone} handler, exactly once, with no join or leave event and no fabricated residence.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class PlayerIdentityGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static SettlementView settlement(GameTestHelper helper, KingdomsService api, int offset, String name) {
        var level = helper.getLevel();
        // One shared row far from other tests (80 blocks apart, bounds never overlap), so few new chunks are generated.
        return api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(72_000 + offset, 2, 72_000)), 32,
                new ResourceLocation("mcakingdoms", "manual"), "identity-" + UUID.randomUUID(), name));
    }

    private static FakePlayer player(GameTestHelper helper, UUID id, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, name));
    }

    /** Fires the event Forge fires at the end of ServerPlayer.restoreFrom, after copying only PlayerPersisted. */
    private static FakePlayer clone(GameTestHelper helper, FakePlayer original, boolean death) {
        var replacement = player(helper, original.getUUID(), original.getGameProfile().getName());
        if (original.getPersistentData().contains(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG))
            replacement.getPersistentData().put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG,
                    original.getPersistentData().get(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG).copy());
        MinecraftForge.EVENT_BUS.post(new PlayerEvent.Clone(replacement, original, death));
        return replacement;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void civicIdentitySurvivesDeathAndEndReturnOnce(GameTestHelper helper) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var home = settlement(helper, api, 0, "Respawn Reach");
        var first = player(helper, UUID.randomUUID(), "CloneResident");
        api.setResidence(first, home.id());
        var before = api.getCivicIdentity(first).orElseThrow();

        AtomicInteger joins = new AtomicInteger(), leaves = new AtomicInteger();
        Consumer<ResidentJoinedEvent> joined = event -> { if (event.entityId().equals(first.getUUID())) joins.incrementAndGet(); };
        Consumer<ResidentLeftEvent> left = event -> { if (event.entityId().equals(first.getUUID())) leaves.incrementAndGet(); };
        MinecraftForge.EVENT_BUS.addListener(joined);
        MinecraftForge.EVENT_BUS.addListener(left);
        try {
            var afterDeath = clone(helper, first, true);
            var afterEnd = clone(helper, afterDeath, false);
            var survived = api.getCivicIdentity(afterEnd).orElseThrow(() -> new GameTestAssertException("The identity was lost on clone"));
            check(survived.residenceSettlement().equals(before.residenceSettlement()), "Residence changed across the clone");
            check(survived.originSettlement().equals(before.originSettlement()), "Origin changed across the clone");
            check(survived.lastResidenceChange() == before.lastResidenceChange(), "The residence-change time was rewritten");
            check(survived.source() == before.source(), "The identity source changed");
            check(joins.get() == 0 && leaves.get() == 0, "A clone fired join/leave events: " + joins + "/" + leaves);
            check(afterEnd.getPersistentData().getAllKeys().stream().filter(k -> k.contains("civic_identity")).count() == 1,
                    "More than one identity compound: " + afterEnd.getPersistentData().getAllKeys());

            // A player with no identity gets none.
            var stranger = player(helper, UUID.randomUUID(), "CloneStranger");
            check(api.getCivicIdentity(clone(helper, stranger, true)).isEmpty(), "A residence was fabricated on clone");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(joined);
            MinecraftForge.EVENT_BUS.unregister(left);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cloneFollowsMergesAndKeepsOriginAcrossReassignment(GameTestHelper helper) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var retired = settlement(helper, api, 80, "Merged Reach");
        var survivor = settlement(helper, api, 160, "Surviving Reach");
        var resident = player(helper, UUID.randomUUID(), "CloneMerge");
        api.setResidence(resident, retired.id());
        ResourceLocation originKingdom = api.getCivicIdentity(resident).orElseThrow().originKingdom().orElseThrow();
        api.merge(retired.id(), survivor.id());
        var afterDeath = clone(helper, resident, true);
        check(api.getResidence(afterDeath).map(SettlementView::id).orElseThrow().equals(survivor.id()),
                "The residence did not resolve to the merge survivor after the clone");

        String other = api.getKingdoms().stream().map(k -> k.id().toString()).filter(k -> !k.equals(survivor.kingdomId().toString())).findFirst().orElse(null);
        if (other != null) {
            api.setKingdom(survivor.id(), new ResourceLocation(other));
            var afterEnd = clone(helper, afterDeath, false);
            var identity = api.getCivicIdentity(afterEnd).orElseThrow();
            check(identity.originKingdom().orElseThrow().equals(originKingdom), "Origin kingdom was rewritten by a reassignment");
            check(identity.residenceKingdom().orElseThrow().toString().equals(other), "Residence kingdom does not follow the settlement");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void preRenameIdentityIsCarriedUnderTheCurrentKey(GameTestHelper helper) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var home = settlement(helper, api, 240, "Legacy Reach");
        var resident = player(helper, UUID.randomUUID(), "CloneLegacy");
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("ResidenceSettlement", home.id());
        legacy.putUUID("OriginSettlement", home.id());
        legacy.putString("OriginKingdom", home.kingdomId().toString().replace("mcakingdoms:", "ultima_kingdoms:"));
        legacy.putString("Source", "COMMAND");
        legacy.putLong("LastResidenceChange", 5L);
        resident.getPersistentData().put("ultima_kingdoms:civic_identity", legacy);
        var replacement = clone(helper, resident, true);
        check(!replacement.getPersistentData().contains("ultima_kingdoms:civic_identity"), "The old key was carried");
        var identity = api.getCivicIdentity(replacement).orElseThrow(() -> new GameTestAssertException("The legacy identity was lost"));
        check(identity.residenceSettlement().orElseThrow().equals(home.id()), "Legacy residence lost");
        check(identity.originKingdom().orElseThrow().equals(home.kingdomId()), "Legacy origin kingdom not rewritten: " + identity.originKingdom());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aRespawnedResidentKeepsFoundingEligibility(GameTestHelper helper) {
        InteractionNetwork.init();
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var home = settlement(helper, api, 320, "Eligible Reach");
        var resident = player(helper, UUID.randomUUID(), "CloneFounder");
        api.setResidence(resident, home.id());
        var respawned = clone(helper, resident, true);
        var status = InteractionNetwork.handle(respawned, new InteractionNetwork.Request(UUID.randomUUID(), InteractionNetwork.NONE,
                InteractionNetwork.NONE, "TASK", "politics.founding_status", "", 0));
        check(status.mode().equals("result"), "Founding status did not run: " + status.detailText());
        check(!status.detailText().contains("You have no recorded civic residence"), "Respawn lost the residence: " + status.detailText());
        check(status.detailText().contains("Eligible Reach"), "The status does not name the residence: " + status.detailText());
        helper.succeed();
    }
}
