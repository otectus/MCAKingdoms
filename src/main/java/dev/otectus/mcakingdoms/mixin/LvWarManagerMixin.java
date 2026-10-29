package dev.otectus.mcakingdoms.mixin;

import dev.otectus.mcakingdoms.compat.livingvillages.LvHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * Every Living Villages 1.1.0 path that starts, moves or ends a war passes the bridge's policy and record: declaration (AI,
 * player and console), AI target selection, the battle start, abstract sieges and their casualty steps, direct abstract
 * resolution, a live war leaving view, occupation on either path, and repels. Applied only to the audited jar (LvGate).
 */
@Pseudo
@Mixin(targets = "com.diego.recruitsvillages.war.WarManager", remap = false)
public abstract class LvWarManagerMixin {
    private static final String DECLARE = "declareWar(Lnet/minecraft/server/level/ServerLevel;Lcom/diego/recruitsvillages/data/FactionData;"
            + "Lcom/diego/recruitsvillages/data/FactionData;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Set;Ljava/util/Set;)Z";

    @Inject(method = DECLARE, at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvDeclare(ServerLevel level, @Coerce Object attacker, @Coerce Object defender, ServerPlayer initiator,
                                             Set<?> groups, Set<?> support, CallbackInfoReturnable<Boolean> callback) {
        if (LvHooks.refuseDeclare(level, attacker, defender, initiator)) callback.setReturnValue(false);
    }

    @Inject(method = DECLARE, at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvDeclared(ServerLevel level, @Coerce Object attacker, @Coerce Object defender, ServerPlayer initiator,
                                              Set<?> groups, Set<?> support, CallbackInfoReturnable<Boolean> callback) {
        LvHooks.declared(level, attacker, defender, callback.getReturnValueZ());
    }

    @Inject(method = "considerAiWars", at = @At("HEAD"), remap = false, require = 1)
    private static void mcakingdoms$lvAiPassStart(ServerLevel level, CallbackInfo callback) {
        LvHooks.aiPass(true);
    }

    @Inject(method = "considerAiWars", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvAiPassEnd(ServerLevel level, CallbackInfo callback) {
        LvHooks.aiPass(false);
    }

    @Redirect(method = "considerAiWars", at = @At(value = "INVOKE",
            target = "Lcom/diego/recruitsvillages/war/WarManager;canWageWar(Lcom/diego/recruitsvillages/data/FactionData;)Z"), remap = false, require = 1)
    private static boolean mcakingdoms$lvAttacker(@Coerce Object attacker) {
        return LvHooks.canWageWar(attacker);
    }

    /** The third isPlayerFaction() in considerAiWars is the defender exclusion; a refused target is skipped like the native ones. */
    @Redirect(method = "considerAiWars", at = @At(value = "INVOKE", target = "Lcom/diego/recruitsvillages/data/FactionData;isPlayerFaction()Z",
            ordinal = 2), remap = false, require = 1)
    private static boolean mcakingdoms$lvTarget(@Coerce Object defender, ServerLevel level) {
        return LvHooks.excludedTarget(level, defender);
    }

    @Inject(method = "startBattle", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvBattle(ServerLevel level, @Coerce Object war, CallbackInfo callback) {
        if (LvHooks.refuseBattle(level, war)) callback.cancel();
    }

    @Inject(method = "startBattle", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvBattleStarted(ServerLevel level, @Coerce Object war, CallbackInfo callback) {
        LvHooks.battleStarted(level, war);
    }

    @Inject(method = "startAbstractSiege", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvAbstractStart(ServerLevel level, @Coerce Object attacker, @Coerce Object defender, CallbackInfo callback) {
        if (LvHooks.refuseAbstract(level, attacker, defender)) callback.cancel();
    }

    @Inject(method = "startAbstractSiege", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvAbstractStarted(ServerLevel level, @Coerce Object attacker, @Coerce Object defender, CallbackInfo callback) {
        LvHooks.abstractStarted(level, attacker, defender);
    }

    @Inject(method = "tickAbstractSieges", at = @At("HEAD"), remap = false, require = 1)
    private static void mcakingdoms$lvAbstractTick(ServerLevel level, CallbackInfo callback) {
        LvHooks.abstractTick(level, true);
    }

    @Inject(method = "tickAbstractSieges", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvAbstractTicked(ServerLevel level, CallbackInfo callback) {
        LvHooks.abstractTick(level, false);
    }

    @Inject(method = "resolveAbstract", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvResolve(ServerLevel level, @Coerce Object attacker, @Coerce Object defender, CallbackInfo callback) {
        if (LvHooks.refuseResolve(level, attacker, defender)) callback.cancel();
    }

    @Inject(method = "resolveAbstract", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvResolved(ServerLevel level, @Coerce Object attacker, @Coerce Object defender, CallbackInfo callback) {
        LvHooks.resolved(level, attacker, defender);
    }

    @Inject(method = "settleDematerializedSiege", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mcakingdoms$lvSettle(ServerLevel level, UUID village, CallbackInfo callback) {
        if (LvHooks.refuseSettle(level, village)) callback.cancel();
    }

    @Inject(method = "occupy", at = @At("HEAD"), remap = false, require = 1)
    private static void mcakingdoms$lvOccupy(ServerLevel level, @Coerce Object war, CallbackInfo callback) {
        LvHooks.occupying(level, LvHooks.warDefender(level, war), LvHooks.warWinner(level, war));
    }

    @Inject(method = "occupy", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvOccupied(ServerLevel level, @Coerce Object war, CallbackInfo callback) {
        Object conquered = LvHooks.warDefender(level, war);
        if (conquered != null) LvHooks.occupied(level, conquered, false);
    }

    @Inject(method = "occupyAbstract", at = @At("HEAD"), remap = false, require = 1)
    private static void mcakingdoms$lvOccupyAbstract(ServerLevel level, @Coerce Object attacker, @Coerce Object conquered, CallbackInfo callback) {
        LvHooks.occupying(level, conquered, null);
    }

    @Inject(method = "occupyAbstract", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvOccupiedAbstract(ServerLevel level, @Coerce Object attacker, @Coerce Object conquered, CallbackInfo callback) {
        LvHooks.occupied(level, conquered, true);
    }

    @Inject(method = "repel", at = @At("HEAD"), remap = false, require = 1)
    private static void mcakingdoms$lvRepel(ServerLevel level, @Coerce Object war, @Coerce Object attacker, @Coerce Object defender, CallbackInfo callback) {
        LvHooks.repelled(level, war);
    }

    @Inject(method = "personalRecruits", at = @At("RETURN"), remap = false, require = 1)
    private static void mcakingdoms$lvLeased(ServerLevel level, ServerPlayer player, CallbackInfoReturnable<java.util.List<?>> callback) {
        LvHooks.withoutLeased(level, callback.getReturnValue());
    }

    @Inject(method = "onSiegeEnd", at = @At("HEAD"), remap = false, require = 1)
    private static void mcakingdoms$lvSiegeEnd(ServerLevel level, @Coerce Object claim, CallbackInfo callback) {
        LvHooks.siegeEnded(level, claim);
    }
}
