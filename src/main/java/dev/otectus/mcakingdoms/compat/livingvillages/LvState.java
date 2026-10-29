package dev.otectus.mcakingdoms.compat.livingvillages;

import java.util.*;

/**
 * Everything the Living Villages bridge keeps, as one versioned document. Provider facts are not copied here: these are
 * the identities, decisions and receipts Kingdoms owns about provider records (which settlement a village is, which realm
 * a faction is, what conflict a native war is, what was agreed, and what was observed to have happened).
 */
public final class LvState {
    public static final int SCHEMA = 1;
    public static final int BINDINGS = 4096, REALMS = 4096, CONFLICTS = 2048, JOURNAL = 4096, TREATIES = 1024,
            RESIDENTS = 16384, HISTORY = 2048, ASSOCIATIONS = 512, ADOPTIONS = 512, CURSORS = 4096, REFUSALS = 256;

    public enum BindingStatus { BOUND, PENDING, AMBIGUOUS, VIRTUAL, UNSUPPORTED_DIMENSION, PROVIDER_REMOVED }
    public enum Leadership { AI, PLAYER }
    public enum RealmStatus { ACTIVE, DEFEATED, DISBANDED, UNAVAILABLE }
    public enum Phase { PROPOSED, NOTICE, ACTIVE_LIVE, ACTIVE_ABSTRACT, RESOLVING, COMPLETED, REPELLED, WITHDRAWN, CANCELLED, SUSPENDED, RECOVERY_REQUIRED }
    public enum Path { LIVE, ABSTRACT }
    public enum OperationKind { IMPORT, CONQUEST, PEACE, RELATION, RELIEF, RENAME, ADOPTION, CAPITAL_CHANGE, ADMINISTRATIVE }
    public enum OperationState { INTENT, APPLIED, ACKNOWLEDGED, PUBLISHED, SUSPENDED, ABANDONED }
    public enum Term { PEACE, CEASEFIRE, ALLIANCE, CEDE }
    public enum TreatyStatus { PROPOSED, ACTIVE, BREACHED, EXPIRED, REJECTED, SUSPENDED, WITHDRAWN }
    public enum Role { CIVILIAN, WORKER }
    public enum Lifecycle { MATERIALIZED, UNLOADED, AWAY, DEAD, UNRESOLVED, EXTERNAL }
    public enum EventKind {
        IMPORTED, BASELINE_CONTROL, WAR_DECLARED, SIEGE_LAID, BATTLE_BEGAN, PATH_CHANGED, CONQUERED, REPELLED, WITHDRAWN,
        CANCELLED, CAPITAL_CHANGED, REALM_FORMED, REALM_DEFEATED, REALM_DISBANDED, TREATY_ACTIVE, TREATY_BREACHED,
        TREATY_EXPIRED, TREATY_REJECTED, RELATION_CHANGED, RELIEF, ADOPTED, RENAMED, RECOVERY, SOVEREIGNTY_CEDED, REFUSED
    }
    /** Who caused something: a player (with their id), a provider-run realm (with its realm id), or an operator. */
    public enum ActorKind { PLAYER, PROVIDER_REALM, ADMINISTRATOR, PROVIDER }

    /** One Living Villages village and the settlement it is. {@code settlement} is null until it can be bound. */
    public record Binding(UUID village, UUID settlement, String dimension, BindingStatus status, String providerName,
                          List<String> aliases, UUID claim, List<UUID> retiredClaims, UUID realm, UUID recognizedRealm,
                          boolean adopted, long importedAt, long lastSeen, String reason, List<UUID> candidates) {
        public Binding {
            Objects.requireNonNull(village); Objects.requireNonNull(status);
            dimension = dimension == null ? "minecraft:overworld" : dimension;
            providerName = providerName == null ? "" : providerName;
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
            retiredClaims = List.copyOf(retiredClaims == null ? List.of() : retiredClaims);
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
            reason = reason == null ? "" : reason;
            if (aliases.size() > 16 || retiredClaims.size() > 16 || candidates.size() > 8) throw new IllegalArgumentException("Binding history too long");
        }
        public Binding with(UUID settlement, BindingStatus status, String reason, List<UUID> candidates) {
            return new Binding(village, settlement, dimension, status, providerName, aliases, claim, retiredClaims, realm,
                    recognizedRealm, adopted, importedAt, lastSeen, reason, candidates);
        }
        public Binding observed(String name, UUID claim, UUID realm, long now) {
            var names = new ArrayList<>(aliases);
            if (!name.isBlank() && !name.equals(providerName) && !providerName.isBlank() && !names.contains(providerName)) {
                names.add(providerName);
                while (names.size() > 16) names.remove(0);
            }
            var retired = new ArrayList<>(retiredClaims);
            if (this.claim != null && claim != null && !this.claim.equals(claim) && !retired.contains(this.claim)) {
                retired.add(this.claim);
                while (retired.size() > 16) retired.remove(0);
            }
            return new Binding(village, settlement, dimension, status, name.isBlank() ? providerName : name, names,
                    claim == null ? this.claim : claim, retired, realm, recognizedRealm == null ? realm : recognizedRealm,
                    adopted, importedAt, now, reason, candidates);
        }
        public Binding recognize(UUID sovereign) {
            return new Binding(village, settlement, dimension, status, providerName, aliases, claim, retiredClaims, realm,
                    sovereign, adopted, importedAt, lastSeen, reason, candidates);
        }
        public boolean occupied() {
            return realm != null && recognizedRealm != null && !realm.equals(recognizedRealm);
        }
    }

    /** A sovereign Living Villages faction: a capital and its villages. Survives a change of capital or team name. */
    public record Realm(UUID id, String name, UUID capital, List<UUID> members, List<String> teams, Leadership leadership,
                        UUID owner, RealmStatus status, long created, long lastSeen, List<UUID> formerCapitals) {
        public Realm {
            Objects.requireNonNull(id); Objects.requireNonNull(leadership); Objects.requireNonNull(status);
            name = name == null ? "" : name;
            members = List.copyOf(members == null ? List.of() : members);
            teams = List.copyOf(teams == null ? List.of() : teams);
            formerCapitals = List.copyOf(formerCapitals == null ? List.of() : formerCapitals);
            if (members.size() > 512 || teams.size() > 16 || formerCapitals.size() > 16) throw new IllegalArgumentException("Realm too large");
        }
        public String team() {
            return teams.isEmpty() ? "" : teams.get(0);
        }
    }

    public record Transition(long at, Phase phase, Path path, String detail) {
        public Transition { Objects.requireNonNull(phase); detail = detail == null ? "" : detail; }
    }

    /** One provider conflict, from the moment the bridge accepted it until its outcome was recorded. */
    public record Conflict(UUID id, UUID attackerRealm, UUID attackerVillage, UUID defenderVillage, UUID defenderRealm,
                           UUID settlement, UUID claim, List<UUID> attackers, List<UUID> defenders, List<UUID> neutral,
                           Phase phase, Path path, boolean undeclared, UUID initiator, long started, long updated,
                           int attackerStart, int defenderStart, String reason, UUID receipt, List<Transition> transitions) {
        public Conflict {
            Objects.requireNonNull(id); Objects.requireNonNull(attackerVillage); Objects.requireNonNull(defenderVillage);
            Objects.requireNonNull(phase); Objects.requireNonNull(path);
            attackers = List.copyOf(attackers == null ? List.of() : attackers);
            defenders = List.copyOf(defenders == null ? List.of() : defenders);
            neutral = List.copyOf(neutral == null ? List.of() : neutral);
            transitions = List.copyOf(transitions == null ? List.of() : transitions);
            reason = reason == null ? "" : reason;
        }
        public boolean open() {
            return switch (phase) { case PROPOSED, NOTICE, ACTIVE_LIVE, ACTIVE_ABSTRACT, RESOLVING, SUSPENDED -> true; default -> false; };
        }
        public Conflict to(Phase next, Path path, long now, String detail) {
            var log = new ArrayList<>(transitions);
            log.add(new Transition(now, next, path, detail));
            while (log.size() > 24) log.remove(0);
            return new Conflict(id, attackerRealm, attackerVillage, defenderVillage, defenderRealm, settlement, claim, attackers,
                    defenders, neutral, next, path, undeclared, initiator, started, now, attackerStart, defenderStart, detail, receipt, log);
        }
        public Conflict sides(List<UUID> attackers, List<UUID> defenders, List<UUID> neutral) {
            return new Conflict(id, attackerRealm, attackerVillage, defenderVillage, defenderRealm, settlement, claim, attackers,
                    defenders, neutral, phase, path, undeclared, initiator, started, updated, attackerStart, defenderStart, reason, receipt, transitions);
        }
        public Conflict receipt(UUID receipt) {
            return new Conflict(id, attackerRealm, attackerVillage, defenderVillage, defenderRealm, settlement, claim, attackers,
                    defenders, neutral, phase, path, undeclared, initiator, started, updated, attackerStart, defenderStart, reason, receipt, transitions);
        }
    }

    /** One consequential cross-mod operation: intent before the provider acts, then what was seen, then acknowledgement. */
    public record Operation(UUID id, OperationKind kind, UUID subject, UUID conflict, OperationState state, String before,
                            String after, long created, long applied, long acknowledged, boolean published, String detail,
                            ActorKind actorKind, UUID actor) {
        public Operation {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(state); Objects.requireNonNull(actorKind);
            before = before == null ? "" : before; after = after == null ? "" : after; detail = detail == null ? "" : detail;
            if (before.length() > 2048 || after.length() > 2048 || detail.length() > 1024) throw new IllegalArgumentException("Operation too large");
        }
        public Operation state(OperationState next, String after, long at) {
            return new Operation(id, kind, subject, conflict, next, before, after == null ? this.after : after, created,
                    next == OperationState.APPLIED ? at : applied, next == OperationState.ACKNOWLEDGED ? at : acknowledged,
                    published || next == OperationState.PUBLISHED, detail, actorKind, actor);
        }
    }

    public record Signature(ActorKind kind, UUID actor, long at, String reason) {
        public Signature { Objects.requireNonNull(kind); reason = reason == null ? "" : reason; }
    }

    /** An agreement between two parties ({@code realm:<id>} or {@code kingdom:<id>}). Its scope is the covered villages. */
    public record Treaty(UUID id, String first, String second, Term term, TreatyStatus status, Map<String, Signature> signatures,
                         List<UUID> covered, long proposed, long activeSince, long expires, long truceUntil, String reason,
                         String decision, int suppressed, String breach) {
        public Treaty {
            Objects.requireNonNull(id); Objects.requireNonNull(first); Objects.requireNonNull(second);
            Objects.requireNonNull(term); Objects.requireNonNull(status);
            if (first.equals(second)) throw new IllegalArgumentException("A treaty needs two parties");
            signatures = Map.copyOf(signatures == null ? Map.of() : signatures);
            covered = List.copyOf(covered == null ? List.of() : covered);
            reason = reason == null ? "" : reason; decision = decision == null ? "" : decision; breach = breach == null ? "" : breach;
            if (covered.size() > 1024 || reason.length() > 512 || decision.length() > 512) throw new IllegalArgumentException("Treaty too large");
        }
        public Treaty to(TreatyStatus next, String decision, long now) {
            return new Treaty(id, first, second, term, next, signatures, covered, proposed,
                    next == TreatyStatus.ACTIVE && activeSince == 0 ? now : activeSince, expires, truceUntil, reason,
                    decision == null ? this.decision : decision, suppressed, breach);
        }
        public Treaty signed(String party, Signature signature) {
            var all = new LinkedHashMap<>(signatures); all.put(party, signature);
            return new Treaty(id, first, second, term, status, all, covered, proposed, activeSince, expires, truceUntil, reason, decision, suppressed, breach);
        }
        public Treaty suppressedOnce() {
            return new Treaty(id, first, second, term, status, signatures, covered, proposed, activeSince, expires, truceUntil, reason, decision,
                    Math.min(Integer.MAX_VALUE - 1, suppressed + 1), breach);
        }
        public Treaty breached(String detail, long now) {
            return new Treaty(id, first, second, term, TreatyStatus.BREACHED, signatures, covered, proposed, activeSince, expires, truceUntil,
                    reason, decision, suppressed, detail);
        }
        public boolean binds(String a, String b) {
            return first.equals(a) && second.equals(b) || first.equals(b) && second.equals(a);
        }
        public boolean inForce(long now) {
            return status == TreatyStatus.ACTIVE && (expires <= 0 || now < expires);
        }
    }

    /** A real person living in an integrated village; never replaced, only rebound when the provider changes their body. */
    public record Resident(UUID entity, UUID village, UUID settlement, Role role, UUID slot, Lifecycle lifecycle, boolean mca,
                           String name, List<String> protections, String dimension, int x, int y, int z, long lastSeen,
                           UUID formerEntity) {
        public Resident {
            Objects.requireNonNull(entity); Objects.requireNonNull(role); Objects.requireNonNull(lifecycle);
            name = name == null ? "" : name.length() > 64 ? name.substring(0, 64) : name;
            protections = List.copyOf(protections == null ? List.of() : protections);
            dimension = dimension == null ? "minecraft:overworld" : dimension;
        }
        public Resident at(Lifecycle next, String dimension, int x, int y, int z, long now) {
            return new Resident(entity, village, settlement, role, slot, next, mca, name, protections, dimension, x, y, z, now, formerEntity);
        }
        public Resident life(Lifecycle next, long now) {
            return new Resident(entity, village, settlement, role, slot, next, mca, name, protections, dimension, x, y, z, now, formerEntity);
        }
        public Resident employed(Role role, UUID slot) {
            return new Resident(entity, village, settlement, role, slot, lifecycle, mca, name, protections, dimension, x, y, z, lastSeen, formerEntity);
        }
    }

    public record Event(long sequence, long gameTime, EventKind kind, UUID settlement, UUID realm, UUID otherRealm, UUID conflict,
                        String detail, boolean announce) {
        public Event { Objects.requireNonNull(kind); detail = detail == null ? "" : detail.length() > 512 ? detail.substring(0, 512) : detail; }
    }

    /** An operator's explicit link between a kingdom government and a realm, and what it covers. Never inferred. */
    public record Association(UUID realm, String kingdom, String scope, UUID operator, long at, long revision) {
        public Association { Objects.requireNonNull(realm); Objects.requireNonNull(kingdom); scope = scope == null ? "DIPLOMACY" : scope; }
    }

    /** A Kingdoms settlement placed under Living Villages management on purpose, and what was seeded from it. */
    public record Adoption(UUID settlement, UUID village, UUID operator, long at, int residents, int houses, boolean constructionEnabled,
                           String inventory) {
        public Adoption { Objects.requireNonNull(settlement); Objects.requireNonNull(village); inventory = inventory == null ? "" : inventory; }
    }

    /** A provider operation the bridge refused, kept briefly so operators can see why AI wars did not start. */
    public record Refusal(long at, UUID attacker, UUID defender, String reason) {
        public Refusal { reason = reason == null ? "" : reason; }
    }

    public static final class Migration {
        public int version;
        public boolean complete;
        public long started, completed;
        public UUID cursor;
        public int imported, preserved, pending, ambiguous, virtualRealms, unsupported;
    }

    public static final class Provenance {
        /** Random per world; a provider save restored from another world or an older backup shows up as a regression. */
        public UUID world;
        public String provider = "recruitsvillages", version = "", sha256 = "";
        public long elapsedSteps = -1, highestElapsedSteps = -1;
        public int villages;
        public long regressedAt;
    }

    public int schema = SCHEMA;
    public long revision;
    public long historySequence;
    public Provenance provenance = new Provenance();
    public Migration migration = new Migration();
    public Map<UUID, Binding> bindings = new TreeMap<>();
    public Map<UUID, Realm> realms = new TreeMap<>();
    public Map<UUID, Conflict> conflicts = new LinkedHashMap<>();
    public Map<UUID, Operation> journal = new LinkedHashMap<>();
    public Map<UUID, Treaty> treaties = new LinkedHashMap<>();
    public Map<UUID, Resident> residents = new LinkedHashMap<>();
    public List<Event> history = new ArrayList<>();
    public Map<UUID, Association> associations = new TreeMap<>();
    public Map<UUID, Adoption> adoptions = new TreeMap<>();
    /** The last history sequence each player has been told about, so one event is announced to a player once. */
    public Map<UUID, Long> delivered = new LinkedHashMap<>();
    /** Players who asked not to be told about Living Villages wars and treaties outside their own realm. */
    public Set<UUID> quiet = new TreeSet<>();
    public List<Refusal> refusals = new ArrayList<>();

    public LvState copy() {
        var next = new LvState();
        next.schema = schema; next.revision = revision; next.historySequence = historySequence;
        var p = new Provenance();
        p.world = provenance.world; p.provider = provenance.provider; p.version = provenance.version; p.sha256 = provenance.sha256;
        p.elapsedSteps = provenance.elapsedSteps; p.highestElapsedSteps = provenance.highestElapsedSteps;
        p.villages = provenance.villages; p.regressedAt = provenance.regressedAt;
        next.provenance = p;
        var m = new Migration();
        m.version = migration.version; m.complete = migration.complete; m.started = migration.started; m.completed = migration.completed;
        m.cursor = migration.cursor; m.imported = migration.imported; m.preserved = migration.preserved; m.pending = migration.pending;
        m.ambiguous = migration.ambiguous; m.virtualRealms = migration.virtualRealms; m.unsupported = migration.unsupported;
        next.migration = m;
        next.bindings.putAll(bindings); next.realms.putAll(realms); next.conflicts.putAll(conflicts);
        next.journal.putAll(journal); next.treaties.putAll(treaties); next.residents.putAll(residents);
        next.history.addAll(history); next.associations.putAll(associations); next.adoptions.putAll(adoptions);
        next.delivered.putAll(delivered); next.quiet.addAll(quiet); next.refusals.addAll(refusals);
        return next;
    }

    public void validate() {
        if (schema != SCHEMA || revision < 0 || historySequence < 0 || provenance == null || migration == null || bindings == null
                || realms == null || conflicts == null || journal == null || treaties == null || residents == null || history == null
                || associations == null || adoptions == null || delivered == null || quiet == null || refusals == null)
            throw new IllegalArgumentException("Incomplete Living Villages bridge data");
        if (bindings.size() > BINDINGS || realms.size() > REALMS || conflicts.size() > CONFLICTS || journal.size() > JOURNAL
                || treaties.size() > TREATIES || residents.size() > RESIDENTS || history.size() > HISTORY
                || associations.size() > ASSOCIATIONS || adoptions.size() > ADOPTIONS || delivered.size() > CURSORS
                || quiet.size() > CURSORS || refusals.size() > REFUSALS)
            throw new IllegalArgumentException("Living Villages bridge capacity exceeded");
        bindings.forEach((id, b) -> { if (b == null || !id.equals(b.village())) throw new IllegalArgumentException("Binding identity mismatch"); });
        realms.forEach((id, r) -> { if (r == null || !id.equals(r.id())) throw new IllegalArgumentException("Realm identity mismatch"); });
        conflicts.forEach((id, c) -> { if (c == null || !id.equals(c.id())) throw new IllegalArgumentException("Conflict identity mismatch"); });
        journal.forEach((id, o) -> { if (o == null || !id.equals(o.id())) throw new IllegalArgumentException("Operation identity mismatch"); });
        treaties.forEach((id, t) -> { if (t == null || !id.equals(t.id())) throw new IllegalArgumentException("Treaty identity mismatch"); });
        residents.forEach((id, r) -> { if (r == null || !id.equals(r.entity())) throw new IllegalArgumentException("Resident identity mismatch"); });
        associations.forEach((id, a) -> { if (a == null || !id.equals(a.realm())) throw new IllegalArgumentException("Association identity mismatch"); });
        adoptions.forEach((id, a) -> { if (a == null || !id.equals(a.settlement())) throw new IllegalArgumentException("Adoption identity mismatch"); });
        // One settlement per village and one village per settlement: two provider villages are never one place.
        var settlements = new HashMap<UUID, UUID>();
        bindings.values().forEach(b -> {
            if (b.settlement() == null) return;
            UUID other = settlements.putIfAbsent(b.settlement(), b.village());
            if (other != null && b.status() == BindingStatus.BOUND && bindings.get(other).status() == BindingStatus.BOUND)
                throw new IllegalArgumentException("Two villages are bound to one settlement");
        });
        long previous = 0;
        for (Event event : history) {
            if (event == null || event.sequence() <= previous || event.sequence() > historySequence) throw new IllegalArgumentException("Unordered history");
            previous = event.sequence();
        }
    }

    // ---- small helpers shared by the services --------------------------------------------------------------------------

    public Optional<Binding> bindingForSettlement(UUID settlement) {
        return bindings.values().stream().filter(b -> settlement.equals(b.settlement()) && b.status() == BindingStatus.BOUND).findFirst();
    }

    public Optional<Realm> realmOfVillage(UUID village) {
        var binding = bindings.get(village);
        if (binding != null && binding.realm() != null && realms.containsKey(binding.realm())) return Optional.of(realms.get(binding.realm()));
        return realms.values().stream().filter(r -> r.members().contains(village)).findFirst();
    }

    public Optional<Realm> realmByTeam(String team) {
        if (team == null || team.isBlank()) return Optional.empty();
        return realms.values().stream().filter(r -> r.status() == RealmStatus.ACTIVE && r.team().equals(team)).findFirst()
                .or(() -> realms.values().stream().filter(r -> r.teams().contains(team)).findFirst());
    }

    public void record(EventKind kind, long now, UUID settlement, UUID realm, UUID other, UUID conflict, String detail, boolean announce) {
        historySequence = Math.addExact(historySequence, 1);
        history.add(new Event(historySequence, now, kind, settlement, realm, other, conflict, detail, announce));
        while (history.size() > HISTORY) history.remove(0);
    }

    public void refuse(long now, UUID attacker, UUID defender, String reason) {
        refusals.add(new Refusal(now, attacker, defender, reason));
        while (refusals.size() > REFUSALS) refusals.remove(0);
    }

    /** Terminal conflicts and published operations beyond their bound are dropped oldest first; open ones never are. */
    public void compact() {
        if (conflicts.size() > CONFLICTS - 64) {
            var closed = conflicts.values().stream().filter(c -> !c.open()).sorted(Comparator.comparingLong(Conflict::updated)).toList();
            for (int i = 0; i < closed.size() && conflicts.size() > CONFLICTS - 256; i++) conflicts.remove(closed.get(i).id());
        }
        if (journal.size() > JOURNAL - 64) {
            var done = journal.values().stream().filter(o -> o.state() == OperationState.PUBLISHED || o.state() == OperationState.ABANDONED)
                    .sorted(Comparator.comparingLong(Operation::created)).toList();
            for (int i = 0; i < done.size() && journal.size() > JOURNAL - 256; i++) journal.remove(done.get(i).id());
        }
        if (residents.size() > RESIDENTS - 64) {
            var gone = residents.values().stream().filter(r -> r.lifecycle() == Lifecycle.DEAD).sorted(Comparator.comparingLong(Resident::lastSeen)).toList();
            for (int i = 0; i < gone.size() && residents.size() > RESIDENTS - 256; i++) residents.remove(gone.get(i).entity());
        }
        while (delivered.size() > CURSORS) delivered.remove(delivered.keySet().iterator().next());
    }
}
