package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No template is stamped, no road graded and no plot flattened in an adopted village while construction there is off, and no
 * new building's spot is taken from another settlement, a protected home or someone's standing building.
 */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.StructurePlacer", remap = false)
public abstract class LvPlacementMixin {
    @Inject(method = "syncStructures(Lnet/minecraft/server/level/ServerLevel;Lcom/diego/recruitsvillages/data/FactionData;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldPlacement(ServerLevel level, @Coerce Object village, CallbackInfo callback) {
        if (LvHooks.holdConstruction(village)) callback.cancel();
    }

    @Inject(method = "syncStructures(Lnet/minecraft/server/level/ServerLevel;Lcom/diego/recruitsvillages/data/FactionData;Z)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldLivePlacement(ServerLevel level, @Coerce Object village, boolean live, CallbackInfo callback) {
        if (LvHooks.holdConstruction(village)) callback.cancel();
    }

    @Redirect(method = "searchSpot", at = @At(value = "INVOKE",
            target = "Lcom/diego/recruitsvillages/faction/RecruitsFactionBridge;spotClaimable(Lcom/diego/recruitsvillages/data/FactionData;IILnet/minecraft/core/Vec3i;)Z"),
            remap = false, require = 1)
    private static boolean mcakingdoms$lvGround(@Coerce Object village, int x, int z, Vec3i footprint) {
        return LvHooks.spotClaimable(village, x, z, footprint);
    }
}
