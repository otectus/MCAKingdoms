package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The animals of an adopted village are not removed as strays or restocked while construction there is off. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.FoodVisuals", remap = false)
public abstract class LvLivestockMixin {
    @Inject(method = "clearStrayLivestock", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldClear(ServerLevel level, @Coerce Object village, CallbackInfoReturnable<Integer> callback) {
        if (LvHooks.holdConstruction(village)) callback.setReturnValue(0);
    }

    @Inject(method = "stockPaddocks", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldStock(ServerLevel level, @Coerce Object village, CallbackInfo callback) {
        if (LvHooks.holdConstruction(village)) callback.cancel();
    }
}
