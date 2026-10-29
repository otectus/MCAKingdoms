package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.event.SettlementMutationPreflightEvent;
import dev.otectus.mcakingdoms.core.KingdomsServiceImpl;
import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

/**
 * Gives every real Living Villages village exactly one Kingdoms settlement and keeps native factions as realms. Existing
 * settlements are found by their provider reference first, then by place; an unambiguous village is imported on its own,
 * an ambiguous one waits for an operator with its candidates listed. A player faction with no Ledger placed yet is a realm
 * only. Nothing here spawns, places, loads chunks or writes provider state.
 */
public final class LvBindings {
    public static final String EXTERNAL_REF = "recruitsvillages:village";
    public static final ResourceLocation SOURCE = new ResourceLocation("mcakingdoms", "living_villages");
    public static final ResourceLocation STRUCTURE = new ResourceLocation("recruitsvillages", "village");
    private static final Map<LvRuntime, Registration> FOOTPRINTS = new WeakHashMap<>();
    /** Set while the bridge itself performs an operator-approved rebind, so its own merge passes the preflight. */
    private static final ThreadLocal<Boolean> BRIDGE_MERGE = ThreadLocal.withInitial(() -> false);

    private LvBindings() {
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------------------

    static void begin(LvRuntime rt) {
        var next = rt.data.snapshot();
        var p = next.provenance;
        long steps = rt.access.elapsedSteps(rt.server);
        int villages = rt.access.villages(rt.server).size();
        // A provider store older than what this world has already seen means a restored backup: reconcile, never re-reward.
        if (p.highestElapsedSteps >= 0 && steps + 2 < p.highestElapsedSteps && p.regressedAt == 0) {
            p.regressedAt = rt.now();
            next.record(EventKind.RECOVERY, rt.now(), null, null, null, null,
                    "Living Villages data is older than this world has seen (" + steps + " < " + p.highestElapsedSteps + "); conquests are not re-applied", false);
            LvRuntime.LOGGER.warn("[MCA: Kingdoms] Living Villages saved data went back from step {} to {} (restored backup?). "
                    + "Control changes are reconciled without rewards; an operator clears this with /kingdoms livingvillages recover provider.",
                    p.highestElapsedSteps, steps);
        }
        p.elapsedSteps = steps;
        p.highestElapsedSteps = Math.max(p.highestElapsedSteps, steps);
        p.villages = villages;
        p.version = LvCompat.version();
        p.sha256 = System.getProperty("mcakingdoms.livingvillages.sha256", "");
        if (next.migration.version < 1) {
            next.migration.version = 1;
            next.migration.started = rt.now();
            next.migration.complete = false;
            next.migration.cursor = null;
        }
        // Rebuild in-memory indexes from the provider before enabling writes: every known village is reconciled once.
        for (Object village : rt.access.villages(rt.server)) rt.touch(rt.access.id(village));
        rt.commit(next);
        LvWarBridge.restore(rt);
    }

    static void registerFootprints(LvRuntime rt) {
        try {
            var registration = rt.kingdoms().registerVillageFootprintSource(SOURCE, (level, pos, margin) -> footprints(rt, level, pos, margin));
            FOOTPRINTS.put(rt, registration);
        } catch (IllegalArgumentException already) {
            rt.once("footprints", "Living Villages footprint source was already registered for this server");
        }
    }

    static void closeFootprints(LvRuntime rt) {
        var registration = FOOTPRINTS.remove(rt);
        if (registration != null) {
            try {
                registration.close();
            } catch (RuntimeException ignored) {
                // The service may already be detached at shutdown.
            }
        }
    }

    /** A bound village's settlement bounds, as a footprint: later structure, POI and MCA discovery inside it joins that settlement. */
    private static List<VillageFootprint> footprints(LvRuntime rt, net.minecraft.server.level.ServerLevel level, BlockPos pos, int margin) {
        if (!level.dimension().equals(Level.OVERWORLD)) return List.of();
        var out = new ArrayList<VillageFootprint>();
        for (var binding : rt.view().bindings.values()) {
            if (binding.status() != BindingStatus.BOUND || binding.settlement() == null) continue;
            var settlement = rt.kingdoms().getSettlement(binding.settlement()).orElse(null);
            if (settlement == null) continue;
            var bounds = settlement.bounds();
            var box = new BoundingBox(bounds.minX(), level.getMinBuildHeight(), bounds.minZ(), bounds.maxX(), level.getMaxBuildHeight() - 1, bounds.maxZ());
            if (!box.inflatedBy(margin).isInside(pos)) continue;
            out.add(new VillageFootprint(Level.OVERWORLD, STRUCTURE, new ChunkPos(settlement.anchor()), box));
        }
        return out;
    }

    // ---- batches -------------------------------------------------------------------------------------------------------

    /** One bounded batch: the next changed villages, or the next slice of the initial import. */
    static void batch(LvRuntime rt) {
        if (!rt.data.writable()) return;
        var next = rt.data.snapshot();
        var all = LvSnapshots.villages(rt.server, rt.access);
        var byId = new LinkedHashMap<UUID, LvSnapshots.Village>();
        all.forEach(v -> byId.put(v.id(), v));
        List<UUID> work = new ArrayList<>();
        boolean migrationMoved = false;
        if (!next.migration.complete) {
            migrationMoved = true;
            // Initial import: resumable by village id across restarts; a repeated pass is a no-op for completed bindings.
            UUID cursor = next.migration.cursor;
            for (var v : all) {
                if (cursor != null && v.id().compareTo(cursor) <= 0) continue;
                work.add(v.id());
                if (work.size() >= LvConfig.batch()) break;
            }
            if (work.isEmpty()) {
                next.migration.complete = true;
                next.migration.completed = rt.now();
                LvRuntime.LOGGER.info("[MCA: Kingdoms] Living Villages import complete: {} bound, {} preserved, {} pending, {} ambiguous, "
                        + "{} realm-only player factions, {} unsupported", next.migration.imported, next.migration.preserved,
                        next.migration.pending, next.migration.ambiguous, next.migration.virtualRealms, next.migration.unsupported);
            } else {
                next.migration.cursor = work.get(work.size() - 1);
            }
        }
        var iterator = rt.changed.iterator();
        while (iterator.hasNext() && work.size() < LvConfig.batch() * 2) {
            UUID id = iterator.next();
            iterator.remove();
            if (!work.contains(id)) work.add(id);
        }
        if (work.isEmpty() && !migrationMoved) return;
        boolean changed = realms(rt, next, all);
        for (UUID id : work) {
            var village = byId.get(id);
            if (village == null) changed |= providerRemoved(rt, next, id);
            else changed |= reconcile(rt, next, village, !next.migration.complete);
        }
        // The import cursor moved, or a record really changed: only then is the store written.
        if (changed || migrationMoved) rt.commit(next);
    }

    /** Every reconciliation interval: a bounded slice of known bindings, and any village the change queue missed. */
    static void slice(LvRuntime rt) {
        var view = rt.view();
        int budget = LvConfig.batch();
        var known = new ArrayList<>(view.bindings.keySet());
        Collections.sort(known);
        int start = 0;
        if (rt.reconcileCursor != null) {
            int at = Collections.binarySearch(known, rt.reconcileCursor);
            start = at >= 0 ? at + 1 : -at - 1;
        }
        for (int i = 0; i < budget && !known.isEmpty(); i++) {
            UUID id = known.get((start + i) % known.size());
            rt.touch(id);
            rt.reconcileCursor = id;
        }
        for (Object village : rt.access.villages(rt.server)) {
            UUID id = rt.access.id(village);
            if (!view.bindings.containsKey(id)) rt.touch(id);
            // The people of a materialized integrated village are on the resident index (the provider tags them after they join).
            if (LvResidents.integrated(rt, id) && rt.access.materialized(id))
                for (UUID body : rt.access.trackedCivilians(id)) {
                    var entity = rt.overworld().getEntity(body);
                    if (entity != null && entity.isAlive() && !rt.view().residents.containsKey(body))
                        LvResidents.record(rt, entity, id, LvState.Lifecycle.MATERIALIZED, LvState.Role.CIVILIAN, null);
                }
        }
        // A restored provider backup: keep tracking the epoch so a later return to normal is visible to operators.
        long steps = rt.access.elapsedSteps(rt.server);
        if (steps > view.provenance.highestElapsedSteps || steps != view.provenance.elapsedSteps) {
            var next = rt.data.snapshot();
            next.provenance.elapsedSteps = steps;
            next.provenance.highestElapsedSteps = Math.max(next.provenance.highestElapsedSteps, steps);
            rt.data.markChanged(next);
        }
    }

    // ---- realms ----------------------------------------------------------------------------------------------------------

    /** Maps every native capital to a realm id, keeping ids across capital changes. Returns whether anything changed. */
    static boolean realms(LvRuntime rt, LvState next, List<LvSnapshots.Village> all) {
        if (!LvConfig.autoRealms()) return false;
        var members = new LinkedHashMap<UUID, Set<UUID>>();
        var capitals = new LinkedHashMap<UUID, LvSnapshots.Village>();
        for (var v : all) {
            members.computeIfAbsent(v.realmCapital(), k -> new LinkedHashSet<>()).add(v.id());
            if (v.isCapital()) capitals.put(v.id(), v);
        }
        var observed = new ArrayList<LvPolicy.Capital>();
        capitals.values().forEach(c -> observed.add(new LvPolicy.Capital(c.id(), c.team(), members.getOrDefault(c.id(), Set.of(c.id())), c.owner() != null)));
        var assignment = LvPolicy.assign(next.realms.values(), observed, UUID::randomUUID);
        boolean changed = false;
        long now = rt.now();
        var assignedRealms = new HashSet<>(assignment.values());
        for (var entry : assignment.entrySet()) {
            var capital = capitals.get(entry.getKey());
            UUID realmId = entry.getValue();
            var before = next.realms.get(realmId);
            var memberList = members.getOrDefault(capital.id(), Set.of(capital.id())).stream().sorted().toList();
            var teams = new ArrayList<String>();
            if (!capital.team().isEmpty()) teams.add(capital.team());
            if (before != null) before.teams().stream().filter(t -> !teams.contains(t)).limit(15).forEach(teams::add);
            var former = new ArrayList<UUID>(before == null ? List.of() : before.formerCapitals());
            if (before != null && before.capital() != null && !before.capital().equals(capital.id()) && !former.contains(before.capital())) {
                former.add(before.capital());
                while (former.size() > 16) former.remove(0);
            }
            var realm = new Realm(realmId, capital.name(), capital.id(), memberList, teams,
                    capital.owner() == null ? Leadership.AI : Leadership.PLAYER, capital.owner(), RealmStatus.ACTIVE,
                    before == null ? now : before.created(), now, former);
            if (before == null) {
                next.record(EventKind.REALM_FORMED, now, settlementOf(next, capital.id()), realmId, null, null, capital.name(), false);
                changed = true;
            } else if (!Objects.equals(before.capital(), capital.id())) {
                next.record(EventKind.CAPITAL_CHANGED, now, settlementOf(next, capital.id()), realmId, null, null,
                        before.name() + " → " + capital.name(), true);
                changed = true;
            }
            if (!realm.equals(before) && (before == null || !sameExceptSeen(before, realm))) changed = true;
            next.realms.put(realmId, realm);
            for (UUID member : memberList) {
                var binding = next.bindings.get(member);
                if (binding != null && !realmId.equals(binding.realm())) {
                    next.bindings.put(member, new Binding(binding.village(), binding.settlement(), binding.dimension(), binding.status(),
                            binding.providerName(), binding.aliases(), binding.claim(), binding.retiredClaims(), realmId,
                            binding.recognizedRealm() == null ? realmId : binding.recognizedRealm(), binding.adopted(),
                            binding.importedAt(), binding.lastSeen(), binding.reason(), binding.candidates()));
                    changed = true;
                }
            }
        }
        for (var realm : List.copyOf(next.realms.values())) {
            if (realm.status() != RealmStatus.ACTIVE || assignedRealms.contains(realm.id())) continue;
            // Its capital and every member now belong to other realms, or the player faction was disbanded.
            boolean disbanded = realm.leadership() == Leadership.PLAYER && all.stream().noneMatch(v -> v.id().equals(realm.capital()));
            var status = disbanded ? RealmStatus.DISBANDED : RealmStatus.DEFEATED;
            next.realms.put(realm.id(), new Realm(realm.id(), realm.name(), realm.capital(), List.of(), realm.teams(), realm.leadership(),
                    realm.owner(), status, realm.created(), now, realm.formerCapitals()));
            next.record(disbanded ? EventKind.REALM_DISBANDED : EventKind.REALM_DEFEATED, now, settlementOf(next, realm.capital()),
                    realm.id(), null, null, realm.name(), true);
            // Agreements of a realm that no longer exists stop binding anyone; they stay on record.
            for (var treaty : List.copyOf(next.treaties.values()))
                if (treaty.status() == TreatyStatus.ACTIVE && (treaty.first().equals("realm:" + realm.id()) || treaty.second().equals("realm:" + realm.id())))
                    next.treaties.put(treaty.id(), treaty.to(TreatyStatus.EXPIRED, "The realm " + realm.name() + " no longer exists.", now));
            changed = true;
        }
        return changed;
    }

    private static boolean sameExceptSeen(Realm a, Realm b) {
        return a.id().equals(b.id()) && a.name().equals(b.name()) && Objects.equals(a.capital(), b.capital()) && a.members().equals(b.members())
                && a.teams().equals(b.teams()) && a.leadership() == b.leadership() && Objects.equals(a.owner(), b.owner()) && a.status() == b.status();
    }

    static UUID settlementOf(LvState state, UUID village) {
        var binding = state.bindings.get(village);
        return binding == null ? null : binding.settlement();
    }

    // ---- villages --------------------------------------------------------------------------------------------------------

    private static boolean providerRemoved(LvRuntime rt, LvState next, UUID id) {
        var binding = next.bindings.get(id);
        if (binding == null || binding.status() == BindingStatus.PROVIDER_REMOVED) return false;
        // Absence of the provider record is not destruction: the settlement and its history stay; only the binding is marked.
        next.bindings.put(id, binding.with(binding.settlement(), BindingStatus.PROVIDER_REMOVED, "provider_record_removed", List.of()));
        next.record(EventKind.RECOVERY, rt.now(), binding.settlement(), binding.realm(), null, null, "Living Villages no longer lists this village", false);
        return true;
    }

    /** Reconciles one village. {@code importing} is true during the initial existing-world import (no rewards, baseline control). */
    static boolean reconcile(LvRuntime rt, LvState next, LvSnapshots.Village village, boolean importing) {
        var before = next.bindings.get(village.id());
        long now = rt.now();
        UUID realm = next.realmOfVillage(village.id()).map(Realm::id).orElse(before == null ? null : before.realm());
        if (village.virtual()) {
            // A player faction mirrored before its Ledger is placed: a realm, never a settlement at the origin.
            if (before == null) {
                next.bindings.put(village.id(), new Binding(village.id(), null, "minecraft:overworld", BindingStatus.VIRTUAL, village.name(),
                        List.of(), null, List.of(), realm, realm, false, now, now, "no_ledger", List.of()));
                if (importing) next.migration.virtualRealms++;
                return true;
            }
            return false;
        }
        if (before != null && before.status() == BindingStatus.BOUND) {
            var updated = before.observed(village.name(), village.claim().orElse(null), realm, now);
            boolean claimChanged = before.claim() != null && village.claim().isPresent() && !before.claim().equals(village.claim().get());
            if (claimChanged) {
                // The provider replaced the claim at this village's own centre: the village record is the succession evidence.
                next.record(EventKind.RECOVERY, now, before.settlement(), realm, null, null,
                        "Claim " + before.claim() + " was replaced by " + village.claim().get() + " at the same village", false);
            }
            if (!Objects.equals(before.realm(), realm) && before.realm() != null && realm != null && !LvWarBridge.conquestPending(next, village.id())) {
                // Control moved without an observed conquest (an operator edit, a restored backup, a disband): history only.
                next.record(EventKind.RECOVERY, now, before.settlement(), realm, before.realm(), null,
                        "Control changed without an observed conquest; recorded as an administrative change", false);
            }
            boolean meaningful = !updated.providerName().equals(before.providerName()) || !Objects.equals(updated.claim(), before.claim())
                    || !Objects.equals(updated.realm(), before.realm()) || now - before.lastSeen() >= 24000;
            if (meaningful) next.bindings.put(village.id(), updated);
            return meaningful;
        }
        if (!LvConfig.autoBind() && before == null) return false;
        return bind(rt, next, village, before, realm, importing);
    }

    private static boolean bind(LvRuntime rt, LvState next, LvSnapshots.Village village, Binding before, UUID realm, boolean importing) {
        long now = rt.now();
        var level = rt.overworld();
        var kingdoms = rt.kingdoms();
        // The inspected provider store and the Kingdoms military lookup are Overworld-only; nothing else is claimed.
        var existing = kingdoms.getSettlementByExternalRef(EXTERNAL_REF, village.id().toString());
        if (existing.isPresent()) {
            put(next, village, existing.get().id(), BindingStatus.BOUND, "provider_reference", List.of(), realm, before, now);
            if (importing) next.migration.preserved++;
            return true;
        }
        var candidates = candidates(rt, next, village);
        if (candidates.size() > 1) {
            put(next, village, null, BindingStatus.AMBIGUOUS, "several_settlements", candidates.stream().map(SettlementView::id).limit(8).toList(), realm, before, now);
            if (importing && (before == null || before.status() != BindingStatus.AMBIGUOUS)) next.migration.ambiguous++;
            return true;
        }
        try {
            SettlementView bound;
            String reason;
            if (candidates.size() == 1) {
                var target = candidates.get(0);
                // Registering with the existing settlement's own anchor and bounds lands on it exactly; it keeps its UUID,
                // name, kingdom, discovery and history, and gains this village's provider reference and footprint.
                var candidate = SettlementCandidate.external(Level.OVERWORLD, target.anchor(), target.radius(), target.bounds(),
                        SOURCE, sourceKey(village.id()), null, Map.of(EXTERNAL_REF, village.id().toString()), null);
                bound = kingdoms.registerCandidate(level, candidate);
                if (!bound.id().equals(target.id())) {
                    put(next, village, null, BindingStatus.AMBIGUOUS, "registration_diverged", List.of(target.id(), bound.id()), realm, before, now);
                    return true;
                }
                reason = "matched_existing";
                if (importing) next.migration.preserved++;
                // With preserveExistingNames off, an unlocked settlement takes the Living Villages name and keeps its own as a
                // former name. A locked name, or one another settlement holds, stays as it is.
                if (!LvConfig.preserveNames() && !bound.nameLocked() && !village.name().isBlank() && !village.name().equals(bound.displayName())) {
                    String previous = bound.displayName();
                    try {
                        bound = kingdoms.rename(bound.id(), village.name());
                        next.record(EventKind.RENAMED, now, bound.id(), realm, null, null, previous + " → " + bound.displayName(), false);
                    } catch (IllegalArgumentException kept) {
                        rt.once("name:" + village.id(), "{} keeps its name: Living Villages' name {} is not available ({})", previous, village.name(), kept.getMessage());
                    }
                }
            } else {
                int radius = Math.max(32, rt.access.number("CLAIM_RADIUS_CHUNKS", 2) * 16 + 8);
                BlockPos anchor = village.center();
                var candidate = new SettlementCandidate(Level.OVERWORLD, anchor, radius, SettlementBounds.around(anchor, radius), SOURCE,
                        sourceKey(village.id()), DetectionSource.EXTERNAL, Optional.empty(), Optional.empty(),
                        Map.of(EXTERNAL_REF, village.id().toString()), Optional.of(village.name()));
                try {
                    bound = kingdoms.registerCandidate(level, candidate);
                } catch (IllegalArgumentException nameTaken) {
                    if (!String.valueOf(nameTaken.getMessage()).contains("name")) throw nameTaken;
                    // The provider's name belongs to another settlement: Kingdoms names this one; the provider name stays an alias here.
                    bound = kingdoms.registerCandidate(level, new SettlementCandidate(Level.OVERWORLD, anchor, radius, SettlementBounds.around(anchor, radius),
                            SOURCE, sourceKey(village.id()), DetectionSource.EXTERNAL, Optional.empty(), Optional.empty(),
                            Map.of(EXTERNAL_REF, village.id().toString()), Optional.empty()));
                }
                var other = next.bindingForSettlement(bound.id()).filter(b -> !b.village().equals(village.id()));
                if (other.isPresent()) {
                    // Kingdoms resolved the candidate to a settlement another provider village already is: keep both apart.
                    put(next, village, null, BindingStatus.AMBIGUOUS, "shares_settlement", List.of(bound.id()), realm, before, now);
                    return true;
                }
                reason = "imported";
                if (importing) next.migration.imported++;
            }
            put(next, village, bound.id(), BindingStatus.BOUND, reason, List.of(), realm, before, now);
            next.record(importing ? EventKind.BASELINE_CONTROL : EventKind.IMPORTED, now, bound.id(), realm, null, null,
                    village.name(), false);
            return true;
        } catch (IllegalArgumentException | IllegalStateException pending) {
            // Unresolved culture, a definition reload, a refused preflight: keep it pending and try again later.
            put(next, village, null, BindingStatus.PENDING, reasonKey(pending), List.of(), realm, before, now);
            if (importing && (before == null || before.status() != BindingStatus.PENDING)) next.migration.pending++;
            rt.once("pending:" + village.id(), "Living Villages village {} is waiting to be bound: {}", village.name(), pending.getMessage());
            return true;
        }
    }

    private static String reasonKey(RuntimeException failure) {
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("unknown kingdom")) return "culture_unresolved";
        if (message.contains("preflight") || message.contains("refuse")) return "preflight_refused";
        if (message.contains("already owned")) return "reference_conflict";
        return "registration_failed";
    }

    static String sourceKey(UUID village) {
        return "recruitsvillages:village:" + village;
    }

    private static void put(LvState next, LvSnapshots.Village village, UUID settlement, BindingStatus status, String reason,
                            List<UUID> candidates, UUID realm, Binding before, long now) {
        var binding = new Binding(village.id(), settlement, "minecraft:overworld", status, village.name(),
                before == null ? List.of() : before.aliases(), village.claim().orElse(before == null ? null : before.claim()),
                before == null ? List.of() : before.retiredClaims(), realm,
                before != null && before.recognizedRealm() != null ? before.recognizedRealm() : realm,
                before != null && before.adopted(), before == null ? now : before.importedAt(), now, reason, candidates);
        next.bindings.put(village.id(), binding);
    }

    /**
     * Settlements that may be this village: in the Overworld, containing its centre or anchored within the match distance,
     * and not already another provider village. Never by name.
     */
    static List<SettlementView> candidates(LvRuntime rt, LvState state, LvSnapshots.Village village) {
        var out = new ArrayList<SettlementView>();
        var kingdoms = rt.kingdoms();
        var level = rt.overworld();
        int distance = LvConfig.matchDistance();
        long limit = (long) distance * distance;
        var direct = kingdoms.getSettlementAt(level, village.center());
        direct.ifPresent(out::add);
        if (kingdoms instanceof KingdomsServiceImpl impl) {
            for (var view : impl.orderedSettlements(Optional.empty())) {
                if (!view.dimension().equals(Level.OVERWORLD) || out.stream().anyMatch(v -> v.id().equals(view.id()))) continue;
                long dx = view.anchor().getX() - village.center().getX(), dz = view.anchor().getZ() - village.center().getZ();
                if (dx * dx + dz * dz <= limit || view.bounds().contains(village.center())) out.add(view);
            }
        }
        out.removeIf(view -> state.bindingForSettlement(view.id()).filter(b -> !b.village().equals(village.id())).isPresent()
                || kingdoms.getSettlementExternalRefs(view.id()).getOrDefault(EXTERNAL_REF, Set.of()).stream()
                .anyMatch(ref -> !ref.equals(village.id().toString())));
        out.sort(Comparator.comparing(SettlementView::id));
        return out;
    }

    // ---- ground Living Villages may build on ------------------------------------------------------------------------------

    /**
     * Why Living Villages may not raise a building of {@code footprint} centred on ({@code x}, {@code z}) for {@code village},
     * or empty while {@code protectExternalBuildings} allows it: another settlement's ground (any but the village's own),
     * a player's protected home, or a block entity already standing there (someone's bed, chest, furnace or workstation;
     * the provider keeps new buildings clear of its own). The provider then tries its next spot, and nothing is built.
     */
    static Optional<String> buildingRefusal(LvRuntime rt, UUID village, String name, int x, int z, Vec3i footprint) {
        int hx = footprint.getX() / 2 + 1, hz = footprint.getZ() / 2 + 1;
        var box = new SettlementBounds(x - hx, z - hz, x + hx, z + hz);
        var binding = rt.view().bindings.get(village);
        var refusal = groundRefusal(rt, binding == null ? null : binding.settlement(), box);
        if (refusal.isEmpty()) refusal = standingBlockEntity(rt.overworld(), box, footprint.getY());
        refusal.ifPresent(reason -> rt.once("site:" + village + ":" + reason,
                "Living Villages may not build for {} at {}, {} ({}; protectExternalBuildings): it tries another spot", name, x, z, reason));
        return refusal;
    }

    /** Why Living Villages may not found a natural village centred on {@code center}, or empty: its building ground is free. */
    static Optional<String> foundingRefusal(LvRuntime rt, BlockPos center) {
        int half = Math.max(rt.access.number("VILLAGE_BUILD_RADIUS", 60), rt.access.number("PLOT_FLATTEN_RADIUS", 24));
        var refusal = groundRefusal(rt, null, SettlementBounds.around(center, half));
        refusal.ifPresent(reason -> rt.once("found:" + center.asLong(),
                "Living Villages may not found a village at {} ({}; protectExternalBuildings)", center.toShortString(), reason));
        return refusal;
    }

    private static Optional<String> groundRefusal(LvRuntime rt, UUID own, SettlementBounds box) {
        if (rt.kingdoms() instanceof KingdomsServiceImpl impl)
            for (var view : impl.orderedSettlements(Optional.empty()))
                if (view.dimension().equals(Level.OVERWORLD) && !view.id().equals(own) && view.bounds().intersects(box)) return Optional.of("other_settlement");
        String overworld = Level.OVERWORLD.location().toString();
        for (var home : DevelopmentSavedData.get(rt.server).records().homes()) {
            if (!home.dimension().equals(overworld)) continue;
            long dx = Math.max(0, Math.max(box.minX() - home.pos().getX(), home.pos().getX() - box.maxX()));
            long dz = Math.max(0, Math.max(box.minZ() - home.pos().getZ(), home.pos().getZ() - box.maxZ()));
            if (dx * dx + dz * dz <= (long) home.radius() * home.radius()) return Optional.of("protected_home");
        }
        return Optional.empty();
    }

    private static Optional<String> standingBlockEntity(ServerLevel level, SettlementBounds box, int height) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                // The provider sampled this ground already; a chunk that is not loaded cannot be checked, so it is not used.
                if (chunk == null) return Optional.of("unloaded");
                for (BlockPos pos : chunk.getBlockEntitiesPos())
                    if (box.contains(pos.getX(), pos.getZ()) && pos.getY() >= ground - 4 && pos.getY() <= ground + height + 4)
                        return Optional.of("existing_building");
            }
        return Optional.empty();
    }

    // ---- operator decisions --------------------------------------------------------------------------------------------------

    /** Binds an ambiguous or pending village to the settlement an operator chose. The settlement keeps everything it has. */
    public static String bindTo(LvRuntime rt, UUID village, UUID settlement, UUID operator) {
        var next = rt.data.snapshot();
        var binding = next.bindings.get(village);
        if (binding == null) throw LvText.refuse("unknown_village", "That Living Villages village is not known to the bridge.");
        if (binding.status() == BindingStatus.BOUND) throw LvText.refuse("already_bound", "That village is already bound; it is never rebound implicitly.");
        var target = rt.kingdoms().getSettlement(settlement).orElseThrow(() -> LvText.refuse("unknown_settlement", "That settlement is not available."));
        if (!target.dimension().equals(Level.OVERWORLD)) throw LvText.refuse("dimension", "Living Villages villages exist only in the Overworld.");
        if (next.bindingForSettlement(target.id()).filter(b -> !b.village().equals(village)).isPresent())
            throw LvText.refuse("settlement_taken", "That settlement is already another Living Villages village; two villages are never merged.");
        var provider = rt.access.village(rt.server, village).orElseThrow(() -> LvText.refuse("provider_missing", "Living Villages no longer lists that village."));
        var snapshot = LvSnapshots.village(rt.server, rt.access, provider);
        var bound = rt.kingdoms().registerCandidate(rt.overworld(), SettlementCandidate.external(Level.OVERWORLD, target.anchor(), target.radius(),
                target.bounds(), SOURCE, sourceKey(village), null, Map.of(EXTERNAL_REF, village.toString()), null));
        if (!bound.id().equals(target.id())) throw LvText.refuse("diverged", "Kingdoms resolved that village to another settlement; inspect both first.");
        put(next, snapshot, target.id(), BindingStatus.BOUND, "operator", List.of(), binding.realm(), binding, rt.now());
        next.journal.put(UUID.randomUUID(), new Operation(UUID.randomUUID(), OperationKind.ADMINISTRATIVE, target.id(), null,
                OperationState.PUBLISHED, binding.status().name(), "BOUND", rt.now(), rt.now(), rt.now(), true,
                "Operator bound village " + snapshot.name(), ActorKind.ADMINISTRATOR, operator));
        next.record(EventKind.IMPORTED, rt.now(), target.id(), binding.realm(), null, null, snapshot.name() + " (operator)", false);
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return LvText.text("bound_operator", "%s is now bound to %s. Its name, kingdom and history are unchanged.", snapshot.name(), target.displayName());
    }

    /** Creates a separate new settlement for an ambiguous village, keeping the nearby records as they are. */
    public static String createSeparate(LvRuntime rt, UUID village, UUID operator) {
        var next = rt.data.snapshot();
        var binding = next.bindings.get(village);
        if (binding == null || binding.status() == BindingStatus.BOUND) throw LvText.refuse("not_ambiguous", "That village is not waiting for a decision.");
        var provider = rt.access.village(rt.server, village).orElseThrow(() -> LvText.refuse("provider_missing", "Living Villages no longer lists that village."));
        var snapshot = LvSnapshots.village(rt.server, rt.access, provider);
        // A small manual-ranked footprint at the village centre, so Kingdoms does not fold it into a neighbour.
        int radius = 24;
        var candidate = SettlementCandidate.manual(Level.OVERWORLD, snapshot.center(), radius, SOURCE, sourceKey(village), snapshot.name());
        var created = rt.kingdoms().registerCandidate(rt.overworld(), new SettlementCandidate(candidate.dimension(), candidate.anchor(), radius,
                candidate.bounds(), SOURCE, sourceKey(village), DetectionSource.MANUAL, Optional.empty(), Optional.empty(),
                Map.of(EXTERNAL_REF, village.toString()), candidate.proposedName()));
        if (next.bindingForSettlement(created.id()).isPresent() || binding.candidates().contains(created.id()))
            throw LvText.refuse("would_merge", "Kingdoms would fold this village into an existing settlement; bind it to that settlement instead.");
        put(next, snapshot, created.id(), BindingStatus.BOUND, "operator_new", List.of(), binding.realm(), binding, rt.now());
        next.record(EventKind.IMPORTED, rt.now(), created.id(), binding.realm(), null, null, snapshot.name() + " (operator, separate)", false);
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return LvText.text("bound_separate", "%s is now its own settlement, %s.", snapshot.name(), created.displayName());
    }

    /**
     * Renames a bound village in Kingdoms and Living Villages together. The provider's display name is the only provider
     * field written; team ids and references never change. A locked Kingdoms name refuses the whole rename.
     */
    public static String rename(LvRuntime rt, UUID settlement, String name, ActorKind actorKind, UUID actor) {
        var next = rt.data.snapshot();
        var binding = next.bindingForSettlement(settlement).orElseThrow(() -> LvText.refuse("not_bound", "That settlement is not a Living Villages village."));
        var provider = rt.access.village(rt.server, binding.village()).orElseThrow(() -> LvText.refuse("provider_missing", "Living Villages no longer lists that village."));
        String previous = rt.access.name(provider);
        UUID op = UUID.randomUUID();
        next.journal.put(op, new Operation(op, OperationKind.RENAME, settlement, null, OperationState.INTENT, previous, name, rt.now(), 0, 0,
                false, "rename", actorKind, actor));
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        var renamed = rt.kingdoms().rename(settlement, name); // refuses a locked or taken name before anything else changes
        rt.access.setName(provider, renamed.displayName());
        rt.access.dirty(rt.server);
        next = rt.data.snapshot();
        next.journal.put(op, next.journal.get(op).state(OperationState.APPLIED, renamed.displayName(), rt.now()));
        var b = next.bindings.get(binding.village());
        next.bindings.put(binding.village(), b.observed(renamed.displayName(), null, b.realm(), rt.now()));
        next.record(EventKind.RENAMED, rt.now(), settlement, binding.realm(), null, null, previous + " → " + renamed.displayName(), false);
        rt.commit(next);
        rt.awaitingSave.add(op);
        return LvText.text("renamed", "%s is now %s in both MCA: Kingdoms and Living Villages; its banner, team and records are unchanged.",
                previous, renamed.displayName());
    }

    // ---- merge protection ------------------------------------------------------------------------------------------------------

    /** A settlement that is a Living Villages village is never merged automatically; an operator rebinds it through the bridge. */
    @SubscribeEvent
    public static void preflight(SettlementMutationPreflightEvent event) {
        if (BRIDGE_MERGE.get() || event.mergeTarget().isEmpty()) return;
        var runtime = LvRuntime.get(event.server()).orElse(null);
        var data = runtime == null ? (LvCompat.installed() ? LvBridgeSavedData.get(event.server()) : null) : runtime.data;
        if (data == null) return;
        var state = data.view();
        UUID source = event.source().id();
        UUID target = event.mergeTarget().get().id();
        if (state.bindingForSettlement(source).isPresent() || state.bindingForSettlement(target).isPresent())
            event.reject(LvText.text("merge_refused", "This settlement is a Recruits: Living Villages village; it is never merged with another record. Ask an operator to inspect it with /kingdoms livingvillages inspect."));
    }
}
