package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.document.DocumentLedger;
import dev.otectus.mcakingdoms.document.DocumentService;
import dev.otectus.mcakingdoms.heraldry.SealPressBlock;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;

import java.util.*;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/** Kingdom Tasks for sealed documents and heraldic colours (B2): seal, inspect, revoke, browse, and choose the arms' colours. */
final class DocumentTasks {
    private DocumentTasks() {
    }

    private static Component d(String key, String fallback, Object... args) {
        return tr("document.mcakingdoms." + key, fallback, args);
    }

    /**
     * A seal's review depends on that kingdom's government (its offices, agreements and capital) and on the blank charter
     * and press at hand, never on other kingdoms or other documents (AUD-10).
     */
    private static String sealVersion(Context c) {
        var government = KingdomsPoliticsApi.get(c.server()).government(c.text("kingdom"));
        return Review.of("kingdom", c.text("kingdom") + "@" + government.map(g -> Long.toString(g.revision())).orElse("none"),
                "hand", Boolean.toString(DocumentService.blank(c.player.getMainHandItem())),
                "press", Boolean.toString(SealPressBlock.near(c.player.level(), c.player.blockPosition(), DocumentService.PRESS_RANGE)));
    }

    /** A decision about one document depends on that document only (its state and charter delivery). */
    private static String documentVersion(Context c) {
        var ledger = DocumentLedger.get(c.server());
        UUID id;
        try { id = UUID.fromString(c.text("document")); } catch (IllegalArgumentException none) { return Review.of("document", "none"); }
        return Review.of("document", ledger.document(id).map(d -> d.id() + "@" + d.state()).orElse("none"), "record", ledger.attachment(id).name(),
                "hand", Boolean.toString(DocumentService.blank(c.player.getMainHandItem())));
    }

    private static String coloursVersion(Context c) {
        return Review.of("kingdom", c.text("kingdom") + "@" + DocumentLedger.get(c.server()).colours(c.text("kingdom")).map(x -> Long.toString(x.revision())).orElse("0"));
    }

    private static Outcome outcome(DocumentService.Reply reply) {
        return reply.pending() ? Outcome.pending(Keyed.component(reply.text()), reply.document().toString()) : Outcome.applied(Keyed.component(reply.text()), reply.document().toString());
    }

    private static Optional<Outcome> documentStatus(Context c, String operation) {
        try { return Optional.of(outcome(DocumentService.status(c.player, UUID.fromString(operation)))); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
    }

    /** Sealed documents the player may put on a charter again: their own (sealed or received), or any for an operator. */
    private static List<Choice> recoverable(Context c) {
        var ledger = DocumentLedger.get(c.server());
        var out = new ArrayList<Choice>();
        for (var doc : ledger.documents()) {
            if (doc.state() != DocumentLedger.State.ISSUED) continue;
            boolean mine = doc.issuer().equals(c.player.getUUID()) || ledger.delivery(doc.id()).map(d -> d.player().equals(c.player.getUUID())).orElse(false);
            if (!mine && !c.player.hasPermissions(2)) continue;
            var attachment = ledger.attachment(doc.id());
            Component detail = switch (attachment) {
                case MISSING -> d("recover.missing", "No charter carries it: the server stopped before it was saved");
                case PENDING -> d("recover.pending", "Its charter waits for the server's next save");
                case ATTACHED -> d("recover.copy", "A charter carries it; this makes a copy at a seal press");
            };
            var choice = attachment == DocumentLedger.Attachment.PENDING
                    ? Choice.of(doc.id().toString(), DocumentService.title(c.server(), doc), detail, Map.of(ActionRegistry.UNAVAILABLE, "pending"))
                    : Choice.of(doc.id().toString(), DocumentService.title(c.server(), doc), detail);
            out.add(choice);
            if (out.size() >= 64) break;
        }
        return out;
    }

    static void init() {
        targets("document_kind", c -> Arrays.stream(DocumentLedger.Kind.values()).map(k -> Choice.of(k.name().toLowerCase(Locale.ROOT), enumLabel(k),
                d("kind." + k.name().toLowerCase(Locale.ROOT), switch (k) {
                    case PROCLAMATION -> "Public words of the kingdom; needs the Award honors responsibility.";
                    case OFFICE_APPOINTMENT -> "Proof of an office held now; needs the Appoint officials responsibility.";
                    case TREATY -> "Proof of an agreement in force; needs the Ratify agreements responsibility.";
                    case FOUNDING -> "The kingdom's founding and capital; the leader seals it.";
                    case TITLE_GRANT -> "Proof of a court title granted now; needs the Award honors responsibility.";
                }))).toList());
        targets("document_record", DocumentTasks::records);
        targets("document", c -> {
            var out = new ArrayList<Choice>();
            for (var kingdom : c.choices("kingdom")) for (var doc : DocumentService.documents(c.server(), kingdom.value()))
                out.add(Choice.of(doc.id().toString(), DocumentService.title(c.server(), doc), doc.state() == DocumentLedger.State.REVOKED
                        ? d("state.revoked", "Revoked") : d("state.issued", "Sealed on day %s", doc.issuedAt() / 24000 + 1)));
            return out;
        });
        targets("dye", c -> Arrays.stream(DyeColor.values()).map(dye -> Choice.of(dye.getName(), tr("color.minecraft." + dye.getName(), dye.getName()))).toList());

        add(new Task("documents.seal", "Government", "Seal a charter",
                "With a blank charter in hand, near a seal press, seal a proclamation, an office appointment, a treaty in force or the founding. The ledger records it first; then the charter carries it.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom"), pick("kind", "Kind of document", "document_kind"),
                        pick("record", "Record it proves", "document_record").after("kingdom", "kind"), text("text", "Words of the proclamation", "", true)),
                DocumentTasks::sealVersion,
                c -> outcome(DocumentService.seal(c.player, UUID.randomUUID(), DocumentLedger.Kind.valueOf(c.text("kind").toUpperCase(Locale.ROOT)), c.text("kingdom"), c.text("record"), c.text("text")))));
        probe("documents.seal", DocumentTasks::documentStatus);
        effects("documents.seal", c -> List.of(d("effect.seal", "The ledger records the document first; then the blank charter in your hand carries it. The charter is proof of the record and grants nothing by itself.")));
        requirements("documents.seal", c -> List.of(
                Requirement.of(DocumentService.blank(c.player.getMainHandItem()), d("req.blank", "A blank charter in your main hand"), Component.empty()),
                Requirement.of(SealPressBlock.near(c.player.level(), c.player.blockPosition(), DocumentService.PRESS_RANGE), d("req.press", "A seal press within 4 blocks"), Component.empty())));
        add(new Task("documents.inspect", "Government", "Inspect a charter",
                "Asks the ledger about the charter in your hand: official, official but historical, revoked, or unverified. Read-only.",
                false, false, List.of(), c -> "", c -> DocumentService.inspect(c.player, c.player.getItemInHand(InteractionHand.MAIN_HAND)).lines().stream().map(l -> (Object) l).toList()));
        add(new Task("documents.revoke", "Government", "Revoke a sealed document",
                "Marks a document revoked with your reason. Every copy of its charter then reads as revoked; its history is kept. Works even while new sealing is turned off.",
                false, true, List.of(pick("document", "Document", "document"), text("text", "Reason")), DocumentTasks::documentVersion,
                c -> DocumentService.revoke(c.player, c.uuid("document"), c.text("text"))));
        targets("recoverable_document", DocumentTasks::recoverable);
        targetHint("recoverable_document", d("hint.recoverable", "No sealed document of yours is in force. Documents you sealed or whose charter you received appear here."));
        add(new Task("documents.recover", "Government", "Recover a charter",
                "Puts a sealed record onto the blank charter in your hand: the same record, never a new one. If its charter never reached your saved inventory (the server stopped first), this completes it; if a charter already carries it, this makes a copy at a seal press. Every copy reads the same and is revoked together; none grants anything by itself. Works even while new sealing is turned off.",
                false, true, List.of(pick("document", "Document", "recoverable_document")), DocumentTasks::documentVersion,
                c -> outcome(DocumentService.recover(c.player, UUID.randomUUID(), c.uuid("document")))));
        requirements("documents.recover", c -> List.of(
                Requirement.of(DocumentService.blank(c.player.getMainHandItem()), d("req.blank", "A blank charter in your main hand"), Component.empty())));
        probe("documents.recover", DocumentTasks::documentStatus);
        add(new Task("documents.browse", "Government", "Read a kingdom's sealed documents",
                "Lists the kingdom's sealed documents, newest first, and whether each is still in force. Read-only.",
                false, false, List.of(pick("kingdom", "Kingdom", "kingdom")), c -> "", c -> {
                    var lines = new ArrayList<Object>();
                    for (var doc : DocumentService.documents(c.server(), c.text("kingdom")))
                        lines.add(d("browse.line", "%s · %s", DocumentService.title(c.server(), doc), doc.state() == DocumentLedger.State.REVOKED
                                ? d("state.revoked", "Revoked") : d("state.issued", "Sealed on day %s", doc.issuedAt() / 24000 + 1)));
                    if (lines.isEmpty()) lines.add(d("browse.none", "This kingdom has sealed no documents yet."));
                    return lines;
                }));
        add(new Task("heraldry.colours", "Government", "Choose your kingdom's colours",
                "The leader chooses the arms' two colours. The kingdom's banners and shields show them within a few seconds. Each colour has a name, so the arms never depend on colour alone.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom"), pick("primary", "Main colour", "dye"), pick("secondary", "Second colour", "dye")), DocumentTasks::coloursVersion,
                c -> DocumentService.chooseColours(c.player, c.text("kingdom"), DyeColor.byName(c.text("primary"), DyeColor.WHITE), DyeColor.byName(c.text("secondary"), DyeColor.BLACK))));
    }

    private static List<Choice> records(Context c) {
        String kingdom = c.text("kingdom");
        var kind = c.text("kind");
        if (kingdom.isBlank() || kind.isBlank()) return List.of();
        var politics = KingdomsPoliticsApi.get(c.server());
        return switch (DocumentLedger.Kind.valueOf(kind.toUpperCase(Locale.ROOT))) {
            case PROCLAMATION -> List.of(Choice.of("", d("record.proclamation", "No record: the proclamation's words are the record")));
            case FOUNDING -> List.of(Choice.of("", d("record.founding", "The government's founding and its capital")));
            case OFFICE_APPOINTMENT -> politics instanceof GovernmentService government ? government.occupiedOffices(c.player, kingdom).stream()
                    .map(o -> Choice.of(o.key(), tr("politics.mcakingdoms.office.choice", "%s · %s", PlayerWords.component(o.title()), Component.literal(o.holderName())))).toList() : List.of();
            case TREATY -> politics instanceof GovernmentService government ? government.activeAgreements(kingdom).stream()
                    .map(a -> Choice.of(a.id().toString(), PlayerWords.component(a.terms().title()), d("record.treaty", "Between %s and %s", Names.kingdomName(c.server(), a.proposer()), Names.kingdomName(c.server(), a.recipient())))).toList() : List.of();
            case TITLE_GRANT -> dev.otectus.mcakingdoms.politics.dynasty.DynastyService.titleChoices(c, kingdom);
        };
    }
}
