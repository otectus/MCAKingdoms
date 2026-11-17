package dev.otectus.mcakingdoms.document;

import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.document.DocumentLedger.Document;
import dev.otectus.mcakingdoms.document.DocumentLedger.Kind;
import dev.otectus.mcakingdoms.document.DocumentLedger.State;
import dev.otectus.mcakingdoms.heraldry.HeraldryConfig;
import dev.otectus.mcakingdoms.heraldry.SealPressBlock;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Names;
import dev.otectus.mcakingdoms.item.ModItems;
import dev.otectus.mcakingdoms.persistence.FaultPoints;
import dev.otectus.mcakingdoms.persistence.PlayerEvidence;
import dev.otectus.mcakingdoms.politics.GovernmentActionPolicy;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Sealed documents and heraldic colours (B2). A document is proof that a specific record existed and that a specific
 * official sealed it: a proclamation, an office appointment, a treaty in force, or a founding. Sealing needs a blank
 * charter in hand, a seal press within {@value #PRESS_RANGE} blocks and the authority the record needs, checked now by
 * the political service; the record is written to the ledger first, and only then does the blank charter become the
 * sealed one. A charter never authorizes anything: copying one copies a reference, and every inspection asks the ledger.
 * Crown documents (founding, titles) of a kingdom MCA Capitals governs are Capitals' to seal.
 */
public final class DocumentService {
    public static final int PRESS_RANGE = 4;
    private static final String P = "document.mcakingdoms.";
    /** The owner name in a {@link PlayerEvidence} marker for a charter delivery. */
    static final String OWNER = "documents";

    /** What an inspection found, from the ledger and the record it names. */
    public enum Verdict { OFFICIAL, OFFICIAL_HISTORICAL, REVOKED, UNVERIFIED, BLANK }
    public record Inspection(Verdict verdict, Optional<Document> document, List<Component> lines) { }

    private DocumentService() {
    }

    private static Component t(String key, String fallback, Object... args) {
        return ActionRegistry.tr(P + key, fallback, args);
    }

    private static GovernmentService politics(ServerPlayer player) {
        if (KingdomsPoliticsApi.get(player.getServer()) instanceof GovernmentService government) return government;
        throw Keyed.refuse(P + "politics_unavailable", "Political records are unavailable on this server.");
    }

    public static boolean blank(ItemStack stack) {
        return stack.is(ModItems.KINGDOM_CHARTER.get()) && !stack.hasTag() || stack.is(ModItems.KINGDOM_CHARTER.get()) && !stack.getTag().contains("Charter");
    }

    /** Whether {@code stack} is a charter carrying exactly {@code document} (its id, hash, kingdom and kind). */
    public static boolean carries(ItemStack stack, Document document) {
        if (!stack.is(ModItems.KINGDOM_CHARTER.get()) || !stack.hasTag() || !stack.getTag().contains("Charter")) return false;
        CompoundTag tag = stack.getTag().getCompound("Charter");
        return tag.hasUUID("Document") && tag.getUUID("Document").equals(document.id()) && document.hash().equals(tag.getString("Hash"))
                && document.kingdom().equals(tag.getString("Kingdom")) && document.kind().name().equals(tag.getString("Kind"));
    }

    /** What a seal or recovery did: its words, whether the charter still waits for the player's save, and the document. */
    public record Reply(String text, boolean pending, UUID document) { }

    /**
     * Seals the blank charter in the actor's main hand as {@code kind} for {@code record} in {@code kingdom} (AUD-09). The
     * ledger records the document and a pending delivery first; in the same tick the charter receives the record and the
     * player's saved data a marker ({@link PlayerEvidence}); the player's next save confirms the delivery. A repeated
     * request never seals twice and says only what is true of the charter in hand.
     */
    public static Reply seal(ServerPlayer actor, UUID request, Kind kind, String kingdom, String record, String text) {
        var server = actor.getServer();
        Keyed.require(server.isSameThread() && !actor.hasDisconnected(), P + "connected", "You must be connected to this server.");
        var ledger = DocumentLedger.get(server);
        String fingerprint = GovernmentService.hash(kind + "|" + kingdom + "|" + record + "|" + text);
        var replay = ledger.receipt(request);
        if (replay.isPresent()) {
            Keyed.require(replay.get().actor().equals(actor.getUUID()) && replay.get().fingerprint().equals(fingerprint), P + "request_used", "That request was already used for a different charter.");
            return status(actor, replay.get().document());
        }
        Keyed.require(HeraldryConfig.enabled(), P + "disabled", "Physical heraldry is turned off on this server, so no charter can be sealed.");
        Keyed.require(ledger.writable(), P + "read_only", "The document ledger is kept read-only on this server.");
        Keyed.require(PlayerEvidence.room(actor), P + "too_many_pending", "Too many of your charters wait to be saved; try again after the server's next save.");
        ItemStack held = actor.getItemInHand(InteractionHand.MAIN_HAND);
        Keyed.require(blank(held), P + "need_blank", "Hold a blank charter in your main hand.");
        Keyed.require(SealPressBlock.near(actor.level(), actor.blockPosition(), PRESS_RANGE), P + "need_press", "Stand within 4 blocks of a seal press.");
        var politics = politics(actor);
        var government = politics.government(kingdom).orElseThrow(() -> Keyed.refuse(P + "no_government", "That kingdom has no government to seal for."));
        if (kind.crown() && !politics.sovereignty(kingdom).native_())
            throw Keyed.refuse(P + "crown_elsewhere", "MCA Capitals governs this kingdom's crown, so its crown documents are sealed in Capitals.");
        UUID subject = null; String subjectName = ""; String title;
        switch (kind) {
            case PROCLAMATION -> {
                Keyed.require(politics.authorized(actor, kingdom, Politics.Permission.HONOR), P + "need_honor", "Proclamations need the Award honors responsibility in this kingdom.");
                String words = text == null ? "" : text.strip();
                Keyed.require(!words.isEmpty() && words.length() <= 256 && words.chars().noneMatch(Character::isISOControl), P + "proclamation_text", "Write the proclamation in 1 to 256 plain characters.");
                title = words; record = "";
            }
            case OFFICE_APPOINTMENT -> {
                Keyed.require(politics.authorized(actor, kingdom, Politics.Permission.APPOINT), P + "need_appoint", "Appointment documents need the Appoint officials responsibility in this kingdom.");
                var office = government.offices().get(record);
                if (office == null) throw Keyed.refuse(P + "no_office", "That office is not held now.");
                if (GovernmentActionPolicy.LEADER.equals(record) && !politics.sovereignty(kingdom).native_())
                    throw Keyed.refuse(P + "crown_elsewhere", "MCA Capitals governs this kingdom's crown, so its crown documents are sealed in Capitals.");
                subject = office.holder().id(); subjectName = Names.person(server, subject);
                title = office.terms().title();
            }
            case TREATY -> {
                Keyed.require(politics.authorized(actor, kingdom, Politics.Permission.RATIFY), P + "need_ratify", "Treaty documents need the Ratify agreements responsibility in this kingdom.");
                UUID id;
                try { id = UUID.fromString(record); } catch (IllegalArgumentException invalid) { throw Keyed.refuse(P + "no_treaty", "That agreement is not in force for this kingdom."); }
                var agreement = politics.activeAgreements(kingdom).stream().filter(a -> a.id().equals(id)).findFirst()
                        .orElseThrow(() -> Keyed.refuse(P + "no_treaty", "That agreement is not in force for this kingdom."));
                title = agreement.terms().title();
            }
            case FOUNDING -> {
                var leader = government.offices().get(GovernmentActionPolicy.LEADER);
                Keyed.require(leader != null && leader.holder().equals(new Politics.Person(actor.getUUID(), Politics.Kind.PLAYER)), P + "need_leader", "Only the kingdom's leader seals its founding charter.");
                record = government.capital().toString();
                title = government.constitution().title();
            }
            case TITLE_GRANT -> {
                var charter = dev.otectus.mcakingdoms.politics.dynasty.DynastyService.titleForCharter(actor, kingdom, record);
                subject = charter.subject(); subjectName = charter.subjectName();
                title = charter.titleKey();
            }
            default -> throw Keyed.refuse(P + "kind_unavailable", "That kind of document cannot be sealed here.");
        }
        long now = server.overworld().getGameTime();
        UUID id = UUID.randomUUID();
        var document = new Document(id, kind, kingdom, record, subject, subjectName, title, actor.getUUID(), now, State.ISSUED, null, 0, "",
                DocumentLedger.hash(id, kind, kingdom, record, subject, title, now));
        UUID by = actor.getUUID();
        UUID operation = UUID.nameUUIDFromBytes(("seal|" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Keyed.require(ledger.commit(server, l -> { l.put(document); l.putReceipt(request, new DocumentLedger.Receipt(by, fingerprint, id));
                    l.putDelivery(new DocumentLedger.Delivery(id, by, operation, DocumentLedger.Attachment.PENDING, DocumentLedger.Attachment.MISSING, now)); }),
                P + "seal_failed", "The document could not be saved, so the charter is still blank.");
        FaultPoints.crash("documents.seal.recorded");
        // The record is durable; only now does the blank charter in hand become this document, with its marker, in one tick.
        attach(actor, held, document, operation);
        return status(actor, id);
    }

    private static void attach(ServerPlayer actor, ItemStack held, Document document, UUID operation) {
        held.getOrCreateTag().put("Charter", tag(document, actor.getServer()));
        var marker = new CompoundTag();
        marker.putString("Owner", OWNER);
        marker.putUUID("Document", document.id());
        PlayerEvidence.mark(actor, operation, marker);
        actor.inventoryMenu.broadcastChanges();
    }

    /** The truth about a sealed document's charter for {@code viewer}: never "in your hand" unless it is (AUD-09). */
    public static Reply status(ServerPlayer viewer, UUID documentId) {
        var server = viewer.getServer();
        var ledger = DocumentLedger.get(server);
        var document = ledger.document(documentId).orElse(null);
        if (document == null) return new Reply(Keyed.text(P + "status_unknown", "That document is not in the ledger."), false, documentId);
        Component name = title(server, document);
        boolean inHand = carries(viewer.getMainHandItem(), document);
        if (document.state() == State.REVOKED)
            return new Reply(Keyed.text(P + "status_revoked", "%s was sealed and later revoked; every charter carrying it reads as revoked.", name), false, documentId);
        return switch (ledger.attachment(documentId)) {
            case PENDING -> new Reply(inHand ? Keyed.text(P + "sealed", "Sealed: %s. The charter in your hand now carries this record; it is kept with your inventory at the server's next save.", name)
                    : Keyed.text(P + "status_pending_elsewhere", "%s is sealed and its charter waits for the server's next save; that charter is not in your hand now.", name), true, documentId);
            case ATTACHED -> new Reply(inHand ? Keyed.text(P + "status_in_hand", "%s is sealed; the charter in your hand carries it.", name)
                    : Keyed.text(P + "status_elsewhere", "%s is sealed and a charter carries it, but not the one in your hand. Inspect a charter to check it, or Recover a charter for a copy.", name), false, documentId);
            case MISSING -> new Reply(Keyed.text(P + "status_missing",
                    "%s is sealed, but the server stopped before its charter was saved, so no charter carries it. Hold a blank charter and use Recover a charter.", name), false, documentId);
        };
    }

    /**
     * Puts an existing sealed record onto the blank charter in the actor's hand (AUD-09): the same document, never a new
     * one. For a record whose charter never reached a saved inventory this completes the delivery; for one that a charter
     * already carries it makes a copy at a seal press, and every copy reads the same and is revoked together. A charter
     * grants nothing by itself. Allowed while heraldry is off, since it restores an existing record.
     */
    public static Reply recover(ServerPlayer actor, UUID request, UUID documentId) {
        var server = actor.getServer();
        Keyed.require(server.isSameThread() && !actor.hasDisconnected(), P + "connected", "You must be connected to this server.");
        var ledger = DocumentLedger.get(server);
        String fingerprint = GovernmentService.hash("recover|" + documentId);
        var replay = ledger.receipt(request);
        if (replay.isPresent()) {
            Keyed.require(replay.get().actor().equals(actor.getUUID()) && replay.get().fingerprint().equals(fingerprint), P + "request_used", "That request was already used for a different charter.");
            return status(actor, replay.get().document());
        }
        Keyed.require(ledger.writable(), P + "read_only", "The document ledger is kept read-only on this server.");
        var document = ledger.document(documentId).orElseThrow(() -> Keyed.refuse(P + "unknown", "That document is not in the ledger."));
        Keyed.require(document.state() == State.ISSUED, P + "recover_revoked", "That record is revoked; a charter for it would only read as revoked.");
        var delivery = ledger.delivery(documentId);
        boolean holder = delivery.map(d -> d.player().equals(actor.getUUID())).orElse(false) || document.issuer().equals(actor.getUUID());
        Keyed.require(holder || actor.hasPermissions(2), P + "recover_authority", "Only the official who sealed it, the player who received its charter, or an operator may recover its charter.");
        var attachment = ledger.attachment(documentId);
        Keyed.require(attachment != DocumentLedger.Attachment.PENDING, P + "recover_pending", "Its charter is still waiting for the server's next save; check it again after that.");
        ItemStack held = actor.getItemInHand(InteractionHand.MAIN_HAND);
        Keyed.require(blank(held), P + "need_blank", "Hold a blank charter in your main hand.");
        boolean copy = attachment == DocumentLedger.Attachment.ATTACHED;
        if (copy) Keyed.require(SealPressBlock.near(actor.level(), actor.blockPosition(), PRESS_RANGE), P + "need_press", "Stand within 4 blocks of a seal press.");
        Keyed.require(PlayerEvidence.room(actor), P + "too_many_pending", "Too many of your charters wait to be saved; try again after the server's next save.");
        long now = server.overworld().getGameTime();
        UUID by = actor.getUUID();
        UUID operation = UUID.nameUUIDFromBytes(("recover|" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Keyed.require(ledger.commit(server, l -> { l.putReceipt(request, new DocumentLedger.Receipt(by, fingerprint, documentId));
                    l.putDelivery(new DocumentLedger.Delivery(documentId, by, operation, DocumentLedger.Attachment.PENDING, copy ? DocumentLedger.Attachment.ATTACHED : DocumentLedger.Attachment.MISSING, now)); }),
                P + "save_failed", "The ledger could not be saved, so nothing changed.");
        attach(actor, held, document, operation);
        return new Reply(copy ? Keyed.text(P + "recovered_copy", "This charter is now a copy of %s. Every copy reads the same, revoking the record revokes them all, and none grants anything by itself.", title(server, document))
                : Keyed.text(P + "recovered", "This charter now carries %s; the earlier one never received it. It is kept with your inventory at the server's next save.", title(server, document)), true, documentId);
    }

    /**
     * Settles {@code online}'s pending charter deliveries from the markers their saved data holds: present means the
     * charter was saved carrying the record ({@code ATTACHED}); absent after a load ({@code fromSave} false) means it was
     * not, so the delivery returns to its fallback ({@code MISSING}, or {@code ATTACHED} for a copy of a carried record).
     */
    public static void settle(ServerPlayer online, java.util.Map<UUID, CompoundTag> markers, boolean fromSave) {
        var server = online.getServer();
        var ledger = DocumentLedger.get(server);
        if (!ledger.writable()) return;
        long now = server.overworld().getGameTime();
        var changes = new ArrayList<DocumentLedger.Delivery>();
        for (var delivery : ledger.deliveries()) {
            if (!delivery.player().equals(online.getUUID()) || delivery.state() != DocumentLedger.Attachment.PENDING) continue;
            if (markers.containsKey(delivery.operation())) changes.add(delivery.at(DocumentLedger.Attachment.ATTACHED, now));
            else if (!fromSave) changes.add(delivery.at(delivery.fallback(), now));
        }
        if (!changes.isEmpty() && !ledger.commit(server, l -> changes.forEach(l::putDelivery))) return;
        for (var entry : markers.entrySet())
            if (OWNER.equals(entry.getValue().getString("Owner")) && ledger.deliveries().stream().noneMatch(d -> d.operation().equals(entry.getKey()) && d.state() == DocumentLedger.Attachment.PENDING))
                PlayerEvidence.clear(online, entry.getKey());
    }

    /** The player's saved data durably holds their markers (see {@link PlayerEvidence}). */
    public static void playerSaved(ServerPlayer player) {
        var markers = PlayerEvidence.markers(player);
        if (markers.values().stream().anyMatch(m -> OWNER.equals(m.getString("Owner")))) settle(player, markers, true);
    }

    /** At login the player's data was just loaded from the copy that counts. */
    public static void playerLoggedIn(ServerPlayer player) {
        var ledger = DocumentLedger.get(player.getServer());
        if (ledger.deliveries().stream().noneMatch(d -> d.player().equals(player.getUUID()) && d.state() == DocumentLedger.Attachment.PENDING) && !PlayerEvidence.has(player)) return;
        settle(player, PlayerEvidence.markers(player), false);
    }

    private static CompoundTag tag(Document document, net.minecraft.server.MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Document", document.id()); tag.putString("Hash", document.hash()); tag.putString("Kind", document.kind().name());
        tag.putString("Kingdom", document.kingdom()); tag.putString("Title", Component.Serializer.toJson(title(server, document)));
        tag.putLong("Day", document.issuedAt() / 24000 + 1);
        return tag;
    }

    /** The document's title as players read it: a defined title key translated, player-written words literal. */
    public static Component title(net.minecraft.server.MinecraftServer server, Document document) {
        Component words = document.kind() == Kind.PROCLAMATION ? Component.literal(document.title()) : dev.otectus.mcakingdoms.interaction.PlayerWords.component(document.title());
        return switch (document.kind()) {
            case PROCLAMATION -> t("title.proclamation", "Proclamation of %s: “%s”", Names.kingdomName(server, document.kingdom()), words);
            case OFFICE_APPOINTMENT -> t("title.office", "Appointment of %s as %s of %s", document.subjectName(), words, Names.kingdomName(server, document.kingdom()));
            case TREATY -> t("title.treaty", "Treaty: %s, sealed by %s", words, Names.kingdomName(server, document.kingdom()));
            case FOUNDING -> t("title.founding", "Founding charter of %s (%s)", Names.kingdomName(server, document.kingdom()), words);
            case TITLE_GRANT -> t("title.title_grant", "Grant of %s to %s by %s", words, document.subjectName(), Names.kingdomName(server, document.kingdom()));
        };
    }

    /** What the server's ledger says about a charter: never from the item alone. */
    public static Inspection inspect(ServerPlayer viewer, ItemStack stack) {
        var server = viewer.getServer();
        if (!stack.is(ModItems.KINGDOM_CHARTER.get())) return new Inspection(Verdict.UNVERIFIED, Optional.empty(), List.of(t("not_charter", "That is not a kingdom charter.")));
        if (blank(stack)) return new Inspection(Verdict.BLANK, Optional.empty(), List.of(t("blank", "A blank charter. An official seals it at a seal press with “Seal a charter”.")));
        CompoundTag tag = stack.getTag().getCompound("Charter");
        Optional<Document> found = tag.hasUUID("Document") ? DocumentLedger.get(server).document(tag.getUUID("Document")) : Optional.empty();
        if (found.isEmpty() || !found.get().hash().equals(tag.getString("Hash")) || !found.get().kingdom().equals(tag.getString("Kingdom")) || !found.get().kind().name().equals(tag.getString("Kind")))
            return new Inspection(Verdict.UNVERIFIED, Optional.empty(), List.of(t("unverified", "Unverified: no sealed record matches this charter. It may be decorative, altered or from another world.")));
        var document = found.get();
        var lines = new ArrayList<Component>();
        lines.add(title(server, document));
        lines.add(t("sealed_by", "Sealed by %s on day %s.", Names.player(server, document.issuer()), document.issuedAt() / 24000 + 1));
        if (document.state() == State.REVOKED) {
            lines.add(0, t("revoked", "Revoked on day %s by %s: %s", document.revokedAt() / 24000 + 1, Names.player(server, document.revokedBy()), Component.literal(document.reason())));
            return new Inspection(Verdict.REVOKED, found, lines);
        }
        boolean current = current(viewer, document);
        lines.add(0, current ? t("official", "Official: this charter matches a sealed record in the ledger.")
                : t("official_historical", "Official, historical: the record was sealed, but what it proves is no longer in effect."));
        lines.add(t("proves_nothing", "A charter is proof of the record. It grants nothing by itself; actions still need the holder's own authority."));
        return new Inspection(current ? Verdict.OFFICIAL : Verdict.OFFICIAL_HISTORICAL, found, lines);
    }

    /** Whether what the document proves still holds: the office still held by the same person, the treaty still in force. */
    private static boolean current(ServerPlayer viewer, Document document) {
        var politics = KingdomsPoliticsApi.get(viewer.getServer());
        var government = politics.government(document.kingdom());
        return switch (document.kind()) {
            case PROCLAMATION -> true;
            case OFFICE_APPOINTMENT -> government.map(g -> g.offices().get(document.record())).map(o -> o.holder().id().equals(document.subject())).orElse(false);
            case TREATY -> politics instanceof GovernmentService g && g.activeAgreements(document.kingdom()).stream().anyMatch(a -> a.id().toString().equals(document.record()));
            case FOUNDING -> government.map(g -> g.capital().toString().equals(document.record())).orElse(false);
            case TITLE_GRANT -> dev.otectus.mcakingdoms.politics.dynasty.DynastyService.titleActive(viewer.getServer(), document.record());
        };
    }

    /** Revokes a document; the issuer, an official with the document's own responsibility, or an operator may. Charters then read as revoked. */
    public static String revoke(ServerPlayer actor, UUID document, String reason) {
        var server = actor.getServer();
        // Revocation withdraws an existing record, so it stays available while new sealing is turned off (AUD-08).
        var ledger = DocumentLedger.get(server);
        Keyed.require(ledger.writable(), P + "read_only", "The document ledger is kept read-only on this server.");
        var found = ledger.document(document).orElseThrow(() -> Keyed.refuse(P + "unknown", "That document is not in the ledger."));
        Keyed.require(found.state() == State.ISSUED, P + "already_revoked", "That document is already revoked.");
        String words = reason == null ? "" : reason.strip();
        Keyed.require(!words.isEmpty() && words.length() <= 256 && words.chars().noneMatch(Character::isISOControl), P + "reason", "Give a reason in 1 to 256 plain characters.");
        var politics = politics(actor);
        Politics.Permission needed = switch (found.kind()) { case OFFICE_APPOINTMENT -> Politics.Permission.APPOINT; case TREATY -> Politics.Permission.RATIFY; default -> Politics.Permission.HONOR; };
        Keyed.require(actor.hasPermissions(2) || found.issuer().equals(actor.getUUID()) || politics.authorized(actor, found.kingdom(), needed),
                P + "revoke_authority", "Only the official who sealed it, an official with the same responsibility, or an operator may revoke it.");
        long now = server.overworld().getGameTime();
        Keyed.require(ledger.commit(server, l -> l.put(found.revoked(actor.getUUID(), now, words))), P + "save_failed", "The ledger could not be saved, so nothing changed.");
        return Keyed.text(P + "revoked_done", "Revoked: %s. Every copy of its charter now reads as revoked.", title(server, found));
    }

    /** The sealed documents of a kingdom, newest first; documents are public records. */
    public static List<Document> documents(net.minecraft.server.MinecraftServer server, String kingdom) {
        return DocumentLedger.get(server).documents().stream().filter(d -> d.kingdom().equals(kingdom))
                .sorted(Comparator.comparingLong(Document::issuedAt).reversed()).limit(64).toList();
    }

    /** The ruler chooses the kingdom's two heraldic colours; every loaded banner and shield of the kingdom shows them within seconds. */
    public static String chooseColours(ServerPlayer actor, String kingdom, DyeColor primary, DyeColor secondary) {
        var server = actor.getServer();
        Keyed.require(HeraldryConfig.enabled(), P + "disabled_heraldry", "Physical heraldry is turned off on this server, so the kingdom's colours cannot be changed now.");
        var ledger = DocumentLedger.get(server);
        Keyed.require(ledger.writable(), P + "read_only", "The document ledger is kept read-only on this server.");
        var politics = politics(actor);
        Keyed.require(politics.sovereignty(kingdom).native_(), P + "crown_elsewhere", "MCA Capitals governs this kingdom's crown, so its crown documents are sealed in Capitals.");
        var leader = politics.government(kingdom).map(g -> g.offices().get(GovernmentActionPolicy.LEADER)).orElse(null);
        Keyed.require(actor.hasPermissions(2) || leader != null && leader.holder().equals(new Politics.Person(actor.getUUID(), Politics.Kind.PLAYER)),
                P + "colours_leader", "Only the kingdom's leader, or an operator, chooses its colours.");
        Keyed.require(primary != secondary, P + "colours_distinct", "Choose two different colours, so the arms stay readable.");
        long next = ledger.colours(kingdom).map(c -> c.revision() + 1).orElse(1L);
        UUID by = actor.getUUID();
        Keyed.require(ledger.commit(server, l -> l.putColours(kingdom, new DocumentLedger.Colours(primary, secondary, by, next))), P + "save_failed", "The ledger could not be saved, so nothing changed.");
        return Keyed.text(P + "colours_done", "The arms of %s are now %s and %s. The kingdom's banners and shields show them within a few seconds.",
                Names.kingdomName(server, kingdom), ActionRegistry.tr("color.minecraft." + primary.getName(), primary.getName()), ActionRegistry.tr("color.minecraft." + secondary.getName(), secondary.getName()));
    }
}
