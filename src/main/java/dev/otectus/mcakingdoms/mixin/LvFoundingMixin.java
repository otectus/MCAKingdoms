package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Living Villages does not found a natural village on another settlement's ground or a player's protected home. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.sim.NaturalGeneration", remap = false)
public abstract class LvFoundingMixin {
    @Inject(method = "isSuitable", at = @At("RETURN"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvFounding(ServerLevel level, @Coerce Object data, BlockPos center, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && LvHooks.refuseFounding(level, center)) callback.setReturnValue(false);
    }
}
