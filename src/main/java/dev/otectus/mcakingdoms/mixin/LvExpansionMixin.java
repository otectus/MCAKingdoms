package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An adopted village schedules no new building while construction there is off: nothing is charged or marked built. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.sim.OfflineSimulation", remap = false)
public abstract class LvExpansionMixin {
    @Inject(method = "expandVillage", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldExpansion(@Coerce Object village, CallbackInfo callback) {
        if (LvHooks.holdConstruction(village)) callback.cancel();
    }
}
