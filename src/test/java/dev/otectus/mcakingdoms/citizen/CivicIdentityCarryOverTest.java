package dev.otectus.mcakingdoms.citizen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F01: a cloned player (death, End return) keeps exactly one civic identity, and nothing else moves. */
class CivicIdentityCarryOverTest {
    private static final String KEY = "mcakingdoms:civic_identity";

    private static CompoundTag identity(UUID residence, UUID origin, long changed) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OriginSettlement", origin);
        tag.putString("OriginKingdom", "mcakingdoms:serenum");
        tag.putUUID("ResidenceSettlement", residence);
        tag.putString("Source", "COMMAND");
        tag.putLong("LastResidenceChange", changed);
        return tag;
    }

    @Test
    void theIdentityAloneIsCopiedAndOtherModsDataStaysBehind() {
        UUID residence = UUID.randomUUID(), origin = UUID.randomUUID();
        CompoundTag original = new CompoundTag();
        original.put(KEY, identity(residence, origin, 4_200L));
        original.putString("othermod:secret", "kept with the old entity");
        CompoundTag persisted = new CompoundTag();
        persisted.putBoolean("mcakingdoms:welcomed", true);
        original.put(Player.PERSISTED_NBT_TAG, persisted);

        CompoundTag clone = new CompoundTag();
        clone.put(Player.PERSISTED_NBT_TAG, persisted.copy()); // what Forge itself carries over
        assertTrue(CivicIdentityStore.carryOver(original, clone));

        assertEquals(original.getCompound(KEY), clone.getCompound(KEY));
        assertEquals(residence, clone.getCompound(KEY).getUUID("ResidenceSettlement"));
        assertEquals(origin, clone.getCompound(KEY).getUUID("OriginSettlement"));
        assertEquals(4_200L, clone.getCompound(KEY).getLong("LastResidenceChange"), "the residence-change time is kept");
        assertFalse(clone.contains("othermod:secret"), "another mod's root data must not be copied");
        assertEquals(2, clone.getAllKeys().size());
    }

    @Test
    void aPreRenameIdentityMovesToTheCurrentKeyWithItsIdsRewritten() {
        CompoundTag legacy = identity(UUID.randomUUID(), UUID.randomUUID(), 9L);
        legacy.putString("OriginKingdom", "ultima_kingdoms:lunari");
        CompoundTag original = new CompoundTag();
        original.put(CivicIdentityStore.LEGACY_KEY, legacy);

        CompoundTag clone = new CompoundTag();
        assertTrue(CivicIdentityStore.carryOver(original, clone));
        assertFalse(clone.contains(CivicIdentityStore.LEGACY_KEY));
        assertEquals("mcakingdoms:lunari", clone.getCompound(KEY).getString("OriginKingdom"));
        assertFalse(original.contains(CivicIdentityStore.LEGACY_KEY), "the source is migrated in place, as on any read");
    }

    @Test
    void noIdentityMeansNoneIsFabricated() {
        CompoundTag clone = new CompoundTag();
        assertFalse(CivicIdentityStore.carryOver(new CompoundTag(), clone));
        assertTrue(clone.isEmpty());
    }

    @Test
    void copyingTwiceLeavesOneIdentity() {
        CompoundTag original = new CompoundTag();
        original.put(KEY, identity(UUID.randomUUID(), UUID.randomUUID(), 1L));
        CompoundTag clone = new CompoundTag();
        CivicIdentityStore.carryOver(original, clone);
        CivicIdentityStore.carryOver(original, clone);
        assertEquals(1, clone.getAllKeys().size());
        assertEquals(original.getCompound(KEY), clone.getCompound(KEY));
    }
}
