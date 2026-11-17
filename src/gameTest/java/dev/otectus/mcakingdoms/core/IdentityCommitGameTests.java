package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.event.SettlementCreatedEvent;
import dev.otectus.mcakingdoms.api.event.SettlementMergedEvent;
import dev.otectus.mcakingdoms.persistence.FaultPoints;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/**
 * AUD-01: settlement identity changes have one root commit boundary. A new settlement is on disk before anything is told
 * about it; a merge whose root write fails changes nothing and announces nothing; a merge whose dependent store cannot be
 * written is committed, says so, keeps the store pending in the same atomic file, survives a restart and finishes on retry.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class IdentityCommitGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    /** A settlement in its own far lane, so no other test's records are near (the convention of the political GameTests). */
    private static SettlementView settlement(GameTestHelper helper, String key, int lane) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        BlockPos position = helper.absolutePos(new BlockPos(0, 2, 2)).offset(0, 0, 63_000 + lane * 48);
        var made = api.registerCandidate(helper.getLevel(), SettlementCandidate.manual(helper.getLevel().dimension(), position, 6,
                new ResourceLocation("mcakingdoms:identity_test"), key + position, "Identity " + key + " " + position.getX()));
        return api.setKingdom(made.id(), new ResourceLocation("mcakingdoms:madera"));
    }

    private static KingdomsServiceImpl impl(GameTestHelper helper) {
        if (McaKingdomsApi.get(helper.getLevel().getServer()) instanceof KingdomsServiceImpl impl) return impl;
        throw new GameTestAssertException("The settlement service is not the built-in one");
    }

    private static CompoundTag onDisk(GameTestHelper helper) {
        try {
            var file = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("mcakingdoms_settlements.dat").toFile();
            return NbtIo.readCompressed(file).getCompound("data");
        } catch (java.io.IOException unreadable) {
            throw new GameTestAssertException("The settlement file could not be read: " + unreadable);
        }
    }

    private static boolean savedId(CompoundTag data, UUID id) {
        for (Tag entry : data.getList("Settlements", Tag.TAG_COMPOUND)) if (((CompoundTag) entry).getUUID("Id").equals(id)) return true;
        return false;
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "identity_commit")
    public static void aNewSettlementIsOnDiskBeforeItIsAnnounced(GameTestHelper helper) {
        boolean[] seen = {false, false};
        Consumer<SettlementCreatedEvent> listener = event -> {
            seen[0] = true;
            seen[1] = savedId(onDisk(helper), event.settlement().id());
        };
        MinecraftForge.EVENT_BUS.addListener(listener);
        try {
            settlement(helper, "created", 50);
        } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        check(seen[0], "No creation event was posted");
        check(seen[1], "A new settlement was announced before it was on disk");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "identity_commit")
    public static void aFailedRootWriteRefusesTheMergeAndAnnouncesNothing(GameTestHelper helper) {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var a = settlement(helper, "root-a", 52);
        var b = settlement(helper, "root-b", 53);
        int[] merged = {0};
        Consumer<SettlementMergedEvent> listener = event -> merged[0]++;
        MinecraftForge.EVENT_BUS.addListener(listener);
        int pendingBefore = impl(helper).pendingIdentityOperations().size();
        try {
            FaultPoints.failForTests("settlements.root.write");
            try { api.merge(b.id(), a.id()); throw new GameTestAssertException("A merge whose root write failed was reported as done"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("Nothing changed"), "The refusal did not say nothing changed: " + expected.getMessage()); }
            finally { FaultPoints.failForTests(); }
            check(merged[0] == 0, "A merge that was not written was announced");
            check(api.resolveSettlementId(b.id()).equals(b.id()) && api.getSettlement(b.id()).isPresent(), "The refused merge left a redirect in memory");
            check(api.getSettlementAt(helper.getLevel(), b.anchor()).map(SettlementView::id).orElse(null) != null
                    && api.getSettlementAt(helper.getLevel(), b.anchor()).get().id().equals(b.id()), "The refused merge left the spatial index changed");
            check(impl(helper).pendingIdentityOperations().size() == pendingBefore, "The refused merge left an identity operation");
            check(savedId(onDisk(helper), b.id()), "The refused merge removed the settlement from disk");
            api.merge(b.id(), a.id());
            check(merged[0] == 1, "The retried merge was not announced once");
            check(api.resolveSettlementId(b.id()).equals(a.id()), "The retried merge did not redirect");
            check(!savedId(onDisk(helper), b.id()), "The merge was announced before it was on disk");
            helper.succeed();
        } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "identity_commit")
    public static void aStoreThatCannotFollowStaysPendingAcrossARestart(GameTestHelper helper) throws Exception {
        var api = McaKingdomsApi.get(helper.getLevel().getServer());
        var a = settlement(helper, "pending-a", 54);
        var b = settlement(helper, "pending-b", 55);
        IdentityOperation operation;
        FaultPoints.failForTests("identity.consumer.civic");
        try {
            api.merge(b.id(), a.id());
        } finally { FaultPoints.failForTests(); }
        check(api.resolveSettlementId(b.id()).equals(a.id()), "The merge was not committed when only a dependent store failed");
        operation = impl(helper).pendingIdentityOperations().stream().filter(op -> op.source().equals(b.id())).findFirst()
                .orElseThrow(() -> new GameTestAssertException("The failing store was not kept pending"));
        check(operation.pending().equals(Set.of("civic")), "Only the failing store should wait: " + operation.pending());
        check(operation.errors().containsKey("civic"), "The pending store has no reason for operators");
        // A restart: the settlement file holds the redirect and the operation together.
        var disk = onDisk(helper);
        Method load = SettlementSavedData.class.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        var reloaded = (SettlementSavedData) load.invoke(null, disk);
        check(!reloaded.refused(), "A file holding a pending operation was refused");
        check(reloaded.resolveId(b.id()).equals(a.id()), "The reloaded file lost the redirect");
        check(reloaded.identityOperation(operation.id()).map(op -> op.pending().contains("civic")).orElse(false), "The reloaded file lost the pending store");
        // The retry finishes it once; a second retry finds nothing to do.
        check(impl(helper).runIdentityOperation(operation.id()), "The retry did not finish the operation");
        check(impl(helper).pendingIdentityOperations().stream().noneMatch(op -> op.id().equals(operation.id())), "A finished operation stayed pending");
        check(impl(helper).runIdentityOperation(operation.id()), "A finished operation could not be asked again");
        helper.succeed();
    }
}
