package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.client.guide.GuideArticle;
import dev.otectus.mcakingdoms.client.book.*;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.guide.KingdomGuide;
import dev.otectus.mcakingdoms.guide.KingdomGuide.Entry;
import dev.otectus.mcakingdoms.interaction.SearchText;
import dev.otectus.mcakingdoms.presentation.PresentationConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.io.*;
import java.util.*;

/**
 * The Book of Kingdoms reader: searchable chapters and mixed-content articles (text, live recipes, items, diagrams,
 * links). Reading never sends a command or changes the world; task links open the server-reviewed Kingdom Tasks.
 *
 * <p>Back returns to the previous topic with its search, chapter, contents page and reading position; Home opens Start
 * here; Close returns to the screen that opened the book. Bookmarks, recent topics and the last-read topic are kept on
 * this computer ({@link ClientState}). Narrow windows switch between the contents and the article instead of shrinking.
 */
public final class KingdomGuideScreen extends Screen implements GuideArticle.Reader {
    private enum Contents { CHAPTERS, TOPICS, SAVED }
    /** Where the reader was: what Back restores. */
    private record Place(String topic, String query, String category, Contents contents, int offset, RichPanel.Anchor anchor) { }
    private static final int MAX_HISTORY = 32;

    private final Screen parent;
    private final List<Entry> entries;
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final Deque<Place> history = new ArrayDeque<>();
    private String query = "", category = "", selected;
    private Contents contents = Contents.CHAPTERS;
    private boolean showCommands, narrow, showingContents, updatingSearch;
    private int offset, rows, left, panelWidth, sidebar, listTop;
    private BookLayout book;
    private EditBox search;
    private RichPanel article;
    /** The topic and commands state the article pieces were built for. */
    private String articleTopic;
    private boolean articleCommands;
    private List<Entry> matches = List.of();
    private final Map<String, AbstractWidget> controls = new LinkedHashMap<>();

    public KingdomGuideScreen(Screen parent) {
        this(parent, null);
    }

    /** Opens at {@code topic} (a screen's Help button) or, when null or unknown, at the last-read topic. */
    public KingdomGuideScreen(Screen parent, String topic) {
        super(Component.translatable("item.mcakingdoms.book_of_kingdoms"));
        this.parent = parent;
        // Reopening the Book is a deliberate retry of a gallery that failed to load.
        dev.otectus.mcakingdoms.client.guide.GuideKingdoms.reopened();
        var library = dev.otectus.mcakingdoms.client.guide.GuideLibrary.get();
        entries = library.entries();
        byId.putAll(library.byId());
        problems.addAll(library.problems());
        ClientState state = ClientState.get();
        state.pruneTopics(byId::containsKey);
        selected = topic != null && byId.containsKey(topic) ? topic : byId.containsKey(state.lastRead()) ? state.lastRead()
                : entries.isEmpty() ? "start_here" : entries.get(0).id();
        if (topic != null && byId.containsKey(topic)) {
            category = byId.get(topic).category();
            contents = Contents.TOPICS;
        }
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("guide.mcakingdoms." + key, args);
    }

    private static Component label(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("guide.mcakingdoms." + key, fallback, args);
    }

    // ------------------------------------------------------------------ reader callbacks

    @Override public Optional<Entry> topic(String id) { return Optional.ofNullable(byId.get(id)); }
    @Override public void openTopic(String id) { if (byId.containsKey(id)) go(id, true); }
    @Override public void openTask(String task) { InteractionClient.open(this, task.equals("*") ? "" : task, ""); }
    @Override public boolean operator() { return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2); }
    @Override public boolean commandsShown() { return showCommands; }
    @Override public void toggleCommands() { showCommands = !showCommands; rebuild(); article.focusTarget("commands_toggle"); }
    @Override public boolean reducedMotion() { return PresentationConfig.REDUCED_MOTION.get(); }

    // ------------------------------------------------------------------ layout

    private List<Entry> filtered() {
        var tokens = SearchText.tokens(query);
        var ranked = new ArrayList<Map.Entry<Integer, Entry>>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!category.isEmpty() && !entry.category().equals(category)) continue;
            var rank = entry.rank(tokens);
            if (rank != KingdomGuide.Rank.NONE) ranked.add(Map.entry(rank.ordinal() * 10_000 + i, entry));
        }
        ranked.sort(Map.Entry.comparingByKey());
        return ranked.stream().map(Map.Entry::getValue).toList();
    }

    private List<Entry> saved() {
        var out = new LinkedHashMap<String, Entry>();
        ClientState.get().bookmarks().forEach(id -> { if (byId.containsKey(id)) out.put(id, byId.get(id)); });
        ClientState.get().recentTopics().forEach(id -> { if (byId.containsKey(id)) out.putIfAbsent(id, byId.get(id)); });
        return List.copyOf(out.values());
    }

    private <T extends AbstractWidget> T control(String role, T widget) {
        controls.put(role, widget);
        return addRenderableWidget(widget);
    }

    @Override protected void init() {
        controls.clear();
        book = BookLayout.fit(width, height);
        panelWidth = book.width();
        left = book.x();
        narrow = !book.spread();
        sidebar = book.leafWidth();
        matches = filtered();
        int x = book.leafX(false), y = book.contentTop(), w = book.leafWidth();
        boolean showList = !narrow || showingContents, showArticle = !narrow || !showingContents;
        int navY = narrow ? y : y + 18;
        int navX = x;
        if (narrow) {
            control("pane", BookButton.builder(showingContents ? label("pane.article", "Article") : label("pane.contents", "Contents"), b -> {
                showingContents = !showingContents; rebuild();
            }).bounds(navX, navY, 62, 20).tooltip(Tooltip.create(label("pane.help", "Switch between the list of topics and the article."))).build());
            navX += 66;
        }
        int navWidth = narrow ? 40 : (w - 4) / 2;
        var home = control("home", BookButton.builder(label("home", "Home"), b -> home()).bounds(navX, navY, navWidth, 20)
                .tooltip(Tooltip.create(label("home.help", "Open Start here and the chapter list."))).build());
        home.active = !"start_here".equals(selected) || contents != Contents.CHAPTERS || !query.isEmpty();
        var back = control("back", BookButton.builder(label("back", "Back"), b -> back()).bounds(navX + navWidth + 4, navY, navWidth, 20)
                .tooltip(Tooltip.create(history.isEmpty() ? label("back.none", "Nothing to go back to yet.")
                        : label("back.help", "Return to %s, with its search, chapter and reading position.", title(history.peek().topic())))).build());
        back.active = !history.isEmpty();
        int searchX = narrow ? navX + 2 * (navWidth + 4) : x;
        int searchY = narrow ? navY : y + 42, searchWidth = narrow ? x + w - searchX : w;
        if (search == null) {
            search = new BookSearchBox(font, searchX, searchY, searchWidth, 20, label("search"));
            search.setMaxLength(100);
            search.setResponder(value -> {
                if (updatingSearch) return;
                query = value; category = ""; contents = Contents.TOPICS; offset = 0;
                if (narrow) showingContents = true;
                rebuild();
            });
        }
        search.setX(searchX); search.setY(searchY); search.setWidth(searchWidth);
        if (!search.getValue().equals(query)) {
            updatingSearch = true;
            try { search.setValue(query); } finally { updatingSearch = false; }
        }
        control("search", search);
        if (showList) initContents(narrow ? y + 25 : y + 66, narrow ? y + 63 : y + 104);
        if (showArticle) initArticle(book.leafX(true), w);
        else if (article != null) article.visible = false;
        if (narrow && showingContents)
            control("close", BookButton.builder(label("close"), b -> onClose()).bounds(x + w - 52, book.footer(), 52, 20).build());
        if (!narrow) {
            String[] marks = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII"};
            for (int i = 0; i < KingdomGuide.CATEGORIES.size(); i++) {
                String choice = KingdomGuide.CATEGORIES.get(i);
                control("tab:" + choice, BookButton.builder(label("category." + choice), b -> {
                    category = choice; query = ""; contents = Contents.TOPICS; offset = 0; rebuild();
                }).bounds(book.x() - 8, book.y() + 29 + i * 23, 23, 21)
                        .tooltip(Tooltip.create(label("category." + choice))).build().kind(BookButton.Kind.RIBBON).glyph(marks[i])
                        .selected(choice.equals(category.isEmpty() && byId.containsKey(selected) ? byId.get(selected).category() : category)));
            }
        }
    }

    private void initContents(int buttonY, int top) {
        int x = book.leafX(false), w = book.leafWidth(), half = (w - 4) / 2;
        control("chapters", BookButton.builder(label("chapters"), b -> { contents = Contents.CHAPTERS; offset = 0; rebuild(); })
                .bounds(x, buttonY, half, 20).build().selected(contents == Contents.CHAPTERS)).active = contents != Contents.CHAPTERS;
        control("saved", BookButton.builder(label("saved", "Saved"), b -> { contents = Contents.SAVED; offset = 0; rebuild(); })
                .bounds(x + half + 4, buttonY, w - half - 4, 20)
                .tooltip(Tooltip.create(label("saved.help", "Your bookmarks and recently read topics, kept on this computer."))).build().selected(contents == Contents.SAVED)).active = contents != Contents.SAVED;
        listTop = top + (frontispiece() ? Math.min(68, w / 3) + 8 : 0);
        rows = Math.max(1, (book.footer() - 8 - listTop) / 21);
        List<Runnable> actions = new ArrayList<>();
        List<Component> labels = new ArrayList<>(), tips = new ArrayList<>();
        List<Boolean> current = new ArrayList<>();
        switch (contents) {
            case CHAPTERS -> {
                var choices = new ArrayList<String>();
                choices.add(""); choices.addAll(KingdomGuide.CATEGORIES);
                for (String choice : choices) {
                    labels.add(choice.isEmpty() ? label("all_topics") : label("category." + choice));
                    long count = entries.stream().filter(e -> choice.isEmpty() || e.category().equals(choice)).count();
                    tips.add(label("chapter.count", "%s topics", count));
                    actions.add(() -> { category = choice; query = ""; contents = Contents.TOPICS; offset = 0; rebuild(); });
                    current.add(false);
                }
            }
            case TOPICS, SAVED -> {
                for (Entry entry : contents == Contents.TOPICS ? matches : saved()) {
                    labels.add(ClientState.get().bookmarked(entry.id()) ? Component.literal("★ ").append(entry.title()) : Component.literal(entry.title()));
                    tips.add(Component.literal(entry.title() + " — " + entry.summary()));
                    actions.add(() -> go(entry.id(), true)); current.add(entry.id().equals(selected));
                }
            }
        }
        offset = Math.min(offset, Math.max(0, ((Math.max(1, labels.size()) - 1) / rows) * rows));
        for (int i = 0; i < rows && i + offset < labels.size(); i++) {
            int index = i + offset;
            var row = BookButton.builder(labels.get(index), b -> actions.get(index).run()).bounds(x, listTop + i * 21, w, 20)
                    .tooltip(Tooltip.create(tips.get(index))).build().kind(BookButton.Kind.INDEX).selected(current.get(index));
            row.active = !current.get(index);
            control("row:" + index, row);
        }
        if (contents == Contents.TOPICS && labels.isEmpty() && !query.isEmpty())
            control("clear", BookButton.builder(label("clear_search", "Clear search"), b -> { query = ""; contents = Contents.CHAPTERS; offset = 0; rebuild(); })
                    .bounds(x, listTop + 44, w, 20).build());
        int pages = Math.max(1, (labels.size() + rows - 1) / rows), page = offset / rows + 1;
        control("contents_previous", BookButton.builder(label("previous_contents"), b -> { offset = Math.max(0, offset - rows); turnSound(); rebuild(); })
                .bounds(x, book.footer(), 24, 20).tooltip(Tooltip.create(label("previous_contents"))).build().glyph("‹")).active = offset > 0;
        control("contents_next", BookButton.builder(label("next_contents"), b -> { offset += rows; turnSound(); rebuild(); })
                .bounds(x + 28, book.footer(), 24, 20).tooltip(Tooltip.create(label("next_contents"))).build().glyph("›")).active = offset + rows < labels.size();
        contentsPage = label("contents_page", "Page %s of %s", page, pages);
    }

    private boolean frontispiece() { return !narrow && contents == Contents.CHAPTERS && book.height() >= 340; }
    private Component contentsPage = Component.empty();

    private int headingTop() { return book.contentTop() + (narrow ? 26 : 13); }
    private List<net.minecraft.util.FormattedCharSequence> headingLines() {
        return font.split(Component.literal(title(selected)).withStyle(ChatFormatting.BOLD), book.leafWidth() - 6);
    }

    private void initArticle(int x, int w) {
        Entry current = byId.get(selected);
        Component heading = current == null ? getTitle() : Component.literal(current.title());
        int panelTop = headingTop() + headingLines().size() * 11 + 19;
        int panelHeight = Math.max(36, book.footer() - 26 - panelTop);
        if (article == null) article = new RichPanel(x, panelTop, w, panelHeight, heading).manuscript();
        else article.place(x, panelTop, w, panelHeight);
        article.visible = true;
        article.setMessage(heading);
        if (!Objects.equals(articleTopic, selected) || articleCommands != showCommands) {
            boolean sameTopic = Objects.equals(articleTopic, selected);
            articleTopic = selected; articleCommands = showCommands;
            article.setPieces(current == null ? List.of(dev.otectus.mcakingdoms.client.rich.Pieces.text("unavailable", label("unavailable")))
                    : GuideArticle.build(current, this, !problems.isEmpty()), sameTopic ? article.anchor() : pendingAnchor);
            pendingAnchor = null;
        }
        control("article", article);
        if (current != null) {
            boolean marked = ClientState.get().bookmarked(current.id());
            control("bookmark", BookButton.builder(marked ? label("bookmark.remove", "★ Bookmarked") : label("bookmark.add", "☆ Bookmark"), b -> {
                ClientState.get().toggleBookmark(current.id()); rebuild();
            }).bounds(book.x() + book.width() - 18, book.y() + 17, 16, 29)
                    .tooltip(Tooltip.create(marked ? label("bookmark.remove.help", "Remove this topic from your bookmarks.")
                            : label("bookmark.add.help", "Keep this topic under Saved for quick access."))).build().kind(BookButton.Kind.RIBBON).glyph(marked ? "★" : "☆"));
        }
        control("page_previous", BookButton.builder(label("leaf.previous"), b -> turnLeaf(-1)).bounds(x, book.footer() - 23, 26, 19)
                .tooltip(Tooltip.create(label("leaf.previous.help"))).build().glyph("‹"));
        control("page_next", BookButton.builder(label("leaf.next"), b -> turnLeaf(1)).bounds(x + w - 26, book.footer() - 23, 26, 19)
                .tooltip(Tooltip.create(label("leaf.next.help"))).build().glyph("›"));
        List<Entry> ordered = ordered();
        int index = current == null ? -1 : ordered.indexOf(current);
        control("previous", BookButton.builder(label("previous_topic.arrow", "◀ Previous topic"), b -> go(ordered.get(index - 1).id(), true))
                .bounds(x, book.footer(), 26, 20).tooltip(Tooltip.create(index > 0 ? Component.literal(ordered.get(index - 1).title())
                        : label("previous_topic.none", "This is the first topic here."))).build().glyph("«")).active = index > 0;
        control("next", BookButton.builder(label("next_topic.arrow", "Next topic ▶"), b -> go(ordered.get(index + 1).id(), true))
                .bounds(x + 30, book.footer(), 26, 20).tooltip(Tooltip.create(index >= 0 && index + 1 < ordered.size()
                        ? Component.literal(ordered.get(index + 1).title()) : label("next_topic.none", "This is the last topic here."))).build().glyph("»")).active = index >= 0 && index + 1 < ordered.size();
        boolean operatorOnly = current != null && current.operatorTask() && !operator();
        String target = current == null ? "" : operatorOnly ? current.playerTask() : current.task();
        Component openLabel = !operatorOnly ? label("open_task") : target.isEmpty() ? label("operators_only") : label("open_related_task");
        Component help = target.isEmpty() ? label("open_task.none", "This topic has no task of its own; links inside the article lead to related ones.")
                : label("open_task.target", "Opens %s in Kingdom Tasks; the server checks your access.", GuideArticle.taskTitle(target));
        control("open", BookButton.builder(openLabel, b -> { if (!target.isEmpty()) openTask(target); })
                .bounds(x + 60, book.footer(), w - 118, 20).tooltip(Tooltip.create(help)).build()).active = !target.isEmpty() && Minecraft.getInstance().player != null;
        control("close", BookButton.builder(label("close"), b -> onClose()).bounds(x + w - 54, book.footer(), 54, 20)
                .tooltip(Tooltip.create(parent == null ? label("close.game", "Close the book and return to the game.") : label("close.parent", "Close the book and return to the previous screen."))).build());
    }

    private void turnSound() {
        if (minecraft != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1));
    }

    private void turnLeaf(int direction) {
        if (article != null && article.visible && article.turnPage(direction)) turnSound();
    }

    private RichPanel.Anchor pendingAnchor;

    private List<Entry> ordered() {
        if (contents == Contents.TOPICS && !matches.isEmpty() && (!query.isEmpty() || !category.isEmpty())) return matches;
        return entries;
    }

    private String title(String topic) {
        Entry entry = byId.get(topic);
        return entry == null ? topic : entry.title();
    }

    // ------------------------------------------------------------------ navigation

    private Place here() {
        return new Place(selected, query, category, contents, offset, article == null ? null : article.anchor());
    }

    /** Opens a topic; {@code remember} keeps the current place for Back. */
    private void go(String topic, boolean remember) {
        if (remember && !topic.equals(selected)) {
            history.push(here());
            while (history.size() > MAX_HISTORY) history.removeLast();
        }
        if (!topic.equals(selected)) turnSound();
        if (contents == Contents.TOPICS && (!category.isEmpty() || !query.isEmpty())
                && matches.stream().noneMatch(entry -> entry.id().equals(topic))) {
            query = "";
            category = byId.get(topic).category();
            offset = 0;
        }
        selected = topic;
        showCommands = false;
        ClientState.get().readTopic(topic);
        if (narrow) showingContents = false;
        rebuild();
        setFocused(article);
    }

    private void home() {
        history.push(here());
        while (history.size() > MAX_HISTORY) history.removeLast();
        query = "";
        category = "";
        contents = Contents.CHAPTERS;
        offset = 0;
        go("start_here", false);
    }

    private void back() {
        if (history.isEmpty()) return;
        Place place = history.pop();
        query = place.query();
        category = place.category();
        contents = place.contents();
        offset = place.offset();
        boolean changed = !place.topic().equals(selected);
        selected = place.topic();
        showCommands = false;
        if (changed) pendingAnchor = place.anchor();
        if (narrow) showingContents = false;
        rebuild();
        if (!changed && place.anchor() != null) article.setPieces(article.pieces(), place.anchor());
        setFocused(controls.get("back") != null && controls.get("back").active ? controls.get("back") : article);
    }

    /** Rebuilds the widgets, keeping the article's reading position, its focused link and the focused control. */
    private void rebuild() {
        String focusedRole = null;
        GuiEventListener focused = getFocused();
        for (var entry : controls.entrySet()) if (entry.getValue() == focused) focusedRole = entry.getKey();
        String focusedTarget = article == null ? null : article.focusedTarget();
        rebuildWidgets();
        AbstractWidget widget = focusedRole == null ? null : controls.get(focusedRole);
        if (widget != null && widget.visible && widget.active) {
            setFocused(widget);
            if (widget == article && focusedTarget != null) article.focusTarget(focusedTarget);
        } else if (focusedRole != null && focusedRole.equals("search")) setFocused(search);
    }

    /** Lays the article out again with fresh data (recipes synchronized, gallery arrived), keeping the reading position. */
    public void refreshArticle() {
        Entry current = byId.get(selected);
        if (article == null || current == null) return;
        article.setPieces(GuideArticle.build(current, this, !problems.isEmpty()), article.anchor());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        BookSkin.cover(graphics, book.x(), book.y(), book.width(), book.height(), book.spread());
        int lx = book.leafX(false), w = book.leafWidth();
        if (!narrow) BookSkin.title(graphics, font, getTitle().copy().withStyle(ChatFormatting.BOLD), lx, book.contentTop(), w);
        if (!narrow || showingContents) {
            Component heading = switch (contents) {
                case CHAPTERS -> label("choose_chapter");
                case TOPICS -> category.isEmpty() || !query.isEmpty() ? label("results", matches.size()) : label("category." + category);
                case SAVED -> label("saved.heading", "Bookmarks and recent topics");
            };
            int headingY = book.contentTop() + (narrow ? 50 : 92);
            BookSkin.title(graphics, font, heading, lx, headingY, w);
            if (frontispiece()) {
                int iy = book.contentTop() + 104, ih = Math.min(68, w / 3);
                BookIllustrations.CHRONICLE.render(graphics, lx, iy, w, ih);
                if (mouseX >= lx && mouseX < lx + w && mouseY >= iy && mouseY < iy + ih)
                    setTooltipForNextRenderPass(BookIllustrations.CHRONICLE.description());
            }
            if (contents == Contents.TOPICS && matches.isEmpty())
                graphics.drawWordWrap(font, query.isEmpty() ? label("no_results") : label("no_results.help", "No topics match “%s”. Try fewer or different words, such as job, treaty or recipe.", query), lx + 2, listTop + 2, w - 4, BookSkin.INK);
            if (contents == Contents.SAVED && saved().isEmpty())
                graphics.drawWordWrap(font, label("saved.empty", "No saved topics yet. Use ☆ Bookmark on a topic, or read a few topics to see them here."), lx + 2, listTop + 2, w - 4, BookSkin.INK);
            BookSkin.title(graphics, font, contentsPage, lx + 58, book.footer() + 6, w - 58 - (narrow ? 58 : 0));
        }
        if (!narrow || !showingContents) {
            int x = book.leafX(true), headingY = headingTop();
            Entry current = byId.get(selected);
            if (!narrow && current != null) BookSkin.title(graphics, font, label("category." + current.category()), x, book.contentTop(), w);
            var lines = headingLines();
            for (int i = 0; i < lines.size(); i++) graphics.drawString(font, lines.get(i), x + (w - font.width(lines.get(i))) / 2, headingY + i * 11, BookSkin.RED, false);
            int positionY = headingY + lines.size() * 11 + 1;
            List<Entry> ordered = ordered();
            int index = current == null ? -1 : ordered.indexOf(current);
            BookSkin.title(graphics, font, label("folio.topic", index + 1, ordered.size()), x, positionY, w);
            BookSkin.divider(graphics, x + 4, positionY + 11, w - 8);
            BookSkin.title(graphics, font, label("folio.page", article.pageIndex() + 1, article.pageCount()), x + 30, book.footer() - 17, w - 60);
            controls.get("page_previous").active = article.scroll() > 0;
            controls.get("page_next").active = article.pageIndex() + 1 < article.pageCount();
        }
        super.render(graphics, mouseX, mouseY, partial);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_F && (Screen.hasControlDown() || (modifiers & GLFW.GLFW_MOD_CONTROL) != 0)) { setFocused(search); return true; }
        if (key == GLFW.GLFW_KEY_LEFT && (Screen.hasAltDown() || (modifiers & GLFW.GLFW_MOD_ALT) != 0)) { back(); return true; }
        if (search == null || !search.isFocused()) {
            if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
                int direction = key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1;
                if (narrow && showingContents) {
                    var button = controls.get(direction > 0 ? "contents_next" : "contents_previous");
                    if (button instanceof Button page && page.active) page.onPress();
                } else turnLeaf(direction);
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void tick() {
        if (search != null) search.tick();
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() {
        return false;
    }
}
