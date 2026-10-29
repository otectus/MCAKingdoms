package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** An integrated village keeps its people in the world when players leave, and gains MCA persons through MCA's factory. */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.CivilianMaterializer", remap = false)
public abstract class LvCivilianMixin {
    @Inject(method = "capture", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvCapture(ServerLevel level, @Coerce Object village, List<?> villagers, CallbackInfo callback) {
        if (LvHooks.keepBodies(level, village)) callback.cancel();
    }

    @Inject(method = "spawnAt", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvSpawn(ServerLevel level, Vec3 pos, CallbackInfoReturnable<Mob> callback) {
        Mob person = LvHooks.spawnResident(level, pos);
        if (person != null) callback.setReturnValue(person);
    }
}
