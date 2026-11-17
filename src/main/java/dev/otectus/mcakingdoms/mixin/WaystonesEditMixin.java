package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.waystones.WaystonesIntegration;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes a player's accepted waystone edit (WAY-01). The tail of Waystones' own handler is reached only after its
 * permission and distance checks passed and the new name was stored and sent, so a refused edit is never seen here.
 * Applied only to the tested Waystones 14.1.21 jar ({@link WaystonesGate}).
 */
@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.network.message.EditWaystoneMessage", remap = false)
public abstract class WaystonesEditMixin {
    @Inject(method = "handle", at = @At("TAIL"), remap = false, require = 1)
    private static void mcakingdoms$waystoneEdited(ServerPlayer player, @Coerce Object message, CallbackInfo callback) {
        WaystonesIntegration.manualEdit(player, message);
    }
}
