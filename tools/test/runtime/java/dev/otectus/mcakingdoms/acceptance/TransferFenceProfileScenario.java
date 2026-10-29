package dev.otectus.mcakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsTransfer;
import dev.otectus.mcakingdoms.compat.recruits.TransferFenceMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraftforge.common.util.FakePlayer;

import java.util.*;
import java.util.function.Consumer;

import static dev.otectus.mcakingdoms.acceptance.IntegrationScenario.*;

/**
 * H07 measurement on a packaged server with Recruits 1.15.2: the transfer save fence for 1, 5, 20 and 100 hired
 * recruits on a server holding ambient loaded entities, against the broad flush it replaced (every loaded entity
 * section plus every dirty overworld file), timed in the same world. Every fence must still confirm the recruit's
 * saved owner, group, counts and team; the measured costs are printed for the release evidence.
 */
final class TransferFenceProfileScenario {
    private TransferFenceProfileScenario() { }

    @SuppressWarnings({"unchecked"})
    static void run(MinecraftServer server, Consumer<String> finish) throws Exception {
        ServerLevel level = server.overworld();
        var one = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "FenceOne"));
        var two = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "FenceTwo"));
        one.setPos(8, 64, 8); two.setPos(9, 64, 8);
        Map<UUID, ServerPlayer> online = null;
        for (var field : net.minecraft.server.players.PlayerList.class.getDeclaredFields())
            if (field.getGenericType().getTypeName().contains("java.util.UUID") && field.getGenericType().getTypeName().contains("ServerPlayer")) {
                field.setAccessible(true); online = (Map<UUID, ServerPlayer>) field.get(server.getPlayerList()); break;
            }
        check(online != null, "fence fixture locates online registry");
        for (var p : List.of(one, two)) { online.put(p.getUUID(), p); server.getProfileCache().add(p.getGameProfile()); }
        Object factions = Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
        call(factions, "addTeam", "fence_a", "fence_a", one.getUUID(), "FenceOne", new CompoundTag(), (byte) 1, net.minecraft.ChatFormatting.BLUE);
        call(factions, "addTeam", "fence_b", "fence_b", two.getUUID(), "FenceTwo", new CompoundTag(), (byte) 1, net.minecraft.ChatFormatting.RED);
        for (var p : List.of(one, two)) {
            String name = p == one ? "fence_a" : "fence_b"; var team = server.getScoreboard().getPlayerTeam(name);
            if (team == null) team = server.getScoreboard().addPlayerTeam(name);
            server.getScoreboard().addPlayerToTeam(p.getScoreboardName(), team);
        }
        Object groups = Class.forName("com.talhanation.recruits.RecruitEvents").getField("recruitsGroupsManager").get(null);
        Object firstGroup = ((List<?>) call(groups, "getPlayerGroups", one)).get(0);
        UUID secondGroupId = (UUID) call(((List<?>) call(groups, "getPlayerGroups", two)).get(0), "getUUID");

        // Ambient load: 400 persistent entities spread over the loaded spawn chunks, which the old fence saved every time.
        for (int i = 0; i < 400; i++) {
            var stand = EntityType.ARMOR_STAND.create(level);
            stand.setPos(-80 + (i % 20) * 8, 64, -80 + (i / 20) * 8); level.addFreshEntity(stand);
        }
        var manager = (PersistentEntitySectionManager<?>) Arrays.stream(ServerLevel.class.getDeclaredFields())
                .filter(f -> f.getType() == PersistentEntitySectionManager.class).findFirst().map(f -> { try { f.setAccessible(true); return f.get(level); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }).orElseThrow();

        var lines = new ArrayList<String>();
        var units = new ArrayList<Mob>();
        for (int size : List.of(1, 5, 20, 100)) {
            while (units.size() < size) {
                var unit = (Mob) net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits:recruit")).create(level);
                unit.setPos(4 + units.size() % 10, 64, 4 + units.size() / 10); unit.setNoAi(true); unit.setNoGravity(true); unit.setPersistenceRequired();
                level.addFreshEntity(unit);
                check((boolean) call(unit, "hire", one, firstGroup, false), "fixture hires recruit " + units.size());
                units.add(unit);
            }
            long[] broad = new long[3];
            for (int i = 0; i < broad.length; i++) {
                long start = System.nanoTime(); manager.saveAll(); level.getDataStorage().save(); broad[i] = System.nanoTime() - start;
            }
            TransferFenceMetrics.reset();
            for (var unit : units) {
                var snapshot = RecruitsTransfer.snapshot(unit, one, two, secondGroupId);
                check(RecruitsTransfer.saveAndConfirm(unit, snapshot, false), "targeted fence confirms the saved state of recruit " + unit.getUUID());
            }
            var fence = TransferFenceMetrics.snapshot();
            Arrays.sort(broad);
            String line = String.format(Locale.ROOT, "FENCE units=%d loaded_entities=%d broad_ms(min/median/max)=%.1f/%.1f/%.1f targeted_ms(p50/p95/p99/max)=%.1f/%.1f/%.1f/%.1f checks=%d last_chunk_entities=%d queued=%d",
                    size, count(level.getAllEntities()), broad[0] / 1e6, broad[1] / 1e6, broad[2] / 1e6,
                    fence.p50Nanos() / 1e6, fence.p95Nanos() / 1e6, fence.p99Nanos() / 1e6, fence.maxNanos() / 1e6, fence.count(), fence.lastEntities(), fence.lastQueued());
            System.out.println(line); lines.add(line);
            check(fence.count() == size, "every recruit was checked once");
        }
        java.nio.file.Files.writeString(server.getServerDirectory().toPath().resolve("fence-profile.txt"), String.join("\n", lines) + "\n");
        finish.accept("PASS integration transfer fence profile (" + lines.size() + " sizes)");
    }

    private static int count(Iterable<?> all) { int n = 0; for (Object ignored : all) n++; return n; }
}
