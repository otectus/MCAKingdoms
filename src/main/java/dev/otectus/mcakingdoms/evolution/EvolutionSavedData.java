package dev.otectus.mcakingdoms.evolution;

import com.google.gson.*;
import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Synchronous durable intent fence; unsupported data is preserved without writes. */
public final class EvolutionSavedData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_evolution";
    static final Gson JSON = new Gson();
    private EvolutionState state = new EvolutionState();
    private CompoundTag preserved;
    public static EvolutionSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(EvolutionSavedData::load, EvolutionSavedData::new, NAME);
    }
    public static EvolutionSavedData load(CompoundTag tag) {
        var data = new EvolutionSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1
                    || !tag.contains("Payload", Tag.TAG_STRING) || tag.getString("Payload").length() > 16_000_000)
                throw new IllegalArgumentException("Unsupported evolution payload");
            var json = JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            for (String field : new String[]{"revision", "clock", "enabled", "drama", "nextEvaluation", "lastEvaluation", "cursor", "eligible", "activeRegions",
                    "scenarios", "cooldowns", "consumed", "digest", "subscriptions"})
                if (!json.has(field)) throw new IllegalArgumentException("Missing evolution field");
            data.state = JSON.fromJson(json, EvolutionState.class); data.state.validate();
        } catch (RuntimeException failure) { data.state = new EvolutionState(); data.preserved = tag.copy(); }
        return data;
    }
    public boolean writable() { return preserved == null; }
    public static java.util.List<dev.otectus.mcakingdoms.persistence.Capacity> capacity(MinecraftServer server) {
        var data = get(server); var s = data.state;
        return java.util.List.of(
                new dev.otectus.mcakingdoms.persistence.Capacity(NAME, "opportunities", s.scenarios.size(), EvolutionState.CAPACITY, dev.otectus.mcakingdoms.persistence.Capacity.Relief.COMPACT_OPPORTUNITIES),
                new dev.otectus.mcakingdoms.persistence.Capacity(NAME, "cooldowns", s.cooldowns.size(), EvolutionState.CAPACITY, dev.otectus.mcakingdoms.persistence.Capacity.Relief.COMPACT_OPPORTUNITIES),
                new dev.otectus.mcakingdoms.persistence.Capacity(NAME, "digest schedule", s.digest.size(), EvolutionState.CAPACITY, dev.otectus.mcakingdoms.persistence.Capacity.Relief.COMPACT_OPPORTUNITIES),
                new dev.otectus.mcakingdoms.persistence.Capacity(NAME, "used evidence", s.consumed.size(), EvolutionState.CAPACITY * 16L, dev.otectus.mcakingdoms.persistence.Capacity.Relief.KEPT_AS_EVIDENCE));
    }
    public static dev.otectus.mcakingdoms.persistence.StoreStatus status(MinecraftServer server) { var data = get(server); return dev.otectus.mcakingdoms.persistence.StoreStatus.of(NAME, data.writable(), ""); }
    /** Carries a merged settlement's region settings, scenarios and cooldowns over to the survivor. */
    public static void settlementMerged(MinecraftServer server, java.util.UUID source, java.util.UUID target) {
        var data = get(server);
        if (!data.writable()) return;
        var next = data.snapshot();
        if (!next.redirectSettlement(source, target)) return;
        next.revision = Math.addExact(next.revision, 1);
        if (!data.commit(server, next)) throw new IllegalStateException("Evolution records could not be saved");
    }
    /** Finished opportunities are archived only this long after their deadline: three Minecraft days. */
    public static final long ARCHIVE_AFTER = 72_000L;

    /** What a compaction would move or drop now (F12), without changing anything. */
    public record Compaction(java.util.List<EvolutionState.Scenario> scenarios, java.util.List<String> lapsedCooldowns,
                             java.util.List<java.util.UUID> idleDigests) {
        public boolean empty() { return scenarios.isEmpty() && lapsedCooldowns.isEmpty() && idleDigests.isEmpty(); }
    }

    /**
     * Finished opportunities (resolved, declined or expired) past their deadline by {@link #ARCHIVE_AFTER}, cooldowns
     * that have already lapsed, and digest schedules of players who are no longer subscribed. Used evidence is never
     * included: it is what stops a receipt or a cause from counting twice.
     */
    public Compaction compactable(long now) {
        var scenarios = state.scenarios.values().stream().filter(e -> !e.pending() && now - e.deadline() >= ARCHIVE_AFTER).toList();
        var cooldowns = state.cooldowns.entrySet().stream().filter(e -> e.getValue() <= now).map(java.util.Map.Entry::getKey).toList();
        var digests = state.digest.keySet().stream().filter(id -> !state.subscriptions.contains(id)).toList();
        return new Compaction(scenarios, cooldowns, digests);
    }

    /**
     * Archives the finished opportunities to a file, then drops them with the lapsed cooldowns and idle digest schedules
     * in one durable commit. Returns the archive file (or empty when only schedules were dropped or nothing was due).
     */
    public java.util.Optional<java.nio.file.Path> compact(MinecraftServer server, long now) throws java.io.IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        if (!writable()) throw new IllegalStateException("Evolution records are read-only; nothing can be archived");
        var plan = compactable(now);
        if (plan.empty()) return java.util.Optional.empty();
        java.nio.file.Path file = null;
        if (!plan.scenarios().isEmpty()) file = dev.otectus.mcakingdoms.persistence.Archive.write(server.getWorldPath(LevelResource.ROOT), NAME, now,
                "Finished opportunities past their deadline by " + ARCHIVE_AFTER + " ticks", JSON.toJsonTree(plan.scenarios()));
        var next = snapshot();
        plan.scenarios().forEach(e -> next.scenarios.remove(e.id()));
        plan.lapsedCooldowns().forEach(next.cooldowns::remove);
        plan.idleDigests().forEach(next.digest::remove);
        next.revision = Math.addExact(next.revision, 1);
        if (!commit(server, next)) throw new java.io.IOException("Evolution records could not be saved; nothing was removed");
        return java.util.Optional.ofNullable(file);
    }

    boolean evaluationDue(long gameTime) { return writable() && state.enabled && Math.max(gameTime, state.clock) >= state.nextEvaluation; }
    EvolutionState snapshot() { return JSON.fromJson(JSON.toJson(state), EvolutionState.class); }
    long revision() { return state.revision; }
    boolean commit(MinecraftServer server, EvolutionState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        if (!writable()) return false;
        next.validate();
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            state = next; setDirty(false); return true;
        } catch (java.io.IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Evolution transaction refused: save failed", failure); return false;
        }
    }
    /**
     * Adopts a state that differs from the current one only in its evaluation schedule and sampling cursor
     * ({@link #sameApartFromSchedule}). No durable write and no revision: the store is marked dirty so the
     * next world save persists it.
     */
    void advanceSchedule(EvolutionState next) {
        if (!writable()) return;
        next.validate();
        state = next; setDirty();
    }

    /** Whether two states are equal once the schedule fields ({@code clock}, evaluation times, cursor) are ignored. */
    static boolean sameApartFromSchedule(EvolutionState first, EvolutionState second) {
        return withoutSchedule(first).equals(withoutSchedule(second));
    }

    private static String withoutSchedule(EvolutionState state) {
        var tree = JSON.toJsonTree(state).getAsJsonObject();
        for (String field : new String[]{"clock", "nextEvaluation", "lastEvaluation", "cursor"}) tree.remove(field);
        return tree.toString();
    }

    private static CompoundTag encode(EvolutionState state) {
        String payload = JSON.toJson(state); if (payload.length() > 16_000_000) throw new IllegalArgumentException("Evolution storage budget reached");
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload); return tag;
    }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(state) : preserved.copy(); }
}
