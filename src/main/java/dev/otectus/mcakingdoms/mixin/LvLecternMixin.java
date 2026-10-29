package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No lectern is placed into an adopted village's existing hall while construction there is off. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.TownHallLectern", remap = false)
public abstract class LvLecternMixin {
    @Inject(method = "ensureBook", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvHoldLectern(ServerLevel level, @Coerce Object village, CallbackInfo callback) {
        if (LvHooks.holdConstruction(village)) callback.cancel();
    }
}
