package dev.otectus.mcakingdoms.core;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SettlementSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "mcakingdoms_settlements";
    private static final int SCHEMA = 1;
    /** Bump when reconciliation learns something new, so the start-up pass runs once more on existing worlds. */
    static final int RECONCILIATION_VERSION = 1;

    private final Map<UUID, SettlementRecord> settlements = new LinkedHashMap<>();
    private final Map<UUID, UUID> redirects = new LinkedHashMap<>();
    private final ExternalRefIndex externalRefs = new ExternalRefIndex();
    private long revision;
    /** Optional root key {@code Reconciled}: the reconciliation version whose start-up pass completed; absent is 0. */
    private int reconciled;
    private String loadError;
    /** The payload exactly as loaded, kept while refused. */
    private CompoundTag preserved;

    static SettlementSavedData get(MinecraftServer server) {
        java.nio.file.Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve(DATA_NAME + ".dat");
        SettlementSavedData data = server.overworld().getDataStorage().computeIfAbsent(
                SettlementSavedData::load,
                () -> fresh(java.nio.file.Files.exists(file)),
                DATA_NAME
        );
        data.validateLoaded();
        return data;
    }

    /**
     * Minecraft creates a new store when its file is absent, and also when the file exists but cannot be decoded (a
     * truncated or corrupt file), after only logging the error. The second case would start every settlement over and
     * overwrite the file at the next save, so it is refused like any other unreadable payload.
     */
    static SettlementSavedData fresh(boolean fileExists) {
        SettlementSavedData data = new SettlementSavedData();
        if (fileExists) data.refuse(null, DATA_NAME + ".dat exists but could not be read (see the error above)", null);
        return data;
    }

    Collection<SettlementRecord> records() {
        return settlements.values();
    }

    Optional<SettlementRecord> get(UUID requestedId) {
        return Optional.ofNullable(settlements.get(resolveId(requestedId)));
    }

    Optional<SettlementRecord> getByExternalRef(String namespace, String value) {
        return externalRefs.find(namespace, value).flatMap(this::get);
    }

    UUID resolveId(UUID requestedId) {
        UUID current = requestedId;
        for (int i = 0; i < 64; i++) {
            UUID next = redirects.get(current);
            if (next == null || next.equals(current)) return current;
            current = next;
        }
        throw new IllegalStateException("Settlement redirect cycle involving " + requestedId);
    }

    long revision() {
        return revision;
    }

    int reconciled() {
        return reconciled;
    }

    /** Records that the start-up reconciliation completed. Not a record change, so the revision stays put. */
    void markReconciled() {
        writable();
        if (reconciled >= RECONCILIATION_VERSION) return;
        reconciled = RECONCILIATION_VERSION;
        setDirty();
    }

    void add(SettlementRecord record) {
        writable();
        if (settlements.containsKey(record.id)) {
            throw new IllegalArgumentException("Settlement already exists: " + record.id);
        }
        externalRefs.reindex(record);
        settlements.put(record.id, record);
        changed(record);
    }

    void changed(SettlementRecord record) {
        writable();
        externalRefs.reindex(record);
        record.revision = ++revision;
        setDirty();
    }

    void validateExternalRefs(UUID owner, Map<String, String> references) {
        externalRefs.validate(owner, references, Set.of(owner));
    }

    void validateMergeExternalRefs(SettlementRecord source, SettlementRecord target) {
        externalRefs.validateAll(target.id, source.externalRefValues, Set.of(source.id, target.id));
    }

    void redirect(UUID source, UUID target) {
        writable();
        UUID resolvedTarget = resolveId(target);
        if (source.equals(resolvedTarget)) {
            throw new IllegalArgumentException("Cannot redirect a settlement to itself");
        }
        settlements.remove(source);
        redirects.put(source, resolvedTarget);
        redirects.replaceAll((ignored, value) -> value.equals(source) ? resolvedTarget : value);
        externalRefs.redirect(source, resolvedTarget);
        revision++;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (loadError != null) return preserved == null ? tag : preserved.copy();
        tag.putInt("Schema", SCHEMA);
        tag.putLong("Revision", revision);
        tag.putInt("Reconciled", reconciled);
        ListTag settlementList = new ListTag();
        settlements.values().forEach(record -> settlementList.add(record.save()));
        tag.put("Settlements", settlementList);

        ListTag redirectList = new ListTag();
        redirects.forEach((source, target) -> {
            CompoundTag redirect = new CompoundTag();
            redirect.putUUID("Source", source);
            redirect.putUUID("Target", target);
            redirectList.add(redirect);
        });
        tag.put("Redirects", redirectList);
        return tag;
    }

    /**
     * Reads the whole payload before any of it becomes writable. Settlements are the identity every other store points
     * at, so a file this version cannot read completely is refused, never trimmed: an unsupported schema, a record that
     * does not parse, two records with one id, a malformed or conflicting redirect, a redirect cycle or one that ends at
     * no settlement, or two settlements claiming one external reference. A refused store keeps the original payload,
     * reports why, never writes, and stops the server from starting (see {@link #validateLoaded}); the file on disk is
     * left exactly as it was for repair. Legitimate retired-id chains (A to B to C, C live) load as before.
     */
    private static SettlementSavedData load(CompoundTag tag) {
        SettlementSavedData data = new SettlementSavedData();
        try {
            data.read(tag);
        } catch (RuntimeException failure) {
            data.refuse(tag, failure.getMessage(), failure);
        }
        return data;
    }

    private void read(CompoundTag tag) {
        int schema = tag.getInt("Schema");
        if (schema != SCHEMA) throw new IllegalArgumentException("Unsupported MCA: Kingdoms save schema " + schema);
        revision = tag.getLong("Revision");
        if (revision < 0) throw new IllegalArgumentException("Negative settlement revision " + revision);
        reconciled = tag.getInt("Reconciled");
        ListTag settlementList = compoundList(tag, "Settlements");
        for (int i = 0; i < settlementList.size(); i++) {
            CompoundTag settlementTag = settlementList.getCompound(i);
            int settlementSchema = settlementTag.getInt("Schema");
            if (settlementSchema != SettlementRecord.SCHEMA) {
                throw new IllegalArgumentException("Unsupported MCA: Kingdoms settlement schema " + settlementSchema + " at record " + i);
            }
            SettlementRecord record;
            try {
                record = SettlementRecord.load(settlementTag);
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("Settlement record " + i + " is malformed (" + malformed + ")", malformed);
            }
            if (record.radius < 0) throw new IllegalArgumentException("Settlement record " + i + " has a negative radius");
            if (settlements.putIfAbsent(record.id, record) != null) {
                throw new IllegalArgumentException("Two settlement records share the id " + record.id + " (record " + i + ")");
            }
        }
        ListTag redirectList = compoundList(tag, "Redirects");
        for (int i = 0; i < redirectList.size(); i++) {
            CompoundTag redirect = redirectList.getCompound(i);
            if (!redirect.hasUUID("Source") || !redirect.hasUUID("Target")) {
                throw new IllegalArgumentException("Settlement redirect " + i + " is malformed");
            }
            UUID source = redirect.getUUID("Source");
            UUID target = redirect.getUUID("Target");
            if (source.equals(target)) throw new IllegalArgumentException("Settlement redirect " + i + " points " + source + " at itself");
            if (settlements.containsKey(source)) {
                throw new IllegalArgumentException("Settlement redirect " + i + " retires " + source + ", which is still a live settlement");
            }
            UUID previous = redirects.putIfAbsent(source, target);
            if (previous != null) {
                throw new IllegalArgumentException("Settlement " + source + " is redirected to both " + previous + " and " + target);
            }
        }
        for (UUID source : redirects.keySet()) {
            Set<UUID> seen = new java.util.HashSet<>();
            UUID current = source;
            while (redirects.containsKey(current)) {
                if (!seen.add(current)) throw new IllegalArgumentException("Settlement redirect cycle involving " + source);
                current = redirects.get(current);
            }
            if (!settlements.containsKey(current)) {
                throw new IllegalArgumentException("Settlement redirect from " + source + " ends at " + current + ", which is no settlement");
            }
        }
        try {
            externalRefs.rebuild(settlements.values());
        } catch (RuntimeException conflict) {
            throw new IllegalArgumentException("Conflicting MCA: Kingdoms settlement external references: " + conflict.getMessage(), conflict);
        }
    }

    /** A root list that is absent reads as empty; one of the wrong type would silently drop records, so it is refused. */
    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!tag.contains(key)) return new ListTag();
        if (tag.get(key) instanceof ListTag list && (list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND)) return list;
        throw new IllegalArgumentException("Settlement save field " + key + " is not a list of records");
    }

    private void refuse(CompoundTag original, String reason, Throwable failure) {
        loadError = (reason == null || reason.isBlank() ? "Unreadable MCA: Kingdoms settlement data" : reason)
                + "; refusing to initialize so the original save cannot be overwritten. The file " + DATA_NAME
                + ".dat is unchanged: restore it from a backup or repair the named record, then start again";
        preserved = original == null ? null : original.copy();
        settlements.clear();
        redirects.clear();
        externalRefs.clear();
        LOGGER.error(loadError, failure);
    }

    /** A refused store never becomes dirty, so neither a mutation attempt nor an autosave can replace the original file. */
    @Override
    public boolean isDirty() {
        return loadError == null && super.isDirty();
    }

    boolean refused() {
        return loadError != null;
    }

    private void writable() {
        if (loadError != null) throw new IllegalStateException(loadError);
    }

    private void validateLoaded() {
        if (loadError != null) throw new IllegalStateException(loadError);
    }
}
