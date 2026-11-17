package dev.otectus.mcakingdoms.persistence;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Proof, in the player's own saved data, that an inventory change happened (AUD-02, AUD-09).
 *
 * <p>An operation that moves items into or out of a player's inventory first writes its intent to its own store, then,
 * in the same server tick, changes the inventory and adds a marker (the operation id and what actually moved) to the
 * player's {@code PlayerPersisted} data. Minecraft saves a player's inventory and persisted data in one compound, so
 * whatever copy is loaded later holds both the change and the marker, or neither. Nothing forces an early save: the
 * player's data is saved with the world as usual, so it never runs ahead of the chunks around it.
 *
 * <p>When the change is durable depends on which copy Minecraft will load:
 * <ul>
 *   <li>Every player of a dedicated server, and every guest of a shared world, loads from
 *       {@code playerdata/<uuid>.dat}. Forge fires {@link PlayerEvent.SaveToFile} only after that file was replaced
 *       atomically, so that is when an operation may be confirmed.</li>
 *   <li>The owner of a single-player world loads from {@code level.dat}, which the server writes after every level of
 *       the same save. The owner's operations are confirmed at the end of the tick in which the Overworld was saved,
 *       when {@code level.dat} has been written as well.</li>
 * </ul>
 * A player who logs in was just loaded from the copy that counts, so every operation still pending for them is settled
 * from their markers: present means the change is in their saved inventory, absent means it is not. Recovery never
 * guesses.
 */
public final class PlayerEvidence {
    /** The compound under {@code PlayerPersisted} holding one marker per unconfirmed operation, by id. */
    public static final String KEY = "mcakingdoms:pending_effects";
    /** Markers one player may carry at once; an operation that would exceed it is refused before anything moves. */
    public static final int LIMIT = 32;
    private static final List<Consumer<ServerPlayer>> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** World owners whose data was saved into memory and waits for this save's {@code level.dat}. */
    private static final Set<UUID> AWAITING_LEVEL_DATA = ConcurrentHashMap.newKeySet();
    private static volatile boolean overworldSaved;

    private PlayerEvidence() {
    }

    private static final List<Consumer<ServerPlayer>> LOADED = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called once a player's saved data durably holds their markers (see the class comment for when). */
    public static void onSaved(Consumer<ServerPlayer> listener) {
        LISTENERS.add(listener);
    }

    /** Called when a player logs in: their data was just loaded from the copy that counts, so pending operations are settled from it. */
    public static void onLoaded(Consumer<ServerPlayer> listener) {
        LOADED.add(listener);
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (var listener : LOADED) {
            try { listener.accept(player); }
            catch (RuntimeException failure) { LogUtils.getLogger().error("[MCA: Kingdoms] Settling {}'s pending inventory changes failed; it is retried at their next save", player.getGameProfile().getName(), failure); }
        }
    }

    private static CompoundTag persisted(Player player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
    }

    /** The markers in a {@code PlayerPersisted} compound, by operation id. */
    public static Map<UUID, CompoundTag> markers(CompoundTag persisted) {
        var out = new LinkedHashMap<UUID, CompoundTag>();
        CompoundTag all = persisted.getCompound(KEY);
        for (String key : all.getAllKeys()) {
            try { out.put(UUID.fromString(key), all.getCompound(key).copy()); } catch (IllegalArgumentException malformed) { /* not ours */ }
        }
        return out;
    }

    public static Map<UUID, CompoundTag> markers(Player player) {
        return markers(persisted(player));
    }

    public static boolean has(Player player) {
        return persisted(player).contains(KEY, Tag.TAG_COMPOUND) && !persisted(player).getCompound(KEY).isEmpty();
    }

    public static Optional<CompoundTag> marker(Player player, UUID operation) {
        CompoundTag all = persisted(player).getCompound(KEY);
        return all.contains(operation.toString(), Tag.TAG_COMPOUND) ? Optional.of(all.getCompound(operation.toString()).copy()) : Optional.empty();
    }

    /** Whether another marker fits; checked before an operation changes anything. */
    public static boolean room(Player player) {
        return persisted(player).getCompound(KEY).size() < LIMIT;
    }

    /** Adds a marker in memory; it reaches disk with the player's next save, together with the inventory. */
    public static void mark(Player player, UUID operation, CompoundTag detail) {
        CompoundTag root = persisted(player);
        CompoundTag all = root.getCompound(KEY);
        all.put(operation.toString(), detail.copy());
        root.put(KEY, all);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, root);
    }

    /** Removes a settled operation's marker; the player's next save drops it from disk too. */
    public static void clear(Player player, UUID operation) {
        CompoundTag root = persisted(player);
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag all = root.getCompound(KEY);
        all.remove(operation.toString());
        if (all.isEmpty()) root.remove(KEY); else root.put(KEY, all);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, root);
    }

    /**
     * The markers in an offline player's saved file, read-only. Empty when the player has no saved file (nothing of theirs
     * was ever saved, so no change reached it); absent (Optional.empty) when the file exists but cannot be read.
     */
    public static Optional<Map<UUID, CompoundTag>> offline(MinecraftServer server, UUID player) {
        File file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat").toFile();
        if (!file.isFile()) return Optional.of(Map.of());
        try {
            CompoundTag root = NbtIo.readCompressed(file);
            return Optional.of(markers(root.getCompound("ForgeData").getCompound(Player.PERSISTED_NBT_TAG)));
        } catch (java.io.IOException | RuntimeException unreadable) {
            LogUtils.getLogger().warn("[MCA: Kingdoms] {} could not be read to settle a pending operation: {}", file.getName(), unreadable.toString());
            return Optional.empty();
        }
    }

    private static void confirm(ServerPlayer player) {
        if (!has(player)) return;
        for (var listener : LISTENERS) {
            try { listener.accept(player); }
            catch (RuntimeException failure) { LogUtils.getLogger().error("[MCA: Kingdoms] Confirming a saved inventory change failed; it is retried at the next save", failure); }
        }
    }

    /** Forge's save event, fired after the player's file was atomically replaced. */
    @SubscribeEvent
    public static void playerSaved(PlayerEvent.SaveToFile event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !has(player)) return;
        MinecraftServer server = player.getServer();
        // The owner of a single-player world is loaded from level.dat, which this save writes last.
        if (server != null && server.isSingleplayerOwner(player.getGameProfile())) AWAITING_LEVEL_DATA.add(player.getUUID());
        else confirm(player);
    }

    @SubscribeEvent
    public static void levelSaved(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && !AWAITING_LEVEL_DATA.isEmpty()) overworldSaved = true;
    }

    /** The tick that saved the Overworld has also written level.dat by its end. */
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !overworldSaved) return;
        overworldSaved = false;
        for (UUID id : List.copyOf(AWAITING_LEVEL_DATA)) {
            AWAITING_LEVEL_DATA.remove(id);
            var player = event.getServer().getPlayerList().getPlayer(id);
            if (player != null) confirm(player);
        }
    }

    /** A new server run; nothing waits for a save of the previous one. */
    public static void reset() {
        AWAITING_LEVEL_DATA.clear();
        overworldSaved = false;
    }
}
