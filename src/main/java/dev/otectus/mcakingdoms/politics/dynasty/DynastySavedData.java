package dev.otectus.mcakingdoms.politics.dynasty;

import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;

/**
 * The dynasty records (A1-A4), in {@code mcakingdoms_dynasty.dat}: each government's law of succession, the ruler
 * last seen on each throne, succession cases with the evidence each decision read, claims and renunciations, court
 * titles, mourning and memorials. It references the political store's kingdoms and people and never copies its offices
 * or MCA's genealogy. Every change is written before it takes effect; a newer schema or a damaged file is kept
 * read-only and unchanged.
 */
public final class DynastySavedData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_dynasty";
    static final int SCHEMA = 1, KINGDOMS = 256, CASES = 2048, CLAIMS = 4096, TITLES = 4096, MEMORIALS = 1024, RECEIPTS = 4096, PER_KINGDOM = 64;

    /** A government's adopted law; a kingdom without one follows {@code mcakingdoms:manual_only}. */
    public record LawChoice(ResourceLocation law, UUID adoptedBy, long adoptedAt) { }
    /** The ruler last seen seated, since when, and whether an election seated them. */
    public record Ruler(Politics.Person person, long since, boolean elected) { }

    public enum Cause { DEATH, ABDICATION, REMOVAL, VACANCY }
    /**
     * Where a succession stands. {@code CLAIMS_PENDING} waits for the heir's acceptance or the council's confirmation;
     * {@code REGENCY} keeps a minor heir until adulthood; {@code BLOCKED_NEEDS_REVIEW} could not decide from the evidence.
     * {@code LEFT_TO_ELECTION} and {@code LEFT_TO_OFFICIALS} take no automatic step. The last three are closed.
     */
    public enum Status {
        VACANCY_CONFIRMED, CLAIMS_PENDING, REGENCY, BLOCKED_NEEDS_REVIEW, LEFT_TO_ELECTION, LEFT_TO_OFFICIALS, RATIFIED_SUCCESSION, SUPERSEDED;
        public boolean closed() { return this == RATIFIED_SUCCESSION || this == SUPERSEDED; }
        /** True while the dynasty itself still has a step to take. */
        public boolean active() { return this == VACANCY_CONFIRMED || this == CLAIMS_PENDING || this == REGENCY || this == BLOCKED_NEEDS_REVIEW; }
    }
    public enum Pending { NONE, ACCEPTANCE, CONFIRMATION }

    /** One succession, from the confirmed vacancy to a ratified heir; {@code candidates} is the evidence its last decision read. */
    public record Case(UUID id, String kingdom, Politics.Person ruler, String rulerName, Cause cause, Status status, Pending pending, ResourceLocation law,
                       Politics.Person heir, List<SuccessionResolver.Candidate> candidates, String reasonKey, long openedAt, long updatedAt, long deadline,
                       Set<UUID> confirmations, Set<UUID> objections, long revision) {
        public Case {
            Objects.requireNonNull(id); Objects.requireNonNull(kingdom); Objects.requireNonNull(ruler); Objects.requireNonNull(cause);
            Objects.requireNonNull(status); Objects.requireNonNull(pending); Objects.requireNonNull(law);
            rulerName = rulerName == null ? "" : rulerName; reasonKey = reasonKey == null ? "" : reasonKey;
            candidates = List.copyOf(candidates); confirmations = Set.copyOf(confirmations); objections = Set.copyOf(objections);
            if (candidates.size() > SuccessionResolver.LIMIT + 8 || confirmations.size() > 128 || objections.size() > 128 || reasonKey.length() > 4096)
                throw new IllegalArgumentException("Succession case too large");
        }
        /** This case decided again: a new status, heir and evidence; confirmations restart. */
        Case decided(Status status, Pending pending, Politics.Person heir, List<SuccessionResolver.Candidate> candidates, String reasonKey, long now, long deadline, long revision) {
            return new Case(id, kingdom, ruler, rulerName, cause, status, pending, law, heir, candidates, reasonKey, openedAt, now, deadline, Set.of(), Set.of(), revision);
        }
        /** This case moved to {@code status}, keeping its heir and evidence. */
        Case status(Status status, String reasonKey, long now, long revision) {
            return new Case(id, kingdom, ruler, rulerName, cause, status, Pending.NONE, law, heir, candidates, reasonKey, openedAt, now, 0, confirmations, objections, revision);
        }
        Case pending(Pending pending, long deadline, String reasonKey, long now, long revision) {
            return new Case(id, kingdom, ruler, rulerName, cause, Status.CLAIMS_PENDING, pending, law, heir, candidates, reasonKey, openedAt, now, deadline, confirmations, objections, revision);
        }
        Case answers(Set<UUID> confirmations, Set<UUID> objections, long now, long revision) {
            return new Case(id, kingdom, ruler, rulerName, cause, status, pending, law, heir, candidates, reasonKey, openedAt, now, deadline, confirmations, objections, revision);
        }
    }

    public enum ClaimKind { CONTEST, RECOGNITION, RESTORATION }
    public enum ClaimState { OPEN, UPHELD, DISMISSED, WITHDRAWN }
    /** A claim filed with the crown; its decision is final and kept, and a dismissed claim may be filed again after the cooldown (an appeal). */
    public record Claim(UUID id, String kingdom, UUID claimant, String claimantName, ClaimKind kind, ClaimState state, String statement,
                        long filedAt, UUID decidedBy, long decidedAt, long revision) {
        public Claim {
            Objects.requireNonNull(id); Objects.requireNonNull(kingdom); Objects.requireNonNull(claimant); Objects.requireNonNull(kind); Objects.requireNonNull(state);
            claimantName = bounded(claimantName, 64); statement = bounded(statement, 256);
        }
        Claim decided(ClaimState state, UUID by, long at, long revision) {
            return new Claim(id, kingdom, claimant, claimantName, kind, state, statement, filedAt, by, at, revision);
        }
    }

    public enum TitleState { OFFERED, ACTIVE, DECLINED, RESIGNED, REVOKED, EXPIRED;
        public boolean current() { return this == OFFERED || this == ACTIVE; }
    }
    /** A court rank granted to one person: who granted it on what authority, for which settlement if landed, until when. */
    public record Title(UUID id, String kingdom, Politics.Person recipient, String recipientName, ResourceLocation rank, UUID grantor, String authority,
                        UUID scope, long grantedAt, long expiresAt, TitleState state, UUID endedBy, long endedAt, long revision) {
        public Title {
            Objects.requireNonNull(id); Objects.requireNonNull(kingdom); Objects.requireNonNull(recipient); Objects.requireNonNull(rank);
            Objects.requireNonNull(grantor); Objects.requireNonNull(state);
            recipientName = bounded(recipientName, 64); authority = bounded(authority, 32);
        }
        Title ended(TitleState state, UUID by, long at, long revision) {
            return new Title(id, kingdom, recipient, recipientName, rank, grantor, authority, scope, grantedAt, expiresAt, state, by, at, revision);
        }
    }

    /** A kingdom in mourning for its ruler: shown on its pages and banners only, until {@code endsAt}. */
    public record Mourning(UUID caseId, Politics.Person ruler, String rulerName, long startedAt, long endsAt) { }
    /** A ruler's memorial: who reigned, from when to when, and how the reign ended. */
    public record Memorial(UUID id, String kingdom, Politics.Person ruler, String rulerName, Cause cause, long reignFrom, long reignTo) { }
    record Receipt(UUID actor, String fingerprint, String result) { }

    /** All records; copied for a change ({@link #transaction()}, whose revision is already advanced), then swapped in once written. */
    public static final class Records {
        long revision;
        final Map<String, LawChoice> laws = new TreeMap<>();
        final Map<String, Ruler> rulers = new TreeMap<>();
        final Map<UUID, Case> cases = new LinkedHashMap<>();
        final Map<String, Set<UUID>> renounced = new TreeMap<>();
        final Map<String, Set<UUID>> recognized = new TreeMap<>();
        final Map<String, UUID> priority = new TreeMap<>();
        final Map<UUID, Claim> claims = new LinkedHashMap<>();
        final Map<UUID, Title> titles = new LinkedHashMap<>();
        final Map<String, Mourning> mourning = new TreeMap<>();
        final List<Memorial> memorials = new ArrayList<>();
        final Map<UUID, Receipt> receipts = new LinkedHashMap<>();

        Records copy() {
            var next = new Records();
            next.revision = revision; next.laws.putAll(laws); next.rulers.putAll(rulers); next.cases.putAll(cases);
            renounced.forEach((k, v) -> next.renounced.put(k, new TreeSet<>(v))); recognized.forEach((k, v) -> next.recognized.put(k, new TreeSet<>(v)));
            next.priority.putAll(priority); next.claims.putAll(claims); next.titles.putAll(titles); next.mourning.putAll(mourning);
            next.memorials.addAll(memorials); next.receipts.putAll(receipts);
            return next;
        }

        public long revision() { return revision; }
        public Optional<LawChoice> law(String kingdom) { return Optional.ofNullable(laws.get(kingdom)); }
        public Optional<Ruler> ruler(String kingdom) { return Optional.ofNullable(rulers.get(kingdom)); }
        public Collection<Case> cases() { return Collections.unmodifiableCollection(cases.values()); }
        public Set<UUID> renounced(String kingdom) { return Collections.unmodifiableSet(renounced.getOrDefault(kingdom, Set.of())); }
        public Set<UUID> recognized(String kingdom) { return Collections.unmodifiableSet(recognized.getOrDefault(kingdom, Set.of())); }
        public Optional<UUID> priority(String kingdom) { return Optional.ofNullable(priority.get(kingdom)); }
        public Collection<Claim> claims() { return Collections.unmodifiableCollection(claims.values()); }
        public Collection<Title> titles() { return Collections.unmodifiableCollection(titles.values()); }
        public Optional<Mourning> mourning(String kingdom) { return Optional.ofNullable(mourning.get(kingdom)); }
        public List<Memorial> memorials() { return Collections.unmodifiableList(memorials); }

        /** The kingdom's open succession case, if any: the newest one not closed. */
        public Optional<Case> current(String kingdom) {
            return cases.values().stream().filter(c -> c.kingdom().equals(kingdom) && !c.status().closed()).max(Comparator.comparingLong(Case::openedAt).thenComparing(Case::id));
        }

        void validate() {
            if (laws.size() > KINGDOMS || rulers.size() > KINGDOMS || mourning.size() > KINGDOMS || priority.size() > KINGDOMS
                    || cases.size() > CASES || claims.size() > CLAIMS || titles.size() > TITLES || memorials.size() > MEMORIALS || receipts.size() > RECEIPTS
                    || renounced.values().stream().anyMatch(s -> s.size() > PER_KINGDOM) || recognized.values().stream().anyMatch(s -> s.size() > PER_KINGDOM))
                throw new IllegalArgumentException("Dynasty record bounds exceeded");
        }
    }

    private Records records = new Records();
    private boolean readOnly;
    private CompoundTag preserved;

    public static DynastySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DynastySavedData::load, DynastySavedData::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        var data = get(server);
        return StoreStatus.of(NAME, data.writable(), "");
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var r = get(server).records;
        return List.of(new Capacity(NAME, "succession_cases", r.cases.size(), CASES, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "claims", r.claims.size(), CLAIMS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "titles", r.titles.size(), TITLES, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "receipts", r.receipts.size(), RECEIPTS, Capacity.Relief.RETENTION));
    }

    static String bounded(String value, int limit) {
        if (value == null) return "";
        if (value.length() > limit || value.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Invalid dynasty text");
        return value;
    }

    public boolean writable() { return !readOnly; }
    public Records records() { return records; }
    public Records transaction() { var next = records.copy(); next.revision++; return next; }
    Optional<Receipt> receipt(UUID request) { return Optional.ofNullable(records.receipts.get(request)); }

    /** Writes {@code next} and only then makes it current; false (nothing changed) when read-only, over a bound, or the write failed. */
    public boolean commit(MinecraftServer server, Records next) {
        if (readOnly) return false;
        try {
            while (next.receipts.size() > RECEIPTS) next.receipts.remove(next.receipts.keySet().iterator().next());
            while (next.memorials.size() > MEMORIALS) next.memorials.remove(0);
            next.validate();
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), write(next, new CompoundTag()));
            records = next;
            setDirty(false);
            return true;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] The dynasty records were not changed: {}", failure.getMessage());
            return false;
        }
    }

    public static DynastySavedData load(CompoundTag tag) {
        var data = new DynastySavedData();
        if (tag.getInt("Schema") > SCHEMA) {
            data.readOnly = true; data.preserved = tag.copy();
            return data;
        }
        try {
            data.records = read(tag);
            data.records.validate();
        } catch (RuntimeException damaged) {
            data.readOnly = true; data.preserved = tag.copy(); data.records = new Records();
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] {}.dat could not be read and is kept read-only and unchanged: {}", NAME, damaged.toString());
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (readOnly) return preserved.copy();
        return write(records, tag);
    }

    // --- NBT ---------------------------------------------------------------------------------------------------------

    private static Records read(CompoundTag tag) {
        var r = new Records();
        r.revision = tag.getLong("Revision");
        for (Tag entry : tag.getList("Laws", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.laws.put(t.getString("Kingdom"), new LawChoice(new ResourceLocation(t.getString("Law")), optionalUuid(t, "By"), t.getLong("At")));
        }
        for (Tag entry : tag.getList("Rulers", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.rulers.put(t.getString("Kingdom"), new Ruler(person(t.getCompound("Person")), t.getLong("Since"), t.getBoolean("Elected")));
        }
        for (Tag entry : tag.getList("Cases", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var candidates = new ArrayList<SuccessionResolver.Candidate>();
            for (Tag c : t.getList("Candidates", Tag.TAG_COMPOUND)) {
                CompoundTag ct = (CompoundTag) c;
                candidates.add(new SuccessionResolver.Candidate(ct.getUUID("Id"), Lineage.Relation.valueOf(ct.getString("Relation")), Lineage.Stage.valueOf(ct.getString("Stage")),
                        ct.getBoolean("Eligible"), ct.getString("Reason"), ct.getString("Name")));
            }
            var value = new Case(t.getUUID("Id"), t.getString("Kingdom"), person(t.getCompound("Ruler")), t.getString("RulerName"), Cause.valueOf(t.getString("Cause")),
                    Status.valueOf(t.getString("Status")), Pending.valueOf(t.getString("Pending")), new ResourceLocation(t.getString("Law")),
                    t.contains("Heir") ? person(t.getCompound("Heir")) : null, candidates, t.getString("Reason"), t.getLong("OpenedAt"), t.getLong("UpdatedAt"),
                    t.getLong("Deadline"), uuids(t.getList("Confirmations", Tag.TAG_STRING)), uuids(t.getList("Objections", Tag.TAG_STRING)), t.getLong("CaseRevision"));
            r.cases.put(value.id(), value);
        }
        sets(tag.getList("Renounced", Tag.TAG_COMPOUND), r.renounced);
        sets(tag.getList("Recognized", Tag.TAG_COMPOUND), r.recognized);
        for (Tag entry : tag.getList("Priority", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.priority.put(t.getString("Kingdom"), t.getUUID("Person"));
        }
        for (Tag entry : tag.getList("Claims", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var value = new Claim(t.getUUID("Id"), t.getString("Kingdom"), t.getUUID("Claimant"), t.getString("ClaimantName"), ClaimKind.valueOf(t.getString("Kind")),
                    ClaimState.valueOf(t.getString("State")), t.getString("Statement"), t.getLong("FiledAt"), optionalUuid(t, "DecidedBy"), t.getLong("DecidedAt"), t.getLong("ClaimRevision"));
            r.claims.put(value.id(), value);
        }
        for (Tag entry : tag.getList("Titles", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var value = new Title(t.getUUID("Id"), t.getString("Kingdom"), person(t.getCompound("Recipient")), t.getString("RecipientName"), new ResourceLocation(t.getString("Rank")),
                    t.getUUID("Grantor"), t.getString("Authority"), optionalUuid(t, "Scope"), t.getLong("GrantedAt"), t.getLong("ExpiresAt"), TitleState.valueOf(t.getString("State")),
                    optionalUuid(t, "EndedBy"), t.getLong("EndedAt"), t.getLong("TitleRevision"));
            r.titles.put(value.id(), value);
        }
        for (Tag entry : tag.getList("Mourning", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.mourning.put(t.getString("Kingdom"), new Mourning(t.getUUID("Case"), person(t.getCompound("Ruler")), t.getString("RulerName"), t.getLong("StartedAt"), t.getLong("EndsAt")));
        }
        for (Tag entry : tag.getList("Memorials", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.memorials.add(new Memorial(t.getUUID("Id"), t.getString("Kingdom"), person(t.getCompound("Ruler")), t.getString("RulerName"), Cause.valueOf(t.getString("Cause")),
                    t.getLong("From"), t.getLong("To")));
        }
        for (Tag entry : tag.getList("Receipts", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.receipts.put(t.getUUID("Request"), new Receipt(t.getUUID("Actor"), t.getString("Fingerprint"), t.getString("Result")));
        }
        return r;
    }

    private static CompoundTag write(Records r, CompoundTag tag) {
        tag.putInt("Schema", SCHEMA); tag.putLong("Revision", r.revision);
        ListTag laws = new ListTag();
        r.laws.forEach((kingdom, law) -> { CompoundTag t = new CompoundTag(); t.putString("Kingdom", kingdom); t.putString("Law", law.law().toString());
            if (law.adoptedBy() != null) t.putUUID("By", law.adoptedBy()); t.putLong("At", law.adoptedAt()); laws.add(t); });
        tag.put("Laws", laws);
        ListTag rulers = new ListTag();
        r.rulers.forEach((kingdom, ruler) -> { CompoundTag t = new CompoundTag(); t.putString("Kingdom", kingdom); t.put("Person", person(ruler.person()));
            t.putLong("Since", ruler.since()); t.putBoolean("Elected", ruler.elected()); rulers.add(t); });
        tag.put("Rulers", rulers);
        ListTag cases = new ListTag();
        for (Case c : r.cases.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", c.id()); t.putString("Kingdom", c.kingdom()); t.put("Ruler", person(c.ruler())); t.putString("RulerName", c.rulerName());
            t.putString("Cause", c.cause().name()); t.putString("Status", c.status().name()); t.putString("Pending", c.pending().name()); t.putString("Law", c.law().toString());
            if (c.heir() != null) t.put("Heir", person(c.heir()));
            ListTag candidates = new ListTag();
            for (var candidate : c.candidates()) {
                CompoundTag ct = new CompoundTag();
                ct.putUUID("Id", candidate.id()); ct.putString("Relation", candidate.relation().name()); ct.putString("Stage", candidate.stage().name());
                ct.putBoolean("Eligible", candidate.eligible()); ct.putString("Reason", candidate.reasonKey()); ct.putString("Name", candidate.name());
                candidates.add(ct);
            }
            t.put("Candidates", candidates); t.putString("Reason", c.reasonKey()); t.putLong("OpenedAt", c.openedAt()); t.putLong("UpdatedAt", c.updatedAt());
            t.putLong("Deadline", c.deadline()); t.put("Confirmations", strings(c.confirmations())); t.put("Objections", strings(c.objections())); t.putLong("CaseRevision", c.revision());
            cases.add(t);
        }
        tag.put("Cases", cases);
        tag.put("Renounced", sets(r.renounced));
        tag.put("Recognized", sets(r.recognized));
        ListTag priority = new ListTag();
        r.priority.forEach((kingdom, id) -> { CompoundTag t = new CompoundTag(); t.putString("Kingdom", kingdom); t.putUUID("Person", id); priority.add(t); });
        tag.put("Priority", priority);
        ListTag claims = new ListTag();
        for (Claim c : r.claims.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", c.id()); t.putString("Kingdom", c.kingdom()); t.putUUID("Claimant", c.claimant()); t.putString("ClaimantName", c.claimantName());
            t.putString("Kind", c.kind().name()); t.putString("State", c.state().name()); t.putString("Statement", c.statement()); t.putLong("FiledAt", c.filedAt());
            if (c.decidedBy() != null) t.putUUID("DecidedBy", c.decidedBy()); t.putLong("DecidedAt", c.decidedAt()); t.putLong("ClaimRevision", c.revision());
            claims.add(t);
        }
        tag.put("Claims", claims);
        ListTag titles = new ListTag();
        for (Title value : r.titles.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", value.id()); t.putString("Kingdom", value.kingdom()); t.put("Recipient", person(value.recipient())); t.putString("RecipientName", value.recipientName());
            t.putString("Rank", value.rank().toString()); t.putUUID("Grantor", value.grantor()); t.putString("Authority", value.authority());
            if (value.scope() != null) t.putUUID("Scope", value.scope());
            t.putLong("GrantedAt", value.grantedAt()); t.putLong("ExpiresAt", value.expiresAt()); t.putString("State", value.state().name());
            if (value.endedBy() != null) t.putUUID("EndedBy", value.endedBy()); t.putLong("EndedAt", value.endedAt()); t.putLong("TitleRevision", value.revision());
            titles.add(t);
        }
        tag.put("Titles", titles);
        ListTag mourning = new ListTag();
        r.mourning.forEach((kingdom, m) -> { CompoundTag t = new CompoundTag(); t.putString("Kingdom", kingdom); t.putUUID("Case", m.caseId()); t.put("Ruler", person(m.ruler()));
            t.putString("RulerName", m.rulerName()); t.putLong("StartedAt", m.startedAt()); t.putLong("EndsAt", m.endsAt()); mourning.add(t); });
        tag.put("Mourning", mourning);
        ListTag memorials = new ListTag();
        for (Memorial m : r.memorials) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", m.id()); t.putString("Kingdom", m.kingdom()); t.put("Ruler", person(m.ruler())); t.putString("RulerName", m.rulerName());
            t.putString("Cause", m.cause().name()); t.putLong("From", m.reignFrom()); t.putLong("To", m.reignTo());
            memorials.add(t);
        }
        tag.put("Memorials", memorials);
        ListTag receipts = new ListTag();
        r.receipts.forEach((request, receipt) -> { CompoundTag t = new CompoundTag(); t.putUUID("Request", request); t.putUUID("Actor", receipt.actor());
            t.putString("Fingerprint", receipt.fingerprint()); t.putString("Result", receipt.result()); receipts.add(t); });
        tag.put("Receipts", receipts);
        return tag;
    }

    private static CompoundTag person(Politics.Person person) {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", person.id()); t.putString("Kind", person.kind().name());
        return t;
    }

    private static Politics.Person person(CompoundTag t) {
        return new Politics.Person(t.getUUID("Id"), Politics.Kind.valueOf(t.getString("Kind")));
    }

    private static UUID optionalUuid(CompoundTag t, String key) {
        return t.hasUUID(key) ? t.getUUID(key) : null;
    }

    private static Set<UUID> uuids(ListTag list) {
        var out = new TreeSet<UUID>();
        for (Tag value : list) out.add(UUID.fromString(value.getAsString()));
        return out;
    }

    private static ListTag strings(Collection<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) list.add(StringTag.valueOf(id.toString()));
        return list;
    }

    private static void sets(ListTag list, Map<String, Set<UUID>> into) {
        for (Tag entry : list) {
            CompoundTag t = (CompoundTag) entry;
            into.put(t.getString("Kingdom"), uuids(t.getList("People", Tag.TAG_STRING)));
        }
    }

    private static ListTag sets(Map<String, Set<UUID>> from) {
        ListTag list = new ListTag();
        from.forEach((kingdom, people) -> { if (people.isEmpty()) return; CompoundTag t = new CompoundTag(); t.putString("Kingdom", kingdom); t.put("People", strings(people)); list.add(t); });
        return list;
    }
}
