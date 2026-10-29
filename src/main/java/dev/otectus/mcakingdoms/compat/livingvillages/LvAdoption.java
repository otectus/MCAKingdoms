package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * "Enable Living Villages management here": places an existing settlement under Living Villages' simulation on purpose. Living
 * Villages 1.1.0 has no adoption API and its founding routine stamps a town hall, flattens a plot and spawns a starter
 * population, so this is a narrow path instead: a provider record is created around what is already there, counted from the
 * residents and beds actually present, with no founding grant, no starter troops and no catch-up, and with construction, plot
 * work and livestock restocking held off for its footprint while {@code constructionInAdoptedVillages} is off.
 */
public final class LvAdoption {
    private LvAdoption() {
    }

    public record Preview(SettlementView settlement, int residents, int mcaResidents, int beds, int workstations, int containers,
                          List<Component> blockers, List<Component> effects) {
        public boolean allowed() { return blockers.isEmpty(); }
    }

    public static Preview preview(LvRuntime rt, UUID settlementId) {
        var settlement = rt.kingdoms().getSettlement(settlementId).orElseThrow(() -> LvText.refuse("unknown_settlement", "That settlement is not available."));
        var blockers = new ArrayList<Component>();
        var effects = new ArrayList<Component>();
        ServerLevel level = rt.overworld();
        if (!settlement.dimension().equals(Level.OVERWORLD)) blockers.add(LvText.tr("adopt.dimension", "Living Villages manages Overworld villages only."));
        if (rt.view().bindingForSettlement(settlementId).isPresent()) blockers.add(LvText.tr("adopt.bound", "It is already a Living Villages village."));
        var bounds = settlement.bounds();
        BlockPos center = settlement.anchor();
        boolean loaded = true;
        for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4 && loaded; cx++)
            for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4 && loaded; cz++)
                if (!level.hasChunk(cx, cz)) loaded = false;
        if (!loaded) blockers.add(LvText.tr("adopt.unloaded", "Part of the settlement is not loaded; stand in it so every resident and building can be counted."));
        for (var binding : rt.view().bindings.values()) {
            if (binding.status() != BindingStatus.BOUND) continue;
            var other = rt.access.village(rt.server, binding.village()).orElse(null);
            if (other != null && rt.access.box(other).intersects(new AABB(bounds.minX(), level.getMinBuildHeight(), bounds.minZ(), bounds.maxX() + 1, level.getMaxBuildHeight(), bounds.maxZ() + 1)))
                blockers.add(LvText.tr("adopt.overlap", "It overlaps the grounds of %s, another Living Villages village.", binding.providerName()));
        }
        int radius = rt.access.number("CLAIM_RADIUS_CHUNKS", 2);
        var claimed = new TreeSet<String>();
        var centerChunk = new ChunkPos(center);
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            var view = dev.otectus.mcakingdoms.compat.recruits.RecruitsObservation.at(rt.server, new ChunkPos(centerChunk.x + dx, centerChunk.z + dz));
            if (!view.claimId().isEmpty()) claimed.add(view.ownerId());
        }
        if (!claimed.isEmpty()) blockers.add(LvText.tr("adopt.claimed", "Recruits claims already cover its land (%s); claims are never taken over by adoption.", String.join(", ", claimed)));
        if (WarfareProtected.capital(rt, settlementId))
            effects.add(LvText.tr("adopt.capital", "It is a kingdom's capital seat; MCA: Kingdoms' capital protection keeps applying to wars against it."));
        var box = new AABB(bounds.minX(), level.getMinBuildHeight(), bounds.minZ(), bounds.maxX() + 1, level.getMaxBuildHeight(), bounds.maxZ() + 1);
        var villagers = level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby());
        int mca = (int) villagers.stream().filter(LvResidents::isMca).count();
        int radiusBlocks = Math.max(16, settlement.radius());
        int beds = (int) level.getPoiManager().getInRange(type -> type.is(PoiTypes.HOME), center, radiusBlocks, PoiManager.Occupancy.ANY).count();
        int stations = (int) level.getPoiManager().getInRange(type -> !type.is(PoiTypes.HOME) && !type.is(PoiTypes.MEETING), center, radiusBlocks,
                PoiManager.Occupancy.ANY).count();
        int containers = 0;
        for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4; cx++)
            for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (var entity : level.getChunk(cx, cz).getBlockEntities().values())
                    if (entity instanceof net.minecraft.world.Container) containers++;
            }
        if (villagers.isEmpty()) blockers.add(LvText.tr("adopt.empty", "No adult residents are here to seed the village from."));
        effects.add(LvText.tr("adopt.people", "%s residents (%s MCA) stay the same people; Living Villages counts them instead of spawning starters.", villagers.size(), mca));
        effects.add(LvText.tr("adopt.beds", "%s beds and %s workstations are kept as they are; no town hall is stamped and no plot is flattened.", beds, stations));
        effects.add(LvText.tr("adopt.storage", "%s containers and their contents are left untouched.", containers));
        effects.add(LvConfig.adoptedConstruction()
                ? LvText.tr("adopt.construction_on", "Living Villages may build here: construction in adopted villages is turned on.")
                : LvText.tr("adopt.construction_off", "Living Villages will not build, dig, flatten or restock livestock here."));
        effects.add(LvText.tr("adopt.simulation", "It starts with an empty treasury and no soldiers, then grows, taxes and recruits by Living Villages' own rules."));
        effects.add(LvText.tr("adopt.war", "It becomes an independent AI realm: other realms may declare war on it within this server's war rules."));
        return new Preview(settlement, villagers.size(), mca, beds, stations, containers, blockers, effects);
    }

    /** Creates the provider record from the preview's evidence. Refused unless the preview has no blockers at this moment. */
    public static String apply(LvRuntime rt, UUID settlementId, UUID operator, UUID request) {
        if (rt.view().journal.containsKey(request)) return LvText.text("adopt_replayed", "That adoption was already made.");
        var preview = preview(rt, settlementId);
        if (!preview.allowed()) throw LvText.refuse("adopt_blocked", "This settlement cannot be placed under management now: %s", preview.blockers().get(0));
        var settlement = preview.settlement();
        ServerLevel level = rt.overworld();
        UUID village = UUID.randomUUID();
        var next = rt.data.snapshot();
        next.journal.put(request, new Operation(request, OperationKind.ADOPTION, settlementId, null, OperationState.INTENT, "", village.toString(),
                rt.now(), 0, 0, false, "adoption of " + settlement.displayName(), ActorKind.ADMINISTRATOR, operator));
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        Object record = rt.access.newVillage(village, settlement.displayName());
        rt.access.setCenter(record, settlement.anchor());
        rt.access.setTerrainFit(record, true);
        rt.access.setTreasury(record, 0);
        rt.access.setFood(record, 0);
        var structures = rt.access.structures(record);
        structures.add(rt.access.existingBuilding("town_hall", settlement.anchor()));
        int perHouse = Math.max(1, rt.access.number("BEDS_PER_HOUSE", 2));
        int houses = Math.max(1, (preview.beds() + perHouse - 1) / perHouse);
        for (int i = 0; i < houses; i++) structures.add(rt.access.existingBuilding("house", settlement.anchor()));
        // The provider grows houses no further than the beds that already stand here.
        rt.access.setHouseLimit(record, houses);
        var bounds = settlement.bounds();
        var box = new AABB(bounds.minX(), level.getMinBuildHeight(), bounds.minZ(), bounds.maxX() + 1, level.getMaxBuildHeight(), bounds.maxZ() + 1);
        var bodies = rt.access.bodies(record);
        int counted = 0;
        for (var villager : level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby())) {
            villager.getPersistentData().putUUID(LvResidents.VILLAGER_TAG, village);
            villager.setPersistenceRequired();
            bodies.put(villager.getUUID(), villager.blockPosition());
            counted++;
        }
        rt.access.setCivilians(record, counted);
        rt.access.add(rt.server, record);
        next = rt.data.snapshot();
        var candidate = SettlementCandidate.external(Level.OVERWORLD, settlement.anchor(), settlement.radius(), settlement.bounds(), LvBindings.SOURCE,
                LvBindings.sourceKey(village), null, Map.of(LvBindings.EXTERNAL_REF, village.toString()), null);
        rt.kingdoms().registerCandidate(level, candidate);
        next.bindings.put(village, new Binding(village, settlementId, "minecraft:overworld", BindingStatus.BOUND, settlement.displayName(), List.of(),
                null, List.of(), null, null, true, rt.now(), rt.now(), "adopted", List.of()));
        next.adoptions.put(settlementId, new Adoption(settlementId, village, operator, rt.now(), counted, houses, LvConfig.adoptedConstruction(),
                preview.residents() + " residents, " + preview.beds() + " beds, " + preview.workstations() + " workstations, " + preview.containers() + " containers"));
        next.journal.put(request, next.journal.get(request).state(OperationState.APPLIED, village.toString(), rt.now()));
        next.record(EventKind.ADOPTED, rt.now(), settlementId, null, null, null, settlement.displayName(), true);
        for (var villager : level.getEntitiesOfClass(Villager.class, box, v -> village.equals(LvResidents.villageTag(v).orElse(null))))
            LvResidents.record(rt, villager, village, Lifecycle.MATERIALIZED, Role.CIVILIAN, null);
        rt.commit(next);
        rt.awaitingSave.add(request);
        rt.touch(village);
        return LvText.text("adopted", "%s is now managed by Living Villages, counted from its %s residents and %s houses. Its buildings and people are unchanged.",
                settlement.displayName(), counted, houses);
    }

    /** With {@code adoptOrdinaryVillagesAutomatically} on: at most one eligible generated village per interval, by the same preview. */
    static void automatic(LvRuntime rt) {
        if (!(rt.kingdoms() instanceof dev.otectus.mcakingdoms.core.KingdomsServiceImpl impl)) return;
        for (var settlement : impl.orderedSettlements(Optional.empty())) {
            if (!settlement.dimension().equals(Level.OVERWORLD) || rt.view().bindingForSettlement(settlement.id()).isPresent()) continue;
            if (rt.view().adoptions.containsKey(settlement.id()) || settlement.externalRefs().containsKey(LvBindings.EXTERNAL_REF)) continue;
            if (settlement.detectionSource() != dev.otectus.mcakingdoms.api.DetectionSource.STRUCTURE) continue;
            try {
                var preview = preview(rt, settlement.id());
                if (!preview.allowed() || WarfareProtected.capital(rt, settlement.id())) continue;
                LvRuntime.LOGGER.info("[MCA: Kingdoms] {}", dev.otectus.mcakingdoms.interaction.Keyed.english(apply(rt, settlement.id(), null, UUID.randomUUID())));
                return;
            } catch (RuntimeException skipped) {
                // Not eligible now; it is looked at again next interval.
            }
        }
    }

    /** Whether construction-type provider work is held off for this village (an adopted village with construction off). */
    static boolean constructionHeld(LvRuntime rt, UUID village) {
        if (LvConfig.adoptedConstruction()) return false;
        var binding = rt.view().bindings.get(village);
        return binding != null && binding.adopted();
    }

    static final class WarfareProtected {
        private WarfareProtected() {
        }

        static boolean capital(LvRuntime rt, UUID settlement) {
            try {
                return dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(rt.server).isCapital(settlement);
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
    }
}
