package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

/**
 * The only entry points the Living Villages mixins call. Each one finds the running bridge for the provider's level, does
 * nothing when the integration is inactive (the provider's own behaviour stands), and never lets an exception escape into
 * the provider: a policy hook that fails refuses (no war starts on an unchecked path), a resident hook that fails keeps the
 * person, and an observation hook that fails is logged once and leaves recovery evidence.
 */
public final class LvHooks {
    private static final Set<String> FAILED = new HashSet<>();
    private static boolean nativeListeners;

    private LvHooks() {
    }

    private static void failed(String where, Throwable failure) {
        if (FAILED.add(where + ":" + failure.getClass().getName()))
            LvRuntime.LOGGER.error("[MCA: Kingdoms] Living Villages hook {} failed; it fails safe until this is resolved", where, failure);
    }

    private static Optional<LvRuntime> runtime(Level level) {
        return LvRuntime.of(level).filter(rt -> rt.server.isSameThread());
    }

    static void clearTransient() {
        LvWarBridge.clearTransient();
    }

    // ---- war ------------------------------------------------------------------------------------------------------------

    public static boolean refuseDeclare(ServerLevel level, Object attacker, Object defender, ServerPlayer initiator) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvWarBridge.beforeDeclare(rt, attacker, defender, initiator); }
        catch (RuntimeException | LinkageError failure) { failed("declare", failure); return true; }
    }

    public static void declared(ServerLevel level, Object attacker, Object defender, boolean result) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.afterDeclare(rt, attacker, defender, result); } catch (RuntimeException | LinkageError failure) { failed("declared", failure); }
        });
    }

    public static void aiPass(boolean start) {
        if (start) LvWarBridge.beginAiPass(); else LvWarBridge.endAiPass();
    }

    public static boolean canWageWar(Object attacker) {
        LvWarBridge.aiAttacker(attacker);
        return LvAccess.get().map(access -> access.canWageWar(attacker)).orElse(false);
    }

    /** The defender filter in the AI's selection: the provider's own exclusion, or the bridge's refusal of this pair. */
    public static boolean excludedTarget(ServerLevel level, Object defender) {
        var access = LvAccess.get().orElse(null);
        if (access == null) return true;
        if (access.playerFaction(defender)) return true;
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvWarBridge.aiExcluded(rt, defender); }
        catch (RuntimeException | LinkageError failure) { failed("target", failure); return true; }
    }

    public static boolean refuseBattle(ServerLevel level, Object war) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvWarBridge.beforeBattle(rt, war); }
        catch (RuntimeException | LinkageError failure) { failed("battle", failure); return false; }
    }

    public static void battleStarted(ServerLevel level, Object war) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.afterBattle(rt, war); } catch (RuntimeException | LinkageError failure) { failed("battle_started", failure); }
        });
    }

    public static boolean refuseAbstract(ServerLevel level, Object attacker, Object defender) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvWarBridge.beforeAbstract(rt, attacker, defender); }
        catch (RuntimeException | LinkageError failure) { failed("abstract", failure); return true; }
    }

    public static void abstractStarted(ServerLevel level, Object attacker, Object defender) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.afterAbstract(rt, attacker, defender); } catch (RuntimeException | LinkageError failure) { failed("abstract_started", failure); }
        });
    }

    public static void abstractTick(ServerLevel level, boolean before) {
        runtime(level).ifPresent(rt -> {
            try { if (before) LvWarBridge.beforeAbstractTick(rt); else LvWarBridge.afterAbstractTick(rt); }
            catch (RuntimeException | LinkageError failure) { failed("abstract_tick", failure); }
        });
    }

    public static boolean refuseResolve(ServerLevel level, Object attacker, Object defender) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvWarBridge.beforeResolve(rt, attacker, defender); }
        catch (RuntimeException | LinkageError failure) { failed("resolve", failure); return true; }
    }

    public static void resolved(ServerLevel level, Object attacker, Object defender) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.afterResolve(rt, attacker, defender); } catch (RuntimeException | LinkageError failure) { failed("resolved", failure); }
        });
    }

    public static boolean refuseSettle(ServerLevel level, UUID village) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvWarBridge.beforeSettle(rt, village); }
        catch (RuntimeException | LinkageError failure) { failed("settle", failure); return false; }
    }

    public static void occupying(ServerLevel level, Object conquered, Object winnerCapital) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.beforeOccupy(rt, conquered, winnerCapital); } catch (RuntimeException | LinkageError failure) { failed("occupying", failure); }
        });
    }

    public static void occupied(ServerLevel level, Object conquered, boolean abstractPath) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.afterOccupy(rt, conquered, abstractPath); } catch (RuntimeException | LinkageError failure) { failed("occupied", failure); }
        });
    }

    /** The conquered village of a live war, for the occupation hooks. */
    public static Object warDefender(ServerLevel level, Object war) {
        var access = LvAccess.get().orElse(null);
        if (access == null) return null;
        UUID defender = access.war(war).defender();
        return access.village(level.getServer(), defender).orElse(null);
    }

    public static Object warWinner(ServerLevel level, Object war) {
        var access = LvAccess.get().orElse(null);
        if (access == null) return null;
        return access.village(level.getServer(), access.war(war).attackerCapital()).orElse(null);
    }

    public static void repelled(ServerLevel level, Object war) {
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.repelled(rt, war); } catch (RuntimeException | LinkageError failure) { failed("repelled", failure); }
        });
    }

    public static void siegeEnded(ServerLevel level, Object claim) {
        runtime(level).ifPresent(rt -> {
            try {
                UUID id = (UUID) claim.getClass().getMethod("getUUID").invoke(claim);
                LvWarBridge.siegeEnded(rt, id);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failed("siege_end", failure); }
        });
    }

    public static void withoutLeased(ServerLevel level, java.util.List<?> recruits) {
        if (recruits == null) return;
        runtime(level).ifPresent(rt -> {
            try { LvWarBridge.withoutLeased(rt, recruits); } catch (RuntimeException | LinkageError failure) { failed("leased", failure); }
        });
    }

    // ---- diplomacy -----------------------------------------------------------------------------------------------------

    /** Returns true to hold back a native relation change an agreement in force forbids. */
    public static boolean suppressRelation(ServerLevel level, Object a, Object b, Object status) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return !LvDiplomacy.arbitrate(rt, a, b, LvAccess.statusOf(status)); }
        catch (RuntimeException | LinkageError failure) { failed("relation", failure); return false; }
    }

    // ---- residents -------------------------------------------------------------------------------------------------------

    public static boolean keepBodies(ServerLevel level, Object village) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvResidents.keepBodies(rt, village); }
        catch (RuntimeException | LinkageError failure) { failed("capture", failure); return true; }
    }

    /** Replaces the provider's discard at a guarded civilian or worker site. */
    public static void guard(Entity entity, String site) {
        var rt = entity.level() instanceof ServerLevel level ? runtime(level).orElse(null) : null;
        try { LvResidents.discard(rt, entity, site); }
        catch (RuntimeException | LinkageError failure) {
            failed("guard:" + site, failure);
            // Fail toward keeping the person: an unremoved body is recoverable, a deleted one is not.
            if (rt == null) entity.discard();
        }
    }

    public static int bodyTarget(Object village, int wanted) {
        var access = LvAccess.get().orElse(null);
        if (access == null) return wanted;
        ServerLevel level = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() == null ? null
                : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().overworld();
        var rt = level == null ? null : runtime(level).orElse(null);
        if (rt == null) return wanted;
        try { return LvResidents.bodyTarget(rt, village, wanted); }
        catch (RuntimeException | LinkageError failure) { failed("body_target", failure); return wanted; }
    }

    public static int joblessLive(Object village) {
        return LvAccess.get().map(access -> access.joblessLive(village)).orElse(0);
    }

    public static boolean refuseConversion(Object village) {
        var rt = currentRuntime().orElse(null);
        if (rt == null) return false;
        try { return LvResidents.refuseConversion(rt, village); }
        catch (RuntimeException | LinkageError failure) { failed("conversion", failure); return true; }
    }

    /** Null to let the provider choose; otherwise the decision (whose {@code mob} may be null: nobody eligible). */
    public static LvResidents.Employable employable(Object census) {
        var rt = currentRuntime().orElse(null);
        if (rt == null) return null;
        try {
            var choice = LvResidents.employable(rt, census);
            return choice.decided() ? choice : null;
        } catch (RuntimeException | LinkageError failure) { failed("employ", failure); return null; }
    }

    public static boolean promoteInPlace(ServerLevel level, Object village, Object slot, Villager worker, VillagerProfession profession, boolean live) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return false;
        try { return LvResidents.promote(rt, level, village, slot, worker, profession, live); }
        catch (RuntimeException | LinkageError failure) { failed("promote", failure); return true; }
    }

    public static Mob spawnResident(ServerLevel level, Vec3 pos) {
        var rt = runtime(level).orElse(null);
        if (rt == null) return null;
        try { return LvResidents.spawnResident(rt, level, pos).orElse(null); }
        catch (RuntimeException | LinkageError failure) { failed("spawn", failure); return null; }
    }

    public static boolean holdConstruction(Object village) {
        var rt = currentRuntime().orElse(null);
        if (rt == null) return false;
        try { return LvAdoption.constructionHeld(rt, LvAccess.get().orElseThrow().id(village)); }
        catch (RuntimeException | LinkageError failure) { failed("construction", failure); return true; }
    }

    /**
     * Living Villages' own claim test for a building spot it is considering, then MCA: Kingdoms' ground test while
     * {@code protectExternalBuildings} is on. A refused spot makes the provider try its next one; nothing is marked built.
     */
    public static boolean spotClaimable(Object village, int x, int z, Vec3i footprint) {
        var access = LvAccess.get().orElse(null);
        // Without the adapter the provider's own test cannot be called; no spot is offered rather than an unchecked one.
        if (access == null) return false;
        try {
            if (!access.spotClaimable(village, x, z, footprint)) return false;
            var rt = currentRuntime().orElse(null);
            if (rt == null || !LvConfig.protectBuildings()) return true;
            return LvBindings.buildingRefusal(rt, access.id(village), access.name(village), x, z, footprint).isEmpty();
        } catch (RuntimeException | LinkageError failure) { failed("building_ground", failure); return false; }
    }

    /** Whether a natural village Living Villages would found centred on {@code center} is refused: its ground is taken. */
    public static boolean refuseFounding(ServerLevel level, BlockPos center) {
        var rt = runtime(level).orElse(null);
        if (rt == null || !LvConfig.protectBuildings()) return false;
        try { return LvBindings.foundingRefusal(rt, center).isPresent(); }
        catch (RuntimeException | LinkageError failure) { failed("founding_ground", failure); return true; }
    }

    private static Optional<LvRuntime> currentRuntime() {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null || !server.isSameThread() ? Optional.empty() : LvRuntime.get(server);
    }

    // ---- soldier state ---------------------------------------------------------------------------------------------------

    private static final String EXTRA = "McaKingdomsState";

    /**
     * Living Villages' stand-down snapshot keeps a soldier's inventory and hunger. For a unit of an integrated village the
     * bridge adds its name, health, effects and persistent mod data (not targets or other session references), so a soldier
     * who returns to the world is the same soldier rather than a template.
     */
    public static void augmentState(Object recruit, CompoundTag tag) {
        if (!(recruit instanceof LivingEntity living) || tag == null) return;
        var rt = currentRuntime().orElse(null);
        if (rt == null || !living.getPersistentData().hasUUID(LvResidents.UNIT_TAG)
                || !LvResidents.integrated(rt, living.getPersistentData().getUUID(LvResidents.UNIT_TAG))) return;
        try {
            var extra = new CompoundTag();
            if (living.getCustomName() != null) extra.putString("CustomName", Component.Serializer.toJson(living.getCustomName()));
            extra.putFloat("Health", living.getHealth());
            var effects = new ListTag();
            for (MobEffectInstance effect : living.getActiveEffects()) effects.add(effect.save(new CompoundTag()));
            extra.put("Effects", effects);
            var data = new CompoundTag();
            for (String key : living.getPersistentData().getAllKeys())
                if (!key.startsWith("RV") && !key.startsWith("mcakingdoms:lv")) data.put(key, Objects.requireNonNull(living.getPersistentData().get(key)).copy());
            extra.put("Data", data);
            tag.put(EXTRA, extra);
        } catch (RuntimeException failure) { failed("save_state", failure); }
    }

    public static void restoreState(Object recruit, CompoundTag tag) {
        if (!(recruit instanceof LivingEntity living) || tag == null || !tag.contains(EXTRA, Tag.TAG_COMPOUND)) return;
        try {
            var extra = tag.getCompound(EXTRA);
            if (extra.contains("CustomName", Tag.TAG_STRING)) living.setCustomName(Component.Serializer.fromJson(extra.getString("CustomName")));
            if (extra.contains("Health", Tag.TAG_FLOAT)) living.setHealth(Math.max(1f, Math.min(living.getMaxHealth(), extra.getFloat("Health"))));
            for (Tag effect : extra.getList("Effects", Tag.TAG_COMPOUND)) {
                var instance = MobEffectInstance.load((CompoundTag) effect);
                if (instance != null) living.addEffect(instance);
            }
            var data = extra.getCompound("Data");
            for (String key : data.getAllKeys()) if (!living.getPersistentData().contains(key)) living.getPersistentData().put(key, Objects.requireNonNull(data.get(key)).copy());
        } catch (RuntimeException failure) { failed("restore_state", failure); }
    }

    // ---- Forge lifecycle ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LvRuntime.get(event.getServer()).ifPresent(rt -> {
            try { rt.tick(); } catch (RuntimeException | LinkageError failure) { failed("tick", failure); }
        });
    }

    @SubscribeEvent
    public static void levelSaved(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) return;
        LvRuntime.get(level.getServer()).ifPresent(rt -> {
            try { rt.saved(); } catch (RuntimeException | LinkageError failure) { failed("saved", failure); }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void entityJoined(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof LivingEntity)) return;
        runtime(level).ifPresent(rt -> {
            try { LvResidents.joined(rt, event.getEntity()); } catch (RuntimeException | LinkageError failure) { failed("joined", failure); }
        });
    }

    @SubscribeEvent
    public static void entityLeft(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof LivingEntity)) return;
        runtime(level).ifPresent(rt -> {
            try { LvResidents.left(rt, event.getEntity()); } catch (RuntimeException | LinkageError failure) { failed("left", failure); }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void died(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        runtime(level).ifPresent(rt -> {
            try { LvResidents.died(rt, event.getEntity()); } catch (RuntimeException | LinkageError failure) { failed("died", failure); }
        });
    }

    @SubscribeEvent
    public static void converted(LivingConversionEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        runtime(level).ifPresent(rt -> {
            try { LvResidents.converted(rt, event.getEntity(), event.getOutcome()); } catch (RuntimeException | LinkageError failure) { failed("converted", failure); }
        });
    }

    /** Recruits' own faction events mark the villages of that team for the next batch (a disband frees its villages). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static synchronized void registerNativeListeners() {
        if (nativeListeners) return;
        try {
            for (String name : new String[]{"Created", "Disbanded"}) {
                Class type = Class.forName("com.talhanation.recruits.FactionEvent$" + name);
                MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, type, (java.util.function.Consumer<Event>) LvHooks::factionChanged);
            }
            nativeListeners = true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            LvRuntime.LOGGER.warn("[MCA: Kingdoms] Recruits faction events unavailable; realm changes are found by the periodic check only");
        }
    }

    private static void factionChanged(Event event) {
        try {
            var level = (ServerLevel) event.getClass().getMethod("getLevel").invoke(event);
            if (level == null) return;
            runtime(level).ifPresent(rt -> {
                for (Object village : rt.access.villages(rt.server)) rt.touch(rt.access.id(village));
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failed("faction_event", failure);
        }
    }
}
