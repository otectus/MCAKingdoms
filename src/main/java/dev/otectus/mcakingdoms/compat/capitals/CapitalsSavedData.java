package dev.otectus.mcakingdoms.compat.capitals;

import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;

/**
 * Which MCA Capitals capital governs which kingdom's crown (C3), with the evidence and the decision behind each. Kept in
 * {@code mcakingdoms_capitals.dat}; the mapping is ours, the capitals are Capitals'. A file with a newer schema is kept
 * read-only and unchanged.
 */
public final class CapitalsSavedData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_capitals";
    static final int SCHEMA = 1;
    static final int CAPACITY = 1024;

    /**
     * {@code MAPPED}: Capitals governs the kingdom's crown. {@code QUARANTINED}: the evidence is ambiguous or changed, so
     * the kingdom's crown actions wait for an operator. {@code RELEASED}: an operator ended the mapping on purpose.
     */
    public enum Status { MAPPED, QUARANTINED, RELEASED }

    /** One mapping. {@code decidedBy} is the operator who decided it, or null when it followed the evidence automatically. */
    public record Binding(UUID capital, String kingdom, UUID settlement, String dimension, int villageId, Status status, String reason,
                          UUID decidedBy, long at) {
        public Binding {
            Objects.requireNonNull(capital); Objects.requireNonNull(kingdom); Objects.requireNonNull(status);
            dimension = dimension == null ? "" : dimension; reason = reason == null ? "" : reason;
        }
        public Binding with(Status next, String why, UUID by, long when) {
            return new Binding(capital, kingdom, settlement, dimension, villageId, next, why, by, when);
        }
    }

    private final Map<UUID, Binding> bindings = new LinkedHashMap<>();
    private long revision;
    private boolean readOnly;
    private CompoundTag preserved;

    public static CapitalsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CapitalsSavedData::load, CapitalsSavedData::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        var data = get(server);
        return StoreStatus.of(NAME, data.writable(), "");
    }

    static CapitalsSavedData load(CompoundTag tag) {
        var data = new CapitalsSavedData();
        if (tag.getInt("Schema") > SCHEMA) { data.readOnly = true; data.preserved = tag.copy(); return data; }
        data.revision = tag.getLong("Revision");
        for (Tag entry : tag.getList("Bindings", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) entry;
            try {
                var binding = new Binding(b.getUUID("Capital"), b.getString("Kingdom"), b.hasUUID("Settlement") ? b.getUUID("Settlement") : null,
                        b.getString("Dimension"), b.getInt("Village"), Status.valueOf(b.getString("Status")), b.getString("Reason"),
                        b.hasUUID("DecidedBy") ? b.getUUID("DecidedBy") : null, b.getLong("At"));
                data.bindings.put(binding.capital(), binding);
            } catch (IllegalArgumentException | NullPointerException damaged) {
                data.readOnly = true; data.preserved = tag.copy(); data.bindings.clear(); return data;
            }
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (readOnly) return preserved.copy();
        tag.putInt("Schema", SCHEMA);
        tag.putLong("Revision", revision);
        ListTag list = new ListTag();
        for (Binding b : bindings.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Capital", b.capital()); t.putString("Kingdom", b.kingdom());
            if (b.settlement() != null) t.putUUID("Settlement", b.settlement());
            t.putString("Dimension", b.dimension()); t.putInt("Village", b.villageId()); t.putString("Status", b.status().name());
            t.putString("Reason", b.reason()); if (b.decidedBy() != null) t.putUUID("DecidedBy", b.decidedBy()); t.putLong("At", b.at());
            list.add(t);
        }
        tag.put("Bindings", list);
        return tag;
    }

    public boolean writable() { return !readOnly; }
    public long revision() { return revision; }
    public Map<UUID, Binding> bindings() { return Collections.unmodifiableMap(bindings); }
    public Optional<Binding> binding(UUID capital) { return Optional.ofNullable(bindings.get(capital)); }

    /** Replaces the mappings and writes them before they take effect; false (nothing changed) when the write failed. */
    boolean commit(MinecraftServer server, Map<UUID, Binding> next) {
        if (readOnly || next.size() > CAPACITY) return false;
        var previous = new LinkedHashMap<>(bindings);
        long before = revision;
        bindings.clear(); bindings.putAll(next); revision++;
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), save(new CompoundTag()));
            setDirty(false);
            return true;
        } catch (java.io.IOException failure) {
            bindings.clear(); bindings.putAll(previous); revision = before;
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] Could not save the MCA Capitals mappings; they are unchanged", failure);
            return false;
        }
    }
}
