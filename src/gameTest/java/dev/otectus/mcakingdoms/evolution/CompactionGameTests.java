package dev.otectus.mcakingdoms.evolution;

import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.UUID;

/**
 * F12 end to end: finished transfers are written to an archive file, then removed from the live store in one save;
 * unfinished and reconciled ones stay, and a recruit with an unfinished transfer stays blocked.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class CompactionGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void finishedTransfersAreArchivedThenRemoved(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var data = RecruitTransferData.get(server);
        long now = server.overworld().getGameTime();
        long deadline = Math.max(2, now);
        UUID owner = UUID.randomUUID(), recipient = UUID.randomUUID(), group = UUID.randomUUID();
        var finished = new RecruitTransferData.Transfer(UUID.randomUUID(), UUID.randomUUID(), owner, recipient, group, "d".repeat(64), false,
                deadline - 1, deadline, 1, RecruitTransferData.Phase.CANCELLED, null, "finished fixture");
        var open = new RecruitTransferData.Transfer(UUID.randomUUID(), UUID.randomUUID(), owner, recipient, group, "d".repeat(64), false,
                deadline + 1_000_000, deadline + 2_000_000, 1, RecruitTransferData.Phase.OFFERED, null, "open fixture");
        check(data.put(server, finished) && data.put(server, open), "fixtures could not be saved");

        // A synthetic clock past the retention period; the server's own clock is untouched.
        long later = deadline + RecruitTransferData.ARCHIVE_AFTER;
        var result = data.compact(server, later).orElseThrow(() -> new GameTestAssertException("nothing was archived"));
        var archive = JsonParser.parseString(Files.readString(result.getKey())).getAsJsonObject();
        check(archive.get("store").getAsString().equals(RecruitTransferData.NAME), "archive names the wrong store");
        boolean listed = false;
        for (var record : archive.getAsJsonArray("records")) listed |= record.getAsJsonObject().get("id").getAsString().equals(finished.id().toString());
        check(listed, "the finished transfer is missing from its archive");
        check(data.get(finished.id()).isEmpty(), "the archived transfer is still in the store");
        check(data.get(open.id()).isPresent(), "an unfinished transfer was archived");
        check(RecruitTransferService.blocksMobilization(server, open.unit()), "the open offer must still block its recruit");
        var reloaded = RecruitTransferData.load(data.save(new net.minecraft.nbt.CompoundTag()));
        check(reloaded.writable() && reloaded.get(finished.id()).isEmpty(), "the removal did not survive a reload");
        helper.succeed();
    }
}
