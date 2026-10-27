package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.api.ChangeReason;
import dev.otectus.mcakingdoms.api.CivicEvidenceProvider;
import dev.otectus.mcakingdoms.api.CivicIdentityHint;
import dev.otectus.mcakingdoms.api.CivicIdentitySource;
import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.GeneratedName;
import dev.otectus.mcakingdoms.api.KingdomResolution;
import dev.otectus.mcakingdoms.api.KingdomResolver;
import dev.otectus.mcakingdoms.api.KingdomView;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementDetector;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.api.VillageFootprintSource;
import dev.otectus.mcakingdoms.api.event.CivicIdentityChangedEvent;
import dev.otectus.mcakingdoms.api.event.KingdomDefinitionsReloadedEvent;
import dev.otectus.mcakingdoms.api.event.ResidentJoinedEvent;
import dev.otectus.mcakingdoms.api.event.ResidentLeftEvent;
import dev.otectus.mcakingdoms.api.event.SettlementCreatedEvent;
import dev.otectus.mcakingdoms.api.event.SettlementDiscoveredEvent;
import dev.otectus.mcakingdoms.api.event.SettlementKingdomChangedEvent;
import dev.otectus.mcakingdoms.api.event.SettlementRenamedEvent;
import dev.otectus.mcakingdoms.citizen.CivicIdentityStore;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import dev.otectus.mcakingdoms.data.DefinitionRegistry;
import dev.otectus.mcakingdoms.naming.VillageNameGenerator;
import dev.otectus.mcakingdoms.settlement.PoiClusterDetector;
import dev.otectus.mcakingdoms.settlement.SpatialSettlementIndex;
import dev.otectus.mcakingdoms.settlement.VillageFootprints;
import dev.otectus.mcakingdoms.settlement.VillageStructureDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class KingdomsServiceImpl implements KingdomsService {
    private static final ResourceLocation BUILTIN_POI = new ResourceLocation(McaKingdomsApi.MOD_ID, "poi_cluster");
    private static final ResourceLocation BUILTIN_STRUCTURE = new ResourceLocation(McaKingdomsApi.MOD_ID, "village_structure");
    private static final ResourceLocation MANUAL_SOURCE = new ResourceLocation(McaKingdomsApi.MOD_ID, "manual");
    private static final ResourceLocation UNKNOWN_BIOME = new ResourceLocation("minecraft", "the_void");
    /** Datapack-extensible allow list for automatic residence, beside vanilla villagers. */
    private static final net.minecraft.tags.TagKey<net.minecraft.world.entity.EntityType<?>> CIVIC_RESIDENTS =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
                    new ResourceLocation(McaKingdomsApi.MOD_ID, "civic_residents"));
    private static final long OBSERVATION_WRITE_INTERVAL = 1_200L;
    /** MCA's external-reference namespace; see {@code McaAccess.EXTERNAL_REF}. */
    private static final String MCA_REF = "mca";
    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private boolean validatingMutation;
    private final MinecraftServer server;
    private final DefinitionRegistry definitions;
    private final SettlementSavedData data;
    private final SpatialSettlementIndex spatialIndex = new SpatialSettlementIndex();
    private final OrderedSettlements ordered = new OrderedSettlements();
    private final CivicIdentityStore civicStore = new CivicIdentityStore();
    private final Map<ResourceLocation, SettlementDetector> detectors = new LinkedHashMap<>();
    private final Map<ResourceLocation, KingdomResolver> resolvers = new LinkedHashMap<>();
    private final Map<ResourceLocation, CivicEvidenceProvider> evidenceProviders = new LinkedHashMap<>();
    private final Map<ResourceLocation, VillageFootprintSource> footprintSources = new LinkedHashMap<>();
    private final VillageFootprints footprints = new VillageFootprints(() -> Map.copyOf(footprintSources));
    /** Per external-reference namespace: the references that still exist in a level, when the provider can say. */
    private final Map<String, Function<ServerLevel, Optional<Set<String>>>> liveReferences = new LinkedHashMap<>();
    private final SettlementReconciler reconciler;
    private SettlementReconciler.Startup startup;
    /** The finished start-up pass of this server run, for health reports; zero when none ran. */
    private int lastStartupExamined, lastStartupMerged, lastStartupReads;
    private long lastStartupElapsed, startupBegan;
    private final Deque<QueuedChunk> chunkQueue = new ArrayDeque<>();
    private final Set<QueuedChunk> queuedChunks = new LinkedHashSet<>();

    public KingdomsServiceImpl(MinecraftServer server, DefinitionRegistry definitions) {
        this.server = Objects.requireNonNull(server, "server");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.data = SettlementSavedData.get(server);
        spatialIndex.rebuild(data.records().stream().map(SettlementRecord::snapshot).toList());
        detectors.put(BUILTIN_STRUCTURE, new VillageStructureDetector());
        detectors.put(BUILTIN_POI, new PoiClusterDetector(footprints));
        reconciler = new SettlementReconciler(data, new SettlementReconciler.Mutations() {
            @Override
            public void merge(SettlementRecord source, SettlementRecord target) {
                KingdomsServiceImpl.this.merge(source.id, target.id, true);
            }

            @Override
            public void adopt(SettlementRecord record, VillageFootprint village) {
                SettlementSnapshot before = record.snapshot();
                SettlementReconciler.attach(data, record, village, maxSpan());
                spatialIndex.remove(before);
                spatialIndex.add(record.snapshot());
            }
        });
    }

    @Override
    public Map<String, Set<String>> getSettlementExternalRefs(UUID settlementId) {
        requireServerThread();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        data.get(settlementId).ifPresent(record -> record.externalRefValues.forEach((key, values) -> result.put(key, Set.copyOf(values))));
        return Map.copyOf(result);
    }

    @Override
    public Optional<KingdomView> getKingdom(ResourceLocation id) {
        requireServerThread();
        ResourceLocation checked = Objects.requireNonNull(id, "id");
        Optional<KingdomView> defined = definitions.snapshot().kingdom(checked);
        if (defined.isPresent()) return defined;
        return data.records().stream().anyMatch(record -> record.kingdomId.equals(checked))
                ? Optional.of(new MissingKingdomView(checked)) : Optional.empty();
    }

    @Override
    public Collection<KingdomView> getKingdoms() {
        requireServerThread();
        Map<ResourceLocation, KingdomView> kingdoms = new LinkedHashMap<>();
        definitions.snapshot().kingdoms().forEach(kingdom -> kingdoms.put(kingdom.id(), kingdom));
        data.records().stream().map(record -> record.kingdomId).distinct()
                .forEach(id -> kingdoms.putIfAbsent(id, new MissingKingdomView(id)));
        return List.copyOf(kingdoms.values());
    }

    @Override
    public Optional<SettlementView> getSettlement(UUID id) {
        requireServerThread();
        return snapshot(id).map(value -> (SettlementView) value);
    }

    @Override
    public UUID resolveSettlementId(UUID settlementId) {
        requireServerThread();
        return data.resolveId(Objects.requireNonNull(settlementId, "settlementId"));
    }

    @Override
    public Optional<SettlementView> getSettlementByExternalRef(String namespace, String value) {
        requireServerThread();
        return data.getByExternalRef(namespace, value).map(record -> (SettlementView) record.snapshot());
    }

    @Override
    public Optional<SettlementView> getSettlementAt(ServerLevel level, BlockPos pos) {
        requireServerThread();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        return spatialIndex.at(level.dimension(), pos).stream()
                .map(data::get)
                .flatMap(Optional::stream)
                .filter(record -> record.dimension.equals(level.dimension()) && record.bounds.contains(pos))
                .min(Comparator.comparingInt(record -> distanceSquared(record.anchor, pos)))
                .map(SettlementRecord::snapshot);
    }

    @Override
    public Collection<SettlementView> getSettlements(ResourceLocation kingdomId) {
        requireServerThread();
        Objects.requireNonNull(kingdomId, "kingdomId");
        return ordered.of(data, Optional.of(kingdomId));
    }

    @Override
    public List<SettlementView> getSettlementPage(Optional<ResourceLocation> kingdomId, int offset, int limit) {
        requireServerThread();
        Objects.requireNonNull(kingdomId, "kingdomId");
        if (offset < 0) throw new IllegalArgumentException("Settlement page offset must be non-negative");
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("Settlement page limit must be from 1 to 64");
        return ordered.page(data, kingdomId, offset, limit);
    }

    /**
     * Every settlement of {@code kingdom} (or all) in page order, from the snapshot built once per store revision (F13).
     * For server-side searches and selectors that need the whole set; viewer filtering is the caller's.
     */
    public List<SettlementView> orderedSettlements(Optional<ResourceLocation> kingdom) {
        requireServerThread();
        return ordered.of(data, kingdom);
    }

    @Override
    public Optional<SettlementView> findSettlement(String query) {
        requireServerThread();
        String value = Objects.requireNonNull(query, "query").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Settlement query must not be blank");
        try {
            Optional<SettlementRecord> byUuid = data.get(UUID.fromString(value));
            if (byUuid.isPresent()) return Optional.of(byUuid.get().snapshot());
        } catch (IllegalArgumentException ignored) {
            // Continue with slug and human-readable lookup.
        }

        // A slug written before the 0.1.1 rename (ultima_kingdoms:<name>) resolves to the migrated one.
        ResourceLocation parsedSlug = ResourceLocation.tryParse(value.contains(":")
                ? dev.otectus.mcakingdoms.persistence.LegacyRename.rewrite(value)
                : McaKingdomsApi.MOD_ID + ":" + value.toLowerCase(Locale.ROOT));
        List<SettlementRecord> matches = data.records().stream()
                .filter(record -> (parsedSlug != null
                        && (record.slug.equals(parsedSlug) || record.retiredSlugs.contains(parsedSlug)))
                        || VillageNameGenerator.normalize(record.displayName)
                                .equals(VillageNameGenerator.normalize(value))
                        || record.aliases.stream().anyMatch(alias -> VillageNameGenerator.normalize(alias)
                                .equals(VillageNameGenerator.normalize(value))))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Settlement name is ambiguous: " + value);
        }
        return matches.stream().findFirst().map(SettlementRecord::snapshot);
    }

    @Override
    public Optional<SettlementView> getResidence(Entity entity) {
        requireServerThread();
        return identity(entity).flatMap(CivicIdentitySnapshot::residenceSettlement).flatMap(this::snapshot)
                .map(value -> (SettlementView) value);
    }

    @Override
    public Optional<CivicIdentityView> getCivicIdentity(Entity entity) {
        requireServerThread();
        return identity(entity).map(value -> (CivicIdentityView) value);
    }

    @Override
    public Map<String, String> getContext(Entity entity) {
        requireServerThread();
        Optional<CivicIdentitySnapshot> identity = identity(entity);
        if (identity.isEmpty()) return Map.of();
        Map<String, String> context = new LinkedHashMap<>();
        identity.get().residenceSettlement().flatMap(this::snapshot).ifPresent(settlement -> {
            context.put("mcakingdoms.kingdom", displayId(settlement.kingdomId()));
            context.put("mcakingdoms.kingdom_id", settlement.kingdomId().toString());
            context.put("mcakingdoms.village", settlement.displayName());
            context.put("mcakingdoms.village_id", settlement.id().toString());
            context.put("mcakingdoms.residence_kingdom", displayId(settlement.kingdomId()));
            context.put("mcakingdoms.residence_kingdom_id", settlement.kingdomId().toString());
            context.put("mcakingdoms.residence_village", settlement.displayName());
            context.put("mcakingdoms.residence_village_id", settlement.id().toString());
        });
        identity.get().originKingdom().ifPresent(kingdom -> {
            context.put("mcakingdoms.origin_kingdom", displayId(kingdom));
            context.put("mcakingdoms.origin_kingdom_id", kingdom.toString());
        });
        identity.get().originSettlement().flatMap(this::snapshot).ifPresent(settlement -> {
            context.put("mcakingdoms.origin_village", settlement.displayName());
            context.put("mcakingdoms.origin_village_id", settlement.id().toString());
        });
        return Map.copyOf(context);
    }

    @Override
    public long revision() {
        requireServerThread();
        return data.revision() + definitions.snapshot().revision();
    }

    @Override
    public SettlementView registerCandidate(ServerLevel level, SettlementCandidate offered) {
        if (validatingMutation) throw new IllegalStateException("Mutation during settlement preflight");
        requireServerThread();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(offered, "candidate");
        if (!level.dimension().equals(offered.dimension())) {
            throw new IllegalArgumentException("Candidate dimension does not match the supplied level");
        }
        // One village, one identity: a candidate inside a generated village resolves to that village's structure key.
        Resolution resolution = structureIdentity() ? normalise(level, offered) : new Resolution(offered, Optional.empty());
        SettlementCandidate candidate = resolution.candidate();
        Optional<String> village = structureIdentity() ? VillageFootprints.villageKey(candidate) : Optional.empty();
        if (village.isPresent() && reconciling()) claim(level, candidate, village.get(), resolution.village());
        Optional<SettlementRecord> matched = match(candidate, village);
        if (matched.isPresent()) {
            observe(matched.get(), village.isPresent() ? withoutForeignRefs(candidate, matched.get().id) : candidate,
                    level.getGameTime());
            return matched.get().snapshot();
        }
        if (village.isPresent()) candidate = withoutForeignRefs(candidate, null);

        SettlementCandidate created = candidate;
        KingdomResolution kingdom = resolve(level, created);
        if (definitions.snapshot().kingdom(kingdom.kingdomId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown kingdom: " + kingdom.kingdomId());
        }
        GeneratedName name = created.proposedName()
                .map(this::proposedName)
                .orElseGet(() -> definitions.snapshot().generateName(level.getSeed(), created,
                        kingdom.kingdomId(), this::normalizedNameTaken, this::slugTaken));
        if (normalizedNameTaken(name.normalizedKey()) || slugTaken(name.slug())) {
            throw new IllegalArgumentException("Generated settlement name is not unique: " + name.displayName());
        }

        SettlementRecord record = new SettlementRecord();
        record.id = UUID.randomUUID();
        record.dimension = created.dimension();
        record.anchor = created.anchor();
        record.radius = created.radius();
        record.bounds = created.bounds();
        record.kingdomId = kingdom.kingdomId();
        record.displayName = name.displayName();
        record.slug = name.slug();
        record.biomeAtCreation = kingdom.decisiveBiome().orElseGet(() -> biomeAt(level, created.anchor()));
        record.styleId = created.styleId().orElse(null);
        record.assignmentSource = kingdom.source();
        record.detectionSource = created.detectionSource();
        record.createdGameTime = level.getGameTime();
        record.lastObservedGameTime = level.getGameTime();
        created.externalRefs().forEach(record::addExternalRef);
        record.assignmentTrace.addAll(kingdom.trace());
        record.sourceId = created.sourceId();
        record.sourceKey = created.sourceKey();
        record.addDetectorIdentity(created.sourceId(), created.sourceKey());
        if (created.detectionSource() == DetectionSource.STRUCTURE) {
            record.addStrongStructureIdentity(created.sourceId(), created.sourceKey());
        }
        // The first record of a village holds its key, so every later detector's candidate matches it by identity.
        village.ifPresent(key -> record.addStrongStructureIdentity(SettlementReconciler.BUILTIN_STRUCTURE, key));
        data.add(record);
        SettlementSnapshot snapshot = record.snapshot();
        spatialIndex.add(snapshot);
        MinecraftForge.EVENT_BUS.post(new SettlementCreatedEvent(snapshot));
        MinecraftForge.EVENT_BUS.post(new SettlementDiscoveredEvent(snapshot, created.sourceId()));
        return snapshot;
    }

    @Override
    public SettlementView rename(UUID settlementId, String newName) {
        if (validatingMutation) throw new IllegalStateException("Mutation during settlement preflight");
        requireServerThread();
        SettlementRecord record = requireSettlement(settlementId);
        if (record.nameLocked) throw new IllegalArgumentException("Settlement name is locked: " + record.displayName);
        String clean = requireName(newName);
        String normalized = VillageNameGenerator.normalize(clean);
        if (data.records().stream().anyMatch(other -> other != record
                && (VillageNameGenerator.normalize(other.displayName).equals(normalized)
                || other.aliases.stream().anyMatch(alias -> VillageNameGenerator.normalize(alias).equals(normalized))))) {
            throw new IllegalArgumentException("Settlement name is already in use: " + clean);
        }
        if (record.displayName.equals(clean)) return record.snapshot();
        String oldName = record.displayName;
        addAlias(record, oldName);
        record.displayName = clean;
        data.changed(record);
        SettlementSnapshot snapshot = record.snapshot();
        MinecraftForge.EVENT_BUS.post(new SettlementRenamedEvent(snapshot, oldName, ChangeReason.API));
        return snapshot;
    }

    @Override
    public SettlementView setKingdom(UUID settlementId, ResourceLocation kingdomId) {
        return setKingdom(settlementId, kingdomId, ChangeReason.API);
    }

    @Override
    public SettlementView reclassify(UUID settlementId) {
        if (validatingMutation) throw new IllegalStateException("Mutation during settlement preflight");
        requireServerThread();
        SettlementRecord record = requireSettlement(settlementId);
        if (record.kingdomLocked) throw new IllegalArgumentException("Settlement kingdom is locked: " + record.displayName);
        ServerLevel level = server.getLevel(record.dimension);
        if (level == null) throw new IllegalArgumentException("Settlement dimension is unavailable: " + record.dimension.location());
        SettlementCandidate candidate = asCandidate(record);
        KingdomResolution resolution = resolve(level, candidate);
        if (definitions.snapshot().kingdom(resolution.kingdomId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown kingdom: " + resolution.kingdomId());
        }
        ResourceLocation oldKingdom = record.kingdomId;
        if (!oldKingdom.equals(resolution.kingdomId())) preflight(record, null, resolution.kingdomId());
        record.kingdomId = resolution.kingdomId();
        record.assignmentSource = resolution.source();
        record.assignmentTrace.clear();
        record.assignmentTrace.addAll(resolution.trace());
        data.changed(record);
        SettlementSnapshot snapshot = record.snapshot();
        if (!oldKingdom.equals(record.kingdomId)) {
            MinecraftForge.EVENT_BUS.post(new SettlementKingdomChangedEvent(snapshot, oldKingdom,
                    record.kingdomId, ChangeReason.RECLASSIFICATION));
        }
        return snapshot;
    }

    @Override
    public SettlementView setLocks(UUID settlementId, boolean nameLocked, boolean kingdomLocked) {
        if (validatingMutation) throw new IllegalStateException("Mutation during settlement preflight");
        requireServerThread();
        SettlementRecord record = requireSettlement(settlementId);
        if (record.nameLocked == nameLocked && record.kingdomLocked == kingdomLocked) return record.snapshot();
        record.nameLocked = nameLocked;
        record.kingdomLocked = kingdomLocked;
        data.changed(record);
        return record.snapshot();
    }

    @Override
    public SettlementView merge(UUID sourceId, UUID targetId) {
        return merge(sourceId, targetId, false);
    }

    /**
     * Folds the source settlement into the target. Reconciliation merges ({@code carryLocks}) also carry a locked name
     * or kingdom over to an unlocked survivor (D3); the bounds become their union, capped like observed growth.
     */
    SettlementSnapshot merge(UUID sourceId, UUID targetId, boolean carryLocks) {
        if (validatingMutation) throw new IllegalStateException("Mutation during settlement preflight");
        requireServerThread();
        UUID resolvedSource = data.resolveId(Objects.requireNonNull(sourceId, "sourceId"));
        UUID resolvedTarget = data.resolveId(Objects.requireNonNull(targetId, "targetId"));
        if (resolvedSource.equals(resolvedTarget)) throw new IllegalArgumentException("Settlements already resolve to the same record");
        SettlementRecord source = requireSettlement(resolvedSource);
        SettlementRecord target = requireSettlement(resolvedTarget);
        if (!source.dimension.equals(target.dimension)) {
            throw new IllegalArgumentException("Cannot merge settlements in different dimensions");
        }
        data.validateMergeExternalRefs(source, target);
        SettlementReconciler.MergePlan plan = SettlementReconciler.MergePlan.of(source, target, carryLocks);
        // Carrying a kingdom over reassigns the survivor, so its own preflight (capital seat, charters, bindings) applies.
        if (plan.carryKingdom()) preflight(target, null, plan.kingdom());
        preflight(source, target, plan.kingdom());
        SettlementSnapshot retired = source.snapshot();
        SettlementSnapshot before = target.snapshot();
        spatialIndex.remove(retired);
        spatialIndex.remove(before);
        SettlementReconciler.combine(data, source, target, plan, maxSpan());
        SettlementSnapshot merged = target.snapshot();
        spatialIndex.add(merged);
        MinecraftForge.EVENT_BUS.post(new dev.otectus.mcakingdoms.api.event.SettlementMergedEvent(server, retired, merged));
        if (!before.displayName().equals(merged.displayName())) {
            MinecraftForge.EVENT_BUS.post(new SettlementRenamedEvent(merged, before.displayName(), ChangeReason.MERGE));
        }
        if (!before.kingdomId().equals(merged.kingdomId())) {
            MinecraftForge.EVENT_BUS.post(new SettlementKingdomChangedEvent(merged, before.kingdomId(), merged.kingdomId(),
                    ChangeReason.MERGE));
        }
        return merged;
    }

    /**
     * What merging {@code sourceId} into {@code targetId} would do, decided by the same checks the merge runs (dimension,
     * external references, provider preflight), without changing anything. Blockers are the preflight's own reasons.
     */
    public record MergePreview(SettlementView source, SettlementView target, ResourceLocation kingdom, List<String> blockers) {
        public MergePreview { blockers = List.copyOf(blockers); }
    }

    public MergePreview previewMerge(UUID sourceId, UUID targetId) {
        requireServerThread();
        SettlementRecord source = requireSettlement(data.resolveId(Objects.requireNonNull(sourceId, "sourceId")));
        SettlementRecord target = requireSettlement(data.resolveId(Objects.requireNonNull(targetId, "targetId")));
        List<String> blockers = new ArrayList<>();
        if (source.id.equals(target.id)) blockers.add("Both choices are already the same settlement record");
        else if (!source.dimension.equals(target.dimension)) blockers.add("Cannot merge settlements in different dimensions");
        else {
            try { data.validateMergeExternalRefs(source, target); } catch (IllegalArgumentException refused) { blockers.add(refused.getMessage()); }
            if (blockers.isEmpty()) {
                try { preflight(source, target, target.kingdomId); } catch (IllegalArgumentException refused) { blockers.add(refused.getMessage()); }
            }
        }
        return new MergePreview(source.snapshot(), target.snapshot(), target.kingdomId, blockers);
    }

    /** Why changing the settlement's kingdom would be refused now (lock or provider preflight), or empty. */
    public Optional<String> previewKingdomChange(UUID settlementId, ResourceLocation kingdomId) {
        requireServerThread();
        if (definitions.snapshot().kingdom(kingdomId).isEmpty()) return Optional.of("Unknown kingdom: " + kingdomId);
        SettlementRecord record = requireSettlement(settlementId);
        if (record.kingdomLocked) return Optional.of("Settlement kingdom is locked: " + record.displayName);
        if (record.kingdomId.equals(kingdomId)) return Optional.empty();
        try { preflight(record, null, kingdomId); return Optional.empty(); }
        catch (IllegalArgumentException refused) { return Optional.ofNullable(refused.getMessage()); }
    }

    /** Settlement records anchored in loaded chunks within the radius that reconciliation would examine now. */
    public int reconcileScope(ServerLevel level, BlockPos center, int radiusChunks) {
        requireServerThread();
        int reach = radiusChunks * 16 + 15;
        return (int) data.records().stream()
                .filter(record -> record.dimension.equals(level.dimension()) && !record.hasStrongStructureIdentities())
                .filter(record -> Math.abs(record.anchor.getX() - center.getX()) <= reach && Math.abs(record.anchor.getZ() - center.getZ()) <= reach)
                .filter(record -> level.hasChunk(record.anchor.getX() >> 4, record.anchor.getZ() >> 4))
                .count();
    }

    /**
     * Discovery and reconciliation activity for server health: work actually queued and done, never a progress estimate.
     * {@code startupRemaining} is -1 when no start-up pass is running.
     */
    public record ReconcileStatus(boolean structureIdentity, boolean autoReconcile, boolean onStartup, int savedVersion,
                                  int currentVersion, int startupRemaining, int startupExamined, int startupMerged,
                                  int startupChunkReads, long startupElapsedMillis, int queuedChunks, int refusals,
                                  int settlements) { }

    public ReconcileStatus reconcileStatus() {
        requireServerThread();
        int queued;
        synchronized (chunkQueue) { queued = chunkQueue.size(); }
        var running = startup;
        return new ReconcileStatus(structureIdentity(), McaKingdomsConfig.AUTO_RECONCILE.get(), McaKingdomsConfig.RECONCILE_ON_STARTUP.get(),
                data.reconciled(), SettlementSavedData.RECONCILIATION_VERSION,
                running == null ? -1 : running.remaining(), running == null ? lastStartupExamined : running.report().examined(),
                running == null ? lastStartupMerged : running.report().merged(), running == null ? lastStartupReads : running.report().chunkReads(),
                running == null ? lastStartupElapsed : System.currentTimeMillis() - startupBegan, queued, reconciler.refusalCount(),
                data.records().size());
    }

    @Override
    public Collection<SettlementView> discover(ServerLevel level, BlockPos center, int radiusChunks) {
        requireServerThread();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(center, "center");
        if (radiusChunks < 0 || radiusChunks > 32) {
            throw new IllegalArgumentException("Discovery radius must be from 0 to 32 chunks");
        }
        List<SettlementView> found = new ArrayList<>();
        detectors.forEach((owner, detector) -> detector.detect(level, center.immutable(), radiusChunks)
                .limit(256)
                .forEach(candidate -> found.add(registerCandidate(level, candidate))));
        return List.copyOf(found);
    }

    @Override
    public CivicIdentityView setOrigin(Entity entity, UUID settlementId) {
        requireEntity(entity);
        SettlementSnapshot settlement = snapshot(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement: " + settlementId));
        CivicIdentitySnapshot old = identity(entity).orElse(CivicIdentitySnapshot.empty());
        CivicIdentitySnapshot updated = new CivicIdentitySnapshot(Optional.of(settlement.id()),
                Optional.of(settlement.kingdomId()), old.residenceSettlement(), old.residenceKingdom(),
                CivicIdentitySource.COMMAND, old.lastResidenceChange());
        civicStore.write(entity, updated);
        MinecraftForge.EVENT_BUS.post(new CivicIdentityChangedEvent(entity.getUUID(), old, updated));
        return updated;
    }

    @Override
    public CivicIdentityView setResidence(Entity entity, UUID settlementId) {
        return setResidence(entity, settlementId, CivicIdentitySource.COMMAND);
    }

    private CivicIdentityView setResidence(Entity entity, UUID settlementId, CivicIdentitySource source) {
        requireEntity(entity);
        SettlementSnapshot settlement = snapshot(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement: " + settlementId));
        CivicIdentitySnapshot old = identity(entity).orElse(CivicIdentitySnapshot.empty());
        if (old.residenceSettlement().filter(settlement.id()::equals).isPresent()) return old;
        Optional<UUID> originSettlement = old.originSettlement().isPresent()
                ? old.originSettlement() : Optional.of(settlement.id());
        Optional<ResourceLocation> originKingdom = old.originKingdom().isPresent()
                ? old.originKingdom() : Optional.of(settlement.kingdomId());
        long gameTime = entity.level().getGameTime();
        CivicIdentitySnapshot updated = new CivicIdentitySnapshot(originSettlement, originKingdom,
                Optional.of(settlement.id()), Optional.of(settlement.kingdomId()), source,
                gameTime);
        civicStore.write(entity, updated);
        old.residenceSettlement().flatMap(this::snapshot).ifPresent(previous ->
                MinecraftForge.EVENT_BUS.post(new ResidentLeftEvent(entity.getUUID(), previous, updated)));
        MinecraftForge.EVENT_BUS.post(new ResidentJoinedEvent(entity.getUUID(), settlement, updated));
        MinecraftForge.EVENT_BUS.post(new CivicIdentityChangedEvent(entity.getUUID(), old, updated));
        return updated;
    }

    @Override
    public Registration registerSettlementDetector(ResourceLocation owner, SettlementDetector detector) {
        requireServerThread();
        return register(detectors, owner, detector, "settlement detector");
    }

    @Override
    public Registration registerKingdomResolver(ResourceLocation owner, KingdomResolver resolver) {
        requireServerThread();
        return register(resolvers, owner, resolver, "kingdom resolver");
    }

    @Override
    public Registration registerCivicEvidenceProvider(ResourceLocation owner, CivicEvidenceProvider provider) {
        requireServerThread();
        return register(evidenceProviders, owner, provider, "civic evidence provider");
    }

    @Override
    public Registration registerVillageFootprintSource(ResourceLocation owner, VillageFootprintSource source) {
        requireServerThread();
        return register(footprintSources, owner, source, "village footprint source");
    }

    /** Lets reconciliation tell retired provider references from live ones (the dead-MCA rule). */
    public void liveExternalReferences(String namespace, Function<ServerLevel, Optional<Set<String>>> live) {
        requireServerThread();
        liveReferences.put(Objects.requireNonNull(namespace, "namespace"), Objects.requireNonNull(live, "live"));
    }

    /** The generated village whose footprint holds {@code pos}, from loaded chunks and registered sources. */
    public Optional<VillageFootprint> villageAt(ServerLevel level, BlockPos pos) {
        requireServerThread();
        return footprints.at(level, pos, margin());
    }

    public void enqueueChunk(ServerLevel level, ChunkPos chunkPos) {
        QueuedChunk queued = new QueuedChunk(level.dimension(), chunkPos.toLong());
        synchronized (chunkQueue) {
            if (queuedChunks.add(queued)) chunkQueue.addLast(queued);
        }
    }

    public void tickDiscovery() {
        requireServerThread();
        stepStartupReconciliation();
        int budget = McaKingdomsConfig.CHUNK_SCANS_PER_TICK.get();
        for (int i = 0; i < budget; i++) {
            QueuedChunk queued;
            synchronized (chunkQueue) {
                queued = chunkQueue.pollFirst();
                if (queued != null) queuedChunks.remove(queued);
            }
            if (queued == null) return;
            ServerLevel level = server.getLevel(queued.dimension());
            int x = ChunkPos.getX(queued.chunk());
            int z = ChunkPos.getZ(queued.chunk());
            if (level == null || !level.hasChunk(x, z)) continue;
            BlockPos center = new BlockPos((x << 4) + 8, level.getSeaLevel(), (z << 4) + 8);
            discover(level, center, 0);
            if (reconciling()) reconcileChunk(level, new ChunkPos(x, z));
        }
    }

    /**
     * Queues the one-time start-up reconciliation, which runs over the next ticks and then records {@code Reconciled}
     * in the saved data. Returns false, doing nothing, when it already ran for this world or is switched off.
     */
    public boolean beginStartupReconciliation() {
        requireServerThread();
        if (!reconciling() || !McaKingdomsConfig.RECONCILE_ON_STARTUP.get()
                || data.reconciled() >= SettlementSavedData.RECONCILIATION_VERSION) return false;
        startup = reconciler.startup();
        startupBegan = System.currentTimeMillis();
        return true;
    }

    private void stepStartupReconciliation() {
        if (startup == null) return;
        if (!reconciling()) {
            startup = null;
            return;
        }
        int margin = margin();
        boolean done = startup.step(new SettlementReconciler.Lookup() {
            @Override
            public Optional<VillageFootprint> loaded(SettlementRecord record) {
                ServerLevel level = server.getLevel(record.dimension);
                return level == null ? Optional.empty() : footprints.at(level, record.anchor, margin);
            }

            @Override
            public Optional<VillageFootprint> read(SettlementRecord record, VillageFootprints.Budget budget) {
                ServerLevel level = server.getLevel(record.dimension);
                return level == null ? Optional.empty() : footprints.read(level, record.anchor, margin, budget);
            }
        }, margin, McaKingdomsConfig.RECONCILE_STARTUP_CHUNK_BUDGET.get(), McaKingdomsConfig.RECONCILE_CHUNK_READS_PER_TICK.get());
        if (!done) return;
        data.markReconciled();
        LOGGER.info(startup.report().summary("startup"));
        lastStartupExamined = startup.report().examined();
        lastStartupMerged = startup.report().merged();
        lastStartupReads = startup.report().chunkReads();
        lastStartupElapsed = System.currentTimeMillis() - startupBegan;
        startup = null;
    }

    /** Reconciles the records anchored in a chunk that was just scanned. Logs only when something changed. */
    private void reconcileChunk(ServerLevel level, ChunkPos chunk) {
        List<SettlementRecord> anchored = spatialIndex.at(level.dimension(), chunk.getMiddleBlockPosition(0)).stream()
                .map(data::get)
                .flatMap(Optional::stream)
                .filter(record -> !record.hasStrongStructureIdentities() && new ChunkPos(record.anchor).equals(chunk))
                .distinct()
                .toList();
        if (anchored.isEmpty()) return;
        SettlementReconciler.Report report = reconcileRecords(level, anchored);
        if (report.changed()) LOGGER.info(report.summary("scan of chunk " + chunk.x + ", " + chunk.z));
    }

    /**
     * {@code /kingdoms village reconcile}: reconciles the records anchored in loaded chunks within {@code radiusChunks}
     * of {@code center}, reporting refusals remembered earlier this session as well.
     */
    public SettlementReconciler.Report reconcileAround(ServerLevel level, BlockPos center, int radiusChunks) {
        requireServerThread();
        if (radiusChunks < 0 || radiusChunks > 32) throw new IllegalArgumentException("Reconciliation radius must be from 0 to 32 chunks");
        if (!structureIdentity()) throw new IllegalArgumentException("Village reconciliation is off while villageIdentity is SPATIAL");
        int reach = radiusChunks * 16 + 15;
        List<SettlementRecord> nearby = data.records().stream()
                .filter(record -> record.dimension.equals(level.dimension()) && !record.hasStrongStructureIdentities())
                .filter(record -> Math.abs(record.anchor.getX() - center.getX()) <= reach
                        && Math.abs(record.anchor.getZ() - center.getZ()) <= reach)
                .filter(record -> level.hasChunk(record.anchor.getX() >> 4, record.anchor.getZ() >> 4))
                .toList();
        SettlementReconciler.Report report = reconcileRecords(level, nearby);
        LOGGER.info(report.summary("command near " + center.getX() + ", " + center.getZ()));
        return report;
    }

    /** Remembered refusals that involve settlements anchored within the radius, for the reconcile command's report. */
    public List<SettlementReconciler.Refusal> refusalsAround(ServerLevel level, BlockPos center, int radiusChunks) {
        requireServerThread();
        int reach = radiusChunks * 16 + 15;
        List<UUID> nearby = data.records().stream()
                .filter(record -> record.dimension.equals(level.dimension()))
                .filter(record -> Math.abs(record.anchor.getX() - center.getX()) <= reach
                        && Math.abs(record.anchor.getZ() - center.getZ()) <= reach)
                .map(record -> record.id)
                .toList();
        return reconciler.refusals(nearby);
    }

    private SettlementReconciler.Report reconcileRecords(ServerLevel level, List<SettlementRecord> records) {
        SettlementReconciler.Report report = new SettlementReconciler.Report();
        int margin = margin();
        for (SettlementRecord record : records) {
            if (!data.resolveId(record.id).equals(record.id) || record.hasStrongStructureIdentities()) continue;
            Optional<VillageFootprint> village = footprints.at(level, record.anchor, margin);
            report.add(village.map(found -> reconciler.reconcile(found, margin, other -> false, (canonical, other) -> false))
                    .orElseGet(SettlementReconciler.Report::new), village.isEmpty());
        }
        return report;
    }

    public void observeEntity(Entity entity) {
        requireEntity(entity);
        for (CivicEvidenceProvider provider : List.copyOf(evidenceProviders.values())) {
            Optional<CivicIdentityHint> hint = provider.observe(entity);
            if (hint.isPresent()) {
                applyHint(entity, hint.get());
                return;
            }
        }
        if (!civicResident(entity) || identity(entity).isPresent()) return;
        getSettlementAt((ServerLevel) entity.level(), entity.blockPosition())
                .map(SettlementView::id)
                .ifPresent(id -> setResidence(entity, id, CivicIdentitySource.AUTOMATIC));
    }

    /** Only villager-like NPCs acquire residence from position alone; hostile mobs and animals never do. */
    static boolean civicResident(Entity entity) {
        return entity instanceof net.minecraft.world.entity.npc.Villager || entity.getType().is(CIVIC_RESIDENTS);
    }

    /**
     * Records a player's own verified residence (A01): the same change as an operator's setResidence, with the identity
     * source REGISTERED. The caller has verified the evidence; this neither loads chunks nor reads beds.
     */
    public CivicIdentityView registerResidence(Entity entity, UUID settlementId) {
        return setResidence(entity, settlementId, CivicIdentitySource.REGISTERED);
    }

    /** What civic evidence providers say about this entity now, read-only; empty when none speaks for it. */
    public Optional<CivicIdentityHint> providerEvidence(Entity entity) {
        requireEntity(entity);
        for (CivicEvidenceProvider provider : List.copyOf(evidenceProviders.values())) {
            try {
                Optional<CivicIdentityHint> hint = provider.observe(entity);
                if (hint.isPresent()) return hint;
            } catch (RuntimeException failing) {
                // A failing provider is no evidence; the bed rule still applies.
            }
        }
        return Optional.empty();
    }

    public void copyIdentity(Entity source, Entity target) {
        requireServerThread();
        civicStore.copy(source, target);
    }

    public void definitionsReloaded() {
        requireServerThread();
        MinecraftForge.EVENT_BUS.post(new KingdomDefinitionsReloadedEvent(revision()));
    }

    private SettlementView setKingdom(UUID settlementId, ResourceLocation kingdomId, ChangeReason reason) {
        if (validatingMutation) throw new IllegalStateException("Mutation during settlement preflight");
        requireServerThread();
        Objects.requireNonNull(kingdomId, "kingdomId");
        if (definitions.snapshot().kingdom(kingdomId).isEmpty()) {
            throw new IllegalArgumentException("Unknown kingdom: " + kingdomId);
        }
        SettlementRecord record = requireSettlement(settlementId);
        if (record.kingdomLocked) throw new IllegalArgumentException("Settlement kingdom is locked: " + record.displayName);
        if (record.kingdomId.equals(kingdomId)) return record.snapshot();
        ResourceLocation oldKingdom = record.kingdomId;
        preflight(record, null, kingdomId);
        record.kingdomId = kingdomId;
        record.assignmentSource = AssignmentSource.MANUAL;
        record.assignmentTrace.clear();
        record.assignmentTrace.add("Kingdom assigned through " + reason.name().toLowerCase(Locale.ROOT));
        data.changed(record);
        SettlementSnapshot snapshot = record.snapshot();
        MinecraftForge.EVENT_BUS.post(new SettlementKingdomChangedEvent(snapshot, oldKingdom, kingdomId, reason));
        return snapshot;
    }

    private void preflight(SettlementRecord source, SettlementRecord target, ResourceLocation kingdom) {
        if (validatingMutation) throw new IllegalStateException("Nested settlement mutation during preflight");
        var event = new dev.otectus.mcakingdoms.api.event.SettlementMutationPreflightEvent(server,
                source.snapshot(), target == null ? null : target.snapshot(), kingdom);
        validatingMutation = true;
        try {
            if (MinecraftForge.EVENT_BUS.post(event)) throw new IllegalArgumentException(event.rejection());
        } finally { validatingMutation = false; }
    }

    private void applyHint(Entity entity, CivicIdentityHint hint) {
        CivicIdentitySnapshot old = identity(entity).orElse(CivicIdentitySnapshot.empty());
        Optional<SettlementSnapshot> hintedOrigin = hint.originSettlement().flatMap(this::snapshot);
        Optional<SettlementSnapshot> hintedResidence = hint.residenceSettlement().flatMap(this::snapshot);
        if (hint.originSettlement().isPresent() && hintedOrigin.isEmpty()) return;
        if (hint.residenceSettlement().isPresent() && hintedResidence.isEmpty()) return;
        Optional<UUID> originSettlement = old.originSettlement().isPresent()
                ? old.originSettlement() : hintedOrigin.map(SettlementSnapshot::id);
        Optional<ResourceLocation> originKingdom = old.originKingdom().isPresent()
                ? old.originKingdom() : hintedOrigin.map(SettlementSnapshot::kingdomId);
        Optional<UUID> residenceSettlement = hintedResidence.isPresent()
                ? hintedResidence.map(SettlementSnapshot::id) : old.residenceSettlement();
        Optional<ResourceLocation> residenceKingdom = residenceSettlement.flatMap(this::snapshot)
                .map(SettlementSnapshot::kingdomId);
        boolean residenceChanged = !residenceSettlement.equals(old.residenceSettlement());
        CivicIdentitySnapshot updated = new CivicIdentitySnapshot(
                originSettlement, originKingdom, residenceSettlement, residenceKingdom,
                hint.source(), residenceChanged ? entity.level().getGameTime() : old.lastResidenceChange());
        if (updated.equals(old)) return;
        civicStore.write(entity, updated);
        if (residenceChanged) {
            old.residenceSettlement().flatMap(this::snapshot).ifPresent(previous ->
                    MinecraftForge.EVENT_BUS.post(new ResidentLeftEvent(entity.getUUID(), previous, updated)));
            hintedResidence.ifPresent(current ->
                    MinecraftForge.EVENT_BUS.post(new ResidentJoinedEvent(entity.getUUID(), current, updated)));
        }
        MinecraftForge.EVENT_BUS.post(new CivicIdentityChangedEvent(entity.getUUID(), old, updated));
    }

    private Optional<CivicIdentitySnapshot> identity(Entity entity) {
        requireEntity(entity);
        Optional<CivicIdentitySnapshot> loaded = civicStore.read(entity, this::snapshot);
        if (loaded.isEmpty()) return Optional.empty();
        CivicIdentitySnapshot current = loaded.get();
        Optional<UUID> origin = current.originSettlement().map(data::resolveId);
        Optional<UUID> residence = current.residenceSettlement().map(data::resolveId);
        Optional<ResourceLocation> residenceKingdom = residence.flatMap(this::snapshot).map(SettlementSnapshot::kingdomId);
        if (!origin.equals(current.originSettlement()) || !residence.equals(current.residenceSettlement())
                || !residenceKingdom.equals(current.residenceKingdom())) {
            current = new CivicIdentitySnapshot(origin, current.originKingdom(), residence, residenceKingdom,
                    current.source(), current.lastResidenceChange());
            civicStore.write(entity, current);
        }
        return Optional.of(current);
    }

    private KingdomResolution resolve(ServerLevel level, SettlementCandidate candidate) {
        if (candidate.explicitKingdom().isPresent()) {
            ResourceLocation kingdom = candidate.explicitKingdom().get();
            AssignmentSource source = candidate.detectionSource() == DetectionSource.MANUAL
                    ? AssignmentSource.MANUAL : AssignmentSource.ADDON;
            return new KingdomResolution(kingdom, source, Optional.of(biomeAt(level, candidate.anchor())),
                    Integer.MAX_VALUE, 1.0, List.of("Explicit kingdom supplied by " + candidate.sourceId()));
        }
        for (KingdomResolver resolver : List.copyOf(resolvers.values())) {
            Optional<KingdomResolution> resolved = resolver.resolve(level, candidate);
            if (resolved.isPresent()) return resolved.get();
        }
        return definitions.snapshot().resolve(level, candidate);
    }

    /** A candidate after normalisation, with the village footprint that resolved it, when one did. */
    private record Resolution(SettlementCandidate candidate, Optional<VillageFootprint> village) {
    }

    /**
     * Resolves a candidate that carries no structure key to the village footprint its anchor, then one of its bounds
     * corners, lies in. Structure candidates are already keyed; a manual candidate is resolved by its anchor alone, so
     * one registered inside a recognised village joins it (D5).
     */
    private Resolution normalise(ServerLevel level, SettlementCandidate candidate) {
        if (candidate.detectionSource() == DetectionSource.STRUCTURE || VillageFootprints.villageKey(candidate).isPresent()) {
            return new Resolution(candidate, Optional.empty());
        }
        List<BlockPos> probes = new ArrayList<>();
        probes.add(candidate.anchor());
        if (candidate.detectionSource() != DetectionSource.MANUAL) {
            SettlementBounds bounds = candidate.bounds();
            int y = candidate.anchor().getY();
            probes.add(new BlockPos(bounds.minX(), y, bounds.minZ()));
            probes.add(new BlockPos(bounds.maxX(), y, bounds.minZ()));
            probes.add(new BlockPos(bounds.minX(), y, bounds.maxZ()));
            probes.add(new BlockPos(bounds.maxX(), y, bounds.maxZ()));
        }
        int margin = margin();
        for (BlockPos probe : probes) {
            Optional<VillageFootprint> village = footprints.at(level, probe, margin);
            if (village.isPresent()) return new Resolution(VillageFootprints.attach(candidate, village.get()), village);
        }
        return new Resolution(candidate, Optional.empty());
    }

    /**
     * Merges every record that belongs to the village a candidate resolved to into the village's canonical record:
     * records inside the village box, records the candidate's own identity points at, and, for an MCA candidate,
     * records holding only MCA villages that MCA no longer lists, inside the canonical record's bounds.
     */
    private void claim(ServerLevel level, SettlementCandidate candidate, String key, Optional<VillageFootprint> resolved) {
        Optional<VillageFootprint> village = resolved.isPresent() ? resolved
                : footprints.find(level, candidate.anchor(), key, margin()).or(() -> fromBounds(level, candidate, key));
        if (village.isEmpty()) return;
        Predicate<SettlementRecord> identity = record -> record.hasDetectorIdentity(candidate.sourceId(), candidate.sourceKey())
                || candidate.externalRefs().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(VillageFootprints.STRUCTURE_REF))
                .anyMatch(entry -> record.hasExternalRef(entry.getKey(), entry.getValue()));
        BiPredicate<SettlementRecord, SettlementRecord> retiredMca = (canonical, other) -> false;
        Function<ServerLevel, Optional<Set<String>>> live = liveReferences.get(MCA_REF);
        if (live != null && candidate.externalRefs().containsKey(MCA_REF)) {
            retiredMca = SettlementReconciler.retiredReferences(MCA_REF, () -> live.apply(level));
        }
        SettlementReconciler.Report report = reconciler.reconcile(village.get(), margin(), identity, retiredMca);
        if (report.changed()) LOGGER.info(report.summary("discovery of " + key));
    }

    /** A village box taken from a structure candidate's own bounds, when no footprint source knows the village. */
    private static Optional<VillageFootprint> fromBounds(ServerLevel level, SettlementCandidate candidate, String key) {
        String[] parts = key.split(":");
        if (parts.length != 6) return Optional.empty();
        ResourceLocation dimension = ResourceLocation.tryParse(parts[0] + ":" + parts[1]);
        ResourceLocation structure = ResourceLocation.tryParse(parts[2] + ":" + parts[3]);
        if (dimension == null || structure == null || !dimension.equals(level.dimension().location())) return Optional.empty();
        try {
            ChunkPos start = new ChunkPos(Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
            SettlementBounds bounds = candidate.bounds();
            VillageFootprint village = new VillageFootprint(level.dimension(), structure, start, new BoundingBox(bounds.minX(),
                    level.getMinBuildHeight(), bounds.minZ(), bounds.maxX(), level.getMaxBuildHeight() - 1, bounds.maxZ()));
            return village.key().equals(key) ? Optional.of(village) : Optional.empty();
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    /** The candidate without external references some other record already owns; they stay with that record. */
    private SettlementCandidate withoutForeignRefs(SettlementCandidate candidate, UUID owner) {
        Map<String, String> kept = new LinkedHashMap<>();
        candidate.externalRefs().forEach((namespace, value) -> {
            Optional<SettlementRecord> holder = data.getByExternalRef(namespace, value);
            if (holder.isEmpty() || holder.get().id.equals(owner)) kept.put(namespace, value);
        });
        if (kept.size() == candidate.externalRefs().size()) return candidate;
        return new SettlementCandidate(candidate.dimension(), candidate.anchor(), candidate.radius(), candidate.bounds(),
                candidate.sourceId(), candidate.sourceKey(), candidate.detectionSource(), candidate.styleId(),
                candidate.explicitKingdom(), kept, candidate.proposedName());
    }

    private Optional<SettlementRecord> match(SettlementCandidate candidate, Optional<String> village) {
        if (village.isPresent()) {
            Optional<SettlementRecord> canonical = SettlementReconciler.canonical(data, candidate.dimension(), village.get());
            if (canonical.isPresent()) return canonical;
        }
        for (SettlementRecord record : data.records()) {
            if (!record.dimension.equals(candidate.dimension())) continue;
            // A record with a structure identity of its own is another village, whatever else the candidate shares with it.
            if (village.isPresent() && record.hasStrongStructureIdentities()) continue;
            if (record.hasDetectorIdentity(candidate.sourceId(), candidate.sourceKey())) {
                return Optional.of(record);
            }
            if (candidate.externalRefs().entrySet().stream()
                    .anyMatch(entry -> record.hasExternalRef(entry.getKey(), entry.getValue()))) {
                return Optional.of(record);
            }
        }
        Stream<SettlementRecord> nearby = data.records().stream()
                .filter(record -> record.dimension.equals(candidate.dimension()))
                .filter(record -> village.isPresent() ? !record.hasStrongStructureIdentities()
                        : !differentStrongStructures(record, candidate));
        if (village.isEmpty() && structureIdentity() && candidate.detectionSource() == DetectionSource.POI) {
            // A cluster outside any village footprint (player-built, or an untagged modded village) joins a record its
            // extent comes within link distance of: two scans sharing a point always do, so the village assembles in
            // any scan order. It also joins any record whose bounds hold its centroid.
            int link = PoiClusterDetector.linkDistance();
            return nearby.filter(record -> !record.hasStrongStructureIdentities()
                            && inflate(record.bounds, link).intersects(candidate.bounds())
                            || record.bounds.contains(candidate.anchor()))
                    .min(Comparator.comparingInt(record -> distanceSquared(record.anchor, candidate.anchor())));
        }
        double overlap = McaKingdomsConfig.CANDIDATE_OVERLAP_FRACTION.get();
        int distance = McaKingdomsConfig.CANDIDATE_MERGE_DISTANCE.get();
        return nearby.filter(record -> substantiallyOverlaps(record.bounds, candidate.bounds(), overlap)
                        || distanceSquared(record.anchor, candidate.anchor()) <= square(distance))
                .min(Comparator.comparingInt(record -> distanceSquared(record.anchor, candidate.anchor())));
    }

    private void observe(SettlementRecord record, SettlementCandidate candidate, long gameTime) {
        data.validateExternalRefs(record.id, candidate.externalRefs());
        boolean changed = false;
        if (!record.hasDetectorIdentity(candidate.sourceId(), candidate.sourceKey())) {
            record.addDetectorIdentity(candidate.sourceId(), candidate.sourceKey());
            changed = true;
        }
        if (candidate.detectionSource() == DetectionSource.STRUCTURE) {
            int before = record.strongStructureIdentities.getOrDefault(candidate.sourceId(), Set.of()).size();
            record.addStrongStructureIdentity(candidate.sourceId(), candidate.sourceKey());
            changed |= record.strongStructureIdentities.get(candidate.sourceId()).size() != before;
        }
        Optional<String> village = structureIdentity() ? VillageFootprints.villageKey(candidate) : Optional.empty();
        if (village.isPresent() && !record.hasDetectorIdentity(SettlementReconciler.BUILTIN_STRUCTURE, village.get())) {
            // The record a village candidate lands on is that village's canonical record from now on.
            record.addStrongStructureIdentity(SettlementReconciler.BUILTIN_STRUCTURE, village.get());
            changed = true;
        }
        for (Map.Entry<String, String> entry : candidate.externalRefs().entrySet()) {
            changed |= record.addExternalRef(entry.getKey(), entry.getValue());
        }
        int rank = detectionRank(candidate.detectionSource());
        int current = detectionRank(record.detectionSource);
        if (rank > current) {
            SettlementSnapshot oldSnapshot = record.snapshot();
            spatialIndex.remove(oldSnapshot);
            SettlementBounds observed = record.bounds;
            record.anchor = candidate.anchor();
            record.radius = candidate.radius();
            record.bounds = candidate.bounds();
            // A village keeps the union of its structure box and every footprint observed for it.
            if (village.isPresent()) SettlementReconciler.grow(record, observed, maxSpan());
            record.detectionSource = candidate.detectionSource();
            record.sourceId = candidate.sourceId();
            record.sourceKey = candidate.sourceKey();
            spatialIndex.add(record.snapshot());
            changed = true;
        } else if ((rank == current || village.isPresent()) && !contains(record.bounds, candidate.bounds())) {
            // A settlement that keeps being observed beyond its recorded edge grows, up to a bounded span, instead of
            // splitting into a second record or leaving new streets outside civic protection. Any source that resolved
            // to the record's own village counts, not only one of equal rank.
            SettlementSnapshot oldSnapshot = record.snapshot();
            if (SettlementReconciler.grow(record, candidate.bounds(), maxSpan())) {
                spatialIndex.remove(oldSnapshot);
                spatialIndex.add(record.snapshot());
                changed = true;
            }
        }
        if (record.styleId == null && candidate.styleId().isPresent()) {
            record.styleId = candidate.styleId().get();
            changed = true;
        }
        if (gameTime - record.lastObservedGameTime >= OBSERVATION_WRITE_INTERVAL) {
            record.lastObservedGameTime = gameTime;
            changed = true;
        }
        if (changed) data.changed(record);
    }

    private GeneratedName proposedName(String requestedName) {
        String display = requireName(requestedName);
        String normalized = VillageNameGenerator.normalize(display);
        if (normalizedNameTaken(normalized)) throw new IllegalArgumentException("Settlement name is already in use: " + display);
        String base = slugPath(display);
        ResourceLocation slug = new ResourceLocation(McaKingdomsApi.MOD_ID, base);
        int suffix = 2;
        while (slugTaken(slug)) slug = new ResourceLocation(McaKingdomsApi.MOD_ID, base + "_" + suffix++);
        return new GeneratedName(display, normalized, slug, MANUAL_SOURCE, Optional.empty());
    }

    private boolean normalizedNameTaken(String value) {
        String normalized = VillageNameGenerator.normalize(value);
        return data.records().stream().anyMatch(record -> VillageNameGenerator.normalize(record.displayName).equals(normalized)
                || record.aliases.stream().anyMatch(alias -> VillageNameGenerator.normalize(alias).equals(normalized)));
    }

    private boolean slugTaken(ResourceLocation slug) {
        return data.records().stream().anyMatch(record -> record.slug.equals(slug) || record.retiredSlugs.contains(slug));
    }

    private Optional<SettlementSnapshot> snapshot(UUID id) {
        return data.get(Objects.requireNonNull(id, "id")).map(SettlementRecord::snapshot);
    }

    private SettlementRecord requireSettlement(UUID id) {
        return data.get(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement: " + id));
    }

    private void requireEntity(Entity entity) {
        requireServerThread();
        Objects.requireNonNull(entity, "entity");
        if (entity.getServer() != server || entity.level().isClientSide()) {
            throw new IllegalArgumentException("Entity is not attached to this server");
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("MCA: Kingdoms API must be called on the server thread");
        }
    }

    private SettlementCandidate asCandidate(SettlementRecord record) {
        return new SettlementCandidate(record.dimension, record.anchor, record.radius, record.bounds,
                record.sourceId, record.sourceKey, record.detectionSource, Optional.ofNullable(record.styleId),
                Optional.empty(), record.externalRefs, Optional.of(record.displayName));
    }

    private static ResourceLocation biomeAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location()).orElse(UNKNOWN_BIOME);
    }

    private static boolean differentStrongStructures(SettlementRecord record, SettlementCandidate candidate) {
        return candidate.detectionSource() == DetectionSource.STRUCTURE
                && record.hasStrongStructureIdentities();
    }

    private static boolean contains(dev.otectus.mcakingdoms.api.SettlementBounds outer, dev.otectus.mcakingdoms.api.SettlementBounds inner) {
        return outer.minX() <= inner.minX() && outer.minZ() <= inner.minZ() && outer.maxX() >= inner.maxX() && outer.maxZ() >= inner.maxZ();
    }
    private static SettlementBounds inflate(SettlementBounds bounds, int blocks) {
        return new SettlementBounds(bounds.minX() - blocks, bounds.minZ() - blocks, bounds.maxX() + blocks, bounds.maxZ() + blocks);
    }
    private static boolean substantiallyOverlaps(dev.otectus.mcakingdoms.api.SettlementBounds first,
                                                 dev.otectus.mcakingdoms.api.SettlementBounds second, double fraction) {
        int minX = Math.max(first.minX(), second.minX());
        int minZ = Math.max(first.minZ(), second.minZ());
        int maxX = Math.min(first.maxX(), second.maxX());
        int maxZ = Math.min(first.maxZ(), second.maxZ());
        if (minX > maxX || minZ > maxZ) return false;
        long intersection = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        long firstArea = (long) (first.maxX() - first.minX() + 1) * (first.maxZ() - first.minZ() + 1);
        long secondArea = (long) (second.maxX() - second.minX() + 1) * (second.maxZ() - second.minZ() + 1);
        return intersection >= Math.min(firstArea, secondArea) * fraction;
    }

    /** STRUCTURE identity: candidates resolve to the generated village they lie in (D1). */
    private static boolean structureIdentity() {
        return McaKingdomsConfig.VILLAGE_IDENTITY.get() == McaKingdomsConfig.VillageIdentity.STRUCTURE;
    }

    /** Reconciliation merges split records; it is off in SPATIAL mode. */
    private static boolean reconciling() {
        return structureIdentity() && McaKingdomsConfig.AUTO_RECONCILE.get();
    }

    private static int margin() {
        return McaKingdomsConfig.STRUCTURE_MERGE_MARGIN.get();
    }

    /** The widest a settlement grows by observation or merge. */
    private static int maxSpan() {
        return McaKingdomsConfig.DEFAULT_SETTLEMENT_RADIUS.get() * 8;
    }

    static int detectionRank(DetectionSource source) {
        return SettlementReconciler.detectionRank(source);
    }

    private static int distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        long result = dx * dx + dz * dz;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int square(int value) {
        long result = (long) value * value;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static String requireName(String name) {
        String result = Objects.requireNonNull(name, "name").strip();
        if (result.isEmpty() || result.length() > 80) {
            throw new IllegalArgumentException("Settlement name must contain from 1 to 80 characters");
        }
        return result;
    }

    private static String slugPath(String value) {
        String slug = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "settlement" : slug;
    }

    private static String displayId(ResourceLocation id) {
        String[] words = id.getPath().split("[_/.-]+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static void addAlias(SettlementRecord record, String alias) {
        if (!alias.isBlank() && !record.aliases.contains(alias)) record.aliases.add(alias);
    }

    private <T> Registration register(Map<ResourceLocation, T> registrations, ResourceLocation owner,
                                      T value, String kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(value, kind);
        if (registrations.putIfAbsent(owner, value) != null) {
            throw new IllegalArgumentException("A " + kind + " is already registered for " + owner);
        }
        return () -> {
            requireServerThread();
            registrations.remove(owner, value);
        };
    }

    private record QueuedChunk(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                               long chunk) {
    }
}
