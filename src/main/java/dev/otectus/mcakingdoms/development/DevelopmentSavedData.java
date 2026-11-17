package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.FaultPoints;
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
 * Settlement development and economy records (D2-D3), in {@code mcakingdoms_development.dat}: public works with their
 * deliveries and construction journal, each settlement's development state, the productive assets completed works left,
 * protected homes, refunds owed, inventory operations still being confirmed, and request receipts. Keyed to canonical
 * settlement ids. Every change is written before it takes effect; a newer schema or damaged file is kept read-only and
 * unchanged.
 *
 * <p>Schema 2 (0.3.1) adds, without dropping anything schema 1 held: each placement's stage (AUD-03), each work's kind and
 * structured blockers (AUD-04/05), inventory operations (AUD-02), productive assets independent of the display history,
 * abstract overflow kept from merges (AUD-06/07), and request receipts with an issue time and an eviction watermark
 * (AUD-07). A schema 1 file is read and written back as schema 2.
 */
public final class DevelopmentSavedData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_development";
    static final int SCHEMA = 2, PROJECTS = 512, CONTRIBUTIONS = 256, SETTLEMENTS = 4096, HOMES = 1024, REFUNDS = 4096, RECEIPTS = 4096, TRADES = 64,
            ASSETS = 8192, OPERATIONS = 1024, BLOCKERS = 16;

    /** A work's lifecycle (D3); the exceptional states carry a recorded reason. {@code ROLLING_BACK} is removing what it built. */
    public enum State {
        PROPOSED, SURVEYED, APPROVED, FUNDED, READY, BUILDING, COMPLETED, BLOCKED, PAUSED, CANCELLED, REPAIR_NEEDED, ROLLING_BACK;
        public boolean open() { return this != COMPLETED && this != CANCELLED; }
    }
    /** What a work was proposed as, kept for its whole life (AUD-04): a funded milestone, or construction on a surveyed plot. */
    public enum WorkKind { MILESTONE, CONSTRUCTION }
    /** Where an amount comes from (D2): items players really delivered, the abstract ledger, or a work's demand. */
    public enum Provenance { PHYSICAL_VERIFIED, ABSTRACT_SIMULATED, AUTHORED_DEMAND }

    /** One demand and what has been delivered against it. */
    public record Requirement(String ingredient, int count, int delivered) {
        public Requirement { if (count < 1 || delivered < 0 || delivered > count) throw new IllegalArgumentException("Invalid requirement"); }
        public int remaining() { return count - delivered; }
    }
    /** One delivery: who gave which item and how many, under which inventory operation (null before 0.3.1). */
    public record Contribution(UUID player, String playerName, String item, int count, long at, UUID operation) {
        public Contribution(UUID player, String playerName, String item, int count, long at) { this(player, playerName, item, count, at, null); }
    }

    /**
     * A planned block's stage (AUD-03). {@code INTENT} is written before the world is touched; {@code APPLIED} after the
     * block was placed and read back. A world save hands chunks to a background writer, so one save that shows the block
     * makes it {@code SAVED} and the next one {@code VERIFIED}; a later server run that loads it from disk verifies it at
     * once. Restores of a rollback go the same way: {@code RESTORED}, {@code RESTORE_SAVED}, then {@code REVERTED}. Only
     * {@code APPLIED}, {@code SAVED} and {@code VERIFIED} cells holding exactly this work's block are this work's.
     */
    public enum Stage { PLANNED, INTENT, APPLIED, SAVED, VERIFIED, RESTORED, RESTORE_SAVED, REVERTED;
        public boolean placed() { return this == APPLIED || this == SAVED || this == VERIFIED; }
        public boolean restoring() { return this == RESTORED || this == RESTORE_SAVED; }
        /** Not yet known to be on disk: settled from the loaded world in a later server run. */
        public boolean unsettled() { return this == INTENT || this == APPLIED || this == SAVED || restoring(); }
    }
    /** One planned block: where, what was there when surveyed, what the work places, its stage, and the session that last moved it. */
    public record Placement(BlockPos pos, String before, String after, Stage stage, String session) {
        public Placement { Objects.requireNonNull(pos); Objects.requireNonNull(stage); session = session == null ? "" : session; }
        public boolean placed() { return stage.placed(); }
        Placement at(Stage next, String session) { return new Placement(pos, before, after, next, session); }
    }

    /** Why a plot or a block cannot be built (AUD-05): a cause, the position it concerns (or null) and evidence. Worded at presentation. */
    public enum Cause {
        DIMENSION, OTHER_DIMENSION, BORDER, UNLOADED, OUTSIDE, HOME, BUILDING, BUILDINGS_UNREADABLE, GROUND, OCCUPIED, FOREIGN_MATCH, SUPPORT,
        BLOCK_ENTITY, BED, FARMLAND, DOOR, REDSTONE, POI, PROTECTED, CHANGED, UNSUPPORTED, REFUSED, CONSENT, AUTHORITY, MODE, KINGDOM_CHANGED,
        INHERITED, TEMPLATE_MISSING, LEVEL_MISSING
    }
    public record Blocker(Cause cause, BlockPos pos, String evidence) {
        public Blocker { Objects.requireNonNull(cause); evidence = evidence == null ? "" : evidence.length() > 256 ? evidence.substring(0, 256) : evidence; }
    }

    public record Project(UUID id, UUID settlement, String kingdom, ResourceLocation template, State state, String reasonKey, UUID proposer, UUID approver,
                          boolean approvedByOperator, String dimension, BlockPos origin, List<Requirement> demand, List<Contribution> contributions,
                          List<Placement> journal, State pausedFrom, long createdAt, long updatedAt, long revision, WorkKind kind, List<Blocker> blockers,
                          UUID completion) {
        public Project {
            Objects.requireNonNull(id); Objects.requireNonNull(settlement); Objects.requireNonNull(kingdom); Objects.requireNonNull(template);
            Objects.requireNonNull(state); Objects.requireNonNull(proposer); Objects.requireNonNull(kind);
            reasonKey = reasonKey == null ? "" : reasonKey; dimension = dimension == null ? "" : dimension;
            demand = List.copyOf(demand); contributions = List.copyOf(contributions); journal = List.copyOf(journal);
            blockers = List.copyOf(blockers == null ? List.of() : blockers.size() > BLOCKERS ? blockers.subList(0, BLOCKERS) : blockers);
            if (demand.size() > 8 || contributions.size() > CONTRIBUTIONS || journal.size() > DevelopmentTemplate.MAX_BLOCKS || reasonKey.length() > 4096)
                throw new IllegalArgumentException("Development project too large");
            if (kind == WorkKind.CONSTRUCTION && origin == null) throw new IllegalArgumentException("A construction work needs a plot");
        }
        public boolean funded() { return demand.stream().allMatch(r -> r.remaining() == 0); }
        public boolean construction() { return kind == WorkKind.CONSTRUCTION; }
        /** The kingdom the work was proposed in; authority for new actions is the settlement's current kingdom (AUD-06). */
        public String proposedIn() { return kingdom; }
        Project with(State state, String reasonKey, long now, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions, journal,
                    state == State.PAUSED ? (this.state == State.PAUSED ? pausedFrom : this.state) : null, createdAt, now, revision, kind,
                    state == State.PAUSED || state == State.BLOCKED || state == State.REPAIR_NEEDED || state == State.ROLLING_BACK ? blockers : List.of(), completion);
        }
        Project blocked(State state, String reasonKey, List<Blocker> blockers, long now, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions, journal,
                    state == State.PAUSED ? (this.state == State.PAUSED ? pausedFrom : this.state) : null, createdAt, now, revision, kind, blockers, completion);
        }
        Project approved(UUID by, boolean operator, long now, long revision) {
            return new Project(id, settlement, kingdom, template, State.APPROVED, "development.mcakingdoms.reason.approved", proposer, by, operator, dimension, origin,
                    demand, contributions, journal, null, createdAt, now, revision, kind, List.of(), completion);
        }
        /** The same work under a new approver (a resumed or re-reviewed work is approved again by whoever resumed it). */
        Project approvedBy(UUID by, boolean operator) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, by, operator, dimension, origin, demand, contributions, journal,
                    pausedFrom, createdAt, updatedAt, revision, kind, blockers, completion);
        }
        Project delivered(List<Requirement> demand, List<Contribution> contributions, long now, long revision) {
            boolean done = demand.stream().allMatch(r -> r.remaining() == 0);
            return new Project(id, settlement, kingdom, template, done ? State.FUNDED : state, done ? "development.mcakingdoms.reason.funded" : reasonKey, proposer, approver,
                    approvedByOperator, dimension, origin, demand, contributions, journal, pausedFrom, createdAt, now, revision, kind, blockers, completion);
        }
        Project journal(List<Placement> journal, State state, String reasonKey, long now, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions,
                    journal, pausedFrom, createdAt, now, revision, kind, state == State.BUILDING || state == State.READY ? List.of() : blockers, completion);
        }
        Project journal(List<Placement> journal) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions,
                    journal, pausedFrom, createdAt, updatedAt, revision, kind, blockers, completion);
        }
        Project completed(UUID operation, String reasonKey, long now, long revision) {
            return new Project(id, settlement, kingdom, template, State.COMPLETED, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions,
                    journal, null, createdAt, now, revision, kind, List.of(), operation);
        }
        /** The work under a settlement that absorbed its own; the proposing kingdom stays as history (AUD-06). */
        Project movedTo(UUID settlement, long revision) {
            return new Project(id, settlement, kingdom, template, state, reasonKey, proposer, approver, approvedByOperator, dimension, origin, demand, contributions,
                    journal, pausedFrom, createdAt, updatedAt, revision, kind, blockers, completion);
        }
    }

    /**
     * A settlement's development: points and roles from completed works, whether it opted in to construction, its abstract
     * ledger, and {@code overflow}: abstract amounts above the cap that a merge brought in, kept rather than truncated (AUD-06).
     */
    public record Economy(UUID settlement, int points, Map<String, Integer> roles, boolean optedIn, Map<String, Long> abstractGoods, long lastProduced,
                          Map<String, Long> overflow) {
        public Economy { roles = Map.copyOf(roles); abstractGoods = Map.copyOf(abstractGoods); overflow = Map.copyOf(overflow == null ? Map.of() : overflow); }
        public Economy(UUID settlement, int points, Map<String, Integer> roles, boolean optedIn, Map<String, Long> abstractGoods, long lastProduced) {
            this(settlement, points, roles, optedIn, abstractGoods, lastProduced, Map.of());
        }
        public static Economy empty(UUID settlement) { return new Economy(settlement, 0, Map.of(), false, Map.of(), 0, Map.of()); }
        /** The development tier from completed works' points: 0 (none), then 1, 3, 6, 10 and 15 points for tiers 1 to 5. */
        public int tier() { return Development.tier(points); }
        public Optional<String> specialization() {
            return roles.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).map(Map.Entry::getKey);
        }
        Economy withConsent(boolean in) { return new Economy(settlement, points, roles, in, abstractGoods, lastProduced, overflow); }
        Economy withGoods(Map<String, Long> goods, long produced) { return new Economy(settlement, points, roles, optedIn, goods, produced, overflow); }
        Economy withPoints(int points, Map<String, Integer> roles) { return new Economy(settlement, points, roles, optedIn, abstractGoods, lastProduced, overflow); }
    }

    /**
     * What a completed work left behind (AUD-07): its earned points, role and abstract output, recorded at completion and
     * independent of the display history and of later template changes. Production and point reconciliation read these.
     */
    public record Asset(UUID work, UUID settlement, ResourceLocation template, String role, int points, Map<String, Integer> output, long completedAt) {
        public Asset { Objects.requireNonNull(work); Objects.requireNonNull(settlement); Objects.requireNonNull(template); role = role == null ? "" : role; output = Map.copyOf(output); }
        Asset movedTo(UUID settlement) { return new Asset(work, settlement, template, role, points, output, completedAt); }
    }

    /** A player's home that construction never touches: their bed, and a radius. */
    public record Home(UUID player, String dimension, BlockPos pos, int radius) { }
    /** Items owed back to a contributor of a cancelled work. */
    public record Refund(UUID player, String item, int count, UUID project) { }
    /** One abstract trade, labelled as such: no item moved. */
    public record Trade(UUID from, UUID to, String commodity, long amount, long at) { }
    /** A request already answered: who asked, what for, the answer, and when its id was issued (AUD-07). */
    record Receipt(UUID actor, String fingerprint, String result, long issuedAt) { }

    /**
     * An amount of one item: for a delivery, the demand it fills ({@code requirement}); for a refund claim, the work the
     * refund came from ({@code source}), so anything put back keeps its provenance.
     */
    public record ItemCount(String item, int count, int requirement, UUID source) {
        public ItemCount { if (count < 1) throw new IllegalArgumentException("Invalid item count"); }
        public ItemCount(String item, int count, int requirement) { this(item, count, requirement, null); }
    }

    /**
     * An inventory effect being confirmed (AUD-02). The operation is written before an item leaves or enters an inventory;
     * the same id is written into the player's own saved data with the inventory change, so whichever of the two files a
     * crash keeps, recovery can tell from the player's file whether the change happened, and never guesses.
     */
    public enum OpKind { CONTRIBUTION, REFUND_CLAIM }
    public enum OpStage {
        /** Written; the inventory change may or may not be in the player's saved file. */
        PENDING,
        /** The player's saved file holds the change, and the records hold its effect. */
        CONFIRMED,
        /** The player's saved file proves the change never happened; nothing was taken or given. */
        ROLLED_BACK,
        /** Neither file can show what happened; kept for an operator, never guessed. */
        UNRESOLVED;
        public boolean resolved() { return this == CONFIRMED || this == ROLLED_BACK; }
    }
    public record InventoryOp(UUID id, OpKind kind, UUID player, UUID project, List<ItemCount> items, OpStage stage, long createdAt, long updatedAt, String detail) {
        public InventoryOp {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(player); Objects.requireNonNull(stage);
            items = List.copyOf(items); detail = detail == null ? "" : detail;
            if (items.size() > 64) throw new IllegalArgumentException("Inventory operation too large");
        }
        InventoryOp at(OpStage stage, List<ItemCount> items, long now, String detail) { return new InventoryOp(id, kind, player, project, items, stage, createdAt, now, detail); }
        public int total() { return items.stream().mapToInt(ItemCount::count).sum(); }
    }

    public static final class Records {
        long revision;
        /** The Minecraft day abstract trade last ran on, so it runs once a day at most. */
        long tradeDay = -1;
        /** The newest issue time of a receipt dropped for room; requests issued at or before it are refused as expired (AUD-07). */
        long receiptWatermark = Long.MIN_VALUE;
        final Map<UUID, Project> projects = new LinkedHashMap<>();
        final Map<UUID, Economy> economies = new LinkedHashMap<>();
        final Map<UUID, Asset> assets = new LinkedHashMap<>();
        final Map<UUID, Home> homes = new LinkedHashMap<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<Trade> trades = new ArrayList<>();
        final Map<UUID, Receipt> receipts = new LinkedHashMap<>();
        final Map<UUID, InventoryOp> operations = new LinkedHashMap<>();

        Records copy() {
            var next = new Records();
            next.revision = revision; next.tradeDay = tradeDay; next.receiptWatermark = receiptWatermark;
            next.projects.putAll(projects); next.economies.putAll(economies); next.assets.putAll(assets); next.homes.putAll(homes);
            next.refunds.addAll(refunds); next.trades.addAll(trades); next.receipts.putAll(receipts); next.operations.putAll(operations);
            return next;
        }

        public long revision() { return revision; }
        public Collection<Project> projects() { return Collections.unmodifiableCollection(projects.values()); }
        public Optional<Project> project(UUID id) { return Optional.ofNullable(projects.get(id)); }
        public Economy economy(UUID settlement) { return economies.getOrDefault(settlement, Economy.empty(settlement)); }
        public Collection<Asset> assets() { return Collections.unmodifiableCollection(assets.values()); }
        public Collection<Home> homes() { return Collections.unmodifiableCollection(homes.values()); }
        public List<Refund> refunds() { return Collections.unmodifiableList(refunds); }
        public List<Trade> trades() { return Collections.unmodifiableList(trades); }
        public Collection<InventoryOp> operations() { return Collections.unmodifiableCollection(operations.values()); }
        public Optional<InventoryOp> operation(UUID id) { return Optional.ofNullable(operations.get(id)); }
        public long receiptWatermark() { return receiptWatermark; }

        /** Whether a delivery to {@code project} still waits for the player's save; until then the work does nothing irreversible. */
        public boolean unconfirmed(UUID project) {
            return operations.values().stream().anyMatch(o -> o.kind() == OpKind.CONTRIBUTION && project.equals(o.project()) && !o.stage().resolved());
        }

        /** Whether anything still needs the store while development is off: an open work, an unsettled operation or a refund. */
        public boolean obligations() {
            return !refunds.isEmpty() || operations.values().stream().anyMatch(o -> !o.stage().resolved()) || projects.values().stream().anyMatch(p -> p.state().open());
        }

        void validate() {
            if (projects.size() > PROJECTS || economies.size() > SETTLEMENTS || homes.size() > HOMES || refunds.size() > REFUNDS || receipts.size() > RECEIPTS
                    || trades.size() > TRADES || assets.size() > ASSETS || operations.size() > OPERATIONS)
                throw new IllegalArgumentException("Development record bounds exceeded");
            for (var e : economies.values()) if (e.points() < 0 || e.abstractGoods().values().stream().anyMatch(v -> v < 0 || v > Development.ABSTRACT_CAP)
                    || e.overflow().values().stream().anyMatch(v -> v < 0))
                throw new IllegalArgumentException("Development ledger out of range");
        }
    }

    private Records records = new Records();
    private boolean readOnly;
    private CompoundTag preserved;
    private String diagnostic = "";

    public static DevelopmentSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DevelopmentSavedData::load, DevelopmentSavedData::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        var data = get(server);
        return StoreStatus.of(NAME, data.writable(), data.diagnostic);
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var r = get(server).records;
        return List.of(new Capacity(NAME, "projects", r.projects.size(), PROJECTS, Capacity.Relief.RETENTION),
                new Capacity(NAME, "assets", r.assets.size(), ASSETS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "refunds", r.refunds.size(), REFUNDS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "inventory operations", r.operations.size(), OPERATIONS, Capacity.Relief.RETENTION),
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
            FaultPoints.reach("development.commit");
            retain(next);
            next.validate();
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), write(next, new CompoundTag()));
            records = next;
            setDirty(false);
            return true;
        } catch (java.io.IOException | IllegalArgumentException | IllegalStateException failure) {
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] The development records were not changed: {}", failure.getMessage());
            return false;
        }
    }

    /**
     * Automatic retention (AUD-07). Receipts beyond their bound go oldest first and raise the watermark, so a request
     * issued before it is refused as expired rather than replayed. Settled inventory operations go oldest first. A closed
     * work is dropped from the display history only once its live effects are kept elsewhere: a completed work's asset is
     * recorded, a cancelled work owes no refund and has no operation. Open works and anything still owed are never dropped:
     * past that, the change is refused at capacity.
     */
    static void retain(Records next) {
        while (next.receipts.size() > RECEIPTS) {
            var oldest = next.receipts.entrySet().iterator().next();
            next.receiptWatermark = Math.max(next.receiptWatermark, oldest.getValue().issuedAt());
            next.receipts.remove(oldest.getKey());
        }
        while (next.trades.size() > TRADES) next.trades.remove(0);
        if (next.operations.size() > OPERATIONS / 2)
            for (var op : next.operations.values().stream().filter(o -> o.stage().resolved()).sorted(Comparator.comparingLong(InventoryOp::updatedAt)).toList()) {
                if (next.operations.size() <= OPERATIONS / 2) break;
                next.operations.remove(op.id());
            }
        if (next.projects.size() <= PROJECTS) return;
        var referenced = new HashSet<UUID>();
        next.refunds.forEach(r -> referenced.add(r.project()));
        next.operations.values().stream().filter(o -> !o.stage().resolved() && o.project() != null).forEach(o -> referenced.add(o.project()));
        for (var p : next.projects.values().stream().filter(p -> !p.state().open()).sorted(Comparator.comparingLong(Project::updatedAt).thenComparing(Project::id)).toList()) {
            if (next.projects.size() <= PROJECTS) break;
            boolean effectsKept = p.state() == State.COMPLETED ? next.assets.containsKey(p.id()) : !referenced.contains(p.id());
            if (effectsKept) next.projects.remove(p.id());
        }
    }

    public static DevelopmentSavedData load(CompoundTag tag) {
        var data = new DevelopmentSavedData();
        if (tag.getInt("Schema") > SCHEMA) {
            data.readOnly = true; data.preserved = tag.copy();
            data.diagnostic = NAME + ".dat was written by a newer version (schema " + tag.getInt("Schema") + "); it is kept read-only and unchanged";
            return data;
        }
        try {
            data.records = read(tag);
            data.records.validate();
        } catch (RuntimeException damaged) {
            data.readOnly = true; data.preserved = tag.copy(); data.records = new Records();
            data.diagnostic = NAME + ".dat could not be read (" + damaged.getMessage() + "); it is kept read-only and unchanged";
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] {}.dat could not be read and is kept read-only and unchanged: {}", NAME, damaged.toString());
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (readOnly) return preserved.copy();
        return write(records, tag);
    }

    private static BlockPos pos(CompoundTag t, String key) { return t.contains(key) ? BlockPos.of(t.getLong(key)) : null; }

    private static Records read(CompoundTag tag) {
        int schema = tag.getInt("Schema");
        var r = new Records();
        r.revision = tag.getLong("Revision");
        r.tradeDay = tag.contains("TradeDay") ? tag.getLong("TradeDay") : -1;
        r.receiptWatermark = tag.contains("ReceiptWatermark") ? tag.getLong("ReceiptWatermark") : Long.MIN_VALUE;
        for (Tag entry : tag.getList("Projects", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var demand = new ArrayList<Requirement>();
            for (Tag d : t.getList("Demand", Tag.TAG_COMPOUND)) { CompoundTag dt = (CompoundTag) d; demand.add(new Requirement(dt.getString("Ingredient"), dt.getInt("Count"), dt.getInt("Delivered"))); }
            var contributions = new ArrayList<Contribution>();
            for (Tag c : t.getList("Contributions", Tag.TAG_COMPOUND)) {
                CompoundTag ct = (CompoundTag) c;
                contributions.add(new Contribution(ct.getUUID("Player"), ct.getString("Name"), ct.getString("Item"), ct.getInt("Count"), ct.getLong("At"),
                        ct.hasUUID("Operation") ? ct.getUUID("Operation") : null));
            }
            var journal = new ArrayList<Placement>();
            for (Tag j : t.getList("Journal", Tag.TAG_COMPOUND)) {
                CompoundTag jt = (CompoundTag) j;
                // Schema 1 knew only placed or not: a placed block is kept as verified (never placed again), the rest as planned.
                Stage stage = jt.contains("Stage") ? Stage.valueOf(jt.getString("Stage")) : jt.getBoolean("Placed") ? Stage.VERIFIED : Stage.PLANNED;
                journal.add(new Placement(BlockPos.of(jt.getLong("Pos")), jt.getString("Before"), jt.getString("After"), stage, jt.getString("Session")));
            }
            var blockers = new ArrayList<Blocker>();
            for (Tag b : t.getList("Blockers", Tag.TAG_COMPOUND)) {
                CompoundTag bt = (CompoundTag) b;
                blockers.add(new Blocker(Cause.valueOf(bt.getString("Cause")), pos(bt, "Pos"), bt.getString("Evidence")));
            }
            BlockPos origin = pos(t, "Origin");
            WorkKind kind = t.contains("Kind") ? WorkKind.valueOf(t.getString("Kind")) : origin != null ? WorkKind.CONSTRUCTION : WorkKind.MILESTONE;
            var p = new Project(t.getUUID("Id"), t.getUUID("Settlement"), t.getString("Kingdom"), new ResourceLocation(t.getString("Template")), State.valueOf(t.getString("State")),
                    t.getString("Reason"), t.getUUID("Proposer"), t.hasUUID("Approver") ? t.getUUID("Approver") : null, t.getBoolean("ByOperator"), t.getString("Dimension"),
                    origin, demand, contributions, journal, t.contains("PausedFrom") ? State.valueOf(t.getString("PausedFrom")) : null, t.getLong("CreatedAt"),
                    t.getLong("UpdatedAt"), t.getLong("ProjectRevision"), kind, blockers, t.hasUUID("Completion") ? t.getUUID("Completion") : null);
            r.projects.put(p.id(), p);
        }
        for (Tag entry : tag.getList("Economies", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var roles = new TreeMap<String, Integer>();
            CompoundTag rt = t.getCompound("Roles"); for (String key : rt.getAllKeys()) roles.put(key, rt.getInt(key));
            var goods = new TreeMap<String, Long>();
            CompoundTag gt = t.getCompound("Goods"); for (String key : gt.getAllKeys()) goods.put(key, gt.getLong(key));
            var overflow = new TreeMap<String, Long>();
            CompoundTag ot = t.getCompound("Overflow"); for (String key : ot.getAllKeys()) overflow.put(key, ot.getLong(key));
            var e = new Economy(t.getUUID("Settlement"), t.getInt("Points"), roles, t.getBoolean("OptedIn"), goods, t.getLong("LastProduced"), overflow);
            r.economies.put(e.settlement(), e);
        }
        for (Tag entry : tag.getList("Assets", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var output = new TreeMap<String, Integer>();
            CompoundTag ot = t.getCompound("Output"); for (String key : ot.getAllKeys()) output.put(key, ot.getInt(key));
            var a = new Asset(t.getUUID("Work"), t.getUUID("Settlement"), new ResourceLocation(t.getString("Template")), t.getString("Role"), t.getInt("Points"), output, t.getLong("At"));
            r.assets.put(a.work(), a);
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
            r.receipts.put(t.getUUID("Request"), new Receipt(t.getUUID("Actor"), t.getString("Fingerprint"), t.getString("Result"),
                    t.contains("IssuedAt") ? t.getLong("IssuedAt") : Long.MIN_VALUE));
        }
        for (Tag entry : tag.getList("Operations", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            var items = new ArrayList<ItemCount>();
            for (Tag i : t.getList("Items", Tag.TAG_COMPOUND)) {
                CompoundTag it = (CompoundTag) i;
                items.add(new ItemCount(it.getString("Item"), it.getInt("Count"), it.getInt("Requirement"), it.hasUUID("Source") ? it.getUUID("Source") : null));
            }
            var op = new InventoryOp(t.getUUID("Id"), OpKind.valueOf(t.getString("Kind")), t.getUUID("Player"), t.hasUUID("Project") ? t.getUUID("Project") : null, items,
                    OpStage.valueOf(t.getString("Stage")), t.getLong("CreatedAt"), t.getLong("UpdatedAt"), t.getString("Detail"));
            r.operations.put(op.id(), op);
        }
        if (schema < 2) migrateAssets(r);
        return r;
    }

    /** Schema 1 kept no assets: each completed work becomes one, with the points and output its template gives now. */
    private static void migrateAssets(Records r) {
        for (var p : r.projects.values()) {
            if (p.state() != State.COMPLETED || r.assets.containsKey(p.id())) continue;
            var template = DevelopmentTemplate.get(p.template());
            r.assets.put(p.id(), new Asset(p.id(), p.settlement(), p.template(), template.map(t -> t.role().name().toLowerCase(Locale.ROOT)).orElse(""),
                    template.map(DevelopmentTemplate::tierPoints).orElse(0), template.map(DevelopmentTemplate::abstractOutput).orElse(Map.of()), p.updatedAt()));
        }
    }

    private static CompoundTag write(Records r, CompoundTag tag) {
        tag.putInt("Schema", SCHEMA); tag.putLong("Revision", r.revision); tag.putLong("TradeDay", r.tradeDay); tag.putLong("ReceiptWatermark", r.receiptWatermark);
        ListTag projects = new ListTag();
        for (var p : r.projects.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", p.id()); t.putUUID("Settlement", p.settlement()); t.putString("Kingdom", p.kingdom()); t.putString("Template", p.template().toString());
            t.putString("State", p.state().name()); t.putString("Reason", p.reasonKey()); t.putUUID("Proposer", p.proposer());
            if (p.approver() != null) t.putUUID("Approver", p.approver());
            t.putBoolean("ByOperator", p.approvedByOperator()); t.putString("Dimension", p.dimension()); t.putString("Kind", p.kind().name());
            if (p.origin() != null) t.putLong("Origin", p.origin().asLong());
            if (p.completion() != null) t.putUUID("Completion", p.completion());
            ListTag demand = new ListTag();
            for (var d : p.demand()) { CompoundTag dt = new CompoundTag(); dt.putString("Ingredient", d.ingredient()); dt.putInt("Count", d.count()); dt.putInt("Delivered", d.delivered()); demand.add(dt); }
            t.put("Demand", demand);
            ListTag contributions = new ListTag();
            for (var c : p.contributions()) {
                CompoundTag ct = new CompoundTag(); ct.putUUID("Player", c.player()); ct.putString("Name", c.playerName()); ct.putString("Item", c.item());
                ct.putInt("Count", c.count()); ct.putLong("At", c.at()); if (c.operation() != null) ct.putUUID("Operation", c.operation()); contributions.add(ct);
            }
            t.put("Contributions", contributions);
            ListTag journal = new ListTag();
            for (var j : p.journal()) {
                CompoundTag jt = new CompoundTag(); jt.putLong("Pos", j.pos().asLong()); jt.putString("Before", j.before()); jt.putString("After", j.after());
                jt.putString("Stage", j.stage().name()); jt.putBoolean("Placed", j.placed()); if (!j.session().isEmpty()) jt.putString("Session", j.session()); journal.add(jt);
            }
            t.put("Journal", journal);
            ListTag blockers = new ListTag();
            for (var b : p.blockers()) {
                CompoundTag bt = new CompoundTag(); bt.putString("Cause", b.cause().name()); if (b.pos() != null) bt.putLong("Pos", b.pos().asLong());
                bt.putString("Evidence", b.evidence()); blockers.add(bt);
            }
            t.put("Blockers", blockers);
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
            if (!e.overflow().isEmpty()) { CompoundTag overflow = new CompoundTag(); e.overflow().forEach(overflow::putLong); t.put("Overflow", overflow); }
            economies.add(t);
        }
        tag.put("Economies", economies);
        ListTag assets = new ListTag();
        for (var a : r.assets.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Work", a.work()); t.putUUID("Settlement", a.settlement()); t.putString("Template", a.template().toString()); t.putString("Role", a.role());
            t.putInt("Points", a.points()); t.putLong("At", a.completedAt());
            CompoundTag output = new CompoundTag(); a.output().forEach(output::putInt); t.put("Output", output);
            assets.add(t);
        }
        tag.put("Assets", assets);
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
            t.putString("Fingerprint", receipt.fingerprint()); t.putString("Result", receipt.result()); t.putLong("IssuedAt", receipt.issuedAt()); receipts.add(t); });
        tag.put("Receipts", receipts);
        ListTag operations = new ListTag();
        for (var op : r.operations.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", op.id()); t.putString("Kind", op.kind().name()); t.putUUID("Player", op.player()); if (op.project() != null) t.putUUID("Project", op.project());
            t.putString("Stage", op.stage().name()); t.putLong("CreatedAt", op.createdAt()); t.putLong("UpdatedAt", op.updatedAt()); t.putString("Detail", op.detail());
            ListTag items = new ListTag();
            for (var i : op.items()) {
                CompoundTag it = new CompoundTag(); it.putString("Item", i.item()); it.putInt("Count", i.count()); it.putInt("Requirement", i.requirement());
                if (i.source() != null) it.putUUID("Source", i.source());
                items.add(it);
            }
            t.put("Items", items);
            operations.add(t);
        }
        tag.put("Operations", operations);
        return tag;
    }
}
