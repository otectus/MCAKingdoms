package dev.otectus.mcakingdoms.client.politics;

import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.client.HeraldryRenderer;
import dev.otectus.mcakingdoms.client.InteractionClient;
import dev.otectus.mcakingdoms.client.KingdomGuideScreen;
import dev.otectus.mcakingdoms.client.book.BookButton;
import dev.otectus.mcakingdoms.client.book.BookLayout;
import dev.otectus.mcakingdoms.client.book.BookSkin;
import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import dev.otectus.mcakingdoms.politics.PoliticalNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/**
 * A kingdom's government pages, read-only and paged: Overview, Council, Agreements, Petitions, Institutions, Honors and
 * History. Each record reads as labelled sections; the header names the government's state, leader, capital, open
 * elections, any regency and the responsibilities you hold there. Actions open the reviewed Kingdom Tasks workflow with
 * this kingdom (and record) chosen; nothing here changes records. No authoritative client state.
 */
public final class KingdomScreen extends Screen {
    private static final List<String> TABS = List.of("overview", "council", "agreements", "petitions", "institutions", "honors", "history");
    private final Screen parent;
    private final String kingdom;
    private final String settlement;
    private String tab = "overview";
    private Page page;
    private Row selected;
    private int offset, rowScroll;
    private Component status = Component.empty();
    private PoliticalNetwork.Query pending;
    private int timeout, attempts;
    private RichPanel detailPanel;
    private boolean refreshOnReturn;
    /** Translated title, detail and labelled fields per row, from the server. */
    private Map<Row, Presented> presented = new HashMap<>();
    private record Presented(Component title, Component detail, List<Pieces.Field> fields) { }
    private List<Component> header = List.of();
    private Component kingdomName;
    private ResourceLocation heraldry;
    private int color;
    private boolean governed;
    private BookLayout folio;
    public KingdomScreen(Screen parent, String kingdom, String settlement) {
        super(Component.translatable("politics.mcakingdoms.kingdom"));
        this.parent = parent; this.kingdom = kingdom; this.settlement = settlement;
    }
    private static Component label(String key) { return Component.translatable("politics.mcakingdoms." + key); }
    private static Component t(String key, String fallback, Object... args) { return Component.translatableWithFallback("politics.mcakingdoms.screen." + key, fallback, args); }
    public static void receive(PoliticalNetwork.Reply reply) {
        if (Minecraft.getInstance().screen instanceof KingdomScreen screen) screen.accept(reply);
    }
    private void accept(PoliticalNetwork.Reply reply) {
        if (pending == null || !reply.page().requestId().equals(pending.id()) || !reply.page().kingdom().equals(kingdom)) return;
        page = reply.page(); pending = null; timeout = 0;
        if (reply.kingdomName() != null && !reply.kingdomName().isBlank()) kingdomName = InteractionNetwork.component(reply.kingdomName());
        header = reply.header() == null ? List.of() : reply.header().stream().map(InteractionNetwork::component).toList();
        heraldry = reply.heraldry() == null || reply.heraldry().isBlank() ? null : ResourceLocation.tryParse(reply.heraldry());
        color = reply.color();
        governed = reply.governed();
        presented = new HashMap<>();
        int rows = page.rows().size();
        boolean hasTitles = reply.titles() != null && reply.titles().size() == rows;
        boolean hasDetails = reply.details() != null && reply.details().size() == rows;
        boolean hasFields = reply.fields() != null && reply.fields().size() == rows;
        for (int i = 0; i < rows; i++) {
            Row row = page.rows().get(i);
            List<Pieces.Field> fields = new ArrayList<>();
            if (hasFields) { var pairs = reply.fields().get(i); for (int f = 0; f + 1 < pairs.size(); f += 2) fields.add(new Pieces.Field(InteractionNetwork.component(pairs.get(f)), InteractionNetwork.component(pairs.get(f + 1)))); }
            presented.put(row, new Presented(hasTitles ? InteractionNetwork.component(reply.titles().get(i)) : translated(row.title()),
                    hasDetails ? InteractionNetwork.component(reply.details().get(i)) : Component.literal(row.detail()), fields));
        }
        status = reply.result() == null ? page.diagnostic().isBlank() ? Component.empty() : Component.literal(page.diagnostic()) : Component.literal(reply.result().message());
        if (reply.result() != null && reply.result().success()) { selected = null; }
        rebuildWidgets();
    }
    private void request() {
        pending = new PoliticalNetwork.Query(UUID.randomUUID(), kingdom, tab, offset);
        attempts = 1; timeout = 40; PoliticalNetwork.send(pending);
        if (minecraft != null) rebuildWidgets();
    }
    @Override protected void init() {
        clearWidgets();
        folio = BookLayout.folio(width, height, 640, 440);
        int tabWidth = (contentWidth() - 12) / 4;
        for (int i = 0; i < TABS.size(); i++) {
            String value = TABS.get(i);
            var button = addRenderableWidget(BookButton.builder(label(value), b -> { tab = value; offset = rowScroll = 0; selected = null; page = null; request(); })
                    .bounds(contentLeft() + (i % 4) * (tabWidth + 3), folio.y() + 42 + (i / 4) * 22, tabWidth, 20).build().selected(tab.equals(value)));
            button.active = pending == null && !tab.equals(value);
        }
        int count = visibleRows();
        rowScroll = Math.max(0, Math.min(rowScroll, page == null ? 0 : Math.max(0, page.rows().size() - count)));
        int detailX = spread() ? folio.leafX(true) : contentLeft();
        int detailY = spread() ? bodyTop() : bodyTop() + count * 21 + 4;
        int detailW = spread() ? folio.leafWidth() : contentWidth();
        int detailH = Math.max(20, bodyBottom() - detailY);
        if (detailPanel == null) detailPanel = new RichPanel(detailX, detailY, detailW, detailH, Component.translatable("menu.mcakingdoms.record_details")).manuscript();
        else detailPanel.place(detailX, detailY, detailW, detailH);
        detailPanel.setPieces(detailPieces(), detailPanel.anchor());
        addRenderableWidget(detailPanel);
        if (page != null) for (int i = 0; i < count && i + rowScroll < page.rows().size(); i++) {
            Row row = page.rows().get(i + rowScroll);
            Component title = rowLabel(row);
            boolean current = row.equals(currentRow());
            var button = addRenderableWidget(BookButton.builder(title, b -> { selected = row; rebuildWidgets(); setFocused(detailPanel); })
                    .bounds(listLeft(), bodyTop() + i * 21, listWidth(), 20).tooltip(Tooltip.create(title)).build().kind(BookButton.Kind.INDEX).selected(current));
            button.active = pending == null && !row.equals(selected);
        }
        int pagerWidth = Math.max(48, Math.min(90, listWidth() / 3));
        addRenderableWidget(BookButton.builder(label("previous"), b -> { if (rowScroll > 0) { rowScroll = Math.max(0, rowScroll - count); rebuildWidgets(); } else { offset = Math.max(0, offset - 20); rowScroll = 0; request(); } })
                .bounds(listLeft(), pagerY(), pagerWidth, 20).build()).active = pending == null && (rowScroll > 0 || offset > 0);
        addRenderableWidget(BookButton.builder(label("next"), b -> { if (page != null && rowScroll + count < page.rows().size()) { rowScroll += count; rebuildWidgets(); } else { offset += 20; rowScroll = 0; request(); } })
                .bounds(listLeft() + listWidth() - pagerWidth, pagerY(), pagerWidth, 20).build()).active = pending == null && page != null && (rowScroll + count < page.rows().size() || page.hasMore());
        int footerWidth = (contentWidth() - 12) / 4;
        var actions = addRenderableWidget(BookButton.builder(label("actions"), b -> { refreshOnReturn = true; InteractionClient.open(this, "", "Government", settlement.isEmpty() ? "" : InteractionClient.prefill("target", settlement)); })
                .bounds(contentLeft(), footerY(), footerWidth, 20).tooltip(Tooltip.create(t("actions.help", "Government tasks in Kingdom Tasks; each checks your responsibilities and ends on a review."))).build());
        actions.active = page != null && pending == null;
        var refresh = addRenderableWidget(BookButton.builder(label("refresh"), b -> request()).bounds(contentLeft() + footerWidth + 4, footerY(), footerWidth, 20).build());
        refresh.active = pending == null;
        addRenderableWidget(BookButton.builder(t("help", "Help"), b -> { refreshOnReturn = true; minecraft.setScreen(new KingdomGuideScreen(this, helpTopic())); }).bounds(contentLeft() + 2 * (footerWidth + 4), footerY(), footerWidth, 20)
                .tooltip(Tooltip.create(t("help.tip", "This page's topic in the Book."))).build());
        addRenderableWidget(BookButton.builder(label("back"), b -> onClose()).bounds(contentLeft() + 3 * (footerWidth + 4), footerY(), contentRight() - (contentLeft() + 3 * (footerWidth + 4)), 20).build());
        if (refreshOnReturn && pending == null) { refreshOnReturn = false; request(); }
        else if (page == null && pending == null) request();
    }
    private String helpTopic() {
        return switch (tab) {
            case "council" -> "offices_and_permissions";
            case "agreements" -> "agreements";
            case "petitions" -> "petitions";
            case "institutions", "honors" -> "institutions_and_honors";
            case "history" -> "read_political_history";
            default -> governed ? "political_pages" : "found_a_government";
        };
    }
    private Component rowLabel(Row row) {
        var shown = presented.get(row);
        Component title = shown != null ? shown.title() : translated(row.title());
        if (tab.equals("history") && shown != null)
            for (var field : shown.fields()) if (field.label().getString().equals(Component.translatableWithFallback("politics.mcakingdoms.field.when", "When").getString()))
                return t("history.row", "%s · %s", field.value(), title);
        return title;
    }
    private boolean spread() { return folio.spread(); }
    private int contentLeft() { return folio.leafX(false); }
    private int contentRight() { return spread() ? folio.leafX(true) + folio.leafWidth() : contentLeft() + folio.leafWidth(); }
    private int contentWidth() { return contentRight() - contentLeft(); }
    private int bodyTop() { return folio.y() + 88; }
    private int pagerY() { return folio.bottom() - 62; }
    private int footerY() { return folio.bottom() - 34; }
    private int bodyBottom() { return pagerY() - 6; }
    private int listLeft() { return spread() ? folio.leafX(false) : contentLeft(); }
    private int listWidth() { return spread() ? folio.leafWidth() : contentWidth(); }
    private int visibleRows() {
        int rows = Math.max(1, (bodyBottom() - bodyTop() - 80) / 21);
        return spread() ? rows : Math.min(4, rows);
    }
    private Row currentRow() {
        return selected != null ? selected : page == null || page.rows().isEmpty() ? null : page.rows().get(0);
    }
    private List<RichPanel.Piece> detailPieces() {
        var pieces = new ArrayList<RichPanel.Piece>();
        if (pending != null) { pieces.add(Pieces.text("loading", Component.translatable("civic.mcakingdoms.loading"), Pieces.MUTED, 0)); return pieces; }
        // The full status (a refusal, a diagnostic) is the first thing read, in this focusable and narrated panel.
        if (!status.getString().isEmpty()) pieces.add(Pieces.callout("status", Pieces.Tone.NOTE, null, Component.translatable("politics.mcakingdoms.status", status)));
        if (tab.equals("overview")) {
            pieces.add(Pieces.heading("government", t("section.government", "Government")));
            for (int i = 0; i < header.size(); i++) pieces.add(Pieces.text("header_" + i, header.get(i)));
            if (page != null && !governed) pieces.add(Pieces.actions("founding", List.of(
                    Pieces.button("founding_status", t("founding.status", "Check whether you can found a government"), t("founding.status.help", "Explains your case: residence, the server's setting and this kingdom's state."),
                            () -> openTask("politics.founding_status", Map.of()), true),
                    Pieces.button("founding", t("founding.task", "Establish a government"), t("founding.task.help", "Opens the founding task for this kingdom; it shows what you need and ends on a review."),
                            () -> openTask("politics.bootstrap", Map.of("kingdom", kingdom)), true))));
        }
        Row row = selected != null ? selected : page == null || page.rows().isEmpty() ? null : page.rows().get(0);
        if (row == null) { if (page != null) pieces.add(Pieces.text("empty", empty(), Pieces.MUTED, 0)); return pieces; }
        var shown = presented.get(row);
        Component title = shown != null ? shown.title() : translated(row.title());
        pieces.add(Pieces.heading("record", title));
        if (shown != null && !shown.fields().isEmpty()) pieces.add(Pieces.fields("fields", shown.fields()));
        else pieces.add(Pieces.text("detail", shown != null ? shown.detail() : Component.literal(row.detail())));
        contextualAction(row).ifPresent(action -> pieces.add(Pieces.actions("record_actions", List.of(action))));
        return pieces;
    }
    private Component empty() {
        return switch (tab) {
            case "agreements" -> t("empty.agreements", "No agreements you may see. Ratified agreements are public; proposals show only to their parties' authorized officials.");
            case "petitions" -> t("empty.petitions", "No petitions you may see. You see your own petitions, and officials who review petitions see all of them.");
            case "institutions" -> t("empty.institutions", "No recognized institutions in settlements you know.");
            case "honors" -> t("empty.honors", "No honors have been awarded.");
            case "history" -> t("empty.history", "No recorded history you may see.");
            case "council" -> t("empty.council", "No offices are filled.");
            default -> Component.translatable("menu.mcakingdoms.no_records");
        };
    }
    /** The task this record leads to, with the kingdom and the record chosen; the server checks responsibility and terms. */
    private Optional<RichPanel.Target> contextualAction(Row row) {
        if (row.id().isBlank()) return Optional.empty();
        return switch (tab) {
            case "agreements" -> Optional.of(Pieces.button("sign", t("agreement.sign", "Sign or decline this agreement…"), t("agreement.sign.help", "Opens Sign reviewed agreement terms with this agreement chosen; your responsibility is checked."),
                    () -> openTask("politics.sign", Map.of("kingdom", kingdom, "target", row.id())), true));
            case "petitions" -> Optional.of(Pieces.button("review", t("petition.review", "Review this petition…"), t("petition.review.help", "Opens Review a petition with this petition chosen; it needs the review responsibility."),
                    () -> openTask("politics.review", Map.of("kingdom", kingdom, "target", row.id())), true));
            case "institutions" -> Optional.of(Pieces.button("revalidate", t("institution.revalidate", "Revalidate this institution…"), t("institution.revalidate.help", "Checks its building again; it needs the recognize responsibility."),
                    () -> openTask("politics.revalidate", Map.of("kingdom", kingdom, "target", row.id())), true));
            default -> Optional.empty();
        };
    }
    private void openTask(String task, Map<String, String> prefill) {
        var json = new com.google.gson.JsonObject();
        prefill.forEach(json::addProperty);
        refreshOnReturn = true;
        InteractionClient.open(this, task, "", json.toString());
    }
    private Component translated(String value) { return value.startsWith("politics.") ? Component.translatable(value) : Component.literal(value); }
    @Override public void tick() {
        if (timeout > 0 && --timeout == 0 && pending != null) {
            if (attempts++ < 3) { PoliticalNetwork.send(pending); timeout = 40; }
            else { pending = null; status = Component.translatable("politics.mcakingdoms.no_response"); rebuildWidgets(); }
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        int listBottom = spread() ? bodyBottom() : bodyTop() + visibleRows() * 21;
        if (pending == null && page != null && x >= listLeft() && x < listLeft() + listWidth() && y >= bodyTop() && y < listBottom && delta != 0) {
            rowScroll = Math.max(0, Math.min(Math.max(0, page.rows().size() - visibleRows()), rowScroll + (delta > 0 ? -1 : 1)));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics);
        BookSkin.cover(graphics, folio.x(), folio.y(), folio.width(), folio.height(), folio.spread(), BookSkin.Binding.CHRONICLE);
        Component name = kingdomName != null ? kingdomName : Component.literal(kingdom.substring(kingdom.indexOf(':') + 1));
        Component heading = title.copy().append(" — ").append(name);
        int crestSpace = heraldry == null ? 0 : 30;
        BookSkin.title(graphics, font, heading, contentLeft() + crestSpace, folio.y() + 18, contentWidth() - crestSpace);
        if (heraldry != null) HeraldryRenderer.renderInBook(graphics, heraldry, contentLeft() + 8, folio.y() + 15, 16);
        BookSkin.divider(graphics, contentLeft() + 4, folio.y() + 34, contentWidth() - 8);
        if (spread()) {
            graphics.fill(folio.leafX(true) - 5, bodyTop() - 2, folio.leafX(true) - 4, bodyBottom(), 0x55000000 | BookSkin.LINE);
        }
        BookSkin.divider(graphics, contentLeft() + 4, pagerY() - 4, contentWidth() - 8);
        // An empty tab is explained once, in the detail panel, rather than repeated in the list.
        if (pending != null) BookSkin.title(graphics, font, Component.translatable("civic.mcakingdoms.loading"), contentLeft(), pagerY() + 4, contentWidth());
        super.render(graphics, mx, my, partial);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
