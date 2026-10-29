package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The civilian and worker removals of Living Villages 1.1.0 (surplus trimming, stray adoption, body balancing, /rvcleanup,
 * duplicate and stray workers, worker removal on dematerialize). Inside an integrated village the person is kept; elsewhere
 * the removal happens as before. The targets name {@code discard()} by its SRG name, so this mixin applies only in a
 * production runtime (RecruitsMixinPlugin); soldier removals are not touched.
 */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.MaterializationManager", remap = false)
public abstract class LvResidentGuardMixin {
    private static final String MOB_DISCARD = "Lnet/minecraft/world/entity/Mob;m_146870_()V";

    @Redirect(method = "trimSurplusVillagers", at = @At(value = "INVOKE", target = MOB_DISCARD), remap = false, require = 1)
    private static void mcakingdoms$lvGuardTrim(Mob mob) {
        LvHooks.guard(mob, "trim");
    }

    @Redirect(method = "adoptStrays", at = @At(value = "INVOKE", target = MOB_DISCARD), remap = false, require = 1)
    private static void mcakingdoms$lvGuardStray(Mob mob) {
        LvHooks.guard(mob, "stray");
    }

    @Redirect(method = "syncCivilianBodies", at = @At(value = "INVOKE", target = MOB_DISCARD), remap = false, require = 1)
    private static void mcakingdoms$lvGuardBodies(Mob mob) {
        LvHooks.guard(mob, "balance");
    }

    @Redirect(method = "cleanup", at = @At(value = "INVOKE", target = MOB_DISCARD), remap = false, require = 2)
    private static void mcakingdoms$lvGuardCleanup(Mob mob) {
        LvHooks.guard(mob, "cleanup");
    }

    @Redirect(method = {"syncWorkers", "lambda$syncWorkers$3"}, at = @At(value = "INVOKE", target = MOB_DISCARD), remap = false, require = 2)
    private static void mcakingdoms$lvGuardWorkers(Mob mob) {
        LvHooks.guard(mob, "workers");
    }

    @Redirect(method = "dematerialize", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_146870_()V"), remap = false, require = 1)
    private static void mcakingdoms$lvGuardDematerialize(Entity entity) {
        LvHooks.guard(entity, "dematerialize");
    }
}
