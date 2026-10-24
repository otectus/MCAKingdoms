package dev.otectus.mcakingdoms.core;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.settlement.VillageFootprints;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Merges settlement records that turn out to be pieces of one generated village into that village's canonical
 * record. Merges go through the service, so the preflight, redirects, aliases and events all apply; a merge the
 * preflight refuses is logged once per pair and not retried until the server restarts.
 */
public final class SettlementReconciler {
    static final ResourceLocation BUILTIN_STRUCTURE = new ResourceLocation("mcakingdoms", "village_structure");
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Records the start-up pass examines per tick at most, whatever they cost in chunk reads. */
    private static final int STARTUP_RECORDS_PER_TICK = 64;

    /** What reconciliation asks of the service. */
    interface Mutations {
        /** Merges {@code source} into {@code target}; throws IllegalArgumentException or IllegalStateException when refused. */
        void merge(SettlementRecord source, SettlementRecord target);

        /** Makes {@code record} the village's canonical record: it receives the structure key and the village box. */
        void adopt(SettlementRecord record, VillageFootprint village);
    }

    /** Where a record's anchor lies: in loaded chunks and registered sources, or by reading its chunk. */
    interface Lookup {
        Optional<VillageFootprint> loaded(SettlementRecord record);

        Optional<VillageFootprint> read(SettlementRecord record, VillageFootprints.Budget budget);
    }

    /** A merge refused this session, with the preflight's reason and the command that retries it. */
    public record Refusal(UUID source, String sourceName, UUID target, String targetName, String reason) {
        public String command() {
            return "/kingdoms village merge " + source + " " + target;
        }
    }

    /** What one pass did. */
    public static final class Report {
        private int examined, merged, unresolved, chunkReads, newRefusals;
        private final Set<UUID> villages = new LinkedHashSet<>();
        private final Map<String, Refusal> refused = new LinkedHashMap<>();

        public int examined() { return examined; }
        public int merged() { return merged; }
        public int villages() { return villages.size(); }
        public int unresolved() { return unresolved; }
        public int chunkReads() { return chunkReads; }
        public List<Refusal> refused() { return List.copyOf(refused.values()); }

        /** Whether this pass merged something or met a refusal for the first time. */
        boolean changed() {
            return merged > 0 || newRefusals > 0;
        }

        /** Adds one examined record's pass; {@code outsideVillages} when its anchor lies in no known village. */
        void add(Report other, boolean outsideVillages) {
            examined++;
            if (outsideVillages) unresolved++;
            add(other);
        }

        void add(Report other) {
            examined += other.examined;
            merged += other.merged;
            unresolved += other.unresolved;
            chunkReads += other.chunkReads;
            newRefusals += other.newRefusals;
            villages.addAll(other.villages);
            refused.putAll(other.refused);
        }

        String summary(String pass) {
            return "[MCA: Kingdoms] Village reconciliation (" + pass + "): merged " + merged + " split record(s) into "
                    + villages.size() + " village(s); " + refused.size() + " refused"
                    + (examined > 0 ? "; " + unresolved + " of " + examined + " examined record(s) outside any known village" : "")
                    + (chunkReads > 0 ? "; " + chunkReads + " chunk read(s)" : "");
        }
    }

    private final SettlementSavedData data;
    private final Mutations mutations;
    private final Map<String, Refusal> refusals = new LinkedHashMap<>();

    SettlementReconciler(SettlementSavedData data, Mutations mutations) {
        this.data = data;
        this.mutations = mutations;
    }

    /**
     * Merges every record that belongs to {@code village} into its canonical record. A record belongs when it has no
     * strong structure identity of its own and its anchor lies inside the village box grown by {@code margin}, or
     * {@code claims} names it; {@code nearCanonical} may add records once the canonical record is known. When no
     * record carries the village key yet, the best claimed record (D3) becomes canonical and receives it.
     */
    Report reconcile(VillageFootprint village, int margin, Predicate<SettlementRecord> claims,
                     BiPredicate<SettlementRecord, SettlementRecord> nearCanonical) {
        Report report = new Report();
        String key = village.key();
        ResourceKey<Level> dimension = village.dimension();
        BoundingBox reach = village.box().inflatedBy(margin);
        List<SettlementRecord> claimed = new ArrayList<>(data.records().stream()
                .filter(record -> record.dimension.equals(dimension) && !record.hasStrongStructureIdentities())
                .filter(record -> reach.isInside(record.anchor) || claims.test(record))
                .toList());
        SettlementRecord canonical = canonical(data, dimension, key).orElse(null);
        if (canonical == null) {
            if (claimed.isEmpty()) return report;
            canonical = claimed.stream().min(survivorOrder(key)).orElseThrow();
            mutations.adopt(canonical, village);
        }
        SettlementRecord target = canonical;
        claimed.remove(target);
        data.records().stream()
                .filter(record -> record != target && record.dimension.equals(dimension)
                        && !record.hasStrongStructureIdentities() && !claimed.contains(record))
                .filter(record -> nearCanonical.test(target, record))
                .forEach(claimed::add);
        claimed.sort(Comparator.comparingLong((SettlementRecord record) -> record.createdGameTime)
                .thenComparing(record -> record.id));
        for (SettlementRecord record : claimed) {
            if (!data.resolveId(record.id).equals(record.id)) continue;
            String pair = record.id + ">" + target.id;
            Refusal remembered = refusals.get(pair);
            if (remembered != null) {
                report.refused.put(pair, remembered);
                continue;
            }
            try {
                mutations.merge(record, target);
                report.merged++;
                report.villages.add(target.id);
            } catch (IllegalArgumentException | IllegalStateException refused) {
                Refusal refusal = new Refusal(record.id, record.displayName, target.id, target.displayName,
                        String.valueOf(refused.getMessage()));
                refusals.put(pair, refusal);
                report.refused.put(pair, refusal);
                report.newRefusals++;
                LOGGER.warn("[MCA: Kingdoms] Settlement {} ({}) is part of village {} ({}) but was not merged: {}. "
                                + "It is not retried until the server restarts; once resolved, run: {}",
                        refusal.sourceName(), refusal.source(), refusal.targetName(), refusal.target(),
                        refusal.reason(), refusal.command());
            }
        }
        return report;
    }

    /**
     * The dead-reference rule (RC6): a record holding only references in {@code namespace}, none of which the provider
     * still lists, belongs to the canonical record whose bounds hold its anchor. {@code live} is asked at most once and
     * may say nothing (empty), in which case no record is claimed.
     */
    static BiPredicate<SettlementRecord, SettlementRecord> retiredReferences(String namespace,
                                                                             java.util.function.Supplier<Optional<Set<String>>> live) {
        java.util.concurrent.atomic.AtomicReference<Optional<Set<String>>> listed = new java.util.concurrent.atomic.AtomicReference<>();
        return (canonical, other) -> {
            Set<String> values = other.externalRefValues.getOrDefault(namespace, Set.of());
            if (values.isEmpty() || !other.externalRefValues.keySet().equals(Set.of(namespace))
                    || !canonical.bounds.contains(other.anchor)) return false;
            Optional<Set<String>> current = listed.updateAndGet(known -> known != null ? known : live.get());
            return current.isPresent() && values.stream().noneMatch(current.get()::contains);
        };
    }

    /** Refusals remembered this session that involve one of these settlements. */
    List<Refusal> refusals(Collection<UUID> settlements) {
        return refusals.values().stream()
                .filter(refusal -> settlements.contains(refusal.source()) || settlements.contains(refusal.target()))
                .toList();
    }

    /** The record holding this village key, as an external reference or as a strong structure identity. */
    static Optional<SettlementRecord> canonical(SettlementSavedData data, ResourceKey<Level> dimension, String key) {
        Optional<SettlementRecord> referenced = data.getByExternalRef(VillageFootprints.STRUCTURE_REF, key);
        if (referenced.isPresent()) return referenced;
        return data.records().stream()
                .filter(record -> record.dimension.equals(dimension) && carriesKey(record, key))
                .findFirst();
    }

    static boolean carriesKey(SettlementRecord record, String key) {
        return record.hasExternalRef(VillageFootprints.STRUCTURE_REF, key)
                || record.strongStructureIdentities.values().stream().anyMatch(keys -> keys.contains(key));
    }

    /** D3, best first: the record carrying the village key, then the highest detection rank, then the oldest. */
    static Comparator<SettlementRecord> survivorOrder(String key) {
        return Comparator.comparing((SettlementRecord record) -> !carriesKey(record, key))
                .thenComparing(record -> -detectionRank(record.detectionSource))
                .thenComparingLong(record -> record.createdGameTime)
                .thenComparing(record -> record.id);
    }

    /** How much a detection source is trusted for a settlement's anchor and bounds; higher replaces lower. */
    static int detectionRank(DetectionSource source) {
        return switch (source) {
            case EXTERNAL -> 5;
            case STRUCTURE -> 4;
            case MANUAL -> 3;
            case ADDON -> 2;
            case POI -> 1;
        };
    }

    /** What a merge changes beyond identities, decided before the preflight runs. */
    record MergePlan(boolean carryName, boolean carryKingdom, ResourceLocation kingdom) {
        /**
         * A reconciliation merge carries a locked name or kingdom over to an unlocked survivor; otherwise, and for
         * every operator merge, the survivor keeps its own and the retired name becomes an alias.
         */
        static MergePlan of(SettlementRecord source, SettlementRecord target, boolean carryLocks) {
            boolean carryName = carryLocks && source.nameLocked && !target.nameLocked;
            boolean carryKingdom = carryLocks && source.kingdomLocked && !target.kingdomLocked
                    && !source.kingdomId.equals(target.kingdomId);
            return new MergePlan(carryName, carryKingdom, carryKingdom ? source.kingdomId : target.kingdomId);
        }
    }

    /**
     * Folds {@code source} into {@code target} and retires the source id. The caller has validated the references and
     * passed the preflight. Bounds become their union unless that would span more than {@code maxSpan} blocks.
     */
    static void combine(SettlementSavedData data, SettlementRecord source, SettlementRecord target, MergePlan plan,
                        int maxSpan) {
        if (plan.carryName()) {
            addAlias(target, target.displayName);
            target.displayName = source.displayName;
            target.nameLocked = true;
        } else {
            addAlias(target, source.displayName);
        }
        source.aliases.forEach(alias -> addAlias(target, alias));
        target.aliases.removeIf(alias -> alias.equals(target.displayName));
        if (plan.carryKingdom()) {
            target.kingdomId = plan.kingdom();
            target.kingdomLocked = true;
            target.assignmentSource = source.assignmentSource;
            target.assignmentTrace.clear();
            target.assignmentTrace.addAll(source.assignmentTrace);
            target.assignmentTrace.add("Kingdom carried over from merged settlement " + source.displayName);
        }
        target.retiredSlugs.add(source.slug);
        target.retiredSlugs.addAll(source.retiredSlugs);
        target.addDetectorIdentity(source.sourceId, source.sourceKey);
        if (source.detectionSource == DetectionSource.STRUCTURE) {
            target.addStrongStructureIdentity(source.sourceId, source.sourceKey);
        }
        source.detectorIdentities.forEach((detector, keys) -> keys.forEach(key -> target.addDetectorIdentity(detector, key)));
        source.strongStructureIdentities.forEach((detector, keys) ->
                keys.forEach(key -> target.addStrongStructureIdentity(detector, key)));
        source.externalRefs.forEach(target::addExternalRef);
        source.externalRefValues.forEach((namespace, values) -> values.forEach(value -> target.addExternalRef(namespace, value)));
        grow(target, source.bounds, maxSpan);
        target.lastObservedGameTime = Math.max(target.lastObservedGameTime, source.lastObservedGameTime);
        data.redirect(source.id, target.id);
        data.changed(target);
    }

    /** Gives {@code record} the village key and grows it over the village box; the caller re-indexes its bounds. */
    static void attach(SettlementSavedData data, SettlementRecord record, VillageFootprint village, int maxSpan) {
        data.validateExternalRefs(record.id, Map.of(VillageFootprints.STRUCTURE_REF, village.key()));
        record.addStrongStructureIdentity(BUILTIN_STRUCTURE, village.key());
        record.addExternalRef(VillageFootprints.STRUCTURE_REF, village.key());
        if (record.styleId == null) record.styleId = village.structureId();
        grow(record, village.bounds(), maxSpan);
        data.changed(record);
    }

    /** Unions {@code extra} into the record's bounds when the result spans at most {@code maxSpan} blocks. */
    static boolean grow(SettlementRecord record, SettlementBounds extra, int maxSpan) {
        SettlementBounds current = record.bounds;
        SettlementBounds grown = new SettlementBounds(Math.min(current.minX(), extra.minX()), Math.min(current.minZ(), extra.minZ()),
                Math.max(current.maxX(), extra.maxX()), Math.max(current.maxZ(), extra.maxZ()));
        int span = Math.max(grown.maxX() - grown.minX(), grown.maxZ() - grown.minZ());
        if (grown.equals(current) || span > maxSpan) return false;
        record.bounds = grown;
        record.radius = Math.max(record.radius, (span + 1) / 2);
        return true;
    }

    private static void addAlias(SettlementRecord record, String alias) {
        if (!alias.isBlank() && !record.aliases.contains(alias)) record.aliases.add(alias);
    }

    /** The one-time start-up pass over records without a structure identity, spread over ticks. */
    Startup startup() {
        List<UUID> queued = data.records().stream()
                .filter(record -> !record.hasStrongStructureIdentities())
                .sorted(Comparator.comparingLong((SettlementRecord record) -> record.createdGameTime)
                        .thenComparing(record -> record.id))
                .map(record -> record.id)
                .toList();
        return new Startup(new ArrayDeque<>(queued));
    }

    final class Startup {
        private final Deque<UUID> queue;
        private final Report report = new Report();
        private int budget = -1;

        private Startup(Deque<UUID> queue) {
            this.queue = queue;
        }

        /** Works through queued records, spending at most {@code readsPerTick} chunk reads; true once all are done. */
        boolean step(Lookup lookup, int margin, int totalBudget, int readsPerTick) {
            if (budget < 0) budget = Math.max(0, totalBudget);
            VillageFootprints.Budget reads = new VillageFootprints.Budget(Math.min(readsPerTick, budget));
            try {
                for (int examined = 0; examined < STARTUP_RECORDS_PER_TICK && !queue.isEmpty(); examined++) {
                    Optional<SettlementRecord> current = data.get(queue.peekFirst());
                    if (current.isEmpty() || !current.get().id.equals(queue.peekFirst())
                            || current.get().hasStrongStructureIdentities()) {
                        queue.pollFirst();
                        continue;
                    }
                    SettlementRecord record = current.get();
                    Optional<VillageFootprint> village = lookup.loaded(record);
                    if (village.isEmpty() && budget - reads.spent() > 0) {
                        if (reads.remaining() <= 0) return false;
                        village = lookup.read(record, reads);
                    }
                    queue.pollFirst();
                    report.examined++;
                    if (village.isEmpty()) {
                        report.unresolved++;
                        continue;
                    }
                    report.add(reconcile(village.get(), margin, ignored -> false, (canonical, other) -> false));
                }
                return queue.isEmpty();
            } finally {
                budget -= reads.spent();
                report.chunkReads += reads.spent();
            }
        }

        Report report() {
            return report;
        }
    }
}
