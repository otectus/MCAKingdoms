package dev.otectus.mcakingdoms.document;

import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import dev.otectus.mcakingdoms.persistence.Capacity;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * The ledger of sealed documents and chosen heraldic colours (B2), in {@code mcakingdoms_documents.dat}. A charter item
 * only names a record here; this ledger is what gives it meaning. Each record is committed to disk before its charter is
 * sealed, and a request id can seal only once. A newer schema is kept read-only and unchanged.
 */
public final class DocumentLedger extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_documents";
    static final int SCHEMA = 1, DOCUMENTS = 8192, RECEIPTS = 4096, KINGDOMS = 256;

    /** What a sealed document proves. {@code FOUNDING} and {@code TITLE_GRANT} are crown documents (C2). */
    public enum Kind {
        PROCLAMATION, OFFICE_APPOINTMENT, TREATY, FOUNDING, TITLE_GRANT;
        public boolean crown() { return this == FOUNDING || this == TITLE_GRANT; }
    }
    public enum State { ISSUED, REVOKED }

    /**
     * One sealed document: what it proves ({@code record} names the political record, office key or settlement), who
     * sealed it and when, and the hash a charter must carry to be this document.
     */
    public record Document(UUID id, Kind kind, String kingdom, String record, UUID subject, String subjectName, String title,
                           UUID issuer, long issuedAt, State state, UUID revokedBy, long revokedAt, String reason, String hash) {
        public Document {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(kingdom); Objects.requireNonNull(state);
            record = bounded(record, 256); subjectName = bounded(subjectName, 64); title = bounded(title, 256); reason = bounded(reason, 256);
            Objects.requireNonNull(hash);
        }
        Document revoked(UUID by, long at, String why) {
            return new Document(id, kind, kingdom, record, subject, subjectName, title, issuer, issuedAt, State.REVOKED, by, at, why, hash);
        }
    }
    /** A ruler's choice of two dye colours for the kingdom's heraldry. */
    public record Colours(DyeColor primary, DyeColor secondary, UUID chosenBy, long revision) { }
    record Receipt(UUID actor, String fingerprint, UUID document) { }

    /**
     * Whether a charter carries a sealed record (AUD-09), kept apart from the record itself: issuing a document and
     * delivering it onto a charter are separate steps. {@code PENDING}: written onto the charter in the player's hand and
     * confirmed when the player's data is saved; {@code ATTACHED}: a saved charter carries it; {@code MISSING}: the
     * server stopped before the charter was saved, so no charter carries it and the record waits to be recovered.
     * {@code fallback} is the state a copy's delivery returns to if it is lost. Documents sealed before 0.3.1 have no
     * delivery entry and read as attached.
     */
    public enum Attachment { PENDING, ATTACHED, MISSING }
    public record Delivery(UUID document, UUID player, UUID operation, Attachment state, Attachment fallback, long at) {
        public Delivery { Objects.requireNonNull(document); Objects.requireNonNull(player); Objects.requireNonNull(operation); Objects.requireNonNull(state);
            fallback = fallback == null ? Attachment.MISSING : fallback; }
        Delivery at(Attachment next, long when) { return new Delivery(document, player, operation, next, fallback, when); }
    }

    private final Map<UUID, Document> documents = new LinkedHashMap<>();
    private final Map<String, Colours> colours = new LinkedHashMap<>();
    private final Map<UUID, Receipt> receipts = new LinkedHashMap<>();
    /** Optional root key {@code Deliveries} (0.3.1): a version that does not know it ignores it. */
    private final Map<UUID, Delivery> deliveries = new LinkedHashMap<>();
    private long revision;
    private boolean readOnly;
    private CompoundTag preserved;

    public static DocumentLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DocumentLedger::load, DocumentLedger::new, NAME);
    }

    public static StoreStatus status(MinecraftServer server) {
        return StoreStatus.of(NAME, get(server).writable(), "");
    }

    public static List<Capacity> capacity(MinecraftServer server) {
        var data = get(server);
        return List.of(new Capacity(NAME, "documents", data.documents.size(), DOCUMENTS, Capacity.Relief.OFFLINE_PROCEDURE),
                new Capacity(NAME, "receipts", data.receipts.size(), RECEIPTS, Capacity.Relief.RETENTION));
    }

    public static String hash(UUID id, Kind kind, String kingdom, String record, UUID subject, String title, long issuedAt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((id + "|" + kind + "|" + kingdom + "|" + record + "|" + subject + "|" + title + "|" + issuedAt)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String bounded(String value, int limit) {
        if (value == null) return "";
        if (value.length() > limit || value.chars().anyMatch(c -> c < 32 && c != '\n')) throw new IllegalArgumentException("Invalid document text");
        return value;
    }

    public static DocumentLedger load(CompoundTag tag) {
        var data = new DocumentLedger();
        if (tag.getInt("Schema") > SCHEMA) { data.readOnly = true; data.preserved = tag.copy(); return data; }
        try {
            data.revision = tag.getLong("Revision");
            for (Tag entry : tag.getList("Documents", Tag.TAG_COMPOUND)) {
                CompoundTag d = (CompoundTag) entry;
                var doc = new Document(d.getUUID("Id"), Kind.valueOf(d.getString("Kind")), d.getString("Kingdom"), d.getString("Record"),
                        d.hasUUID("Subject") ? d.getUUID("Subject") : null, d.getString("SubjectName"), d.getString("Title"), d.getUUID("Issuer"),
                        d.getLong("IssuedAt"), State.valueOf(d.getString("State")), d.hasUUID("RevokedBy") ? d.getUUID("RevokedBy") : null,
                        d.getLong("RevokedAt"), d.getString("Reason"), d.getString("Hash"));
                data.documents.put(doc.id(), doc);
            }
            for (Tag entry : tag.getList("Colours", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) entry;
                data.colours.put(c.getString("Kingdom"), new Colours(DyeColor.byName(c.getString("Primary"), DyeColor.WHITE),
                        DyeColor.byName(c.getString("Secondary"), DyeColor.BLACK), c.hasUUID("By") ? c.getUUID("By") : null, c.getLong("Revision")));
            }
            for (Tag entry : tag.getList("Receipts", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) entry;
                data.receipts.put(r.getUUID("Request"), new Receipt(r.getUUID("Actor"), r.getString("Fingerprint"), r.getUUID("Document")));
            }
            for (Tag entry : tag.getList("Deliveries", Tag.TAG_COMPOUND)) {
                CompoundTag d = (CompoundTag) entry;
                var delivery = new Delivery(d.getUUID("Document"), d.getUUID("Player"), d.getUUID("Operation"), Attachment.valueOf(d.getString("State")),
                        d.contains("Fallback") ? Attachment.valueOf(d.getString("Fallback")) : Attachment.MISSING, d.getLong("At"));
                if (data.documents.containsKey(delivery.document())) data.deliveries.put(delivery.document(), delivery);
            }
        } catch (IllegalArgumentException | NullPointerException damaged) {
            data.readOnly = true; data.preserved = tag.copy(); data.documents.clear(); data.colours.clear(); data.receipts.clear(); data.deliveries.clear();
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (readOnly) return preserved.copy();
        tag.putInt("Schema", SCHEMA); tag.putLong("Revision", revision);
        ListTag docs = new ListTag();
        for (var d : documents.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", d.id()); t.putString("Kind", d.kind().name()); t.putString("Kingdom", d.kingdom()); t.putString("Record", d.record());
            if (d.subject() != null) t.putUUID("Subject", d.subject());
            t.putString("SubjectName", d.subjectName()); t.putString("Title", d.title()); t.putUUID("Issuer", d.issuer()); t.putLong("IssuedAt", d.issuedAt());
            t.putString("State", d.state().name()); if (d.revokedBy() != null) t.putUUID("RevokedBy", d.revokedBy());
            t.putLong("RevokedAt", d.revokedAt()); t.putString("Reason", d.reason()); t.putString("Hash", d.hash());
            docs.add(t);
        }
        tag.put("Documents", docs);
        ListTag choices = new ListTag();
        colours.forEach((kingdom, c) -> { CompoundTag t = new CompoundTag(); t.putString("Kingdom", kingdom); t.putString("Primary", c.primary().getName());
            t.putString("Secondary", c.secondary().getName()); if (c.chosenBy() != null) t.putUUID("By", c.chosenBy()); t.putLong("Revision", c.revision()); choices.add(t); });
        tag.put("Colours", choices);
        ListTag list = new ListTag();
        receipts.forEach((request, r) -> { CompoundTag t = new CompoundTag(); t.putUUID("Request", request); t.putUUID("Actor", r.actor());
            t.putString("Fingerprint", r.fingerprint()); t.putUUID("Document", r.document()); list.add(t); });
        tag.put("Receipts", list);
        ListTag sent = new ListTag();
        deliveries.values().forEach(d -> { CompoundTag t = new CompoundTag(); t.putUUID("Document", d.document()); t.putUUID("Player", d.player());
            t.putUUID("Operation", d.operation()); t.putString("State", d.state().name()); t.putString("Fallback", d.fallback().name()); t.putLong("At", d.at()); sent.add(t); });
        tag.put("Deliveries", sent);
        return tag;
    }

    public boolean writable() { return !readOnly; }
    public long revision() { return revision; }
    public Optional<Document> document(UUID id) { return Optional.ofNullable(documents.get(id)); }
    public Collection<Document> documents() { return Collections.unmodifiableCollection(documents.values()); }
    public Optional<Colours> colours(String kingdom) { return Optional.ofNullable(colours.get(kingdom)); }
    Optional<Receipt> receipt(UUID request) { return Optional.ofNullable(receipts.get(request)); }
    /** How the document reached a charter; a document without an entry was sealed before 0.3.1 and reads as attached. */
    public Optional<Delivery> delivery(UUID document) { return Optional.ofNullable(deliveries.get(document)); }
    public Attachment attachment(UUID document) { return delivery(document).map(Delivery::state).orElse(Attachment.ATTACHED); }
    public Collection<Delivery> deliveries() { return Collections.unmodifiableCollection(deliveries.values()); }

    /** One change, written before it takes effect; false (and nothing changed) when the store is full, read-only or the write failed. */
    boolean commit(MinecraftServer server, java.util.function.Consumer<DocumentLedger> change) {
        if (readOnly) return false;
        var documentsBefore = new LinkedHashMap<>(documents); var coloursBefore = new LinkedHashMap<>(colours); var receiptsBefore = new LinkedHashMap<>(receipts);
        var deliveriesBefore = new LinkedHashMap<>(deliveries);
        long before = revision;
        change.accept(this);
        revision++;
        boolean bounded = documents.size() <= DOCUMENTS && colours.size() <= KINGDOMS;
        while (receipts.size() > RECEIPTS) receipts.remove(receipts.keySet().iterator().next());
        try {
            if (!bounded) throw new java.io.IOException("document ledger bound reached");
            dev.otectus.mcakingdoms.persistence.FaultPoints.reach("documents.commit");
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), save(new CompoundTag()));
            setDirty(false);
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            documents.clear(); documents.putAll(documentsBefore); colours.clear(); colours.putAll(coloursBefore); receipts.clear(); receipts.putAll(receiptsBefore);
            deliveries.clear(); deliveries.putAll(deliveriesBefore);
            revision = before;
            com.mojang.logging.LogUtils.getLogger().error("[MCA: Kingdoms] The document ledger was not changed: {}", failure.getMessage());
            return false;
        }
    }

    void put(Document document) { documents.put(document.id(), document); }
    void putColours(String kingdom, Colours value) { colours.put(kingdom, value); }
    void putReceipt(UUID request, Receipt receipt) { receipts.put(request, receipt); }
    void putDelivery(Delivery delivery) { deliveries.put(delivery.document(), delivery); }
}
