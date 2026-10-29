package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Living Villages' periodic relation step is arbitrated: an agreement in force between two realms cannot be overturned by it. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.sim.Relations", remap = false)
public abstract class LvRelationsMixin {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvArbitrate(ServerLevel level, @Coerce Object a, @Coerce Object b, @Coerce Object status, String what,
                                               CallbackInfo callback) {
        if (LvHooks.suppressRelation(level, a, b, status)) callback.cancel();
    }
}
