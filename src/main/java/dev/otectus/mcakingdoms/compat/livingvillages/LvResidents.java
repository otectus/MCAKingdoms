package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Residents of integrated villages are persistent people. Living Villages folds a village's people back into numbers when
 * players leave and spawns fresh bodies when they return, replaces a worker at each promotion, turns idle villagers into
 * soldiers, and deletes "surplus" or "stray" villagers to match its counts. For an integrated village every one of those
 * paths is guarded: the body stays in the world (saved with its chunk), keeps its identity and job, is promoted in place,
 * and a person the provider no longer counts is kept as an ordinary resident outside its books instead of being deleted.
 * Villages outside the integration keep the provider's own behaviour.
 */
public final class LvResidents {
    /** Living Villages' own entity tags (persistent data), as its 1.1.0 materializer writes them. */
    static final String VILLAGER_TAG = "RVVillagerFaction", WORKER_TAG = "RVWorkerSlot", UNIT_TAG = "RVUnitFaction";
    /**
     * Marks a person the provider must leave alone: its stray adoption, cleanup and census all skip an entity whose
     * village tag is present but matches no village. They remain the settlement's residents in MCA: Kingdoms.
     */
    static final UUID OUTSIDE_BOOKS = UUID.fromString("6d6b6b64-6c76-4f75-8473-696465626f6f");
    static final String KEPT_TAG = "mcakingdoms:lv_kept";

    private LvResidents() {
    }

    /** Whether the village is integrated: bound to a settlement and under resident protection. */
    static boolean integrated(LvRuntime rt, UUID village) {
        if (!LvConfig.persistentResidents() || village == null) return false;
        var binding = rt.view().bindings.get(village);
        return binding != null && binding.status() == BindingStatus.BOUND;
    }

    static Optional<UUID> villageTag(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.hasUUID(VILLAGER_TAG)) return Optional.empty();
        UUID id = data.getUUID(VILLAGER_TAG);
        return id.equals(OUTSIDE_BOOKS) ? Optional.empty() : Optional.of(id);
    }

    /** The integrated village whose grounds hold {@code entity}, by its provider tag or, for an untagged person, by place. */
    static Optional<UUID> villageOf(LvRuntime rt, Entity entity) {
        var tagged = villageTag(entity);
        if (tagged.isPresent()) return tagged;
        for (var binding : rt.view().bindings.values()) {
            if (binding.status() != BindingStatus.BOUND) continue;
            var village = rt.access.village(rt.server, binding.village()).orElse(null);
            if (village != null && rt.access.box(village).contains(entity.position())) return Optional.of(binding.village());
        }
        return Optional.empty();
    }

    // ---- guards ------------------------------------------------------------------------------------------------------

    /**
     * Replaces the provider's {@code discard()} at the civilian and worker sites. Inside an integrated village the person is
     * kept and taken off the provider's books (so it is not counted, adopted or deleted again); elsewhere it is discarded
     * as the provider intended. A worker folded into the model with its village is the exception: it stays at its post with
     * its tags, because the provider re-adopts each post's recorded holder when players return.
     */
    static void discard(LvRuntime rt, Entity entity, String site) {
        if (rt == null || !(entity instanceof LivingEntity)) { entity.discard(); return; }
        var village = villageOf(rt, entity).filter(v -> integrated(rt, v));
        if (village.isEmpty()) { entity.discard(); return; }
        var data = entity.getPersistentData();
        if (site.equals("dematerialize")) {
            if (entity instanceof Mob mob) mob.setPersistenceRequired();
            record(rt, entity, village.get(), Lifecycle.MATERIALIZED, Role.WORKER, data.hasUUID(WORKER_TAG) ? data.getUUID(WORKER_TAG) : null);
            return;
        }
        data.putUUID(VILLAGER_TAG, OUTSIDE_BOOKS);
        data.remove(WORKER_TAG);
        data.putString(KEPT_TAG, site);
        if (entity instanceof Mob mob) mob.setPersistenceRequired();
        record(rt, entity, village.get(), Lifecycle.EXTERNAL, Role.CIVILIAN, null);
        rt.once("kept:" + site, "Kept residents Living Villages tried to remove at {} in integrated villages; they stay as ordinary residents", site);
    }

    /** Living Villages folds a village into its model when players leave; an integrated village keeps its people in the world. */
    static boolean keepBodies(LvRuntime rt, Object village) {
        UUID id = rt.access.id(village);
        if (!integrated(rt, id)) return false;
        for (UUID body : rt.access.trackedCivilians(id)) {
            var entity = rt.overworld().getEntity(body);
            if (entity instanceof Mob mob) mob.setPersistenceRequired();
            if (entity != null) record(rt, entity, id, Lifecycle.MATERIALIZED, Role.CIVILIAN, null);
        }
        return true;
    }

    /** At least as many bodies as the village already has: the provider's count never asks to delete a person. */
    static int bodyTarget(LvRuntime rt, Object village, int wanted) {
        UUID id = rt.access.id(village);
        if (!integrated(rt, id)) return wanted;
        return Math.max(wanted, rt.access.bodies(village).size());
    }

    /** No resident is turned into a soldier by discarding them; the provider recruits from its unmaterialized manpower instead. */
    static boolean refuseConversion(LvRuntime rt, Object village) {
        return integrated(rt, rt.access.id(village)) && !LvConfig.recruitProtected();
    }

    /** The provider's choice of idle adult to employ: {@code decided} false leaves it to the provider. */
    public record Employable(boolean decided, Mob mob) {
    }

    /** The idle adult the provider may employ in an integrated village: never a protected person when that is turned off. */
    @SuppressWarnings("unchecked")
    static Employable employable(LvRuntime rt, Object census) {
        List<Mob> jobless;
        try {
            jobless = (List<Mob>) census.getClass().getMethod("jobless").invoke(census);
        } catch (ReflectiveOperationException failure) {
            return new Employable(false, null);
        }
        if (jobless.isEmpty()) return new Employable(false, null);
        var village = villageTag(jobless.get(0)).filter(v -> integrated(rt, v));
        if (village.isEmpty()) return new Employable(false, null);
        for (int i = 0; i < jobless.size(); i++) {
            Mob mob = jobless.get(i);
            if (!mob.isAlive() || mob.isBaby()) continue;
            if (!LvConfig.recruitProtected() && !protections(rt, mob).isEmpty()) continue;
            return new Employable(true, jobless.remove(i));
        }
        // Idle people, but none the provider may employ: it spawns a new worker for the post instead.
        return new Employable(true, null);
    }

    /**
     * Promotion in place: the same worker takes the new rank, keeps every trade it had and gains the provider's offers for
     * each newly earned tier, instead of being replaced by a fresh villager at every level.
     */
    static boolean promote(LvRuntime rt, ServerLevel level, Object village, Object slot, Villager worker, VillagerProfession profession, boolean live) {
        if (!integrated(rt, rt.access.id(village))) return false;
        int target = rt.access.jobLevel(slot);
        int shown = rt.access.shownLevel(worker);
        if (shown == target) return false; // the provider only refreshes the rank: nothing is replaced
        rt.access.applyRank(worker, profession, slot);
        var byTier = VillagerTrades.TRADES.get(profession);
        if (byTier != null && target > shown) {
            for (int tier = Math.max(1, shown + 1); tier <= target; tier++) {
                var listings = byTier.get(tier);
                if (listings != null) rt.access.addTier(worker.getOffers(), listings, worker);
            }
        }
        if (live) rt.access.levelUpEffect(level, worker.position());
        record(rt, worker, rt.access.id(village), Lifecycle.MATERIALIZED, Role.WORKER, rt.access.slotId(slot));
        return true;
    }

    /**
     * A new civilian for an integrated village with MCA installed is an MCA person made by MCA's own factory (either sex,
     * MCA names, genetics and traits), not a vanilla villager. Empty leaves the provider's own spawn in place.
     */
    static Optional<Mob> spawnResident(LvRuntime rt, ServerLevel level, Vec3 pos) {
        if (!LvConfig.mcaResidents() || !net.minecraftforge.fml.ModList.get().isLoaded("mca")) return Optional.empty();
        UUID village = null;
        for (var binding : rt.view().bindings.values()) {
            if (binding.status() != BindingStatus.BOUND || !integrated(rt, binding.village())) continue;
            var provider = rt.access.village(rt.server, binding.village()).orElse(null);
            if (provider != null && rt.access.box(provider).contains(pos)) { village = binding.village(); break; }
        }
        if (village == null) return Optional.empty();
        try {
            Class<?> factory = Class.forName("forge.net.mca.entity.VillagerFactory");
            Class<?> gender = Class.forName("forge.net.mca.entity.ai.relationship.Gender");
            Object builder = factory.getMethod("newVillager", net.minecraft.world.level.Level.class).invoke(null, level);
            builder = factory.getMethod("withGender", gender).invoke(builder, gender.getMethod("getRandom").invoke(null));
            builder = factory.getMethod("withPosition", Vec3.class).invoke(builder, pos);
            builder = factory.getMethod("withType", net.minecraft.world.entity.npc.VillagerType.class)
                    .invoke(builder, net.minecraft.world.entity.npc.VillagerType.byBiome(level.getBiome(BlockPos.containing(pos))));
            Object spawned = factory.getMethod("spawn", net.minecraft.world.entity.MobSpawnType.class).invoke(builder, net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED);
            if (!(spawned instanceof Mob person)) return Optional.empty();
            person.setPersistenceRequired();
            person.setCanPickUpLoot(false);
            record(rt, person, village, Lifecycle.MATERIALIZED, Role.CIVILIAN, null);
            return Optional.of(person);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            rt.once("mca-spawn", "MCA's villager factory was unavailable for an integrated village; the provider's own spawn was used: {}", failure.toString());
            return Optional.empty();
        }
    }

    // ---- protected people ---------------------------------------------------------------------------------------------------

    /** Why this person must not be employed, converted or replaced automatically. Empty for an ordinary adult. */
    static List<String> protections(LvRuntime rt, Entity entity) {
        var out = new ArrayList<String>();
        if (entity instanceof LivingEntity living && living.isBaby()) out.add("child");
        if (entity.hasCustomName() && !isMca(entity)) out.add("named");
        if (isMca(entity)) {
            dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence.record(rt.server, entity.getUUID()).ifPresent(family -> {
                if (family.partner() != null) out.add("spouse");
                if (!family.children().isEmpty()) out.add("parent");
                if (!family.parents().isEmpty()) out.add("family");
            });
            if (following(entity)) out.add("follower");
        }
        if (holdsOffice(rt, entity)) out.add("office");
        if (entity instanceof net.minecraft.world.entity.TamableAnimal || entity.getPersistentData().getBoolean("mcakingdoms:companion")) out.add("companion");
        return out;
    }

    static boolean isMca(Entity entity) {
        return entity.getClass().getName().startsWith("forge.net.mca.entity.");
    }

    private static boolean following(Entity entity) {
        try {
            Object brain = entity.getClass().getMethod("getVillagerBrain").invoke(entity);
            return String.valueOf(brain.getClass().getMethod("getMoveState").invoke(brain)).equals("FOLLOW");
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            return false;
        }
    }

    private static boolean holdsOffice(LvRuntime rt, Entity entity) {
        try {
            var settlement = rt.kingdoms().getSettlementAt(rt.overworld(), entity.blockPosition()).orElse(null);
            if (settlement == null) return false;
            return dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(rt.server).government(settlement.kingdomId().toString())
                    .map(g -> g.offices().values().stream().anyMatch(o -> entity.getUUID().equals(o.holder().id()))).orElse(false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------------------

    static void record(LvRuntime rt, Entity entity, UUID village, Lifecycle lifecycle, Role role, UUID slot) {
        var previous = rt.residentUpdates.getOrDefault(entity.getUUID(), rt.view().residents.get(entity.getUUID()));
        var binding = rt.view().bindings.get(village);
        String name = entity.getName().getString();
        var protections = protections(rt, entity);
        var resident = previous == null
                ? new Resident(entity.getUUID(), village, binding == null ? null : binding.settlement(), role, slot, lifecycle, isMca(entity), name,
                protections, entity.level().dimension().location().toString(), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), rt.now(), null)
                : new Resident(entity.getUUID(), village, previous.settlement() != null ? previous.settlement() : binding == null ? null : binding.settlement(),
                role == Role.CIVILIAN && previous.role() == Role.WORKER && slot == null && lifecycle != Lifecycle.EXTERNAL ? Role.WORKER : role,
                slot != null ? slot : previous.slot(), lifecycle, isMca(entity), name, protections, entity.level().dimension().location().toString(),
                entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), rt.now(), previous.formerEntity());
        rt.stage(resident);
    }

    /** A known resident's entity came into the world: the same person, loaded again. Never a new body for an existing person. */
    static void joined(LvRuntime rt, Entity entity) {
        var known = rt.view().residents.get(entity.getUUID());
        var tagged = villageTag(entity).filter(v -> integrated(rt, v));
        if (known == null && tagged.isEmpty()) return;
        UUID village = known != null ? known.village() : tagged.get();
        record(rt, entity, village, known != null && known.lifecycle() == Lifecycle.EXTERNAL ? Lifecycle.EXTERNAL : Lifecycle.MATERIALIZED,
                entity.getPersistentData().hasUUID(WORKER_TAG) ? Role.WORKER : Role.CIVILIAN,
                entity.getPersistentData().hasUUID(WORKER_TAG) ? entity.getPersistentData().getUUID(WORKER_TAG) : null);
    }

    /** Unloading, travel and death are different events; only a confirmed death is a death. */
    static void left(LvRuntime rt, Entity entity) {
        var known = rt.residentUpdates.getOrDefault(entity.getUUID(), rt.view().residents.get(entity.getUUID()));
        if (known == null) return;
        var reason = entity.getRemovalReason();
        Lifecycle next = reason == null ? Lifecycle.UNRESOLVED : switch (reason) {
            case UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER -> Lifecycle.UNLOADED;
            case CHANGED_DIMENSION -> Lifecycle.AWAY;
            case KILLED -> Lifecycle.DEAD;
            case DISCARDED -> entity.getPersistentData().contains(KEPT_TAG) ? Lifecycle.EXTERNAL : Lifecycle.UNRESOLVED;
        };
        if (known.lifecycle() == Lifecycle.EXTERNAL && next == Lifecycle.UNLOADED) next = Lifecycle.EXTERNAL;
        rt.stage(known.at(next, entity.level().dimension().location().toString(), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), rt.now()));
        if (next == Lifecycle.UNRESOLVED) rt.once("unresolved:" + entity.getUUID(), "A resident of an integrated village ({}) left the world without a "
                + "confirmed death or unload; recorded as unresolved, not dead", entity.getName().getString());
    }

    static void died(LvRuntime rt, Entity entity) {
        var known = rt.residentUpdates.getOrDefault(entity.getUUID(), rt.view().residents.get(entity.getUUID()));
        if (known != null) rt.stage(known.life(Lifecycle.DEAD, rt.now()));
    }

    /** A conversion (curing, MCA replacement): the same person continues in the new body, with the old id kept as an alias. */
    static void converted(LvRuntime rt, Entity from, Entity to) {
        var known = rt.residentUpdates.getOrDefault(from.getUUID(), rt.view().residents.get(from.getUUID()));
        if (known == null) return;
        var fromData = from.getPersistentData();
        var toData = to.getPersistentData();
        for (String key : List.of(VILLAGER_TAG, WORKER_TAG)) if (fromData.hasUUID(key) && !toData.hasUUID(key)) toData.putUUID(key, fromData.getUUID(key));
        rt.stage(new Resident(to.getUUID(), known.village(), known.settlement(), known.role(), known.slot(), Lifecycle.MATERIALIZED, isMca(to),
                to.getName().getString(), known.protections(), to.level().dimension().location().toString(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
                rt.now(), from.getUUID()));
        rt.stage(known.life(Lifecycle.EXTERNAL, rt.now()));
    }

    /** Counts for a village: residents the bridge knows, by state. Bounded by the resident index, never an entity scan. */
    static Map<Lifecycle, Integer> census(LvRuntime rt, UUID village) {
        var out = new EnumMap<Lifecycle, Integer>(Lifecycle.class);
        for (var resident : rt.view().residents.values())
            if (village.equals(resident.village())) out.merge(resident.lifecycle(), 1, Integer::sum);
        return out;
    }
}
