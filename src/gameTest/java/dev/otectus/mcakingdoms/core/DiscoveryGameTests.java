package dev.otectus.mcakingdoms.core;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AUD-11: a settlement built after its chunks were loaded and scanned is recognized without reloading them. A village
 * bed, bell or workstation a player places schedules a rescan of its chunk; a player can also ask for their loaded
 * surroundings to be looked at now, at most once every 30 seconds. Neither loads a chunk.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class DiscoveryGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    private static KingdomsServiceImpl impl(GameTestHelper h) {
        if (McaKingdomsApi.get(h.getLevel().getServer()) instanceof KingdomsServiceImpl impl) return impl;
        throw new GameTestAssertException("The settlement service is not the built-in one");
    }

    /** Loads (and holds) the chunks around {@code center} that the cluster will occupy; returns them for release. */
    private static Set<ChunkPos> load(ServerLevel level, BlockPos center) {
        var chunks = new LinkedHashSet<ChunkPos>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) chunks.add(new ChunkPos(center.offset(dx * 16, 0, dz * 16)));
        chunks.forEach(chunk -> { level.getChunk(chunk.x, chunk.z); level.setChunkForced(chunk.x, chunk.z, true); });
        return chunks;
    }

    /** Four beds and a bell (five village points of interest, poiMinimumCount), built in place with no chunk load. */
    private static BlockPos build(ServerLevel level, BlockPos center) {
        level.setBlockAndUpdate(center.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(center, Blocks.BELL.defaultBlockState());
        for (int z = 1; z <= 4; z++) {
            var foot = center.offset(2, 0, z * 3); var head = foot.east();
            level.setBlockAndUpdate(foot.below(), Blocks.STONE.defaultBlockState()); level.setBlockAndUpdate(head.below(), Blocks.STONE.defaultBlockState());
            var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
            level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), 2);
            level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), 2);
        }
        return center;
    }

    /**
     * {@code pos} at the ground: the GameTest world has real terrain, and a test's position moves when tests are added,
     * so a cluster built at sea level can lie deep inside a hill, where the surface rule rightly refuses it.
     */
    private static BlockPos surface(ServerLevel level, BlockPos pos) {
        return pos.atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()));
    }

    private static void release(ServerLevel level, Set<ChunkPos> chunks) {
        chunks.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
    }

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "discovery")
    public static void aVillageBuiltInLoadedChunksIsRecognizedFromItsBlocks(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(1100, 2, 0));
        var chunks = load(level, origin);
        var center = surface(level, origin);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "DiscoveryBuilder"));
        boolean[] built = {false};
        // First the scans the chunk loads asked for run (and find nothing); only then is the village built.
        h.succeedWhen(() -> {
            if (!built[0]) {
                check(!impl(h).scanPending(level, center), "Waiting for the area's initial scans");
                build(level, center);
                check(impl(h).getSettlementAt(level, center).isEmpty(), "The village was recognized before it was built");
                check(!impl(h).scanPending(level, center), "A scan was queued without any block change");
                // A player places the bell: the same event the game posts for a real placement.
                MinecraftForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, center), level.getBlockState(center.below()), player));
                check(impl(h).scanPending(level, center), "Placing a village bell did not schedule a rescan of its chunk");
                built[0] = true;
            }
            var found = impl(h).getSettlementAt(level, center).orElseThrow(() -> new GameTestAssertException("The village built in loaded chunks is not recognized yet"));
            check(found.detectionSource() == DetectionSource.POI, "Expected the point-of-interest detector: " + found.detectionSource());
            release(level, chunks);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "discovery")
    public static void aPlayerCanAskForTheirSurroundingsToBeScanned(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(1400, 2, 0));
        var chunks = load(level, origin);
        var center = surface(level, origin);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "DiscoveryAsker"));
        player.moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        boolean[] asked = {false};
        h.succeedWhen(() -> {
            if (!asked[0]) {
                check(!impl(h).scanPending(level, center), "Waiting for the area's initial scans");
                build(level, center);
                var first = impl(h).requestScan(player);
                check(first.accepted() && first.queued() > 0, "The first request was not queued: " + first);
                var second = impl(h).requestScan(player);
                check(!second.accepted() && second.waitTicks() > 0, "A second request at once was not limited: " + second);
                asked[0] = true;
            }
            var found = impl(h).getSettlementAt(level, center).orElseThrow(() -> new GameTestAssertException("The requested scan has not recognized the village yet"));
            check(found.detectionSource() == DetectionSource.POI, "Expected the point-of-interest detector");
            release(level, chunks);
        });
    }
}
