package dev.otectus.mcakingdoms.evolution;

import dev.otectus.mcakingdoms.compat.recruits.RecruitsMobilization;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsTransfer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * F10: an untouched recruit-transfer offer that passes its deadline expires durably, stops blocking its recruit and
 * survives a restart as expired; a transfer that already began a native change keeps its recovery fence. Both players
 * are offline throughout, and world events stay disabled.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class TransferExpiryGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static RecruitTransferData.Transfer offer(long now, RecruitTransferData.Phase phase, RecruitsTransfer.Snapshot snapshot,
                                                      UUID unit, UUID owner, UUID recipient, UUID group) {
        long deadline = Math.max(2, now - 10);
        return new RecruitTransferData.Transfer(UUID.randomUUID(), unit, owner, recipient, group, "e".repeat(64), false,
                deadline - 1, deadline, 1, phase, snapshot, "test offer");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void overdueUntouchedOffersExpireAndStopBlocking(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        long now = server.overworld().getGameTime();
        var data = RecruitTransferData.get(server);
        if (!data.writable()) throw new GameTestAssertException("transfer store is read-only in the test world");

        UUID owner = UUID.randomUUID(), recipient = UUID.randomUUID(), group = UUID.randomUUID();
        UUID idleUnit = UUID.randomUUID(), consentedUnit = UUID.randomUUID(), applyingUnit = UUID.randomUUID();
        var idle = offer(now, RecruitTransferData.Phase.OFFERED, null, idleUnit, owner, recipient, group);
        var consented = offer(now, RecruitTransferData.Phase.CONSENTED, null, consentedUnit, owner, recipient, group);
        var orders = new RecruitsMobilization.Orders(applyingUnit, owner, UUID.randomUUID(), 0, true, false, false, false, false, false, false, false, false,
                0, 0, 0, 0, 0, 0, null);
        var snapshot = new RecruitsTransfer.Snapshot(applyingUnit, owner, recipient, orders.group(), group, "", "", "e".repeat(64), "{}", 1, 0, 0, orders);
        var applying = offer(now, RecruitTransferData.Phase.CONFIRMING, snapshot, applyingUnit, owner, recipient, group);
        check(data.put(server, idle) && data.put(server, consented) && data.put(server, applying), "fixtures could not be saved");
        check(RecruitTransferService.blocksMobilization(server, idleUnit), "an open offer blocks its recruit before expiry");

        var service = new RecruitTransferService(server);
        int expired = 0;
        for (int pass = 0; pass < 4; pass++) expired += service.expireOverdue();
        check(expired >= 2, "both untouched overdue offers should expire, got " + expired);
        check(data.get(idle.id()).orElseThrow().phase() == RecruitTransferData.Phase.EXPIRED, "the offered transfer did not expire");
        check(data.get(consented.id()).orElseThrow().phase() == RecruitTransferData.Phase.EXPIRED, "the consented transfer did not expire");
        check(data.get(idle.id()).orElseThrow().detail().contains("expired"), "history must explain the expiry, not claim delivery");
        check(!RecruitTransferService.blocksMobilization(server, idleUnit), "an expired offer still blocks its recruit");
        check(!RecruitTransferService.blocksMobilization(server, consentedUnit), "an expired consent still blocks its recruit");
        check(data.get(applying.id()).orElseThrow().phase() == RecruitTransferData.Phase.CONFIRMING, "a started native change lost its recovery fence");
        check(RecruitTransferService.blocksMobilization(server, applyingUnit), "a started native change must keep blocking its recruit");

        // A restart reads the expiry back as terminal, and a second pass changes nothing.
        var reloaded = RecruitTransferData.load(data.save(new CompoundTag()));
        check(reloaded.writable(), "the store with an expired offer did not reload");
        check(reloaded.get(idle.id()).orElseThrow().phase() == RecruitTransferData.Phase.EXPIRED, "expiry did not survive a reload");
        long revision = data.get(idle.id()).orElseThrow().revision();
        service.expireOverdue();
        check(data.get(idle.id()).orElseThrow().revision() == revision, "an expired offer was rewritten");
        helper.succeed();
    }
}
