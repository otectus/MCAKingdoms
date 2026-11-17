package dev.otectus.mcakingdoms.api.townstead;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MCA: Kingdoms' view of Townstead (and of MCA's own building list, which needs no Townstead). Every read is total and
 * server-thread only: an empty answer means "no evidence", never "it does not exist" unless a method says so. Methods
 * added after 0.2.x are default methods, so implementations and callers compiled against the earlier interface keep
 * working; see {@code docs/Addon-API.md}.
 */
public interface TownsteadService {
    int apiVersion();

    Set<TownsteadCapability> capabilities();

    Map<TownsteadCapability, String> diagnostics();

    Optional<TownsteadVillagerView> villager(Entity entity);

    Optional<TownsteadCalendarView> calendar();

    Optional<TownsteadBuildingView> buildingAt(ServerLevel level, BlockPos pos);

    /**
     * The buildings of every MCA village the settlement holds in this level (its own and the ones kept from merged
     * records), each with its original village id. Directly queries MCA's loaded village data or Townstead's list.
     */
    List<TownsteadBuildingView> buildings(ServerLevel level, UUID settlementId);

    Optional<TownsteadOriginView> origin(ResourceLocation id);

    Optional<TownsteadGeneView> gene(ResourceLocation id);

    Optional<TownsteadSpiritView> spirit(ServerLevel level, UUID settlementId);

    default BoundCivicBuilding bind(TownsteadBuildingView building) {
        return BoundCivicBuilding.from(building);
    }

    BuildingRecoveryResult recover(ServerLevel level, BoundCivicBuilding binding, BuildingRecoveryPolicy policy);

    boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> playerCause,
                         ResourceLocation reactionId, Set<String> contextTags);

    long revision();

    // ---- Added in 0.3.0 (Townstead 0.8.0 api.v1). ----

    /** The backend answering: {@code v1} (Townstead api.v1), {@code legacy} (0.7.x), {@code mca} (MCA only) or {@code none}. */
    default String backend() {
        return "legacy";
    }

    /** Every capability with its state and reason; a bound capability whose world system is off reads {@code DISABLED}. */
    default Map<TownsteadCapability, TownsteadCapabilityState> capabilityStates() {
        Map<TownsteadCapability, TownsteadCapabilityState> states = new EnumMap<>(TownsteadCapability.class);
        Set<TownsteadCapability> bound = capabilities();
        Map<TownsteadCapability, String> reasons = diagnostics();
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            states.put(capability, new TownsteadCapabilityState(bound.contains(capability)
                    ? TownsteadCapabilityState.Status.READY : TownsteadCapabilityState.Status.UNSUPPORTED,
                    backend(), reasons.getOrDefault(capability, "")));
        }
        // In declaration order, so diagnostics list capabilities the same way every time.
        return Collections.unmodifiableMap(states);
    }

    /** {@link #buildings} with completion and the backend that read them. */
    default List<TownsteadBuildingEvidence> buildingEvidence(ServerLevel level, UUID settlementId) {
        return buildings(level, settlementId).stream().map(building -> new TownsteadBuildingEvidence(building,
                TownsteadBuildingEvidence.Completion.UNKNOWN, TownsteadBuildingEvidence.Source.TOWNSTEAD_LEGACY)).toList();
    }

    /** {@link #buildingAt} with completion; Townstead's own point query decides membership when it is installed. */
    default Optional<TownsteadBuildingEvidence> buildingEvidenceAt(ServerLevel level, BlockPos pos) {
        return buildingAt(level, pos).map(building -> new TownsteadBuildingEvidence(building,
                TownsteadBuildingEvidence.Completion.UNKNOWN, TownsteadBuildingEvidence.Source.TOWNSTEAD_LEGACY));
    }

    /** What can be said about a bound building now, without rebinding anything. */
    default TownsteadBuildingAvailability availability(ServerLevel level, BoundCivicBuilding binding) {
        if (!capabilities().contains(TownsteadCapability.READ_BUILDINGS)) return TownsteadBuildingAvailability.PROVIDER_UNAVAILABLE;
        return buildings(level, binding.settlementId()).stream().anyMatch(b -> b.villageId() == binding.villageId()
                && b.buildingId() == binding.buildingId()) ? TownsteadBuildingAvailability.CURRENT : TownsteadBuildingAvailability.UNKNOWN;
    }

    /**
     * Complete buildings of the binding's family in its settlement, at least {@code minimumTier}, lowest id first, for an
     * explicit replacement choice. Nothing is rebound; the caller shows the candidate and applies its own use rules.
     */
    default List<TownsteadBuildingEvidence> replacementCandidates(ServerLevel level, BoundCivicBuilding binding, int minimumTier) {
        return List.of();
    }

    /** The MCA villages the settlement holds, as Townstead sees them. */
    default List<TownsteadVillageView> villages(UUID settlementId) {
        return List.of();
    }

    /** The settlement's welfare overview, shared by every viewer and refreshed on Townstead's change events. */
    default Optional<TownsteadVillageLifeView> villageLife(UUID settlementId) {
        return Optional.empty();
    }

    /** One loaded villager's needs on their own scales. */
    default Optional<TownsteadVillagerNeedsView> villagerNeeds(Entity entity) {
        return Optional.empty();
    }

    /** An entity's Townstead career, villager or player. */
    default Optional<TownsteadCareerView> career(Entity entity) {
        return Optional.empty();
    }

    /**
     * Whether the world runs a Townstead system ({@code careers}, {@code politics}, ...). Empty when unknown: no settings
     * capability, or an id Townstead does not list (Townstead itself reads unknown ids as on).
     */
    default Optional<Boolean> systemEnabled(String system) {
        return Optional.empty();
    }
}
