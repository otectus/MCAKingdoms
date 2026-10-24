package dev.otectus.mcakingdoms.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Test helper: a tag written in the current format, as Ultima Kingdoms wrote the same data before the
 * 0.1.1 rename. The rename changed nothing but names, so this is exactly an old save's shape.
 */
public final class LegacyFixtures {
    private LegacyFixtures() {
    }

    public static String toLegacy(String value) {
        return value.replace("mcakingdoms:", "ultima_kingdoms:").replace(".mcakingdoms.", ".ultima_kingdoms.");
    }

    public static Tag toLegacy(Tag tag) {
        if (tag instanceof StringTag string) return StringTag.valueOf(toLegacy(string.getAsString()));
        if (tag instanceof CompoundTag compound) {
            CompoundTag copy = new CompoundTag();
            for (String key : compound.getAllKeys()) copy.put(toLegacy(key), toLegacy(compound.get(key)));
            return copy;
        }
        if (tag instanceof ListTag list) {
            ListTag copy = new ListTag();
            for (Tag element : list) copy.add(toLegacy(element));
            return copy;
        }
        return tag.copy();
    }

    public static CompoundTag toLegacy(CompoundTag tag) {
        return (CompoundTag) toLegacy((Tag) tag);
    }

    /** The legacy save as the migration hands it to a store's loader. */
    public static CompoundTag migrated(CompoundTag current) {
        CompoundTag legacy = toLegacy(current);
        if (current.toString().contains("mcakingdoms") && !legacy.toString().contains("ultima_kingdoms")) {
            throw new AssertionError("fixture carries no namespaced id, so it proves nothing about the migration");
        }
        return LegacyRename.rewrite(legacy);
    }
}
