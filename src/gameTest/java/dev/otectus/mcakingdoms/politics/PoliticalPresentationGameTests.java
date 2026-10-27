package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.network.PoliticalNoticePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * A02: committed political moments become notices and badges, and nothing else does. A founding, an appointment and a
 * confirmed NPC death each produce one notice in neutral words; a private mandate produces none; a local office reaches
 * only players who know its settlement; badges follow office changes; the holder's own data is untouched.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class PoliticalPresentationGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static FakePlayer player(GameTestHelper helper, String name, boolean operator) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        if (helper.getLevel().getServer().getProfileCache() != null) helper.getLevel().getServer().getProfileCache().add(profile);
        return new FakePlayer(helper.getLevel(), profile) { @Override public boolean hasPermissions(int level) { return operator; } };
    }

    @SuppressWarnings("unchecked")
    private static void online(GameTestHelper helper, boolean listed, FakePlayer... players) {
        try {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            var byId = (Map<UUID, net.minecraft.server.level.ServerPlayer>) field.get(helper.getLevel().getServer().getPlayerList());
            // Notices go to the server's list of online players, so these stand-ins join it too (this batch runs alone).
            var listField = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            listField.setAccessible(true);
            var list = (java.util.List<net.minecraft.server.level.ServerPlayer>) listField.get(helper.getLevel().getServer().getPlayerList());
            for (var player : players) {
                if (listed) { byId.put(player.getUUID(), player); if (!list.contains(player)) list.add(player); }
                else { byId.remove(player.getUUID()); list.remove(player); }
            }
        } catch (ReflectiveOperationException unavailable) {
            throw new GameTestAssertException("The test server's player map is unavailable: " + unavailable);
        }
    }

    private static String text(PoliticalNoticePacket packet) {
        return InteractionNetwork.component(packet.text()).getString();
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = "presentation")
    public static void committedMomentsBecomeNoticesAndBadges(GameTestHelper helper) {
        InteractionNetwork.init();
        var server = helper.getLevel().getServer();
        var api = McaKingdomsApi.get(server);
        var politics = KingdomsPoliticsApi.get(server);
        var presentation = OfficePresentation.get(server).orElseThrow(() -> new GameTestAssertException("presentation not running"));
        String kingdom = api.getKingdoms().stream().map(k -> k.id().toString()).sorted().filter(k -> politics.government(k).isEmpty()).findFirst().orElse(null);
        if (kingdom == null) { helper.succeed(); return; }
        var level = helper.getLevel();
        var capital = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), helper.absolutePos(new BlockPos(78_000, 2, 78_000)), 24,
                new ResourceLocation("mcakingdoms", "manual"), "presentation-" + UUID.randomUUID(), "Herald Rest"));
        if (!capital.kingdomId().toString().equals(kingdom)) capital = api.setKingdom(capital.id(), new ResourceLocation(kingdom));
        var leader = player(helper, "NoticeLeader", true);
        var listener = player(helper, "NoticeListener", false);
        var knower = player(helper, "NoticeKnower", false);
        SettlementKnowledge.get(server).discover(knower.getUUID(), capital.id());
        api.setResidence(leader, capital.id()); // a local office is held by a resident of its settlement
        online(helper, true, leader, listener, knower);
        try {
            int before = presentation.recent().size();
            var founded = politics.execute(leader, new Request(UUID.randomUUID(), politics.revision(), Action.BOOTSTRAP, kingdom, kingdom + "_charter",
                    capital.id().toString(), new Person(leader.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0));
            check(founded.success(), "founding failed: " + founded.message());
            check(presentation.recent().size() == before + 1, "a founding must produce exactly one notice");
            String foundedText = text(presentation.recent().get(presentation.recent().size() - 1));
            check(foundedText.contains("NoticeLeader") && foundedText.contains("has a government"), "unexpected founding notice: " + foundedText);
            // The constitution keeps its own name (it may be a "Crown"); the notice itself crowns no one.
            check(!foundedText.toLowerCase(java.util.Locale.ROOT).contains("crowned") && !foundedText.toLowerCase(java.util.Locale.ROOT).contains("coronation"),
                    "a founding notice must not stage a coronation: " + foundedText);
            check(presentation.pending(listener.getUUID()) == 1, "an online player was not told of the founding");
            check(presentation.roles(leader.getUUID()).stream().anyMatch(r -> r.leader()), "the founder has no leader badge");

            var villager = EntityType.VILLAGER.create(level);
            // Spawned where this test's chunk is loaded: residence is a record, and appointment needs a loaded, living NPC.
            var spawn = helper.absolutePos(new BlockPos(1, 2, 1));
            villager.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            level.addFreshEntity(villager);
            api.setResidence(villager, capital.id());
            CompoundTag untouched = villager.saveWithoutId(new CompoundTag());
            var appointed = politics.execute(leader, new Request(UUID.randomUUID(), politics.revision(), Action.APPOINT, kingdom, "mcakingdoms:chancellor", "",
                    new Person(villager.getUUID(), Kind.NPC), "", "", "", 0, 0, 0));
            check(appointed.success(), "appointment failed: " + appointed.message());
            check(text(presentation.recent().get(presentation.recent().size() - 1)).contains("was appointed"), "no appointment notice");
            check(!presentation.roles(villager.getUUID()).isEmpty(), "the appointed NPC has no badge");
            check(untouched.equals(villager.saveWithoutId(new CompoundTag())), "presentation changed the NPC's own data");

            int afterAppointment = presentation.recent().size();
            var mandate = politics.execute(leader, new Request(UUID.randomUUID(), politics.revision(), Action.DELEGATE, kingdom, "PROPOSE", "",
                    new Person(listener.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0));
            check(mandate.success(), "mandate failed: " + mandate.message());
            check(presentation.recent().size() == afterAppointment, "a private mandate produced a public notice");

            var local = politics.execute(leader, new Request(UUID.randomUUID(), politics.revision(), Action.APPOINT, kingdom, "mcakingdoms:local_steward",
                    capital.id().toString(), new Person(leader.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0));
            check(local.success(), "local appointment failed: " + local.message());
            check(presentation.recent().size() == afterAppointment + 1, "the local appointment produced no notice");
            int listenerQueue = presentation.pending(listener.getUUID()), knowerQueue = presentation.pending(knower.getUUID());
            check(knowerQueue >= listenerQueue + 1, "a local office notice must reach only players who know the settlement ("
                    + knowerQueue + " vs " + listenerQueue + ")");

            // A confirmed death: the memorial names the official and the badge goes away.
            int beforeDeath = presentation.recent().size();
            villager.kill();
            helper.succeedWhen(() -> {
                if (presentation.recent().size() <= beforeDeath) throw new GameTestAssertException("memorial not yet announced");
                var memorial = presentation.recent().get(presentation.recent().size() - 1);
                check(memorial.memorial() && text(memorial).contains("In memory of"), "unexpected memorial: " + text(memorial));
                check(presentation.roles(villager.getUUID()).isEmpty(), "a dead official kept a badge");
                online(helper, false, leader, listener, knower);
            });
        } catch (RuntimeException failure) {
            online(helper, false, leader, listener, knower);
            throw failure;
        }
    }
}
