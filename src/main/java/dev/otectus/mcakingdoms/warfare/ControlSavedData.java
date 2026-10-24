package dev.otectus.mcakingdoms.warfare;

import com.google.gson.Gson;
import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Independent sidecar; existing settlement/political/provider payloads are never rewritten. */
public final class ControlSavedData extends SavedData {
    public static final String NAME = "mcakingdoms_control";
    private static final Gson JSON = new Gson();
    private ControlState state = new ControlState();
    private CompoundTag preserved;
    public static ControlSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Control requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(ControlSavedData::load, ControlSavedData::new, NAME);
    }
    public static ControlSavedData load(CompoundTag tag) {
        var data = new ControlSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1 || !tag.contains("Payload", Tag.TAG_STRING)
                    || tag.getString("Payload").length() > 16_000_000) throw new IllegalArgumentException("Unsupported control data");
            var payload = com.google.gson.JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            if (!payload.has("revision") || !payload.has("mappings") || !payload.has("bindings") || !payload.has("retired"))
                throw new IllegalArgumentException("Incomplete control payload");
            data.state = JSON.fromJson(payload, ControlState.class);
            data.state.validate();
        } catch (RuntimeException failure) {
            data.state = new ControlState(); data.preserved = tag.copy();
            com.mojang.logging.LogUtils.getLogger().warn("Political control data retained read-only", failure);
        }
        return data;
    }
    public boolean writable() { return preserved == null; }
    /** Carries a merged settlement's binding history over to the survivor. */
    public static void settlementMerged(MinecraftServer server, java.util.UUID source, java.util.UUID target) {
        var data = get(server);
        if (!data.writable()) return;
        var next = data.snapshot();
        if (!next.redirectSettlement(source, target)) return;
        next.revision = Math.addExact(next.revision, 1);
        if (!data.commit(server, next)) throw new IllegalStateException("Control records could not be saved");
    }
    ControlState snapshot() {
        var copy = new ControlState(); copy.revision = state.revision;
        copy.mappings.putAll(state.mappings); copy.bindings.putAll(state.bindings); copy.retired.putAll(state.retired); return copy;
    }
    long revision() { return state.revision; }
    java.util.Optional<ControlState.Binding> binding(java.util.UUID id) { return java.util.Optional.ofNullable(state.bindings.get(id)); }
    java.util.Optional<ControlState.Mapping> mapping(String id) { return java.util.Optional.ofNullable(state.mappings.get(id)); }
    java.util.List<ControlState.Binding> bindings() { return java.util.List.copyOf(state.bindings.values()); }
    boolean commit(MinecraftServer server, ControlState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Control requires server thread");
        if (!writable()) return false;
        next.validate();
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            state = next; setDirty(false); return true;
        } catch (java.io.IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Control observation was not saved", failure); return false;
        }
    }
    private static CompoundTag encode(ControlState value) {
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", JSON.toJson(value)); return tag;
    }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(state) : preserved.copy(); }
}
