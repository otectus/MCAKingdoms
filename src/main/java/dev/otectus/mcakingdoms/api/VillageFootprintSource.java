package dev.otectus.mcakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Tells MCA: Kingdoms where generated villages are, so that every detector's candidate inside one village resolves to
 * the same settlement. The built-in source reads the structure references of loaded chunks; structure mods and tests
 * add their own through {@link KingdomsService#registerVillageFootprintSource}.
 */
@FunctionalInterface
public interface VillageFootprintSource {
    /**
     * The villages whose box, grown by {@code margin} blocks on every side, contains {@code pos}. Called on the
     * server thread while chunks are scanned, so it must not load chunks and should be cheap.
     */
    List<VillageFootprint> footprintsNear(ServerLevel level, BlockPos pos, int margin);
}
