package dev.otectus.mcakingdoms.interaction;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * AUD-14: the player's feature summary reads the same capability facts as the tasks, so it never promises an action the
 * task refuses. The GameTest server has neither MCA Quests nor MCA: Crime: paid workshop jobs must be refused by the task
 * and described as off, with the task's own reason, in the summary; each newer system's line agrees with its check.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class CapabilityGameTests {
    private static void check(boolean ok, String message) { if (!ok) throw new GameTestAssertException(message); }

    @GameTest(template = "empty")
    public static void theSummaryPromisesNothingItsTasksRefuse(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var viewer = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "CapabilityReader"));
        var workshop = TaskMeta.availability(ActionRegistry.find("civic.workshop").orElseThrow(), server);
        check(!workshop.available(), "Paid workshop jobs were available without MCA Quests and MCA: Crime");
        var lines = ServerFacts.asComponents(ServerFacts.forPlayer(viewer)).stream().map(c -> c.getString()).toList();
        String on = "Paid workshop commissions: on, at recognized workshops.";
        check(lines.stream().noneMatch(on::equals), "The summary promised paid workshop commissions the task refuses: " + lines);
        String off = "Paid workshop commissions: " + workshop.reason().getString();
        check(lines.contains(off), "The summary did not give the task's own reason (" + off + "): " + lines);
        for (var feature : new TaskMeta.Feature[] {TaskMeta.Feature.DYNASTY, TaskMeta.Feature.HERALDRY, TaskMeta.Feature.WAYSTONES}) {
            var answer = TaskMeta.safeCheck(feature, server);
            if (!answer.available()) check(lines.contains(answer.reason().getString()), feature + " is not available, but the summary does not say why: " + lines);
        }
        h.succeed();
    }
}
