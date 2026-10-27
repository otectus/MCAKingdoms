package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.book.*;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.network.LedgerPagePacket;
import dev.otectus.mcakingdoms.network.NetworkHandler;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * The Village Ledger: the settlements this player knows, searched by name (former names count), filtered by an explicit
 * kingdom choice and sorted by name or by distance in their dimension. The server filters and pages; the client never
 * receives a settlement it may not know, and counts cover only those. A selected settlement is kept by its stable id
 * across refreshes, so a rename or merge never selects a different row.
 */
public final class VillageLedgerScreen extends Screen {
    private static final int PAGE_SIZE = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int REQUEST_TIMEOUT_TICKS = 30;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private enum View { LIST, DETAILS, KINGDOMS }

    private LedgerPagePacket page;
    private List<KingdomSummary> kingdoms = List.of();
    private Optional<ResourceLocation> filter = Optional.empty();
    private String query = "";
    private SettlementKnowledge.Sort sort = SettlementKnowledge.Sort.NAME;
    private int offset;
    private View view = View.LIST;
    private boolean loading = true;
    private boolean initialRequestSent;
    private long pendingRequestId;
    private Optional<ResourceLocation> pendingFilter = Optional.empty();
    private int pendingOffset;
    private String pendingQuery = "";
    private SettlementKnowledge.Sort pendingSort = SettlementKnowledge.Sort.NAME;
    private long expectedRevision;
    private int requestTimeoutTicks;
    private int retryTicks;
    private int requestAttempts;
    private int scrollIndex;
    private int visibleRows;
    private boolean draggingScroll;
    private int scrollDragOffset;
    /** Local page in the kingdom picker; it never changes the server-backed settlement result page. */
    private int kingdomPage;
    private int debounce;
    /** The settlement being read, by its stable id; its summary is refreshed from each page that still lists it. */
    private UUID selectedId;
    private SettlementSummary selected;
    private boolean moreDetails;
    private Component requestError;
    private Component notice;
    private EditBox search;
    private RichPanel details, notes;
    private BookLayout book;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<KingdomSummary> pickerRows = new ArrayList<>();
    private Button kingdomPrevious, kingdomNext;

    public VillageLedgerScreen(LedgerPagePacket cachedPage) {
        super(Component.translatable("screen.mcakingdoms.ledger.title"));
        if (cachedPage != null && cachedPage.retryAfterTicks() == 0) {
            page = cachedPage;
            kingdoms = cachedPage.kingdoms();
            filter = cachedPage.kingdomId();
            query = cachedPage.query();
            sort = cachedPage.sort();
        }
    }

    private static Component l(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("screen.mcakingdoms.ledger." + key, fallback, args);
    }

    private int panelTop() { return book.y() + 6; }
    private int panelBottom() { return book.bottom() - 10; }
    private int panelLeft() { return book.leafX(false) - 10; }
    private int panelRight() { return panelLeft() + book.leafWidth() + 20; }
    private int pageCenter() { return (panelLeft() + panelRight()) / 2; }
    private boolean listing() { return view == View.LIST || book.spread() && view == View.DETAILS; }

    @Override protected void init() {
        book = BookLayout.fit(width, height);
        rowButtons.clear();
        pickerRows.clear();
        int left = panelLeft(), right = panelRight(), top = panelTop(), bottom = panelBottom(), w = right - left - 20;
        int quarter = (w - 12) / 4;
        switch (view) {
            case LIST -> initList(left, right, top, bottom, w);
            case KINGDOMS -> initKingdoms(left, top, bottom, w);
            case DETAILS -> {
                if (book.spread()) initList(left, right, top, bottom, w);
                initDetails(book.spread() ? book.leafX(true) - 10 : left, top, bottom, w);
            }
        }
        if (view != View.KINGDOMS && (view != View.DETAILS || book.spread())) {
            int y = bottom - 30;
            addRenderableWidget(BookButton.builder(Component.translatable("menu.mcakingdoms.tasks"), b -> InteractionClient.open(this, "", "", ""))
                    .bounds(left + 10, y, quarter, 20).tooltip(Tooltip.create(l("tasks.help", "Kingdom Tasks: read records and act."))).build());
            addRenderableWidget(BookButton.builder(Component.translatable("civic.mcakingdoms.title"), b -> minecraft.setScreen(new GuildScreen(this)))
                    .bounds(left + 14 + quarter, y, quarter, 20).tooltip(Tooltip.create(l("guilds.help", "Your guild memberships, progress and services."))).build());
            addRenderableWidget(BookButton.builder(l("help", "Help"), b -> minecraft.setScreen(new KingdomGuideScreen(this, "ledger_and_discovery")))
                    .bounds(left + 18 + 2 * quarter, y, quarter, 20).tooltip(Tooltip.create(l("help.tip", "How discovery works, in the Book."))).build());
            addRenderableWidget(BookButton.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(left + 22 + 3 * quarter, y, w - 12 - 3 * quarter, 20)
                    .tooltip(Tooltip.create(l("done.help", "Close the Ledger."))).build());
        }
        if (book.spread() && view != View.DETAILS) initNotes();
        if (!initialRequestSent) {
            initialRequestSent = true;
            request(0); // rebuilds the widgets with the loading state
        }
    }

    private void initList(int left, int right, int top, int bottom, int w) {
        int x = left + 10;
        if (search == null) {
            search = new BookSearchBox(font, x, top + 28, w - 84, 20, l("search", "Search settlement names"));
            search.setMaxLength(64);
            search.setResponder(value -> { if (!value.equals(query)) { query = value; debounce = 10; } });
        }
        search.setX(x);
        search.setY(top + 28);
        search.setWidth(w - 84);
        if (!search.getValue().equals(query)) search.setValue(query);
        addRenderableWidget(search);
        addRenderableWidget(BookButton.builder(l("search.go", "Search"), b -> { debounce = 0; request(0); }).bounds(right - 82, top + 28, 72, 20).build());
        int third = (w - 8) / 3;
        var picker = addRenderableWidget(BookButton.builder(l("kingdom.current", "Kingdom: %s", filterLabel()), b -> { view = View.KINGDOMS; rebuildWidgets(); })
                .bounds(x, top + 52, third + 30, 20).tooltip(Tooltip.create(l("kingdom.help", "Show the settlements of one kingdom, or all."))).build());
        picker.active = !loading && !kingdoms.isEmpty();
        var order = addRenderableWidget(BookButton.builder(sort == SettlementKnowledge.Sort.NAME ? l("sort.name", "Sort: Name") : l("sort.distance", "Sort: Nearest first"), b -> {
            sort = sort == SettlementKnowledge.Sort.NAME ? SettlementKnowledge.Sort.DISTANCE : SettlementKnowledge.Sort.NAME;
            request(0);
        }).bounds(x + third + 34, top + 52, third - 10, 20).tooltip(Tooltip.create(l("sort.help", "By name, or nearest first in your current dimension; other dimensions follow."))).build());
        order.active = !loading;
        addRenderableWidget(BookButton.builder(Component.translatable("civic.mcakingdoms.refresh"), b -> request(offset)).bounds(x + 2 * third + 28, top + 52, w - 2 * third - 28, 20).build());
        int listTop = top + 78, footerY = bottom - 54;
        visibleRows = Math.max(1, Math.min(PAGE_SIZE, (footerY - 4 - listTop) / ROW_HEIGHT));
        int count = resultCount();
        int maxScroll = Math.max(0, count - visibleRows);
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll));
        boolean showRows = !loading && requestError == null;
        // Rows line up with the search box and toolbar; the scroll buttons, when needed, take the right edge.
        int rowWidth = Math.max(60, showRows && maxScroll > 0 ? w - 24 : w);
        for (int slot = 0; slot < visibleRows; slot++) {
            int index = scrollIndex + slot;
            if (!showRows || index >= count) break;
            SettlementSummary settlement = page.settlements().get(index);
            Component label = rowLabel(settlement);
            rowButtons.add(addRenderableWidget(BookButton.builder(label, b -> select(settlement)).bounds(x, listTop + slot * ROW_HEIGHT, rowWidth, 20)
                    .tooltip(Tooltip.create(label)).build().kind(BookButton.Kind.INDEX).selected(settlement.id().equals(selectedId))));
        }
        if (showRows && maxScroll > 0) {
            addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.scroll_up"), b -> scroll(-1)).bounds(x + w - 20, listTop, 20, 20)
                    .tooltip(Tooltip.create(l("scroll.up", "Earlier rows on this page"))).build()).active = scrollIndex > 0;
            addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.scroll_down"), b -> scroll(1)).bounds(x + w - 20, Math.max(listTop + 22, footerY - 24), 20, 20)
                    .tooltip(Tooltip.create(l("scroll.down", "Later rows on this page"))).build()).active = scrollIndex < maxScroll;
        }
        if (requestError != null)
            addRenderableWidget(BookButton.builder(l("retry", "Try again"), b -> request(offset)).bounds(pageCenter() - 60, top + 120, 120, 20).build());
        else if (!loading && count == 0) {
            if (filter.isPresent() || !query.isEmpty())
                addRenderableWidget(BookButton.builder(l("clear", "Clear search and kingdom"), b -> { filter = Optional.empty(); query = ""; search.setValue(""); request(0); })
                        .bounds(pageCenter() - 80, Math.min(top + 140, bottom - 78), 160, 20).build());
            else addRenderableWidget(BookButton.builder(l("discover", "How discovery works"), b -> minecraft.setScreen(new KingdomGuideScreen(this, "ledger_and_discovery")))
                        .bounds(pageCenter() - 80, Math.min(top + 140, bottom - 78), 160, 20).build());
        }
        int sideWidth = sideWidth(w);
        var previous = addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.previous"), b -> request(Math.max(0, offset - PAGE_SIZE)))
                .bounds(left + 10, footerY, sideWidth, 20).tooltip(Tooltip.create(l("page.previous", "The previous page of results"))).build());
        previous.active = !loading && offset > 0;
        var next = addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.next"), b -> request(offset + PAGE_SIZE))
                .bounds(right - 10 - sideWidth, footerY, sideWidth, 20).tooltip(Tooltip.create(l("page.next", "The next page of results"))).build());
        next.active = !loading && page != null && page.settlements().size() > PAGE_SIZE;
    }

    private void initKingdoms(int left, int top, int bottom, int w) {
        int x = left + 34, rowWidth = w - 24;
        var all = addRenderableWidget(BookButton.builder(l("all", "All kingdoms"), b -> chooseKingdom(Optional.empty())).bounds(x, top + 40, rowWidth, 20).build());
        all.active = filter.isPresent();
        all.selected(filter.isEmpty());
        int navigationY = bottom - 54;
        int rows = Math.max(1, (navigationY - 6 - (top + 64)) / ROW_HEIGHT);
        int pages = Math.max(1, (kingdoms.size() + rows - 1) / rows);
        kingdomPage = Math.max(0, Math.min(kingdomPage, pages - 1));
        int start = kingdomPage * rows;
        for (int i = start; i < Math.min(kingdoms.size(), start + rows); i++) {
            KingdomSummary kingdom = kingdoms.get(i);
            var button = addRenderableWidget(BookButton.builder(Component.translatable(kingdom.translationKey()), b -> chooseKingdom(Optional.of(kingdom.id())))
                    .bounds(x, top + 64 + (i - start) * ROW_HEIGHT, rowWidth, 20).build());
            button.active = !filter.equals(Optional.of(kingdom.id()));
            button.selected(filter.equals(Optional.of(kingdom.id())));
            pickerRows.add(kingdom);
        }
        int sideWidth = sideWidth(w);
        kingdomPrevious = addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.previous"), b -> changeKingdomPage(-1))
                .bounds(left + 10, navigationY, sideWidth, 20).tooltip(Tooltip.create(l("picker.previous", "Earlier kingdoms"))).build());
        kingdomPrevious.active = kingdomPage > 0;
        kingdomNext = addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.next"), b -> changeKingdomPage(1))
                .bounds(left + w - sideWidth + 10, navigationY, sideWidth, 20).tooltip(Tooltip.create(l("picker.next", "Later kingdoms"))).build());
        kingdomNext.active = kingdomPage + 1 < pages;
        addRenderableWidget(BookButton.builder(l("picker.back", "Back to the list"), b -> { view = View.LIST; rebuildWidgets(); }).bounds(left + 10, bottom - 30, w, 20).build());
    }

    private void initDetails(int left, int top, int bottom, int w) {
        if (details == null) details = new RichPanel(left + 10, top + 62, w, bottom - top - 122, Component.translatable("menu.mcakingdoms.record_details")).manuscript();
        else details.place(left + 10, top + 62, w, bottom - top - 122);
        details.setMessage(selected == null ? Component.translatable("menu.mcakingdoms.record_details") : Component.literal(selected.displayName()));
        details.setPieces(detailPieces(), details.anchor());
        addRenderableWidget(details);
        int half = (w - 4) / 2;
        addRenderableWidget(BookButton.builder(Component.translatable("gui.mcakingdoms.back"), b -> showList()).bounds(left + 10, bottom - 30, half, 20).build());
        addRenderableWidget(BookButton.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(left + 14 + half, bottom - 30, w - half - 4, 20).build());
        setInitialFocus(details);
    }

    private List<RichPanel.Piece> detailPieces() {
        var pieces = new ArrayList<RichPanel.Piece>();
        if (selected == null) return pieces;
        SettlementSummary s = selected;
        KingdomSummary kingdom = kingdom(s.kingdomId());
        Component kingdomName = kingdom == null ? l("kingdom.unknown", "A kingdom this server no longer defines (%s)", s.kingdomId().toString())
                : Component.translatable(kingdom.translationKey());
        var fields = new ArrayList<Pieces.Field>();
        fields.add(new Pieces.Field(l("field.kingdom", "Kingdom"), kingdomName));
        fields.add(new Pieces.Field(l("field.dimension", "Dimension"), dimensionName(s.dimension())));
        fields.add(new Pieces.Field(l("field.location", "Location"), l("location", "%s, %s, %s", s.anchor().getX(), s.anchor().getY(), s.anchor().getZ())));
        fields.add(new Pieces.Field(l("field.distance", "Distance"), distance(s)));
        pieces.add(Pieces.fields("summary", fields));
        pieces.add(Pieces.actions("links", List.of(
                Pieces.button("government", l("link.government", "Government of %s", kingdomName), l("link.government.help", "Leaders, agreements, petitions and history of this settlement's kingdom."),
                        () -> minecraft.setScreen(new dev.otectus.mcakingdoms.client.politics.KingdomScreen(this, s.kingdomId().toString(), s.id().toString())), kingdom != null),
                Pieces.button("guilds", l("link.guilds", "Guild services"), l("link.guilds.help", "Your guild progress and the services you can use; contacts are found near their branch."),
                        () -> minecraft.setScreen(new GuildScreen(this)), true),
                Pieces.button("war_room", Component.translatable("menu.mcakingdoms.war_room"), l("link.war_room.help", "Who controls this settlement, campaigns and civilian options."),
                        () -> minecraft.setScreen(new WarRoomScreen(this, s.id(), s.displayName())), true),
                Pieces.button("tasks", l("link.tasks", "Tasks for this settlement"), l("link.tasks.help", "Kingdom Tasks, with this settlement chosen wherever a task asks for one."),
                        () -> InteractionClient.open(this, "", "", InteractionClient.prefill("settlement", s.id())), true))));
        pieces.add(Pieces.actions("more_toggle", List.of(Pieces.button("more", moreDetails ? l("more.hide", "Hide technical details") : l("more.show", "More details"),
                l("more.help", "When and how this settlement was recognized, for troubleshooting."), () -> { moreDetails = !moreDetails; details.setPieces(detailPieces(), details.anchor()); details.focusTarget("more"); }, true))));
        if (moreDetails) pieces.add(Pieces.fields("more", List.of(
                new Pieces.Field(l("field.recognized", "Recognized"), Component.translatable("screen.mcakingdoms.ledger.day", s.recognizedGameTime() / 24_000L + 1)),
                new Pieces.Field(l("field.biome", "Biome when recognized"), Component.translatable(Util.makeDescriptionId("biome", s.biome()))),
                new Pieces.Field(l("field.assignment", "How its kingdom was chosen"), Component.translatable("assignment.mcakingdoms." + s.assignmentSource().name().toLowerCase(Locale.ROOT))),
                new Pieces.Field(l("field.record", "Record"), l("record", "%s · revision %s", s.slug().toString(), s.revision())))));
        return pieces;
    }

    /** Previous and Next leave the page line room for "Page 1 of 1 · 1 settlements" even in a compact window. */
    private static int sideWidth(int w) {
        return Math.max(54, Math.min(110, (w - 210) / 2));
    }

    private Component distance(SettlementSummary settlement) {
        var player = minecraft == null ? null : minecraft.player;
        if (player == null) return l("distance.unknown", "Unknown");
        if (!player.level().dimension().location().equals(settlement.dimension())) return l("distance.elsewhere", "In another dimension (%s)", dimensionName(settlement.dimension()));
        long blocks = Math.round(Math.sqrt(player.blockPosition().distSqr(settlement.anchor())));
        return blocks <= 8 ? l("distance.here", "Here") : l("distance.blocks", "About %s blocks away", blocks);
    }

    private void chooseKingdom(Optional<ResourceLocation> kingdom) {
        filter = kingdom;
        view = View.LIST;
        request(0);
    }

    public boolean receive(LedgerPagePacket newPage) {
        if (newPage.requestId() != pendingRequestId || !newPage.kingdomId().equals(pendingFilter)) return false;
        if (newPage.retryAfterTicks() > 0) {
            if (newPage.offset() != pendingOffset) return false;
            retryTicks = Math.max(1, Math.min(100, newPage.retryAfterTicks()));
            requestTimeoutTicks = retryTicks + REQUEST_TIMEOUT_TICKS;
            return false;
        }
        if (!newPage.revisionReset() && newPage.offset() != pendingOffset) return false;
        if (!newPage.revisionReset() && expectedRevision != 0 && newPage.registryRevision() != expectedRevision) {
            request(0);
            return false;
        }
        page = newPage;
        kingdoms = new ArrayList<>(newPage.kingdoms());
        filter = newPage.kingdomId();
        offset = newPage.offset();
        notice = newPage.notice().equals("kingdom_missing")
                ? l("notice.kingdom_missing", "The kingdom you chose is no longer defined on this server; showing all kingdoms.") : null;
        if (newPage.notice().equals("kingdom_missing")) filter = Optional.empty();
        loading = false;
        retryTicks = 0;
        requestTimeoutTicks = 0;
        requestAttempts = 0;
        requestError = null;
        if (selectedId != null) {
            // Kept by identity: refresh the summary, or explain that it is no longer listed here.
            var still = newPage.settlements().stream().limit(PAGE_SIZE).filter(s -> s.id().equals(selectedId)).findFirst();
            if (still.isPresent()) selected = still.get();
            else if (view == View.DETAILS) {
                view = View.LIST;
                selectedId = null;
                selected = null;
                notice = l("notice.gone", "The settlement you were reading is no longer on this page; it may have been renamed, merged or filtered out.");
            }
        }
        rebuildWidgets();
        return true;
    }

    @Override public void tick() {
        super.tick();
        if (search != null && listing()) search.tick();
        if (debounce > 0 && --debounce == 0 && !loading) request(0);
        if (!loading) return;
        if (retryTicks > 0) {
            retryTicks--;
            if (retryTicks == 0) retryPending();
            return;
        }
        if (requestTimeoutTicks > 0) {
            requestTimeoutTicks--;
            if (requestTimeoutTicks == 0) retryPending();
        }
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (view == View.KINGDOMS) {
            if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_LEFT) { changeKingdomPage(-1); return true; }
            if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_RIGHT) { changeKingdomPage(1); return true; }
            if (key == GLFW.GLFW_KEY_HOME) { setKingdomPage(0); return true; }
            if (key == GLFW.GLFW_KEY_END) { setKingdomPage(Integer.MAX_VALUE); return true; }
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && search != null && search.isFocused() && listing()) {
            debounce = 0;
            request(0);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void initNotes() {
        int x = book.leafX(true), y = book.contentTop() + 24, w = book.leafWidth();
        if (notes == null) notes = new RichPanel(x, y, w, book.height() - 76, l("notes.title", "Field notes")).manuscript();
        else notes.place(x, y, w, book.height() - 76);
        notes.setPieces(List.of(
                BookIllustrations.LEDGER.piece("landscape", w - 16),
                BookPieces.opening("notes", l("notes.opening", "Every journey leaves a record. These pages hold the settlements you have discovered, and the kingdoms to which they belong.")),
                Pieces.callout("reading", Pieces.Tone.TIP, null, l("notes.reading", "Choose a settlement on the facing page to read its record. Search, kingdom filters and distance sorting apply only to places you know.")),
                Pieces.actions("help", List.of(Pieces.button("guide:ledger_and_discovery", l("discover", "How discovery works"), l("help.tip", "How discovery works, in the Book."),
                        () -> minecraft.setScreen(new KingdomGuideScreen(this, "ledger_and_discovery")), true)))
        ), notes.anchor());
        addRenderableWidget(notes);
    }

    private void paperTitle(GuiGraphics g, net.minecraft.client.gui.Font font, Component text, int center, int y, int w) {
        BookSkin.title(g, font, text, center - w / 2, y, w);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = panelLeft(), right = panelRight(), top = panelTop(), bottom = panelBottom();
        BookSkin.cover(graphics, book.x(), book.y(), book.width(), book.height(), book.spread(), BookSkin.Binding.LEDGER);
        paperTitle(graphics, font, title, pageCenter(), top + 12, right - left - 24);
        if (view == View.DETAILS && selected != null) {
            int detailLeft = book.spread() ? book.leafX(true) - 10 : left;
            if (book.spread()) BookSkin.title(graphics, font, Component.translatable("menu.mcakingdoms.record_details"), book.leafX(true), top + 12, book.leafWidth());
            renderDetailHeader(graphics, selected, detailLeft);
        } else if (book.spread()) BookSkin.title(graphics, font, l("notes.title", "Field notes"), book.leafX(true), top + 12, book.leafWidth());
        if (view == View.KINGDOMS) {
            paperTitle(graphics, font, l("picker.title", "Choose a kingdom"), pageCenter(), top + 28, right - left - 24);
            for (int i = 0; i < pickerRows.size(); i++) HeraldryRenderer.renderInBook(graphics, pickerRows.get(i).heraldryIcon(), left + 13, top + 66 + i * ROW_HEIGHT, 16);
            int rows = Math.max(1, (bottom - 60 - (top + 64)) / ROW_HEIGHT);
            int pages = Math.max(1, (kingdoms.size() + rows - 1) / rows);
            paperTitle(graphics, font, l("picker.page", "Page %s of %s · %s kingdoms", kingdomPage + 1, pages, kingdoms.size()), pageCenter(), bottom - 48,
                    right - left - 32 - 2 * sideWidth(right - left - 20));
        }
        if (listing()) {
            renderListState(graphics, left, right, top, bottom);
            renderListScrollTrack(graphics, left, top, bottom);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderListState(GuiGraphics graphics, int left, int right, int top, int bottom) {
        int textWidth = right - left - 32;
        if (notice != null) graphics.drawWordWrap(font, notice, left + 16, top + 76, textWidth, BookSkin.RED);
        int stateTop = top + 84;
        if (loading) paperTitle(graphics, font, Component.translatable("screen.mcakingdoms.ledger.loading"), pageCenter(), stateTop, textWidth);
        else if (requestError != null) graphics.drawWordWrap(font, requestError, left + 16, stateTop, textWidth, BookSkin.RED);
        else if (page == null || page.settlements().isEmpty()) {
            // Four different situations, each with its own next step.
            Component heading, next;
            if (!query.isEmpty() || filter.isPresent()) {
                heading = l("empty.match", "No settlement you know matches");
                next = l("empty.match.next", "Check the spelling, try part of the name, or clear the search and kingdom.");
            } else {
                heading = Component.translatable("screen.mcakingdoms.ledger.empty");
                next = Component.translatable("screen.mcakingdoms.ledger.empty.next");
            }
            paperTitle(graphics, font, heading, pageCenter(), stateTop, textWidth);
            graphics.drawWordWrap(font, next, left + 16, stateTop + 14, textWidth, BookSkin.INK);
            if (page != null && !page.diagnostic().isEmpty())
                graphics.drawWordWrap(font, Component.literal(page.diagnostic()), left + 16, stateTop + 70, textWidth, BookSkin.RED);
        }
        if (page != null && !loading && requestError == null && page.total() > 0) {
            int pages = Math.max(1, (page.total() + PAGE_SIZE - 1) / PAGE_SIZE);
            paperTitle(graphics, font, l("page", "Page %s of %s · %s settlements", offset / PAGE_SIZE + 1, pages, page.total()), pageCenter(), bottom - 48, right - left - 32 - 2 * sideWidth(right - left - 20));
        }
    }

    /** A thin thumb distinguishes moving within this server result page from requesting the next server page. */
    private void renderListScrollTrack(GuiGraphics graphics, int left, int top, int bottom) {
        if (!hasLocalScrollbar()) return;
        int count = resultCount();
        int maxScroll = Math.max(0, count - visibleRows);
        BookSkin.scrollbar(graphics, scrollTrackX(), scrollTrackTop(), scrollTrackHeight(), count * ROW_HEIGHT,
                scrollIndex * ROW_HEIGHT, maxScroll * ROW_HEIGHT);
    }

    private void renderDetailHeader(GuiGraphics graphics, SettlementSummary settlement, int left) {
        KingdomSummary kingdom = kingdom(settlement.kingdomId());
        int y = panelTop() + 32;
        if (kingdom != null) HeraldryRenderer.renderInBook(graphics, kingdom.heraldryIcon(), left + 13, y, 22);
        var name = Language.getInstance().getVisualOrder(font.ellipsize(Component.literal(settlement.displayName()), book.leafWidth() - 38));
        graphics.drawString(font, name, left + 43, y + 2, BookSkin.INK, false);
        Component sub = kingdom == null ? l("kingdom.unknown.short", "Unknown kingdom") : Component.translatable(kingdom.translationKey());
        graphics.drawString(font, Language.getInstance().getVisualOrder(font.ellipsize(sub, book.leafWidth() - 38)), left + 43, y + 13, BookSkin.MUTED, false);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (listing() && delta != 0 && mouseX >= panelLeft() && mouseX < panelRight() && mouseY >= panelTop() + 78 && mouseY < panelBottom() - 59) {
            scroll(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hasLocalScrollbar() && mouseX >= scrollTrackX() && mouseX < scrollTrackX() + 8
                && mouseY >= scrollTrackTop() && mouseY < scrollTrackTop() + scrollTrackHeight()) {
            int thumbTop = scrollThumbTop();
            scrollDragOffset = Math.max(0, Math.min(scrollThumbHeight() - 1, (int) mouseY - thumbTop));
            draggingScroll = true;
            scrollToThumbTop((int) mouseY - scrollDragOffset);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll && button == 0) {
            scrollToThumbTop((int) mouseY - scrollDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScroll && button == 0) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean isPauseScreen() {
        return false;
    }

    private void pageSound() {
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1));
    }

    private void request(int requestedOffset) {
        if (initialRequestSent && requestedOffset != offset) pageSound();
        pendingFilter = filter;
        pendingOffset = requestedOffset;
        pendingQuery = query;
        pendingSort = sort;
        expectedRevision = page == null ? 0 : page.registryRevision();
        requestAttempts = 0;
        requestError = null;
        notice = null;
        scrollIndex = 0;
        loading = true;
        sendPendingRequest();
    }

    private void sendPendingRequest() {
        requestAttempts++;
        pendingRequestId = NetworkHandler.requestLedgerPage(pendingFilter, pendingOffset, expectedRevision, pendingQuery, pendingSort);
        retryTicks = 0;
        requestTimeoutTicks = REQUEST_TIMEOUT_TICKS;
        if (minecraft != null) rebuildWidgets();
    }

    private void retryPending() {
        if (requestAttempts < MAX_REQUEST_ATTEMPTS) {
            sendPendingRequest();
        } else {
            loading = false;
            requestError = Component.translatable("screen.mcakingdoms.ledger.request_failed");
            rebuildWidgets();
        }
    }

    private void select(SettlementSummary settlement) {
        pageSound();
        selected = settlement;
        selectedId = settlement.id();
        moreDetails = false;
        view = View.DETAILS;
        if (details != null) details.setPieces(List.of());
        rebuildWidgets();
    }

    private void showList() {
        pageSound();
        view = View.LIST;
        selectedId = null;
        selected = null;
        rebuildWidgets();
        if (!rowButtons.isEmpty()) setFocused(rowButtons.get(0));
    }

    private void scroll(int amount) {
        scrollToIndex(scrollIndex + amount);
    }

    private void scrollToThumbTop(int requestedTop) {
        int travel = scrollTrackHeight() - scrollThumbHeight();
        int maxScroll = Math.max(0, resultCount() - visibleRows);
        int top = Math.max(scrollTrackTop(), Math.min(scrollTrackTop() + travel, requestedTop));
        int next = travel <= 0 ? 0 : Math.round((top - scrollTrackTop()) * (float) maxScroll / travel);
        scrollToIndex(next);
    }

    private void scrollToIndex(int requestedIndex) {
        int maxScroll = Math.max(0, resultCount() - visibleRows);
        int next = Math.max(0, Math.min(maxScroll, requestedIndex));
        if (next != scrollIndex) {
            UUID focused = focusedSettlement();
            boolean searchFocused = search != null && search.isFocused();
            scrollIndex = next;
            rebuildWidgets();
            restoreScrollFocus(focused, searchFocused);
        }
    }

    private boolean hasLocalScrollbar() {
        return listing() && !loading && requestError == null && resultCount() > visibleRows;
    }

    private int scrollTrackX() { return panelLeft() + 10 + panelRight() - panelLeft() - 20 - 25; }
    private int scrollTrackTop() { return panelTop() + 100; }
    private int scrollTrackHeight() { return Math.max(1, panelBottom() - 54 - (panelTop() + 78) - 46); }
    private int scrollThumbHeight() {
        int height = scrollTrackHeight();
        return Math.min(height, Math.max(12, Math.min(height, height * height / Math.max(1, resultCount() * ROW_HEIGHT))));
    }
    private int scrollThumbTop() {
        int maxScroll = Math.max(0, resultCount() - visibleRows);
        int travel = scrollTrackHeight() - scrollThumbHeight();
        return scrollTrackTop() + (maxScroll == 0 ? 0 : Math.round(scrollIndex * (float) travel / maxScroll));
    }
    private UUID focusedSettlement() {
        int row = rowButtons.indexOf(getFocused());
        int index = scrollIndex + row;
        return row >= 0 && page != null && index < resultCount() ? page.settlements().get(index).id() : null;
    }
    private void restoreScrollFocus(UUID id, boolean searchFocused) {
        if (id != null && page != null) for (int i = 0; i < rowButtons.size(); i++)
            if (page.settlements().get(scrollIndex + i).id().equals(id)) { setFocused(rowButtons.get(i)); return; }
        if (searchFocused && search != null) setFocused(search);
    }

    private void changeKingdomPage(int amount) {
        setKingdomPage(kingdomPage + amount, amount);
    }

    private void setKingdomPage(int requestedPage) {
        setKingdomPage(requestedPage, 0);
    }

    private void setKingdomPage(int requestedPage, int direction) {
        int navigationY = panelBottom() - 54;
        int rows = Math.max(1, (navigationY - 6 - (panelTop() + 64)) / ROW_HEIGHT);
        int pages = Math.max(1, (kingdoms.size() + rows - 1) / rows);
        int next = Math.max(0, Math.min(pages - 1, requestedPage));
        if (next != kingdomPage) {
            kingdomPage = next;
            rebuildWidgets();
            // Rebuild creates fresh controls, so keep keyboard navigation on the matching live pager.
            Button focus = direction < 0 ? kingdomPrevious : kingdomNext;
            if (!focus.active) focus = focus == kingdomNext ? kingdomPrevious : kingdomNext;
            if (focus.active) setFocused(focus);
        }
    }

    private Component dimensionName(ResourceLocation dimension) {
        if (dimension.equals(Level.OVERWORLD.location())) return Component.translatable("dimension.mcakingdoms.overworld");
        if (dimension.equals(Level.NETHER.location())) return Component.translatable("dimension.mcakingdoms.the_nether");
        if (dimension.equals(Level.END.location())) return Component.translatable("dimension.mcakingdoms.the_end");
        String translationKey = Util.makeDescriptionId("dimension", dimension);
        if (Language.getInstance().has(translationKey)) return Component.translatable(translationKey);
        return Component.translatable("screen.mcakingdoms.ledger.dimension_fallback", friendlyName(dimension.getPath()), dimension.getNamespace());
    }

    private static String friendlyName(String path) {
        String[] words = path.replace('/', ' ').replace('_', ' ').strip().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    /** Name, kingdom and how far: the facts that pick a settlement out of a list. */
    private Component rowLabel(SettlementSummary settlement) {
        KingdomSummary kingdom = kingdom(settlement.kingdomId());
        Component kingdomName = kingdom == null ? l("kingdom.unknown.short", "Unknown kingdom") : Component.translatable(kingdom.translationKey());
        return l("row.v2", "%s · %s · %s", settlement.displayName(), kingdomName, distance(settlement));
    }

    private Component filterLabel() {
        if (filter.isEmpty()) return l("all", "All kingdoms");
        KingdomSummary kingdom = kingdom(filter.get());
        return kingdom == null ? Component.literal(filter.get().toString()) : Component.translatable(kingdom.translationKey());
    }

    private KingdomSummary kingdom(ResourceLocation id) {
        for (KingdomSummary kingdom : kingdoms) if (kingdom.id().equals(id)) return kingdom;
        return null;
    }

    private int resultCount() {
        return page == null ? 0 : Math.min(PAGE_SIZE, page.settlements().size());
    }
}
