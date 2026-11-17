package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MCA: Kingdoms' own records about Townstead, in {@code mcakingdoms_townstead.dat} (spec 4.2, 6.3): an operator's
 * decision about who governs a settlement's local government, with the Townstead evidence it was made on, and the
 * first-observation baseline used to notice a Townstead save restored from an older backup. Townstead's records are never
 * written from here. A file with a newer schema, or one that cannot be read, is kept read-only and unchanged.
 */
public final class TownsteadSavedData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_townstead";
    static final int SCHEMA = 1;
    static final int DECISIONS = 4_096;
    static final int BASELINES = 8_192;

    /** Who an operator decided governs the settlement's local government. */
    public enum Owner { KINGDOMS, TOWNSTEAD }

    /**
     * {@code faction} and {@code governance} are Townstead's faction for the settlement and the fingerprint of its native
     * offices when the decision was made (empty and 0 when Townstead had none), so a later change is noticed.
     */
    public record Decision(UUID settlement, Owner owner, String faction, int governance, String version, UUID decidedBy, long at, String note) {
        public Decision {
            Objects.requireNonNull(settlement);
            Objects.requireNonNull(owner);
            faction = faction == null ? "" : faction;
            version = version == null ? "" : version;
            note = note == null ? "" : note;
        }
    }

    /** What Townstead looked like when MCA: Kingdoms first saw a settlement with it; a baseline, never an award. */
    public record Baseline(UUID settlement, String version, int revision, long townsteadDay, long observedAt) {
        public Baseline {
            Objects.requireNonNull(settlement);
            version = version == null ? "" : version;
        }
    }

    private final Map<UUID, Decision> decisions = new LinkedHashMap<>();
    private final Map<UUID, Baseline> baselines = new LinkedHashMap<>();
    private long revision;
    private boolean readOnly;
    private CompoundTag preserved;

    public static TownsteadSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TownsteadSavedData::load, TownsteadSavedData::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        return StoreStatus.of(NAME, get(server).writable(), "");
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var data = get(server);
        return List.of(new Capacity(NAME, "decisions", data.decisions.size(), DECISIONS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "baselines", data.baselines.size(), BASELINES, Capacity.Relief.RETENTION));
    }

    static TownsteadSavedData load(CompoundTag tag) {
        var data = new TownsteadSavedData();
        if (tag.getInt("Schema") > SCHEMA) { data.readOnly = true; data.preserved = tag.copy(); return data; }
        data.revision = tag.getLong("Revision");
        try {
            for (Tag entry : tag.getList("Decisions", Tag.TAG_COMPOUND)) {
                CompoundTag d = (CompoundTag) entry;
                var decision = new Decision(d.getUUID("Settlement"), Owner.valueOf(d.getString("Owner")), d.getString("Faction"), d.getInt("Governance"),
                        d.getString("Version"), d.hasUUID("DecidedBy") ? d.getUUID("DecidedBy") : null, d.getLong("At"), d.getString("Note"));
                data.decisions.put(decision.settlement(), decision);
            }
            for (Tag entry : tag.getList("Baselines", Tag.TAG_COMPOUND)) {
                CompoundTag b = (CompoundTag) entry;
                var baseline = new Baseline(b.getUUID("Settlement"), b.getString("Version"), b.getInt("Revision"), b.getLong("Day"), b.getLong("At"));
                data.baselines.put(baseline.settlement(), baseline);
            }
        } catch (IllegalArgumentException | NullPointerException | ClassCastException damaged) {
            data.readOnly = true;
            data.preserved = tag.copy();
            data.decisions.clear();
            data.baselines.clear();
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (readOnly) return preserved.copy();
        tag.putInt("Schema", SCHEMA);
        tag.putLong("Revision", revision);
        ListTag decisionList = new ListTag();
        for (Decision d : decisions.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Settlement", d.settlement()); t.putString("Owner", d.owner().name()); t.putString("Faction", d.faction());
            t.putInt("Governance", d.governance()); t.putString("Version", d.version());
            if (d.decidedBy() != null) t.putUUID("DecidedBy", d.decidedBy());
            t.putLong("At", d.at()); t.putString("Note", d.note());
            decisionList.add(t);
        }
        tag.put("Decisions", decisionList);
        ListTag baselineList = new ListTag();
        for (Baseline b : baselines.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Settlement", b.settlement()); t.putString("Version", b.version()); t.putInt("Revision", b.revision());
            t.putLong("Day", b.townsteadDay()); t.putLong("At", b.observedAt());
            baselineList.add(t);
        }
        tag.put("Baselines", baselineList);
        return tag;
    }

    public boolean writable() { return !readOnly; }
    public long revision() { return revision; }
    public Map<UUID, Decision> decisions() { return Collections.unmodifiableMap(decisions); }
    public Optional<Decision> decision(UUID settlement) { return Optional.ofNullable(decisions.get(settlement)); }
    public Optional<Baseline> baseline(UUID settlement) { return Optional.ofNullable(baselines.get(settlement)); }
    public int baselineCount() { return baselines.size(); }

    /** Records or replaces (or with {@code null}, removes) a decision, durably; false and unchanged when the write failed. */
    boolean decide(MinecraftServer server, UUID settlement, Decision decision) {
        if (readOnly || (decision != null && !decisions.containsKey(settlement) && decisions.size() >= DECISIONS)) return false;
        Decision previous = decisions.get(settlement);
        long before = revision;
        if (decision == null) decisions.remove(settlement); else decisions.put(settlement, decision);
        revision++;
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), save(new CompoundTag()));
            setDirty(false);
            return true;
        } catch (java.io.IOException failure) {
            if (previous == null) decisions.remove(settlement); else decisions.put(settlement, previous);
            revision = before;
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] Could not save the Townstead authority decision; it is unchanged", failure);
            return false;
        }
    }

    /** Records a baseline once (or replaces it after an operator acknowledged a change); saved with the world. */
    void baseline(Baseline baseline) {
        if (readOnly || (!baselines.containsKey(baseline.settlement()) && baselines.size() >= BASELINES)) return;
        baselines.put(baseline.settlement(), baseline);
        revision++;
        setDirty();
    }

    /**
     * Retention (AUD-07): drops the baselines {@code keep} rejects and returns how many; saved with the world. Nothing
     * changes while the store is read-only.
     */
    int retainBaselines(java.util.function.Predicate<UUID> keep) {
        if (readOnly) return 0;
        int before = baselines.size();
        if (baselines.keySet().removeIf(id -> !keep.test(id))) { revision++; setDirty(); }
        return before - baselines.size();
    }

    /**
     * A baseline belongs to one settlement. Once that id is no longer a settlement of its own (merged into another, or
     * gone), nothing reads it again, so it is dropped at server start; {@code live} says which ids still are.
     */
    public static int retainLiveBaselines(MinecraftServer server, java.util.function.Predicate<UUID> live) {
        return get(server).retainBaselines(live);
    }
}
