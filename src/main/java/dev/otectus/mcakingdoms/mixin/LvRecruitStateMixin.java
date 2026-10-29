package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A soldier stood down into Living Villages' model keeps its name, health, effects and mod data, not only its pack. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.RecruitMaterializer", remap = false)
public abstract class LvRecruitStateMixin {
    @Inject(method = "saveState", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvSaveState(@Coerce Object recruit, CallbackInfoReturnable<CompoundTag> callback) {
        LvHooks.augmentState(recruit, callback.getReturnValue());
    }

    @Inject(method = "restoreState", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvRestoreState(@Coerce Object recruit, CompoundTag tag, CallbackInfo callback) {
        LvHooks.restoreState(recruit, tag);
    }
}
