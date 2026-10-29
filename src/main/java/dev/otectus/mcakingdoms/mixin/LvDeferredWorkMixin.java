package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mine digging and other deferred block work wait in an adopted village while construction there is off. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.sim.DeferredWork", remap = false)
public abstract class LvDeferredWorkMixin {
    @Inject(method = "settle", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldWork(ServerLevel level, @Coerce Object village, CallbackInfoReturnable<Boolean> callback) {
        if (LvHooks.holdConstruction(village)) callback.setReturnValue(false);
    }
}
