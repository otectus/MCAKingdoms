package dev.otectus.mcakingdoms.compat.waystones;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Every Waystones and Balm member this integration uses, resolved by exact name and signature (WAY-01). Nothing here is
 * on the compile classpath, so the mod loads, and the rest of MCA: Kingdoms works, without Waystones; a missing or
 * changed member makes the binding fail with its name, and the integration then reports itself unsupported.
 *
 * <p>Contracts verified against Waystones 14.1.21 and Balm 7.3.44 (WORKPLAN): {@code GenerateWaystoneNameEvent} fires
 * after Waystones resolved its own duplicates and before the name is stored; {@code WaystoneActivatedEvent} fires during
 * placement before the settings screen opens; a later rename is the native edit's own steps: set the backing record's
 * name, mark {@code WaystoneManager} dirty, send {@code sendWaystoneUpdateToAll}.
 */
final class WaystonesAccess {
    static final String API = "net.blay09.mods.waystones.api.";
    static final String CORE = "net.blay09.mods.waystones.core.";
    static final ResourceLocation NORMAL = new ResourceLocation("waystones", "waystone");

    final Class<?> waystone, mutable, nameEvent, activatedEvent;
    private final Method uid, name, dimension, pos, type, hasName, setName, eventWaystone, eventName, eventSetName, activatedPlayer, activatedWaystone,
            byId, all, managerGet, syncAll, mayEdit, onEvent;
    private final Object events, lowPriority;

    private WaystonesAccess() throws ReflectiveOperationException {
        waystone = Class.forName(API + "IWaystone");
        mutable = Class.forName(API + "IMutableWaystone");
        nameEvent = Class.forName(API + "GenerateWaystoneNameEvent");
        activatedEvent = Class.forName(API + "WaystoneActivatedEvent");
        uid = waystone.getMethod("getWaystoneUid");
        name = waystone.getMethod("getName");
        dimension = waystone.getMethod("getDimension");
        pos = waystone.getMethod("getPos");
        type = waystone.getMethod("getWaystoneType");
        hasName = waystone.getMethod("hasName");
        setName = mutable.getMethod("setName", String.class);
        eventWaystone = nameEvent.getMethod("getWaystone");
        eventName = nameEvent.getMethod("getName");
        eventSetName = nameEvent.getMethod("setName", String.class);
        activatedPlayer = activatedEvent.getMethod("getPlayer");
        activatedWaystone = activatedEvent.getMethod("getWaystone");
        Class<?> api = Class.forName(API + "WaystonesAPI");
        byId = api.getMethod("getWaystone", MinecraftServer.class, UUID.class);
        all = api.getMethod("getAllWaystones", MinecraftServer.class);
        managerGet = Class.forName(CORE + "WaystoneManager").getMethod("get", MinecraftServer.class);
        syncAll = Class.forName(CORE + "WaystoneSyncManager").getMethod("sendWaystoneUpdateToAll", MinecraftServer.class, waystone);
        mayEdit = Class.forName(CORE + "PlayerWaystoneManager").getMethod("mayEditWaystone", Player.class, Level.class, waystone);
        Class<?> balm = Class.forName("net.blay09.mods.balm.api.Balm");
        Class<?> balmEvents = Class.forName("net.blay09.mods.balm.api.event.BalmEvents");
        Class<?> priority = Class.forName("net.blay09.mods.balm.api.event.EventPriority");
        onEvent = balmEvents.getMethod("onEvent", Class.class, Consumer.class, priority);
        events = balm.getMethod("getEvents").invoke(null);
        lowPriority = priority.getField("Low").get(null);
        if (events == null) throw new IllegalStateException("Balm events are not available yet");
        if (!SavedData.class.isAssignableFrom(managerGet.getReturnType())) throw new NoSuchMethodException("WaystoneManager is not saved data");
    }

    /** The binding, or the reason it could not be made. */
    record Bound(Optional<WaystonesAccess> access, String reason) { }

    static Bound bind() {
        try {
            return new Bound(Optional.of(new WaystonesAccess()), "");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Bound(Optional.empty(), failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Waystones failed in " + method.getName(), failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Waystones member is not accessible: " + method.getName(), failure);
        }
    }

    // ---- events ----

    /** Registers a handler with Balm's own event API at low priority, so other mods' naming runs first. */
    void listen(Class<?> event, Consumer<Object> handler) {
        call(onEvent, events, event, handler, lowPriority);
    }

    Object nameEventWaystone(Object event) { return call(eventWaystone, event); }
    String nameEventName(Object event) { return (String) call(eventName, event); }
    void nameEventSetName(Object event, String value) { call(eventSetName, event, value); }
    Player activatedPlayer(Object event) { return (Player) call(activatedPlayer, event); }
    Object activatedWaystone(Object event) { return call(activatedWaystone, event); }

    // ---- stones ----

    UUID id(Object stone) { return (UUID) call(uid, stone); }
    String name(Object stone) { Object value = call(name, stone); return value == null ? "" : (String) value; }
    boolean hasName(Object stone) { return (Boolean) call(hasName, stone); }
    @SuppressWarnings("unchecked") ResourceKey<Level> dimension(Object stone) { return (ResourceKey<Level>) call(dimension, stone); }
    BlockPos pos(Object stone) { return (BlockPos) call(pos, stone); }
    ResourceLocation type(Object stone) { return (ResourceLocation) call(type, stone); }
    boolean normal(Object stone) { return NORMAL.equals(type(stone)); }

    Optional<Object> stone(MinecraftServer server, UUID id) {
        return ((Optional<?>) call(byId, null, server, id)).map(found -> (Object) found);
    }

    @SuppressWarnings("unchecked")
    Stream<Object> all(MinecraftServer server) {
        return ((Stream<Object>) call(all, null, server));
    }

    /** Whether Waystones' own rules let {@code player} edit {@code stone} (owner, creative and global-stone rules). */
    boolean mayEdit(Player player, Level level, Object stone) {
        Object verdict = call(mayEdit, null, player, level, stone);
        return verdict instanceof Enum<?> e && e.name().equals("ALLOW");
    }

    /**
     * Names a stone the way Waystones' own edit does: the backing record's name, its manager marked dirty so the next
     * save writes it, and the native update to every client that knows the stone. Activation state, owner, the global flag,
     * costs and cooldowns are untouched.
     */
    void rename(MinecraftServer server, Object stone, String value) {
        Object backing = backing(stone);
        if (!mutable.isInstance(backing)) throw new IllegalStateException("That waystone's record cannot be renamed");
        call(setName, backing, value);
        ((SavedData) call(managerGet, null, server)).setDirty();
        call(syncAll, null, server, backing);
    }

    /** The name only, during activation: Waystones itself marks the record dirty and sends it to the player right after. */
    void nameDuringActivation(MinecraftServer server, Object stone, String value) {
        Object backing = backing(stone);
        if (!mutable.isInstance(backing)) throw new IllegalStateException("That waystone's record cannot be renamed");
        call(setName, backing, value);
        ((SavedData) call(managerGet, null, server)).setDirty();
    }

    private static Object backing(Object stone) {
        try {
            Method getter = stone.getClass().getMethod("getBackingWaystone");
            Object backing = getter.invoke(stone);
            return backing == null ? stone : backing;
        } catch (NoSuchMethodException notAProxy) {
            return stone;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Waystones failed in getBackingWaystone", failure);
        }
    }

    /** The level a stone is in, or empty when that dimension is not loaded. */
    static Optional<ServerLevel> level(MinecraftServer server, ResourceKey<Level> dimension) {
        return Optional.ofNullable(server.getLevel(dimension));
    }
}
