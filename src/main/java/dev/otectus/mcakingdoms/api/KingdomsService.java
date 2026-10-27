package dev.otectus.mcakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Settlements, kingdoms and civic identity for one running server. Obtain it with {@link McaKingdomsApi#get} once
 * MCA: Kingdoms has attached it while the server starts (it is ready from {@code ServerStartedEvent}, and is detached
 * when the server stops); do not keep it past that server.
 *
 * <p><b>Threading.</b> Every method must be called on that server's thread; any other thread gets
 * {@link IllegalStateException}. <b>Errors.</b> Mutations refuse with {@link IllegalArgumentException} (an unknown
 * record, a locked name or kingdom, a refused provider preflight, a bound out of range) and change nothing when they
 * refuse. <b>Views.</b> Returned views are immutable snapshots of the moment of the call; read again after a change.
 *
 * <p><b>Visibility.</b> This is a trusted server API: it returns every record, including settlements a given player
 * has not discovered. Filter by the player's knowledge yourself before showing results to players; MCA: Kingdoms'
 * own screens and tasks do that for their players.
 *
 * <p><b>Events.</b> The Forge events under {@code dev.otectus.mcakingdoms.api.event} report changes after they are
 * made in memory. They are notifications, not durable receipts: they do not prove that a save completed, that a
 * reward was delivered, or that another mod acted on them.
 */
public interface KingdomsService {
    /**
     * The kingdom with this id. A kingdom that settlements still reference but whose definition is no longer loaded is
     * returned with {@link KingdomView#defined()} false; an id nothing uses is empty.
     */
    Optional<KingdomView> getKingdom(ResourceLocation id);

    /** Every defined kingdom, followed by retained ids whose definitions are unavailable. */
    Collection<KingdomView> getKingdoms();

    /** The settlement with this id; a retired id (see {@link #resolveSettlementId}) finds the record it was merged into. */
    Optional<SettlementView> getSettlement(UUID id);

    /**
     * The settlement {@code settlementId} denotes now. A merge retires the merged record's id, which from then on
     * resolves to the record it was merged into; any other id is returned unchanged. Compare stored settlement ids
     * through this, never raw.
     */
    default UUID resolveSettlementId(UUID settlementId) {
        return getSettlement(settlementId).map(SettlementView::id).orElse(settlementId);
    }

    /** The settlement holding this provider reference, including references kept from merged records. */
    Optional<SettlementView> getSettlementByExternalRef(String namespace, String value);

    default Optional<SettlementView> getSettlementForMcaVillage(ResourceLocation dimension, int villageId) {
        McaCommunityRef reference = new McaCommunityRef(dimension, villageId);
        return getSettlementByExternalRef(McaCommunityRef.EXTERNAL_REF_NAMESPACE, reference.format());
    }

    default Optional<SettlementView> getSettlementByMcaVillage(ResourceLocation dimension, int villageId) {
        return getSettlementForMcaVillage(dimension, villageId);
    }

    /** The settlement whose bounds contain {@code pos} in that level, the nearest anchor winning an overlap. Loads nothing. */
    Optional<SettlementView> getSettlementAt(ServerLevel level, BlockPos pos);

    /** Every settlement of a kingdom, in a stable order. Unbounded; prefer {@link #getSettlementPage} for listings. */
    Collection<SettlementView> getSettlements(ResourceLocation kingdomId);

    /**
     * A stable-ordered page of settlements, all of them or one kingdom's.
     *
     * @throws IllegalArgumentException if {@code offset} is negative or {@code limit} is not from 1 to 64
     */
    List<SettlementView> getSettlementPage(Optional<ResourceLocation> kingdomId, int offset, int limit);

    /**
     * Looks a settlement up by UUID, slug (with or without namespace; a pre-rename {@code ultima_kingdoms:} slug still
     * works), display name or historical alias, ignoring case and spacing.
     *
     * @throws IllegalArgumentException if the query is blank or matches more than one settlement
     */
    Optional<SettlementView> findSettlement(String query);

    /** The settlement recorded as the entity's civic residence, if any. */
    Optional<SettlementView> getResidence(Entity entity);

    /** All provider aliases, including references retained through merges. */
    default java.util.Map<String, java.util.Set<String>> getSettlementExternalRefs(UUID settlementId) {
        java.util.Map<String, java.util.Set<String>> refs = new java.util.LinkedHashMap<>();
        getSettlement(settlementId).ifPresent(v -> v.externalRefs().forEach((key, value) -> refs.put(key, java.util.Set.of(value))));
        return java.util.Map.copyOf(refs);
    }

    /** The entity's recorded civic origin and residence, if MCA: Kingdoms has recorded either. */
    Optional<CivicIdentityView> getCivicIdentity(Entity entity);

    /**
     * Dialogue and quest context values for the entity ({@code mcakingdoms.kingdom}, {@code mcakingdoms.village},
     * {@code mcakingdoms.origin_kingdom} and their {@code _id} forms); empty without a civic identity.
     */
    Map<String, String> getContext(Entity entity);

    /** Increases whenever settlement records or definitions change; compare it to notice that cached views are stale. */
    long revision();

    /**
     * Registers a detected or manual settlement, or returns the existing settlement the candidate belongs to: a candidate
     * inside a known generated village, or matching an existing record, yields that record, so repeating the call is safe.
     *
     * @throws IllegalArgumentException if the candidate's dimension is not {@code level}'s
     */
    SettlementView registerCandidate(ServerLevel level, SettlementCandidate candidate);

    /**
     * Renames a settlement; the old name stays searchable as an alias. Renaming to the current name changes nothing.
     *
     * @throws IllegalArgumentException if the settlement is unknown, its name is locked, the name is blank or longer than
     *                                  80 characters, or another settlement already uses it as a name or alias
     */
    SettlementView rename(UUID settlementId, String newName);

    /**
     * Assigns a settlement to a defined kingdom. Other mods may refuse through
     * {@link dev.otectus.mcakingdoms.api.event.SettlementMutationPreflightEvent}, for example a capital seat.
     *
     * @throws IllegalArgumentException if the settlement or kingdom is unknown, the kingdom is locked, or a preflight refuses
     */
    SettlementView setKingdom(UUID settlementId, ResourceLocation kingdomId);

    /**
     * Runs the current biome and structure-style rules again and applies the result.
     *
     * @throws IllegalArgumentException if the settlement is unknown, its kingdom is locked, its dimension is not loaded,
     *                                  or a preflight refuses the resulting change
     */
    SettlementView reclassify(UUID settlementId);

    /** Locks or unlocks automatic renaming and kingdom changes. Setting the current values changes nothing. */
    SettlementView setLocks(UUID settlementId, boolean nameLocked, boolean kingdomLocked);

    /**
     * Folds {@code sourceId} into {@code targetId}: the source's id and slug resolve to the target from then on, its name
     * becomes an alias, and its provider references move to the target.
     *
     * @throws IllegalArgumentException if either is unknown, they are the same record or in different dimensions, an
     *                                  external reference conflicts, or a preflight refuses (both records stay unchanged)
     */
    SettlementView merge(UUID sourceId, UUID targetId);

    /**
     * Runs every registered detector over loaded chunks around {@code center} and registers what they find (at most 256
     * candidates per detector). Never loads or generates chunks.
     *
     * @throws IllegalArgumentException if {@code radiusChunks} is not from 0 to 32
     */
    Collection<SettlementView> discover(ServerLevel level, BlockPos center, int radiusChunks);

    /**
     * Records where an entity comes from. It does not change the entity's MCA home, family or name.
     *
     * @throws IllegalArgumentException if the settlement is unknown or the entity is not on this server
     */
    CivicIdentityView setOrigin(Entity entity, UUID settlementId);

    /**
     * Records where an entity lives; the first residence also becomes its origin when none is recorded. Resident
     * founding and civic services read it. Setting the current residence changes nothing.
     *
     * @throws IllegalArgumentException if the settlement is unknown or the entity is not on this server
     */
    CivicIdentityView setResidence(Entity entity, UUID settlementId);

    /**
     * Adds a settlement detector under {@code owner}; close the returned registration (on the server thread) before the
     * server stops. Registrations do not survive a restart.
     *
     * @throws IllegalArgumentException if {@code owner} already has a detector
     */
    Registration registerSettlementDetector(ResourceLocation owner, SettlementDetector detector);

    /** Adds a kingdom resolver under {@code owner}, with the same lifetime rules as {@link #registerSettlementDetector}. */
    Registration registerKingdomResolver(ResourceLocation owner, KingdomResolver resolver);

    /** Adds a civic evidence provider under {@code owner}, with the same lifetime rules as {@link #registerSettlementDetector}. */
    Registration registerCivicEvidenceProvider(ResourceLocation owner, CivicEvidenceProvider provider);

    /** Adds a source of generated-village footprints, consulted after the built-in chunk-reference reader. */
    Registration registerVillageFootprintSource(ResourceLocation owner, VillageFootprintSource source);
}
