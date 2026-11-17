package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCalendarView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCareerView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadGeneView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadOriginView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadSpiritView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerNeedsView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Kingdoms-owned internal surface over Townstead (spec 5.1). Implementations: {@code v1} (Townstead's public api.v1,
 * {@code compat/townstead/v1}, the only package that names a Townstead type), {@code legacy} (the 0.7.x public facade plus
 * the separately switched internal spirit and reaction reads) and {@code mca} (MCA's own loaded-village buildings, when
 * Townstead is absent or switched off). Every method is total and server-thread only, and returns Kingdoms types.
 */
public interface TownsteadBackend {
    /** {@code v1}, {@code legacy}, {@code mca} or {@code none}. */
    String id();

    Info info();

    Set<TownsteadCapability> capabilities();

    Map<TownsteadCapability, String> diagnostics();

    Optional<TownsteadVillagerView> villager(Entity entity);

    Optional<TownsteadCalendarView> calendar(MinecraftServer server);

    /** The building containing {@code pos}, by the provider's own point query where it has one. */
    Optional<RawBuilding> buildingAt(ServerLevel level, BlockPos pos);

    BuildingRead buildings(ServerLevel level, int villageId);

    Optional<TownsteadOriginView> origin(ResourceLocation id);

    Optional<TownsteadGeneView> gene(ResourceLocation id);

    Optional<TownsteadSpiritView> spirit(ServerLevel level, int villageId);

    boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> player, ResourceLocation reactionId,
                         Set<String> tags);

    // ---- api.v1 only; the other backends keep these defaults. ----

    default Optional<TownsteadVillagerNeedsView> needs(Entity entity) {
        return Optional.empty();
    }

    default Optional<TownsteadVillageView> village(ServerLevel level, int villageId) {
        return Optional.empty();
    }

    default Optional<NeedsSummary> needsSummary(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return Optional.empty();
    }

    default Optional<ResidentRecord> record(MinecraftServer server, UUID villager) {
        return Optional.empty();
    }

    default Optional<TownsteadCareerView> career(Entity entity) {
        return Optional.empty();
    }

    /** A profession's canonical id and track, resolving aliases and bare paths as Townstead does. */
    default Optional<Profession> profession(String id) {
        return Optional.empty();
    }

    default Optional<Boolean> knownSkill(ResourceLocation skill) {
        return Optional.empty();
    }

    default Optional<Boolean> hasSkill(Entity entity, ResourceLocation skill) {
        return Optional.empty();
    }

    default List<Worksite> worksites(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return List.of();
    }

    default List<Order> orders(MinecraftServer server, long worksite) {
        return List.of();
    }

    /** Advisory count; empty when it cannot be read (a zero is a reading, not proof of absence). */
    default OptionalInt countItem(ServerLevel level, int villageId, ResourceLocation item) {
        return OptionalInt.empty();
    }

    default OptionalInt countTag(ServerLevel level, int villageId, ResourceLocation tag) {
        return OptionalInt.empty();
    }

    /** Townstead's system ids; empty without a settings capability. */
    default List<String> systems() {
        return List.of();
    }

    /** Empty for an unknown system or without a settings capability; never Townstead's "unknown reads as on". */
    default Optional<Boolean> systemEnabled(String system) {
        return Optional.empty();
    }

    default Optional<DigestPage> digest(MinecraftServer server, ResourceLocation dimension, int villageId, long cursor, int limit) {
        return Optional.empty();
    }

    default Emission emit(ServerLevel level, LivingEntity actor, String triggerKey, float magnitude, Map<String, String> params) {
        return Emission.UNAVAILABLE;
    }

    default Optional<Faction> faction(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return Optional.empty();
    }

    default Optional<Governance> governance(MinecraftServer server, ResourceLocation faction) {
        return Optional.empty();
    }

    /** The founding profile Townstead recorded when the settlement was explicitly founded. */
    default Optional<ResourceLocation> foundingProfile(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return Optional.empty();
    }

    /** False when Townstead has no animation backend, so every reaction would be inert. */
    default boolean reactionsPlayable() {
        return capabilities().contains(TownsteadCapability.DISPATCH_REACTION);
    }

    default Set<ResourceLocation> reactionIds() {
        return Set.of();
    }

    /**
     * Registry ids Townstead knows for one data registry: empty when the registry cannot be read, and an empty list when
     * it was read and holds nothing (Townstead 0.8.0 itself authors no cultures, for example).
     */
    default Optional<List<String>> registryIds(Registry registry) {
        return Optional.empty();
    }

    /** The phase of a villager's current hangout visit ({@code traveling}, {@code present}, ...). */
    default Optional<String> hangoutPhase(Entity villager) {
        return Optional.empty();
    }

    /**
     * Subscribes to Townstead's change events; each is copied into a scalar signal. {@code residence} reads the MCA:
     * Kingdoms residence of a villager an event names, while the event fires. The registration must be closed.
     */
    default Registration subscribe(MinecraftServer server, Consumer<TownsteadSignal> sink, java.util.function.Function<Entity, UUID> residence) {
        return () -> { };
    }

    enum Registry { CULTURES, FOUNDING_PROFILES, PROFESSIONS, SKILLS, REACTIONS, NEEDS, ROOTS }

    /** {@code version} is the installed Townstead; {@code generation}/{@code revision} are api.v1's, or -1. */
    record Info(String version, int generation, int revision, String detail) {
        public Info {
            version = version == null ? "absent" : version;
            detail = detail == null ? "" : detail;
        }
    }

    /** One building as a backend read it, before it is mapped to a MCA: Kingdoms settlement. */
    record RawBuilding(int villageId, int id, String type, String family, int tier, int size, BlockPos center, BlockPos min,
                       BlockPos max, TownsteadBuildingEvidence.Completion completion, TownsteadBuildingEvidence.Source source) {
        public RawBuilding {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            Objects.requireNonNull(completion, "completion");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * A village's building list with how it was read. Only {@code OK} is evidence about which buildings exist; a
     * {@code FAILED} or {@code INCONSISTENT} read must never be treated as an empty village.
     */
    record BuildingRead(Status status, List<RawBuilding> buildings, String detail) {
        public enum Status { OK, VILLAGE_ABSENT, INCONSISTENT, FAILED, UNSUPPORTED }

        public BuildingRead {
            Objects.requireNonNull(status, "status");
            buildings = List.copyOf(Objects.requireNonNull(buildings, "buildings"));
            detail = detail == null ? "" : detail;
        }

        public static BuildingRead of(Status status, String detail) {
            return new BuildingRead(status, List.of(), detail);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    /** Townstead's aggregate for one village, unchanged; {@code VillageLife} interprets it. */
    record NeedsSummary(int residents, int known, int loaded, int collapsed, long oldestSampleWorldDay, long worldDay,
                        Map<String, NeedStat> stats) {
        public NeedsSummary {
            stats = Map.copyOf(stats);
        }
    }

    /** {@code band} is Townstead's mean-fraction band, meaningless for temperature. */
    record NeedStat(String needId, int sampled, double mean, int min, int max, int inCrisis, String band) {
    }

    record ResidentRecord(UUID uuid, String name, Optional<Integer> villageId, Optional<ResourceLocation> dimension,
                          String professionId, int professionTier, Map<String, TownsteadNeedLevelView> levels, boolean loaded,
                          long lastSeenWorldDay, boolean alive) {
    }

    record Profession(String id, boolean progressive, int maxTier, List<String> aliases, boolean hidden) {
        public Profession {
            aliases = List.copyOf(aliases);
        }
    }

    record Worksite(long id, ResourceLocation binding, BlockPos anchor, String name, Optional<UUID> driver, int orderCount) {
    }

    /** {@code position} is the order's place in the worksite's list, never a stable identity. */
    record Order(long worksite, int position, ResourceLocation output, Optional<ResourceLocation> product, String productName,
                 String kind, String mode, int target, String scope, boolean paused, Optional<ResourceLocation> profession,
                 int minRank, Optional<UUID> villager) {
    }

    record DigestPage(List<DigestEntry> entries, boolean hasMore, long next, Map<String, Integer> categoryCounts) {
        public DigestPage {
            entries = List.copyOf(entries);
            categoryCounts = Map.copyOf(categoryCounts);
        }
    }

    record DigestEntry(long worldDay, long eventId, String templateId, String headline, String headlineKey, Map<String, String> params) {
        public DigestEntry {
            params = Map.copyOf(params);
        }
    }

    /** What happened to one emission: a story, no template produced one, or the capability is unavailable. */
    enum Emission { STORY, NO_STORY, UNAVAILABLE }

    record Faction(ResourceLocation id, ResourceLocation kind, String name, int color, Optional<ResourceLocation> emblem,
                   String status, int settlements, Optional<ResourceLocation> parent, int members) {
    }

    /**
     * Townstead's native governance of a faction. {@code provider} names another mod governing it in Townstead's place;
     * Townstead still lists its native offices then, so a native head is never that mod's ruler.
     */
    record Governance(ResourceLocation faction, ResourceLocation form, Optional<UUID> head, int offices, int populatedOffices,
                      int holders, OptionalInt legitimacy, Optional<String> band, Optional<String> provider, int fingerprint) {
    }
}
