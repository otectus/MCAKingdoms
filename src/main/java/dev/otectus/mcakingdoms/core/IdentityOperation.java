package dev.otectus.mcakingdoms.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/**
 * One committed settlement identity change whose dependent stores are still being carried over (AUD-01). It is written
 * into the settlement file in the same atomic write as the change itself, so after any crash the change and the list of
 * stores still to update are either both on disk or neither is. {@code pending} names the consumers (see
 * {@link IdentityConsumers}) that have not yet confirmed a durable update; each is idempotent, so running one again
 * after a crash changes nothing twice. {@code errors} keeps each consumer's last reason for operators. {@code label}
 * is the settlement's name for explanations (for a merge, the retired one's, since its record is gone).
 */
public record IdentityOperation(UUID id, Kind kind, UUID source, UUID target, String kingdomBefore, String kingdomAfter, long createdAt,
                                String label, Set<String> pending, Map<String, String> errors) {
    /** A merge retires {@code source} into {@code target}; a reassignment moves {@code target} between kingdoms ({@code source} equals it). */
    public enum Kind { MERGE, REASSIGN }

    public IdentityOperation {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(source); Objects.requireNonNull(target);
        kingdomBefore = kingdomBefore == null ? "" : kingdomBefore;
        kingdomAfter = kingdomAfter == null ? "" : kingdomAfter;
        label = label == null ? "" : label.length() > 128 ? label.substring(0, 128) : label;
        pending = Collections.unmodifiableSet(new TreeSet<>(pending));
        errors = Map.copyOf(errors);
        if (pending.size() > 64 || errors.size() > 64) throw new IllegalArgumentException("Identity operation " + id + " names too many consumers");
    }

    IdentityOperation done(String consumer) {
        var left = new TreeSet<>(pending); left.remove(consumer);
        var why = new TreeMap<>(errors); why.remove(consumer);
        return new IdentityOperation(id, kind, source, target, kingdomBefore, kingdomAfter, createdAt, label, left, why);
    }

    IdentityOperation failed(String consumer, String reason) {
        var why = new TreeMap<>(errors);
        why.put(consumer, reason == null || reason.isBlank() ? "unknown" : reason.length() > 240 ? reason.substring(0, 240) : reason);
        return new IdentityOperation(id, kind, source, target, kingdomBefore, kingdomAfter, createdAt, label, pending, why);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id); tag.putString("Kind", kind.name()); tag.putUUID("Source", source); tag.putUUID("Target", target);
        tag.putString("KingdomBefore", kingdomBefore); tag.putString("KingdomAfter", kingdomAfter); tag.putLong("CreatedAt", createdAt);
        tag.putString("Label", label);
        ListTag list = new ListTag();
        pending.forEach(name -> list.add(StringTag.valueOf(name)));
        tag.put("Pending", list);
        CompoundTag why = new CompoundTag();
        errors.forEach(why::putString);
        tag.put("Errors", why);
        return tag;
    }

    static IdentityOperation load(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Source") || !tag.hasUUID("Target")) throw new IllegalArgumentException("Identity operation is missing an id");
        var pending = new TreeSet<String>();
        for (Tag name : tag.getList("Pending", Tag.TAG_STRING)) pending.add(name.getAsString());
        var errors = new TreeMap<String, String>();
        CompoundTag why = tag.getCompound("Errors");
        for (String key : why.getAllKeys()) errors.put(key, why.getString(key));
        return new IdentityOperation(tag.getUUID("Id"), Kind.valueOf(tag.getString("Kind")), tag.getUUID("Source"), tag.getUUID("Target"),
                tag.getString("KingdomBefore"), tag.getString("KingdomAfter"), tag.getLong("CreatedAt"), tag.getString("Label"), pending, errors);
    }
}
