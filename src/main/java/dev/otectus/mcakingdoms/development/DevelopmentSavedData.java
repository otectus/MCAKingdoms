package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;

/**
 * Settlement development and economy records (D2-D3), in {@code mcakingdoms_development.dat}, written only once the mode is
 * above {@code OFF}: public works with their deliveries and construction journal, each settlement's development state,
 * protected homes, refunds owed and request receipts. Keyed to canonical settlement ids. Every change is written before it
 * takes effect; a newer schema or damaged file is kept read-only and unchanged.
 */
public final class DevelopmentSavedData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_development";
    static final int SCHEMA = 1, PROJECTS = 512, CONTRIBUTIONS = 256, SETTLEMENTS = 4096, HOMES = 1024, REFUNDS = 4096, RECEIPTS = 4096, TRADES = 64;

    /** A work's lifecycle (D3); the last four are exceptional states with a recorded reason. */
    public enum State {
        PROPOSED, SURVEYED, APPROVED, FUNDED, READY, BUILDING, COMPLETED, BLOCKED, PAUSED, CANCELLED, REPAIR_NEEDED;
        public boolean open() { return this != COMPLETED && this != CANCELLED; }
    }
    /** Where an amount comes from (D2): items players really delivered, the abstract ledger, or a work's demand. */
    public enum Provenance { PHYSICAL_VERIFIED, ABSTRACT_SIMULATED, AUTHORED_DEMAND }

    /** One demand and what has been delivered against it. */
    public record Requirement(String ingredient, int count, int delivered) {
        public Requirement { if (count < 1 || delivered < 0 || delivered > count) throw new IllegalArgumentException("Invalid requirement"); }
        public int remaining() { return count - delivered; }
    }
    /** One delivery: who gave which item, how many, under which receipt. */
    public record Contribution(UUID player, String playerName, String item, int count, long at) { }
    /** One planned block: where, what was there when surveyed, what the work places, and whether it was placed. */
    public record Placement(BlockPos pos, String before, String after, boolean placed) { }

    public record Project(UUID id, UUID settlement, String kingdom, ResourceLocation template, State state, String reasonKey, UUID proposer, UUID approver,
                          boolean approvedByOperator, String dimension, BlockPos origin, List<Requirement> demand, List<Contribution> contributions,
                          List<Placement> journal, State pausedFrom, long createdAt, long updatedAt, long revision) {
        public Project {
            Objects.requireNonNull(id); Objects.requireNonNull(settlement); Objects.requireNonNull(kingdom); Objects.requireNonNull(template);
            Objects.requireNonNull(state); Objects.requireNonNull(proposer);
            reasonKey = reasonKey == null ? "" : reasonKey; dimension = dimension == null ? "" : dimension;
            demand = List.copyOf(demand); contributions = List.copyOf(contributions); journal = List.copyOf(journal);
            if (demand.size() > 8 || contributions.size() > CONTRIBUTIONS || journal.size() > DevelopmentTemplate.MAX_BLOCKS || reasonKey.length() > 4096)
                throw new IllegalArgumentException("Development project too large");
        }
        public boolean funded() { return demand.stream().allMatch(r -> r.remaining() == 0); }
        Project with(State state, String reasonKey, long now, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions, journal,
                    state == State.PAUSED ? (this.state == State.PAUSED ? pausedFrom : this.state) : null, createdAt, now, revision);
        }
        Project approved(UUID by, boolean operator, long now, long revision) {
            return new Project(id, settlement, kingdom, template, State.APPROVED, "development.mcakingdoms.reason.approved", proposer, by, operator, dimension, origin,
                    demand, contributions, journal, null, createdAt, now, revision);
        }
        Project delivered(List<Requirement> demand, List<Contribution> contributions, long now, long revision) {
            boolean done = demand.stream().allMatch(r -> r.remaining() == 0);
            return new Project(id, settlement, kingdom, template, done ? State.FUNDED : state, done ? "development.mcakingdoms.reason.funded" : reasonKey, proposer, approver,
                    approvedByOperator, dimension, origin, demand, contributions, journal, pausedFrom, createdAt, now, revision);
        }
        Project journal(List<Placement> journal, State state, String reasonKey, long now, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions,
                    journal, pausedFrom, createdAt, now, revision);
        }
        Project movedTo(UUID settlement, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions,
                    journal, pausedFrom, createdAt, updatedAt, revision);
        }
    }

    /** A settlement's development: points and roles from completed works, whether it opted in to construction, its abstract ledger. */
    public record Economy(UUID settlement, int points, Map<String, Integer> roles, boolean optedIn, Map<String, Long> abstractGoods, long lastProduced) {
        public Economy { roles = Map.copyOf(roles); abstractGoods = Map.copyOf(abstractGoods); }
        public static Economy empty(UUID settlement) { return new Economy(settlement, 0, Map.of(), false, Map.of(), 0); }
        /** The development tier from completed works' points: 0 (none), then 1, 3, 6, 10 and 15 points for tiers 1 to 5. */
        public int tier() { return Development.tier(points); }
        public Optional<String> specialization() {
            return roles.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).map(Map.Entry::getKey);
        }
    }
    /** A player's home that construction never touches: their bed, and a radius. */
    public record Home(UUID player, String dimension, BlockPos pos, int radius) { }
    /** Items owed back to a contributor of a cancelled work. */
    public record Refund(UUID player, String item, int count, UUID project) { }
    /** One abstract trade, labelled as such: no item moved. */
    public record Trade(UUID from, UUID to, String commodity, long amount, long at) { }
    record Receipt(UUID actor, String fingerprint, String result) { }

    public static final class Records {
        long revision;
        /** The Minecraft day abstract trade last ran on, so it runs once a day at most. */
        long tradeDay = -1;
        final Map<UUID, Project> projects = new LinkedHashMap<>();
        final Map<UUID, Economy> economies = new LinkedHashMap<>();
        final Map<UUID, Home> homes = new LinkedHashMap<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<Trade> trades = new ArrayList<>();
        final Map<UUID, Receipt> receipts = new LinkedHashMap<>();

        Records copy() {
            var next = new Records();
            next.revision = revision; next.tradeDay = tradeDay; next.projects.putAll(projects); next.economies.putAll(economies); next.homes.putAll(homes);
            next.refunds.addAll(refunds); next.trades.addAll(trades); next.receipts.putAll(receipts);
            return next;
        }

        public long revision() { return revision; }
        public Collection<Project> projects() { return Collections.unmodifiableCollection(projects.values()); }
        public Optional<Project> project(UUID id) { return Optional.ofNullable(projects.get(id)); }
        public Economy economy(UUID settlement) { return economies.getOrDefault(settlement, Economy.empty(settlement)); }
        public Collection<Home> homes() { return Collections.unmodifiableCollection(homes.values()); }
        public List<Refund> refunds() { return Collections.unmodifiableList(refunds); }
        public List<Trade> trades() { return Collections.unmodifiableList(trades); }

        void validate() {
            if (projects.size() > PROJECTS || economies.size() > SETTLEMENTS || homes.size() > HOMES || refunds.size() > REFUNDS || receipts.size() > RECEIPTS || trades.size() > TRADES)
                throw new IllegalArgumentException("Development record bounds exceeded");
            for (var e : economies.values()) if (e.points() < 0 || e.abstractGoods().values().stream().anyMatch(v -> v < 0 || v > Development.ABSTRACT_CAP))
                throw new IllegalArgumentException("Development ledger out of range");
        }
    }

    private Records records = new Records();
    private boolean readOnly;
    private CompoundTag preserved;

    public static DevelopmentSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DevelopmentSavedData::load, DevelopmentSavedData::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        return StoreStatus.of(NAME, get(server).writable(), "");
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var r = get(server).records;
        return List.of(new Capacity(NAME, "projects", r.projects.size(), PROJECTS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "refunds", r.refunds.size(), REFUNDS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "receipts", r.receipts.size(), RECEIPTS, Capacity.Relief.RETENTION));
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
            while (next.trades.size() > TRADES) next.trades.remove(0);
            pruneClosed(next);
            next.validate();
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), write(next, new CompoundTag()));
            records = next;
            setDirty(false);
            return true;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] The development records were not changed: {}", failure.getMessage());
            return false;
        }
    }

    /** Completed and cancelled works beyond the bound go first, oldest first; open works are never dropped. */
    private static void pruneClosed(Records next) {
        if (next.projects.size() <= PROJECTS) return;
        for (var p : next.projects.values().stream().filter(p -> !p.state().open()).sorted(Comparator.comparingLong(Project::updatedAt).thenComparing(Project::id)).toList()) {
            if (next.projects.size() <= PROJECTS) break;
            next.projects.remove(p.id());
        }
    }

    public static DevelopmentSavedData load(CompoundTag tag) {
        var data = new DevelopmentSavedData();
        if (tag.getInt("Schema") > SCHEMA) { data.readOnly = true; data.preserved = tag.copy(); return data; }
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

    private static Records read(CompoundTag tag) {
        var r = new Records();
        r.revision = tag.getLong("Revision");
        r.tradeDay = tag.contains("TradeDay") ? tag.getLong("TradeDay") : -1;
        for (Tag entry : tag.getList("Projects", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var demand = new ArrayList<Requirement>();
            for (Tag d : t.getList("Demand", Tag.TAG_COMPOUND)) { CompoundTag dt = (CompoundTag) d; demand.add(new Requirement(dt.getString("Ingredient"), dt.getInt("Count"), dt.getInt("Delivered"))); }
            var contributions = new ArrayList<Contribution>();
            for (Tag c : t.getList("Contributions", Tag.TAG_COMPOUND)) {
                CompoundTag ct = (CompoundTag) c;
                contributions.add(new Contribution(ct.getUUID("Player"), ct.getString("Name"), ct.getString("Item"), ct.getInt("Count"), ct.getLong("At")));
            }
            var journal = new ArrayList<Placement>();
            for (Tag j : t.getList("Journal", Tag.TAG_COMPOUND)) {
                CompoundTag jt = (CompoundTag) j;
                journal.add(new Placement(BlockPos.of(jt.getLong("Pos")), jt.getString("Before"), jt.getString("After"), jt.getBoolean("Placed")));
            }
            var p = new Project(t.getUUID("Id"), t.getUUID("Settlement"), t.getString("Kingdom"), new ResourceLocation(t.getString("Template")), State.valueOf(t.getString("State")),
                    t.getString("Reason"), t.getUUID("Proposer"), t.hasUUID("Approver") ? t.getUUID("Approver") : null, t.getBoolean("ByOperator"), t.getString("Dimension"),
                    t.contains("Origin") ? BlockPos.of(t.getLong("Origin")) : null, demand, contributions, journal,
                    t.contains("PausedFrom") ? State.valueOf(t.getString("PausedFrom")) : null, t.getLong("CreatedAt"), t.getLong("UpdatedAt"), t.getLong("ProjectRevision"));
            r.projects.put(p.id(), p);
        }
        for (Tag entry : tag.getList("Economies", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var roles = new TreeMap<String, Integer>();
            CompoundTag rt = t.getCompound("Roles"); for (String key : rt.getAllKeys()) roles.put(key, rt.getInt(key));
            var goods = new TreeMap<String, Long>();
            CompoundTag gt = t.getCompound("Goods"); for (String key : gt.getAllKeys()) goods.put(key, gt.getLong(key));
            var e = new Economy(t.getUUID("Settlement"), t.getInt("Points"), roles, t.getBoolean("OptedIn"), goods, t.getLong("LastProduced"));
            r.economies.put(e.settlement(), e);
        }
        for (Tag entry : tag.getList("Homes", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.homes.put(t.getUUID("Player"), new Home(t.getUUID("Player"), t.getString("Dimension"), BlockPos.of(t.getLong("Pos")), t.getInt("Radius")));
        }
        for (Tag entry : tag.getList("Refunds", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.refunds.add(new Refund(t.getUUID("Player"), t.getString("Item"), t.getInt("Count"), t.getUUID("Project")));
        }
        for (Tag entry : tag.getList("Trades", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.trades.add(new Trade(t.getUUID("From"), t.getUUID("To"), t.getString("Commodity"), t.getLong("Amount"), t.getLong("At")));
        }
        for (Tag entry : tag.getList("Receipts", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            r.receipts.put(t.getUUID("Request"), new Receipt(t.getUUID("Actor"), t.getString("Fingerprint"), t.getString("Result")));
        }
        return r;
    }

    private static CompoundTag write(Records r, CompoundTag tag) {
        tag.putInt("Schema", SCHEMA); tag.putLong("Revision", r.revision); tag.putLong("TradeDay", r.tradeDay);
        ListTag projects = new ListTag();
        for (var p : r.projects.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", p.id()); t.putUUID("Settlement", p.settlement()); t.putString("Kingdom", p.kingdom()); t.putString("Template", p.template().toString());
            t.putString("State", p.state().name()); t.putString("Reason", p.reasonKey()); t.putUUID("Proposer", p.proposer());
            if (p.approver() != null) t.putUUID("Approver", p.approver());
            t.putBoolean("ByOperator", p.approvedByOperator()); t.putString("Dimension", p.dimension());
            if (p.origin() != null) t.putLong("Origin", p.origin().asLong());
            ListTag demand = new ListTag();
            for (var d : p.demand()) { CompoundTag dt = new CompoundTag(); dt.putString("Ingredient", d.ingredient()); dt.putInt("Count", d.count()); dt.putInt("Delivered", d.delivered()); demand.add(dt); }
            t.put("Demand", demand);
            ListTag contributions = new ListTag();
            for (var c : p.contributions()) {
                CompoundTag ct = new CompoundTag(); ct.putUUID("Player", c.player()); ct.putString("Name", c.playerName()); ct.putString("Item", c.item());
                ct.putInt("Count", c.count()); ct.putLong("At", c.at()); contributions.add(ct);
            }
            t.put("Contributions", contributions);
            ListTag journal = new ListTag();
            for (var j : p.journal()) {
                CompoundTag jt = new CompoundTag(); jt.putLong("Pos", j.pos().asLong()); jt.putString("Before", j.before()); jt.putString("After", j.after());
                jt.putBoolean("Placed", j.placed()); journal.add(jt);
            }
            t.put("Journal", journal);
            if (p.pausedFrom() != null) t.putString("PausedFrom", p.pausedFrom().name());
            t.putLong("CreatedAt", p.createdAt()); t.putLong("UpdatedAt", p.updatedAt()); t.putLong("ProjectRevision", p.revision());
            projects.add(t);
        }
        tag.put("Projects", projects);
        ListTag economies = new ListTag();
        for (var e : r.economies.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Settlement", e.settlement()); t.putInt("Points", e.points()); t.putBoolean("OptedIn", e.optedIn()); t.putLong("LastProduced", e.lastProduced());
            CompoundTag roles = new CompoundTag(); e.roles().forEach(roles::putInt); t.put("Roles", roles);
            CompoundTag goods = new CompoundTag(); e.abstractGoods().forEach(goods::putLong); t.put("Goods", goods);
            economies.add(t);
        }
        tag.put("Economies", economies);
        ListTag homes = new ListTag();
        for (var h : r.homes.values()) {
            CompoundTag t = new CompoundTag(); t.putUUID("Player", h.player()); t.putString("Dimension", h.dimension()); t.putLong("Pos", h.pos().asLong()); t.putInt("Radius", h.radius());
            homes.add(t);
        }
        tag.put("Homes", homes);
        ListTag refunds = new ListTag();
        for (var f : r.refunds) {
            CompoundTag t = new CompoundTag(); t.putUUID("Player", f.player()); t.putString("Item", f.item()); t.putInt("Count", f.count()); t.putUUID("Project", f.project());
            refunds.add(t);
        }
        tag.put("Refunds", refunds);
        ListTag trades = new ListTag();
        for (var x : r.trades) {
            CompoundTag t = new CompoundTag(); t.putUUID("From", x.from()); t.putUUID("To", x.to()); t.putString("Commodity", x.commodity()); t.putLong("Amount", x.amount()); t.putLong("At", x.at());
            trades.add(t);
        }
        tag.put("Trades", trades);
        ListTag receipts = new ListTag();
        r.receipts.forEach((request, receipt) -> { CompoundTag t = new CompoundTag(); t.putUUID("Request", request); t.putUUID("Actor", receipt.actor());
            t.putString("Fingerprint", receipt.fingerprint()); t.putString("Result", receipt.result()); receipts.add(t); });
        tag.put("Receipts", receipts);
        return tag;
    }
}
