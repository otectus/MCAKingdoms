package dev.otectus.mcakingdoms.presentation;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.network.OverlayPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.stream.IntStream;

@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class PresentationGameTests {
    private static void check(boolean ok, String message) { if (!ok) throw new GameTestAssertException(message); }

    @GameTest(template = "empty")
    public static void entryOverlayMarksOnlyEachPlayersFirstVisit(GameTestHelper h) {
        var api = McaKingdomsApi.get(h.getLevel().getServer());
        BlockPos inside = h.absolutePos(new BlockPos(1, 2, 1));
        api.registerCandidate(h.getLevel(), SettlementCandidate.structure(h.getLevel().dimension(), inside, 4,
                new ResourceLocation("mcakingdoms", "test"), "first-visit" + inside, null));
        UUID settlement = api.getSettlementAt(h.getLevel(), inside).orElseThrow().id();
        // The test world can outlive a run, so pick a spot that no earlier settlement covers.
        BlockPos outside = IntStream.range(1, 64).mapToObj(step -> inside.offset(0, 0, step * 4096))
                .filter(pos -> api.getSettlementAt(h.getLevel(), pos).isEmpty()).findFirst().orElseThrow();
        FakePlayer alice = player(h, "Alice"), bob = player(h, "Bob");

        move(alice, inside);
        OverlayPacket first = poll(alice, "First entry");
        check(first.settlement().orElseThrow().id().equals(settlement) && first.firstVisit(), "First entry was not marked as a first visit");
        check(ServerPresentationEvents.poll(alice).isEmpty(), "Standing still resent the overlay");
        move(alice, outside);
        OverlayPacket left = poll(alice, "Leaving");
        check(left.settlement().isEmpty() && !left.firstVisit(), "Leaving did not clear the overlay");
        move(alice, inside);
        OverlayPacket again = poll(alice, "Re-entry");
        check(again.settlement().orElseThrow().id().equals(settlement) && !again.firstVisit(), "Re-entry was marked as a first visit");
        move(bob, inside);
        check(poll(bob, "Another player's entry").firstVisit(), "One player's visit hid another player's first entry");
        h.succeed();
    }

    private static OverlayPacket poll(FakePlayer player, String step) {
        return ServerPresentationEvents.poll(player).orElseThrow(() -> new GameTestAssertException(step + " sent no overlay"));
    }

    private static FakePlayer player(GameTestHelper h, String name) {
        return new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static void move(FakePlayer player, BlockPos pos) {
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
