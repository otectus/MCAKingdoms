package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCalendarView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadGeneVariantView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadGeneView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadLifeStageView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedsView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadOriginView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadScheduleView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadSpiritView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Townstead 0.7.x's public facade ({@code api.TownsteadAPI}) with the separately switched internal spirit and reaction
 * reads, or, when Townstead is absent or switched off, MCA's own loaded-village buildings only ({@code mca}). The building
 * list is always MCA's raw map here: a deliberately limited fallback, with MCA's own types rather than Townstead's
 * effective ones. Completion is read from MCA's {@code Building#isComplete} where it exists.
 */
final class TownsteadLegacyBackend implements TownsteadBackend {
    private static final TownsteadScheduleView EMPTY_SCHEDULE = new TownsteadScheduleView(
            "", "", false, false, 0, 0, 0, "", "", "", List.of(), List.of());
    private static final TownsteadNeedsView EMPTY_NEEDS =
            new TownsteadNeedsView(0, 0, 0, 0, 0, 0, 0, false, false);

    private final TownsteadBinding binding;
    private final boolean installed;
    private final Info info;

    TownsteadLegacyBackend(TownsteadBinding binding, boolean installed, String version, String detail) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.installed = installed;
        this.info = new Info(version, -1, -1, detail);
    }

    @Override public String id() { return installed ? "legacy" : binding.capabilities().isEmpty() ? "none" : "mca"; }
    @Override public Info info() { return info; }
    @Override public Set<TownsteadCapability> capabilities() { return binding.capabilities(); }
    @Override public Map<TownsteadCapability, String> diagnostics() { return binding.diagnostics(); }

    /** True when {@code buildingAt} is answered from MCA's list rather than a provider point query. */
    boolean buildingFallback() {
        return binding.buildingFallback();
    }

    @Override
    public Optional<TownsteadVillagerView> villager(Entity entity) {
        return binding.entity(entity).flatMap(this::villagerView);
    }

    @Override
    public Optional<TownsteadCalendarView> calendar(MinecraftServer server) {
        return binding.calendar(server).flatMap(this::calendarView);
    }

    /** Townstead's 0.7.x point query; empty under the MCA fallback, which the service answers from the settlement. */
    @Override
    public Optional<RawBuilding> buildingAt(ServerLevel level, BlockPos pos) {
        if (binding.buildingFallback()) return Optional.empty();
        return binding.buildingAt(level, pos).flatMap(snapshot -> {
            try {
                int villageId = integer(value(TownsteadCapability.READ_BUILDING, snapshot, "villageId"));
                int id = integer(value(TownsteadCapability.READ_BUILDING, snapshot, "id"));
                String type = string(value(TownsteadCapability.READ_BUILDING, snapshot, "type"));
                if (type.isBlank()) return Optional.empty();
                int size = integer(value(TownsteadCapability.READ_BUILDING, snapshot, "size"));
                BlockPos center = new BlockPos(integer(value(TownsteadCapability.READ_BUILDING, snapshot, "centerX")),
                        integer(value(TownsteadCapability.READ_BUILDING, snapshot, "centerY")),
                        integer(value(TownsteadCapability.READ_BUILDING, snapshot, "centerZ")));
                BlockPos min = new BlockPos(integer(value(TownsteadCapability.READ_BUILDING, snapshot, "minX")),
                        integer(value(TownsteadCapability.READ_BUILDING, snapshot, "minY")),
                        integer(value(TownsteadCapability.READ_BUILDING, snapshot, "minZ")));
                BlockPos max = new BlockPos(integer(value(TownsteadCapability.READ_BUILDING, snapshot, "maxX")),
                        integer(value(TownsteadCapability.READ_BUILDING, snapshot, "maxY")),
                        integer(value(TownsteadCapability.READ_BUILDING, snapshot, "maxZ")));
                // Townstead 0.7.x does not publish completion; MCA's own entry for the same building does.
                TownsteadBuildingEvidence.Completion completion = completion(level, villageId, id);
                return Optional.of(new RawBuilding(villageId, id, type, TownsteadBuildingView.familyOf(type), TownsteadBuildingView.levelOf(type),
                        size, center, min, max, completion, TownsteadBuildingEvidence.Source.TOWNSTEAD_LEGACY));
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
        });
    }

    private TownsteadBuildingEvidence.Completion completion(ServerLevel level, int villageId, int buildingId) {
        if (!binding.capabilities().contains(TownsteadCapability.READ_BUILDING_COMPLETION)) return TownsteadBuildingEvidence.Completion.UNKNOWN;
        TownsteadBinding.McaRead read = binding.mcaBuildings(level, villageId);
        for (Object building : read.buildings()) {
            if (integer(value(TownsteadCapability.READ_BUILDINGS, building, "getId")) != buildingId) continue;
            return binding.complete(building).map(done -> done ? TownsteadBuildingEvidence.Completion.COMPLETE
                    : TownsteadBuildingEvidence.Completion.INCOMPLETE).orElse(TownsteadBuildingEvidence.Completion.UNKNOWN);
        }
        return TownsteadBuildingEvidence.Completion.UNKNOWN;
    }

    @Override
    public BuildingRead buildings(ServerLevel level, int villageId) {
        if (!binding.capabilities().contains(TownsteadCapability.READ_BUILDINGS)) {
            return BuildingRead.of(BuildingRead.Status.UNSUPPORTED, binding.diagnostics().get(TownsteadCapability.READ_BUILDINGS));
        }
        TownsteadBinding.McaRead read = binding.mcaBuildings(level, villageId);
        if (read.failed()) return BuildingRead.of(BuildingRead.Status.FAILED, "MCA building map unreadable");
        if (!read.villageFound()) return BuildingRead.of(BuildingRead.Status.VILLAGE_ABSENT, "no MCA village " + villageId);
        List<RawBuilding> out = new ArrayList<>();
        int unreadable = 0;
        for (Object raw : read.buildings()) {
            try {
                int id = integer(value(TownsteadCapability.READ_BUILDINGS, raw, "getId"));
                String type = string(value(TownsteadCapability.READ_BUILDINGS, raw, "getType"));
                int size = integer(value(TownsteadCapability.READ_BUILDINGS, raw, "getSize"));
                BlockPos center = (BlockPos) value(TownsteadCapability.READ_BUILDINGS, raw, "getCenter");
                BlockPos p0 = (BlockPos) value(TownsteadCapability.READ_BUILDINGS, raw, "getPos0");
                BlockPos p1 = (BlockPos) value(TownsteadCapability.READ_BUILDINGS, raw, "getPos1");
                if (type.isBlank() || center == null || p0 == null || p1 == null || id < 0) { unreadable++; continue; }
                TownsteadBuildingEvidence.Completion completion = binding.complete(raw).map(done -> done
                        ? TownsteadBuildingEvidence.Completion.COMPLETE : TownsteadBuildingEvidence.Completion.INCOMPLETE)
                        .orElse(TownsteadBuildingEvidence.Completion.UNKNOWN);
                out.add(new RawBuilding(villageId, id, type, TownsteadBuildingView.familyOf(type), TownsteadBuildingView.levelOf(type), size, center,
                        new BlockPos(Math.min(p0.getX(), p1.getX()), Math.min(p0.getY(), p1.getY()), Math.min(p0.getZ(), p1.getZ())),
                        new BlockPos(Math.max(p0.getX(), p1.getX()), Math.max(p0.getY(), p1.getY()), Math.max(p0.getZ(), p1.getZ())),
                        completion, TownsteadBuildingEvidence.Source.MCA_DIRECT));
            } catch (RuntimeException malformed) {
                unreadable++;
            }
        }
        return unreadable == 0 ? new BuildingRead(BuildingRead.Status.OK, out, "")
                : new BuildingRead(BuildingRead.Status.INCONSISTENT, out, unreadable + " MCA building entries were unreadable");
    }

    @Override
    public Optional<TownsteadOriginView> origin(ResourceLocation id) {
        return binding.origin(id).flatMap(this::originView);
    }

    @Override
    public Optional<TownsteadGeneView> gene(ResourceLocation id) {
        return binding.gene(id).flatMap(this::geneView);
    }

    @Override
    public Optional<TownsteadSpiritView> spirit(ServerLevel level, int villageId) {
        return binding.spirit(level, villageId).flatMap(entry -> spiritView(villageId, entry));
    }

    @Override
    public boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> player, ResourceLocation reactionId,
                                Set<String> tags) {
        return binding.fireReaction(level, villager, player, reactionId, tags);
    }

    // ---- 0.7.x conversions (unchanged from 0.2.x) ----

    private Optional<TownsteadVillagerView> villagerView(Object snapshot) {
        UUID id = uuid(value(TownsteadCapability.READ_VILLAGER, snapshot, "uuid"));
        ResourceLocation entityType = resource(value(TownsteadCapability.READ_VILLAGER, snapshot, "entityType"));
        if (id == null || entityType == null) return Optional.empty();
        TownsteadScheduleView schedule = binding.call(TownsteadCapability.READ_SCHEDULE, snapshot, "schedule")
                .flatMap(this::scheduleView).orElse(EMPTY_SCHEDULE);
        TownsteadNeedsView needs = binding.call(TownsteadCapability.READ_NEEDS, snapshot, "needs")
                .flatMap(this::needsView).orElse(EMPTY_NEEDS);
        return Optional.of(new TownsteadVillagerView(
                id, string(value(TownsteadCapability.READ_VILLAGER, snapshot, "name")), entityType,
                optionalResource(value(TownsteadCapability.READ_VILLAGER, snapshot, "rootId")),
                string(value(TownsteadCapability.READ_VILLAGER, snapshot, "lifeStage")),
                number(value(TownsteadCapability.READ_VILLAGER, snapshot, "biologicalAgeDays")).longValue(),
                integer(value(TownsteadCapability.READ_VILLAGER, snapshot, "apparentAgeYears")),
                bool(value(TownsteadCapability.READ_VILLAGER, snapshot, "immortal")),
                bool(value(TownsteadCapability.READ_VILLAGER, snapshot, "ageless")),
                bool(value(TownsteadCapability.READ_VILLAGER, snapshot, "senior")),
                string(value(TownsteadCapability.READ_VILLAGER, snapshot, "personalityId")),
                optionalResource(value(TownsteadCapability.READ_VILLAGER, snapshot, "professionId")),
                integer(value(TownsteadCapability.READ_VILLAGER, snapshot, "professionLevel")),
                integer(value(TownsteadCapability.READ_VILLAGER, snapshot, "professionXp")),
                number(value(TownsteadCapability.READ_VILLAGER, snapshot, "fertility")).floatValue(),
                schedule, needs,
                stringMap(value(TownsteadCapability.READ_VILLAGER, snapshot, "carriedVariants")),
                stringList(value(TownsteadCapability.READ_VILLAGER, snapshot, "expressedAlleles")),
                resourceFloatMap(value(TownsteadCapability.READ_VILLAGER, snapshot, "heritage"))));
    }

    private Optional<TownsteadScheduleView> scheduleView(Object value) {
        return Optional.of(new TownsteadScheduleView(
                string(value(TownsteadCapability.READ_SCHEDULE, value, "mode")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "templateId")),
                bool(value(TownsteadCapability.READ_SCHEDULE, value, "customShifts")),
                bool(value(TownsteadCapability.READ_SCHEDULE, value, "nonDefaultCustomShifts")),
                integer(value(TownsteadCapability.READ_SCHEDULE, value, "currentTickHour")),
                integer(value(TownsteadCapability.READ_SCHEDULE, value, "currentDisplayHour")),
                integer(value(TownsteadCapability.READ_SCHEDULE, value, "currentShiftOrdinal")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "currentActivity")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "plannedActivity")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "currentTemplateId")),
                intList(value(TownsteadCapability.READ_SCHEDULE, value, "shifts")),
                stringList(value(TownsteadCapability.READ_SCHEDULE, value, "weekDayTemplates"))));
    }

    private Optional<TownsteadNeedsView> needsView(Object value) {
        return Optional.of(new TownsteadNeedsView(
                integer(value(TownsteadCapability.READ_NEEDS, value, "hunger")),
                number(value(TownsteadCapability.READ_NEEDS, value, "saturation")).floatValue(),
                number(value(TownsteadCapability.READ_NEEDS, value, "hungerExhaustion")).floatValue(),
                integer(value(TownsteadCapability.READ_NEEDS, value, "thirst")),
                integer(value(TownsteadCapability.READ_NEEDS, value, "quenched")),
                number(value(TownsteadCapability.READ_NEEDS, value, "thirstExhaustion")).floatValue(),
                integer(value(TownsteadCapability.READ_NEEDS, value, "fatigue")),
                bool(value(TownsteadCapability.READ_NEEDS, value, "collapsed")),
                bool(value(TownsteadCapability.READ_NEEDS, value, "gated"))));
    }

    private Optional<TownsteadCalendarView> calendarView(Object value) {
        return Optional.of(new TownsteadCalendarView(
                string(value(TownsteadCapability.READ_CALENDAR, value, "profileId")),
                number(value(TownsteadCapability.READ_CALENDAR, value, "worldDay")).longValue(),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "epochYearOffset")),
                string(value(TownsteadCapability.READ_CALENDAR, value, "timeMode")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "year")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "month")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "day")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "dayOfYear")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "dayOfWeek")),
                string(value(TownsteadCapability.READ_CALENDAR, value, "season"))));
    }

    private Optional<TownsteadOriginView> originView(Object value) {
        ResourceLocation id = resource(value(TownsteadCapability.READ_ORIGIN, value, "id"));
        if (id == null) return Optional.empty();
        List<TownsteadLifeStageView> stages = objectList(value(TownsteadCapability.READ_ORIGIN, value, "lifeStages"))
                .stream().map(this::lifeStage).toList();
        return Optional.of(new TownsteadOriginView(id,
                string(value(TownsteadCapability.READ_ORIGIN, value, "displayName")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "species")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "ancestry")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "lineage")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "effectiveSpecies")),
                resourceList(value(TownsteadCapability.READ_ORIGIN, value, "defaultGenes")), stages));
    }

    private TownsteadLifeStageView lifeStage(Object value) {
        return new TownsteadLifeStageView(
                string(callRaw(value, "id")), string(callRaw(value, "label")), integer(callRaw(value, "days")),
                number(callRaw(value, "scale")).floatValue(), string(callRaw(value, "presentsAs")),
                number(callRaw(value, "narrativeStart")).floatValue(),
                number(callRaw(value, "narrativeEnd")).floatValue());
    }

    private Optional<TownsteadGeneView> geneView(Object value) {
        ResourceLocation id = resource(value(TownsteadCapability.READ_GENE, value, "id"));
        if (id == null) return Optional.empty();
        List<TownsteadGeneVariantView> variants = objectList(value(TownsteadCapability.READ_GENE, value, "variants"))
                .stream().map(this::geneVariant).toList();
        return Optional.of(new TownsteadGeneView(id,
                string(value(TownsteadCapability.READ_GENE, value, "displayName")),
                string(value(TownsteadCapability.READ_GENE, value, "description")),
                string(value(TownsteadCapability.READ_GENE, value, "category")),
                string(value(TownsteadCapability.READ_GENE, value, "dominance")),
                optionalResource(value(TownsteadCapability.READ_GENE, value, "locus")),
                integer(value(TownsteadCapability.READ_GENE, value, "weight")),
                string(value(TownsteadCapability.READ_GENE, value, "displayMode")), variants));
    }

    private TownsteadGeneVariantView geneVariant(Object value) {
        return new TownsteadGeneVariantView(string(callRaw(value, "id")), string(callRaw(value, "displayName")),
                integer(callRaw(value, "weight")), string(callRaw(value, "type")));
    }

    private Optional<TownsteadSpiritView> spiritView(int villageId, Object entry) {
        Object totals = value(TownsteadCapability.READ_SPIRIT, entry, "totals");
        Object readout = value(TownsteadCapability.READ_SPIRIT, entry, "readout");
        if (totals == null || readout == null) return Optional.empty();
        Object classification = value(TownsteadCapability.READ_SPIRIT, readout, "classification");
        return Optional.of(new TownsteadSpiritView(villageId,
                integerMap(value(TownsteadCapability.READ_SPIRIT, totals, "perSpirit")),
                integer(value(TownsteadCapability.READ_SPIRIT, totals, "total")),
                integer(value(TownsteadCapability.READ_SPIRIT, totals, "contributingBuildings")),
                integer(value(TownsteadCapability.READ_SPIRIT, readout, "tierIndex")),
                classification == null ? "" : classification.toString().toLowerCase(java.util.Locale.ROOT),
                optionalString(value(TownsteadCapability.READ_SPIRIT, readout, "primarySpiritId")),
                optionalString(value(TownsteadCapability.READ_SPIRIT, readout, "secondarySpiritId"))));
    }

    private Object value(TownsteadCapability capability, Object target, String accessor) {
        return binding.call(capability, target, accessor).orElse(null);
    }

    private Object callRaw(Object target, String accessor) {
        try { return target.getClass().getMethod(accessor).invoke(target); }
        catch (Throwable ignored) { return null; }
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean bool(Object value) { return value instanceof Boolean b && b; }
    private static Number number(Object value) { return value instanceof Number n ? n : 0; }
    private static int integer(Object value) { return number(value).intValue(); }
    private static UUID uuid(Object value) { try { return UUID.fromString(string(value)); } catch (Exception ignored) { return null; } }
    private static ResourceLocation resource(Object value) { return ResourceLocation.tryParse(string(value)); }
    private static Optional<ResourceLocation> optionalResource(Object value) {
        String raw = string(value); return raw.isBlank() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(raw));
    }
    private static Optional<String> optionalString(Object value) {
        return value == null || string(value).isBlank() ? Optional.empty() : Optional.of(string(value));
    }
    private static List<Object> objectList(Object value) { return value instanceof List<?> list ? new ArrayList<>(list) : List.of(); }
    private static List<String> stringList(Object value) { return objectList(value).stream().map(String::valueOf).toList(); }
    private static List<Integer> intList(Object value) { return objectList(value).stream().map(TownsteadLegacyBackend::integer).toList(); }
    private static List<ResourceLocation> resourceList(Object value) { return objectList(value).stream().map(TownsteadLegacyBackend::resource).filter(Objects::nonNull).toList(); }
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>(); map.forEach((key, item) -> out.put(String.valueOf(key), String.valueOf(item))); return Map.copyOf(out);
    }
    private static Map<ResourceLocation, Float> resourceFloatMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<ResourceLocation, Float> out = new LinkedHashMap<>();
        map.forEach((key, item) -> { ResourceLocation id = ResourceLocation.tryParse(String.valueOf(key)); if (id != null && item instanceof Number n) out.put(id, n.floatValue()); });
        return Map.copyOf(out);
    }
    private static Map<String, Integer> integerMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>(); map.forEach((key, item) -> out.put(String.valueOf(key), integer(item))); return Map.copyOf(out);
    }
}
