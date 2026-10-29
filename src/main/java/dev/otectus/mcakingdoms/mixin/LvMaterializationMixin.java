package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Worker and population paths of an integrated village: promotion in place, no protected person employed or converted
 * automatically, and the body target never below the people already there (so the provider never asks to delete one).
 */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.materialize.MaterializationManager", remap = false)
public abstract class LvMaterializationMixin {
    @Inject(method = "promoteIfNeeded", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvPromote(ServerLevel level, @Coerce Object village, @Coerce Object slot, Villager worker,
                                             VillagerProfession profession, boolean live, List<Mob> atPost, CallbackInfo callback) {
        if (LvHooks.promoteInPlace(level, village, slot, worker, profession, live)) callback.cancel();
    }

    @Inject(method = "takeJobless", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvJobless(@Coerce Object census, CallbackInfoReturnable<Mob> callback) {
        var choice = LvHooks.employable(census);
        if (choice != null) callback.setReturnValue(choice.mob());
    }

    @Inject(method = "consumeJobless", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvConsume(@Coerce Object village, @Coerce Object census, CallbackInfoReturnable<Vec3> callback) {
        if (LvHooks.refuseConversion(village)) callback.setReturnValue(null);
    }

    @Redirect(method = "syncCivilianBodies", at = @At(value = "INVOKE",
            target = "Lcom/diego/recruitsvillages/sim/Population;joblessLive(Lcom/diego/recruitsvillages/data/FactionData;)I"), remap = false, require = 1)
    private static int mcakingdoms$lvKeepBodies(@Coerce Object village) {
        return LvHooks.bodyTarget(village, LvHooks.joblessLive(village));
    }
}
