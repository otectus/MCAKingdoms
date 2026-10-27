package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * F15: a political review depends on the government it names, the record it selects and the actor's authority there.
 * A decision in an unrelated government leaves it valid; a decision in the same government voids it.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class ReviewScopeGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
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

    private static FakePlayer player(GameTestHelper helper, String name, boolean operator) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        if (helper.getLevel().getServer().getProfileCache() != null) helper.getLevel().getServer().getProfileCache().add(profile);
        return new FakePlayer(helper.getLevel(), profile) { @Override public boolean hasPermissions(int level) { return operator; } };
    }

    private static SettlementView settlement(GameTestHelper helper, KingdomsService api, int offset, String kingdom, String name) {
        var level = helper.getLevel();
        // The same far row as the player-identity tests, so few new chunks are generated.
        var s = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(72_000 + offset, 2, 72_000)), 32,
                new ResourceLocation("mcakingdoms", "manual"), "scope-" + UUID.randomUUID(), name));
        return s.kingdomId().toString().equals(kingdom) ? s : api.setKingdom(s.id(), new ResourceLocation(kingdom));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unrelatedGovernmentsDoNotVoidAReview(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var politics = KingdomsPoliticsApi.get(server);
        // Two kingdoms nobody else governs, from the end of the list so other tests' choices are untouched.
        var free = api.getKingdoms().stream().map(k -> k.id().toString()).sorted(java.util.Comparator.reverseOrder())
                .filter(k -> politics.government(k).isEmpty()).limit(2).toList();
        if (free.size() < 2) { helper.succeed(); return; }
        String first = free.get(0), second = free.get(1);
        var operator = player(helper, "ScopeOperator", true);
        var aide = player(helper, "ScopeAide", false);
        online(helper, true, operator, aide);
        try {
            var a = settlement(helper, api, 400, first, "Scope Seat One");
            var b = settlement(helper, api, 480, second, "Scope Seat Two");
            String firstCharter = first + "_charter", secondCharter = second + "_charter";
            check(politics.execute(operator, new Request(UUID.randomUUID(), politics.revision(), Action.BOOTSTRAP, first, firstCharter, a.id().toString(),
                    new Person(operator.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0)).success(), "first founding failed");
            check(politics.execute(operator, new Request(UUID.randomUUID(), politics.revision(), Action.BOOTSTRAP, second, secondCharter, b.id().toString(),
                    new Person(operator.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0)).success(), "second founding failed");

            var appoint = ActionRegistry.find("politics.delegate").orElseThrow();
            var review = new ActionRegistry.Context(operator, Map.of("kingdom", first, "person", aide.getUUID().toString(), "definition", "propose"), Map.of());
            String reviewed = appoint.version().apply(review);

            // A decision in the other government: this review is still current.
            var elsewhere = politics.execute(operator, new Request(UUID.randomUUID(), politics.revision(), Action.DELEGATE, second, "PROPOSE", "",
                    new Person(aide.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0));
            check(elsewhere.success(), elsewhere.message());
            check(appoint.version().apply(review).equals(reviewed), "an unrelated government's decision voided the review");

            // A decision in the reviewed government: the review is stale and must be rebuilt.
            var here = politics.execute(operator, new Request(UUID.randomUUID(), politics.revision(), Action.DELEGATE, first, "RATIFY", "",
                    new Person(aide.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0));
            check(here.success(), here.message());
            check(!appoint.version().apply(review).equals(reviewed), "a decision in the reviewed government left the review current");
        } finally {
            online(helper, false, operator, aide);
        }
        helper.succeed();
    }
}
