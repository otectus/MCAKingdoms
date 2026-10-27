package dev.otectus.mcakingdoms.evolution;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F07 through the real form protocol: a typed outcome decides the status, not the handler returning normally; Check
 * reads the operation's current state through its probe and never runs the handler again; repeated Apply replays the
 * retained result; a refused outcome reads as a refusal.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class OutcomeCheckGameTests {
    private static final UUID NONE = InteractionNetwork.NONE;
    private static final AtomicInteger RUNS = new AtomicInteger();
    private static final AtomicReference<ActionRegistry.Outcome.State> CURRENT = new AtomicReference<>(ActionRegistry.Outcome.State.PENDING);
    private static boolean registered;

    private static synchronized void register() {
        if (registered) return;
        registered = true;
        ActionRegistry.add(new ActionRegistry.Task("test.typed_outcome", "Testing", "Typed outcome test", "A test operation.", false, true, List.of(),
                c -> "fixed", c -> {
                    RUNS.incrementAndGet();
                    return ActionRegistry.Outcome.pending("Recorded and waiting for another step.", "op-1")
                            .then("evolution.overview", Map.of());
                }));
        ActionRegistry.probe("test.typed_outcome", (c, operation) -> operation.equals("op-1")
                ? Optional.of(ActionRegistry.Outcome.of(CURRENT.get(), "Read again: " + CURRENT.get(), operation)) : Optional.empty());
        ActionRegistry.add(new ActionRegistry.Task("test.typed_refusal", "Testing", "Typed refusal test", "A test operation.", false, true, List.of(),
                c -> "fixed", c -> ActionRegistry.Outcome.refused("The provider refused it.", "op-2")));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static InteractionNetwork.Reply request(FakePlayer player, UUID session, UUID state, String operation, String value) {
        return InteractionNetwork.handle(player, new InteractionNetwork.Request(UUID.randomUUID(), session, state, operation, value, "", 0));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void checkReadsTheOperationAgainWithoutRepeatingIt(GameTestHelper helper) {
        InteractionNetwork.init();
        register();
        RUNS.set(0);
        CURRENT.set(ActionRegistry.Outcome.State.PENDING);
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "OutcomeChecker"));
        var review = request(player, NONE, NONE, "TASK", "test.typed_outcome");
        check(review.mode().equals("review"), "expected a review page, got " + review.mode());
        var applied = request(player, review.session(), review.state(), "APPLY", "");
        check(applied.mode().equals("result") && applied.statusOrEmpty().equals("waiting"), "a pending outcome must report waiting, got " + applied.statusOrEmpty());
        check(RUNS.get() == 1, "the handler ran " + RUNS.get() + " times");
        check(applied.nextOrEmpty().contains("evolution.overview"), "the outcome's own next step was not offered: " + applied.nextOrEmpty());

        var again = request(player, review.session(), review.state(), "APPLY", "");
        check(again.statusOrEmpty().equals("waiting") && RUNS.get() == 1, "a repeated Apply ran the handler again");

        CURRENT.set(ActionRegistry.Outcome.State.APPLIED);
        var checked = request(player, review.session(), applied.state(), "CHECK", "");
        check(checked.statusOrEmpty().equals("done"), "Check did not read the new state: " + checked.statusOrEmpty());
        check(checked.detailText().contains("Read again: APPLIED"), "Check did not show the current reading: " + checked.detailText());
        check(RUNS.get() == 1, "Check ran the handler again");

        CURRENT.set(ActionRegistry.Outcome.State.UNKNOWN);
        var unknown = request(player, review.session(), checked.state(), "CHECK", "");
        check(unknown.statusOrEmpty().equals("unknown"), "an unconfirmable state must read unknown, got " + unknown.statusOrEmpty());

        var refusalReview = request(player, NONE, NONE, "TASK", "test.typed_refusal");
        var refused = request(player, refusalReview.session(), refusalReview.state(), "APPLY", "");
        check(refused.statusOrEmpty().equals("refused"), "a refused outcome returned normally must still read refused, got " + refused.statusOrEmpty());
        check(refused.detailText().contains("Could not complete"), "a refusal must read as one: " + refused.detailText());
        helper.succeed();
    }
}
