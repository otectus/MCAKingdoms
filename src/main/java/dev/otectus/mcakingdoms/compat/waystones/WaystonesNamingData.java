package dev.otectus.mcakingdoms.compat.waystones;

import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;

/**
 * Which waystone names MCA: Kingdoms gave, and why (WAY-01), in {@code mcakingdoms_waystones.dat}. One entry per stone
 * this integration looked at. Nothing here holds a Waystones type, so the file loads without Waystones.
 *
 * <p>Stages: {@code PENDING} waits (briefly, bounded) for the settlement around a stone to be recognized;
 * {@code ASSIGNED} records the name about to be written into Waystones' own record, before it is written, so a crash
 * between the two is reconciled from the stone itself; {@code MANAGED} is a name the next world save showed Waystones
 * holding; {@code DETACHED} is a stone a player (or anything else) named, which is never touched again;
 * {@code EXPIRED} is a pending stone whose settlement was not recognized in time; {@code UNRESOLVED} is a stone whose
 * name after a restart neither matches the record nor is empty, so its native name is left alone. Every change is
 * written before it takes effect; a newer schema or damaged file is kept read-only and unchanged.
 */
public final class WaystonesNamingData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_waystones";
    static final int SCHEMA = 1, ENTRIES = 16_384, PENDING = 256;

    public enum Stage { PENDING, ASSIGNED, MANAGED, DETACHED, EXPIRED, UNRESOLVED;
        /** Names held by these stages are reserved, so a second assignment never renumbers or repeats them. */
        public boolean reserves() { return this == ASSIGNED || this == MANAGED; }
    }

    /**
     * One stone: its id and place, the settlement it was named after (canonical at the time), the base name, the name
     * given and its number, the stage, when it changed, and a short reason for operators.
     */
    public record Entry(UUID stone, String dimension, BlockPos pos, UUID settlement, String base, String name, int number, Stage stage,
                        long createdAt, long updatedAt, String detail) {
        public Entry {
            Objects.requireNonNull(stone); Objects.requireNonNull(stage); Objects.requireNonNull(pos);
            dimension = dimension == null ? "" : dimension; base = base == null ? "" : base; name = name == null ? "" : name;
            detail = detail == null ? "" : detail.length() > 128 ? detail.substring(0, 128) : detail;
            if (name.length() > WaystoneNames.NATIVE_LIMIT || base.length() > WaystoneNames.NATIVE_LIMIT) throw new IllegalArgumentException("Waystone name too long");
        }
        Entry at(Stage next, long now, String why) { return new Entry(stone, dimension, pos, settlement, base, name, number, next, createdAt, now, why); }
        Entry named(UUID settlement, String base, String name, int number, Stage next, long now) {
            return new Entry(stone, dimension, pos, settlement, base, name, number, next, createdAt, now, "");
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long revision;
    private boolean readOnly;
    private CompoundTag preserved;
    private String diagnostic = "";

    public static WaystonesNamingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WaystonesNamingData::load, WaystonesNamingData::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        var data = get(server);
        return StoreStatus.of(NAME, data.writable(), data.diagnostic);
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var data = get(server);
        return List.of(new Capacity(NAME, "named waystones", data.entries.size(), ENTRIES, Capacity.Relief.RETENTION),
                new Capacity(NAME, "waystones waiting for a settlement", data.count(Stage.PENDING), PENDING, Capacity.Relief.RETENTION));
    }

    public boolean writable() { return !readOnly; }
    public long revision() { return revision; }
    public Optional<Entry> entry(UUID stone) { return Optional.ofNullable(entries.get(stone)); }
    public Collection<Entry> entries() { return Collections.unmodifiableCollection(entries.values()); }
    public int count(Stage stage) { return (int) entries.values().stream().filter(e -> e.stage() == stage).count(); }

    /** Names reserved by earlier assignments, for collision checks. */
    public Set<String> reserved() {
        var out = new HashSet<String>();
        for (var e : entries.values()) if (e.stage().reserves() && !e.name().isEmpty()) out.add(e.name());
        return out;
    }

    /**
     * Writes {@code changes} (entries replaced by stone) and only then makes them current; false (nothing changed) when
     * read-only, over a bound or the write failed. Settled entries beyond the bound go oldest first, a player's rename
     * last of all (it keeps a cleared name from being filled in later); pending and assigned ones are never dropped, and a
     * change that needs more room is refused.
     */
    public boolean commit(MinecraftServer server, Collection<Entry> changes) {
        if (readOnly) return false;
        var next = new LinkedHashMap<>(entries);
        for (var change : changes) next.put(change.stone(), change);
        if (next.values().stream().filter(e -> e.stage() == Stage.PENDING).count() > PENDING) return false;
        if (next.size() > ENTRIES)
            for (var old : next.values().stream().filter(e -> e.stage() != Stage.PENDING && e.stage() != Stage.ASSIGNED)
                    .sorted(Comparator.comparing((Entry e) -> e.stage() == Stage.DETACHED).thenComparingLong(Entry::updatedAt)).toList()) {
                if (next.size() <= ENTRIES) break;
                next.remove(old.stone());
            }
        if (next.size() > ENTRIES) return false;
        try {
            dev.otectus.mcakingdoms.persistence.FaultPoints.reach("waystones.commit");
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), write(next, revision + 1, new CompoundTag()));
            entries.clear(); entries.putAll(next); revision++;
            setDirty(false);
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] The waystone naming records were not changed: {}", failure.getMessage());
            return false;
        }
    }

    public static WaystonesNamingData load(CompoundTag tag) {
        var data = new WaystonesNamingData();
        if (tag.getInt("Schema") > SCHEMA) {
            data.readOnly = true; data.preserved = tag.copy();
            data.diagnostic = NAME + ".dat was written by a newer version; it is kept read-only and unchanged";
            return data;
        }
        try {
            data.revision = tag.getLong("Revision");
            for (Tag item : tag.getList("Entries", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) item;
                var e = new Entry(t.getUUID("Stone"), t.getString("Dimension"), BlockPos.of(t.getLong("Pos")), t.hasUUID("Settlement") ? t.getUUID("Settlement") : null,
                        t.getString("Base"), t.getString("Name"), t.getInt("Number"), Stage.valueOf(t.getString("Stage")), t.getLong("CreatedAt"), t.getLong("UpdatedAt"),
                        t.getString("Detail"));
                data.entries.put(e.stone(), e);
            }
            if (data.entries.size() > ENTRIES) throw new IllegalArgumentException("more than " + ENTRIES + " entries");
        } catch (RuntimeException damaged) {
            data.readOnly = true; data.preserved = tag.copy(); data.entries.clear();
            data.diagnostic = NAME + ".dat could not be read (" + damaged.getMessage() + "); it is kept read-only and unchanged";
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] {}", data.diagnostic);
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (readOnly) return preserved.copy();
        return write(entries, revision, tag);
    }

    private static CompoundTag write(Map<UUID, Entry> entries, long revision, CompoundTag tag) {
        tag.putInt("Schema", SCHEMA); tag.putLong("Revision", revision);
        ListTag list = new ListTag();
        for (var e : entries.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Stone", e.stone()); t.putString("Dimension", e.dimension()); t.putLong("Pos", e.pos().asLong());
            if (e.settlement() != null) t.putUUID("Settlement", e.settlement());
            t.putString("Base", e.base()); t.putString("Name", e.name()); t.putInt("Number", e.number()); t.putString("Stage", e.stage().name());
            t.putLong("CreatedAt", e.createdAt()); t.putLong("UpdatedAt", e.updatedAt()); t.putString("Detail", e.detail());
            list.add(t);
        }
        tag.put("Entries", list);
        return tag;
    }
}
