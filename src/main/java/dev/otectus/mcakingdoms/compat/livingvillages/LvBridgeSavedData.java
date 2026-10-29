package dev.otectus.mcakingdoms.compat.livingvillages;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.DurableSavedData;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The Living Villages bridge store ({@code mcakingdoms_living_villages.dat}). Consequential changes (an import, a
 * conflict phase, a journal step, a treaty) are committed with an atomic write before the provider is asked to act, so
 * an intent is durable before its effect. Its own sidecar: no Kingdoms or provider file is rewritten. A file this
 * version cannot read is kept byte-for-byte and read-only; a file from an older schema is archived before it is upgraded.
 */
public final class LvBridgeSavedData extends DurableSavedData {
    public static final String NAME = "mcakingdoms_living_villages";
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private LvState state = fresh();
    private CompoundTag preserved;
    private String diagnostic = "";

    public static LvBridgeSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Living Villages bridge requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(LvBridgeSavedData::load, LvBridgeSavedData::new, NAME);
    }

    private static LvState fresh() {
        var state = new LvState();
        state.provenance.world = UUID.randomUUID();
        return state;
    }

    public static LvBridgeSavedData load(CompoundTag tag) {
        var data = new LvBridgeSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || !tag.contains("Payload", Tag.TAG_STRING))
                throw new IllegalArgumentException("Not a Living Villages bridge store");
            int schema = tag.getInt("Schema");
            if (schema > LvState.SCHEMA) throw new IllegalArgumentException("Saved by a newer MCA: Kingdoms (schema " + schema + ")");
            if (schema < 1) throw new IllegalArgumentException("Unknown schema " + schema);
            if (tag.getString("Payload").length() > 24_000_000) throw new IllegalArgumentException("Bridge store too large");
            var payload = JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            data.state = JSON.fromJson(payload, LvState.class);
            if (data.state.provenance == null) data.state.provenance = new LvState.Provenance();
            if (data.state.provenance.world == null) data.state.provenance.world = UUID.randomUUID();
            data.state.validate();
        } catch (RuntimeException failure) {
            data.state = fresh();
            data.preserved = tag.copy();
            data.diagnostic = "mcakingdoms_living_villages.dat could not be read (" + failure.getMessage()
                    + "); it is kept unchanged and read-only, and the integration makes no new bindings or provider changes.";
            com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] {}", data.diagnostic, failure);
        }
        return data;
    }

    public boolean writable() {
        return preserved == null;
    }

    public String diagnostic() {
        return diagnostic;
    }

    /** A copy to change; nothing is visible until {@link #commit} succeeds. */
    public LvState snapshot() {
        return state.copy();
    }

    /** The current committed state, for reads only. */
    LvState view() {
        return state;
    }

    public long revision() {
        return state.revision;
    }

    /** Validates and writes {@code next} atomically, then makes it current. Returns false (and changes nothing) when it cannot. */
    public boolean commit(MinecraftServer server, LvState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Living Villages bridge requires the server thread");
        if (!writable()) return false;
        next.compact();
        next.revision = Math.addExact(next.revision, 1);
        try {
            next.validate();
            AtomicSavedDataWriter.write(file(server).toFile(), encode(next));
            state = next;
            setDirty(false);
            return true;
        } catch (IOException | RuntimeException failure) {
            next.revision = state.revision;
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] Living Villages bridge change was not saved; nothing changed", failure);
            return false;
        }
    }

    /** Low-value updates (last-seen times) that may wait for the next world save. */
    public void markChanged(LvState next) {
        if (!writable()) return;
        next.validate();
        state = next;
        setDirty();
    }

    static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat");
    }

    /** Copies the current file into {@code mcakingdoms_archive/} before a schema upgrade or an operator repair. */
    public static Path backup(MinecraftServer server, String reason) throws IOException {
        Path source = file(server);
        Path folder = server.getWorldPath(LevelResource.ROOT).resolve("mcakingdoms_archive");
        Files.createDirectories(folder);
        Path target = folder.resolve(NAME + "-" + reason.replaceAll("[^a-z0-9_-]", "_") + "-" + System.currentTimeMillis() + ".dat");
        if (Files.isRegularFile(source)) Files.copy(source, target);
        return target;
    }

    private static CompoundTag encode(LvState value) {
        var tag = new CompoundTag();
        tag.putInt("Schema", LvState.SCHEMA);
        tag.putString("Payload", JSON.toJson(value));
        return tag;
    }

    @Override
    public boolean isDirty() {
        return writable() && super.isDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        return writable() ? encode(state) : preserved.copy();
    }

    public static StoreStatus status(MinecraftServer server) {
        var data = get(server);
        return StoreStatus.of(NAME, data.writable(), data.diagnostic());
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var s = get(server).view();
        return List.of(
                new Capacity(NAME, "village bindings", s.bindings.size(), LvState.BINDINGS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "realms", s.realms.size(), LvState.REALMS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "conflicts (finished ones compact)", s.conflicts.size(), LvState.CONFLICTS, Capacity.Relief.RETENTION),
                new Capacity(NAME, "operation journal (published ones compact)", s.journal.size(), LvState.JOURNAL, Capacity.Relief.RETENTION),
                new Capacity(NAME, "treaties", s.treaties.size(), LvState.TREATIES, Capacity.Relief.KEPT_AS_EVIDENCE),
                new Capacity(NAME, "residents", s.residents.size(), LvState.RESIDENTS, Capacity.Relief.RETENTION),
                new Capacity(NAME, "history (oldest roll off)", s.history.size(), LvState.HISTORY, Capacity.Relief.RETENTION));
    }
}
