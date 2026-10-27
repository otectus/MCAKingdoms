package dev.otectus.mcakingdoms.clienttest;

import dev.otectus.mcakingdoms.client.ClientState;
import dev.otectus.mcakingdoms.client.KingdomGuideScreen;
import dev.otectus.mcakingdoms.client.VillageLedgerScreen;
import dev.otectus.mcakingdoms.client.book.BookLayout;
import dev.otectus.mcakingdoms.client.book.BookButton;
import dev.otectus.mcakingdoms.client.guide.GuideLibrary;
import dev.otectus.mcakingdoms.network.LedgerPagePacket;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.api.AssignmentSource;
import net.minecraft.core.BlockPos;
import dev.otectus.mcakingdoms.client.guide.GuideKingdoms;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.guide.GuideRecipe;
import dev.otectus.mcakingdoms.guide.KingdomGuide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

/**
 * Isolated packaged-client acceptance for the real Book of Kingdoms item and reader: recipes rendered from the recipes
 * the integrated server synchronizes (including a data-pack replacement and removal applied with /reload while the Book
 * is open), the kingdom gallery from the server, links, Back and Home, bookmarks kept in the local state file, synonym
 * search, collapsed commands, the compact and narrow layouts, keyboard link activation and reopening at the last topic.
 */
public final class BookGuideClientHarness {
    private static final ResourceLocation BOOK_ID = new ResourceLocation("mcakingdoms", "book_of_kingdoms");
    private static final ResourceLocation LEDGER_ID = new ResourceLocation("mcakingdoms", "village_ledger");
    private static final List<String> SCREENSHOTS = List.of(
            "01-book-start.png", "02-recipe-ledger.png", "03-recipe-replaced.png", "04-recipe-removed.png", "05-recipe-book.png",
            "06-saved.png", "07-search-synonym.png", "08-kingdom-gallery.png", "09-commands.png", "10-book-compact.png",
            "11-book-narrow-contents.png", "12-book-narrow-article.png", "13-book-reopened.png",
            "14-manuscript-spread.png", "15-ledger-spread.png", "16-ledger-record.png", "17-book-scale-one.png",
            "18-book-spanish.png", "19-book-chinese.png", "20-book-small-window.png", "21-ledger-compact.png", "22-ledger-live-record.png");

    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("mcakingdoms.clientTest.output"));
    private final long started = System.currentTimeMillis();
    private final List<String> evidence = new ArrayList<>();
    private int stage, ticks, entered;
    private boolean creating;
    private volatile boolean finished;
    private volatile boolean seeded, recipeChecked, reloaded;
    private String lastTopic;
    private volatile boolean languageReady;
    private volatile boolean seededLedger;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 300_000L)
                throw new AssertionError("Book guide client timeout at stage " + stage + " screen=" + mc.screen);
            // Resource reload completion precedes the loading overlay's fade. Capture only fully rendered book frames.
            if (mc.getOverlay() != null) { entered = ticks; return; }
            if (!creating && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) createWorld();
            if (!creating || ticks - entered < 30) return;

            switch (stage) {
                case 0 -> {
                    if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
                    mc.setScreen(null);
                    mc.getSingleplayerServer().execute(this::seedBookAndCheckRecipe);
                    next();
                }
                case 1 -> {
                    Item book = book();
                    if (!seeded || mc.player == null || !mc.player.getMainHandItem().is(book) || mc.gameMode == null) return;
                    check(recipeChecked, "Integrated server recipe registry contains the Book of Kingdoms output");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    next();
                }
                case 2 -> {
                    KingdomGuideScreen screen = guide();
                    List<KingdomGuide.Entry> entries = entries(screen);
                    check(entries.size() >= 70, "Packaged guide loads every topic, old and new: " + entries.size());
                    for (String id : List.of("start_here", "two_books", "book_recipe", "ledger_recipe", "tasks_workflow", "identity_government_control"))
                        check(entries.stream().anyMatch(e -> e.id().equals(id)), "Topic loaded: " + id);
                    check(((List<?>) field(KingdomGuideScreen.class, screen, "problems")).isEmpty(), "All packaged guide resources parsed");
                    check("start_here".equals(selected(screen)), "Book opens at Start here");
                    RichPanel article = article(screen);
                    check(article.targets().stream().anyMatch(t -> t.key().equals("guide:ledger_recipe")), "Start here links to the ledger recipe");
                    shot("01-book-start.png");
                    // Keyboard: focus the article, select the link and press Enter.
                    ((Screen) screen).setFocused(article);
                    check(article.focusTarget("guide:ledger_recipe"), "The link can take keyboard focus");
                    check(((Screen) screen).keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0), "Enter activates the focused link");
                    next();
                }
                case 3 -> {
                    KingdomGuideScreen screen = guide();
                    check("ledger_recipe".equals(selected(screen)), "The link opened the ledger recipe topic");
                    check(!((java.util.Deque<?>) field(KingdomGuideScreen.class, screen, "history")).isEmpty(), "Following a link records Back history");
                    GuideRecipe recipe = recipe(screen);
                    check(recipe.status() == GuideRecipe.Status.AVAILABLE && recipe.layout() == GuideRecipe.Layout.SHAPED && recipe.width() == 3 && recipe.height() == 3,
                            "The ledger recipe comes from the synchronized recipes as a 3 by 3 shaped grid: " + recipe.status() + " " + recipe.width() + "x" + recipe.height());
                    check(recipe.result().getCount() == 1 && recipe.station() == GuideRecipe.Station.CRAFTING_TABLE, "Output count and crafting table");
                    String narration = article(screen).narrationText().getString();
                    check(narration.contains("Row 3: empty, Compass, empty") && narration.contains("5 × Paper"), "Recipe narration reads the arrangement and ingredients");
                    evidence.add("Ledger recipe narration: " + narration);
                    shot("02-recipe-ledger.png");
                    mc.getSingleplayerServer().execute(() -> writePack("""
                            {"type":"minecraft:crafting_shaped","pattern":["PP","BC"],"key":{"P":{"item":"minecraft:paper"},"B":{"item":"minecraft:book"},"C":{"item":"minecraft:compass"}},
                             "result":{"item":"mcakingdoms:village_ledger","count":2}}"""));
                    next();
                }
                case 4 -> {
                    if (!reloaded) return;
                    KingdomGuideScreen screen = guide();
                    GuideRecipe recipe = recipe(screen);
                    if (recipe.width() != 2) { if (ticks - entered > 400) throw new AssertionError("The Book did not show the replaced recipe: " + recipe.width()); return; }
                    check(recipe.height() == 2 && recipe.result().getCount() == 2 && recipe.station() == GuideRecipe.Station.INVENTORY_OR_TABLE,
                            "After /reload the open Book shows the server's replacement: 2 by 2, makes 2, inventory grid");
                    check("ledger_recipe".equals(selected(screen)), "The reader stayed on its topic while the recipes changed");
                    // Each screenshot is taken at the start of the following stage: a grab reads the last frame drawn, so a
                    // shot in the same tick as a change would show the state before it.
                    next();
                }
                case 5 -> {
                    shot("03-recipe-replaced.png");
                    mc.getSingleplayerServer().execute(() -> writePack("{\"conditions\":[{\"type\":\"forge:false\"}],\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:paper\"}],\"result\":{\"item\":\"mcakingdoms:village_ledger\"}}"));
                    next();
                }
                case 6 -> {
                    if (!reloaded) return;
                    KingdomGuideScreen screen = guide();
                    GuideRecipe recipe = recipe(screen);
                    if (recipe.status() != GuideRecipe.Status.REMOVED) { if (ticks - entered > 400) throw new AssertionError("The removed recipe was still shown: " + recipe.status()); return; }
                    String narration = article(screen).narrationText().getString();
                    check(narration.contains("This recipe is unavailable on this server."), "A removed recipe is never presented as available");
                    next();
                }
                case 7 -> {
                    shot("04-recipe-removed.png");
                    mc.getSingleplayerServer().execute(this::removePack);
                    next();
                }
                case 8 -> {
                    if (!reloaded) return;
                    KingdomGuideScreen screen = guide();
                    GuideRecipe recipe = recipe(screen);
                    if (recipe.status() != GuideRecipe.Status.AVAILABLE || recipe.width() != 3) { if (ticks - entered > 400) throw new AssertionError("The shipped recipe did not return"); return; }
                    check(true, "Removing the data pack restores the shipped recipe");
                    click(label("guide.mcakingdoms.back", "Back"));
                    check("start_here".equals(selected(screen)), "Back returns to the previous topic");
                    ((KingdomGuideScreen) screen).openTopic("book_recipe");
                    GuideRecipe book = recipe(screen);
                    check(book.status() == GuideRecipe.Status.AVAILABLE && book.layout() == GuideRecipe.Layout.SHAPELESS && book.slots().size() == 2
                            && book.station() == GuideRecipe.Station.INVENTORY_OR_TABLE, "The Book's own recipe: shapeless, two ingredients, inventory grid");
                    check(article(screen).narrationText().getString().contains("Any arrangement"), "Shapeless recipes say position does not matter");
                    next();
                }
                case 9 -> {
                    shot("05-recipe-book.png");
                    click(label("guide.mcakingdoms.bookmark.add", "☆ Bookmark"));
                    check(ClientState.get().bookmarked("book_recipe"), "Bookmark recorded");
                    Path state = mc.gameDirectory.toPath().resolve("config").resolve(ClientState.FILE_NAME);
                    check(Files.isRegularFile(state) && Files.readString(state).contains("book_recipe"), "Bookmarks are kept in the local state file");
                    click(label("guide.mcakingdoms.saved", "Saved"));
                    check(buttons().stream().anyMatch(b -> b.contains("Craft the Book of Kingdoms")), "Saved lists the bookmark: " + buttons());
                    next();
                }
                case 10 -> {
                    shot("06-saved.png");
                    KingdomGuideScreen screen = guide();
                    click(label("guide.mcakingdoms.home", "Home"));
                    check("start_here".equals(selected(screen)), "Home opens Start here");
                    EditBox search = (EditBox) field(KingdomGuideScreen.class, screen, "search");
                    ((Screen) screen).setFocused(search);
                    for (char c : "job".toCharArray()) ((Screen) screen).charTyped(c, 0);
                    @SuppressWarnings("unchecked") var matches = (List<KingdomGuide.Entry>) field(KingdomGuideScreen.class, screen, "matches");
                    check(!matches.isEmpty() && matches.stream().anyMatch(e -> e.category().equals("civic")), "\"job\" finds commission topics by synonym: " + matches.stream().map(KingdomGuide.Entry::id).toList());
                    next();
                }
                case 11 -> {
                    shot("07-search-synonym.png");
                    KingdomGuideScreen screen = guide();
                    ((EditBox) field(KingdomGuideScreen.class, screen, "search")).setValue("");
                    screen.openTopic("kingdoms");
                    next();
                }
                case 12 -> {
                    KingdomGuideScreen screen = guide();
                    if (GuideKingdoms.state() != GuideKingdoms.State.READY) { if (ticks - entered > 200) throw new AssertionError("Kingdom gallery state " + GuideKingdoms.state()); return; }
                    check(GuideKingdoms.tiles().size() == 6, "The gallery shows the server's six bundled kingdoms: " + GuideKingdoms.tiles().size());
                    check(article(screen).narrationText().getString().contains("Shimaguni"), "Gallery names are read, not only drawn");
                    next();
                }
                case 13 -> {
                    shot("08-kingdom-gallery.png");
                    KingdomGuideScreen screen = guide();
                    screen.openTopic("command_basics");
                    RichPanel article = article(screen);
                    check(article.pieces().stream().noneMatch(p -> p.anchorId().equals("command_list")), "Commands start collapsed on player topics");
                    article.targets().stream().filter(t -> t.key().equals("commands_toggle")).findFirst().orElseThrow().activate();
                    check(article(screen).pieces().stream().anyMatch(p -> p.anchorId().equals("command_list")), "Commands and advanced details expands");
                    next();
                }
                case 14 -> {
                    shot("09-commands.png");
                    KingdomGuideScreen screen = guide();
                    lastTopic = selected(screen);
                    mc.getWindow().setGuiScale(mc.getWindow().getHeight() / 240.0D);
                    ((Screen) screen).resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    next();
                }
                case 15 -> {
                    KingdomGuideScreen screen = guide();
                    Screen vanilla = screen;
                    check(vanilla.height >= 239 && vanilla.height <= 241, "Compact reader is 240 GUI pixels high");
                    inBounds(vanilla);
                    check(lastTopic.equals(selected(screen)), "A resize keeps the topic");
                    AbstractWidget article = article(screen);
                    vanilla.setFocused(article);
                    check(vanilla.keyPressed(GLFW.GLFW_KEY_END, 0, 0) && article(screen).scroll() > 0, "End reaches later article content");
                    check(vanilla.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0) && article(screen).scroll() == 0, "Home returns to the article start");
                    next();
                }
                case 16 -> {
                    shot("10-book-compact.png");
                    KingdomGuideScreen screen = guide();
                    Screen vanilla = screen;
                    // The compact view (about 384 GUI pixels wide here) is also the narrow layout: Minecraft caps larger
                    // scales on an 800-pixel window, so this is the narrowest real case.
                    check((Boolean) field(KingdomGuideScreen.class, screen, "narrow"), "A narrow window switches layout instead of shrinking: width " + vanilla.width);
                    click(label("guide.mcakingdoms.pane.contents", "Contents"));
                    check((Boolean) field(KingdomGuideScreen.class, screen, "showingContents"), "Contents pane shown");
                    inBounds(vanilla);
                    next();
                }
                case 17 -> {
                    shot("11-book-narrow-contents.png");
                    KingdomGuideScreen screen = guide();
                    click(label("guide.mcakingdoms.pane.article", "Article"));
                    check(!(Boolean) field(KingdomGuideScreen.class, screen, "showingContents"), "Article pane shown again");
                    check(lastTopic.equals(selected(screen)), "Switching panes keeps the topic");
                    next();
                }
                case 18 -> {
                    shot("12-book-narrow-article.png");
                    Screen vanilla = guide();
                    check(vanilla.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0), "Escape is handled by the reader");
                    check(mc.screen == null, "Escape closes the item-opened reader");
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    next();
                }
                case 19 -> {
                    if (mc.screen != null || mc.player == null || mc.gameMode == null) return;
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    next();
                }
                case 20 -> {
                    KingdomGuideScreen screen = guide();
                    check(lastTopic.equals(selected(screen)), "Reopening through item use remembers the last-read topic: " + selected(screen));
                    next();
                }
                case 21 -> {
                    shot("13-book-reopened.png");
                    click(label("guide.mcakingdoms.close", "Close"));
                    check(mc.screen == null, "Close returns to play");
                    next();
                }
                case 22 -> { mc.setScreen(new KingdomGuideScreen(null, "using_the_book")); next(); }
                case 23 -> {
                    shot("14-manuscript-spread.png");
                    KingdomGuideScreen screen = guide();
                    check(!((Boolean) field(KingdomGuideScreen.class, screen, "narrow")), "The normal scale uses facing pages");
                    check(((Screen) screen).children().stream().filter(BookButton.class::isInstance).count() >= 15, "Book controls retain vanilla Button semantics");
                    RichPanel article = article(screen);
                    check(article.pageCount() > 1, "A long topic has multiple reading pages");
                    int turns = 0;
                    while (article.turnPage(1)) { check(++turns <= article.pageCount(), "Page turns make bounded progress"); }
                    check(article.pageIndex() == article.pageCount() - 1 && article.scroll() > 0, "Page turns reach the final lines");
                    while (article.turnPage(-1)) { }
                    check(article.scroll() == 0 && article.pageIndex() == 0, "Reverse page turns return to the opening");
                    AbstractWidget paper = article;
                    check(paper.mouseScrolled(paper.getX() + 4, paper.getY() + 4, -1) && article.scroll() > 0 && article.pageIndex() == 0,
                            "A partial wheel scroll stays on its current page and does not disable the next leaf");
                    article.turnPage(-1);
                    check(((Screen) screen).keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0) && article.scroll() > 0, "Page Down turns a manuscript page");
                    int priorScroll = article.scroll();
                    check(((Screen) screen).keyPressed(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_CONTROL) && ((EditBox) field(KingdomGuideScreen.class, screen, "search")).isFocused(), "Ctrl+F focuses search");
                    screen.openTopic("campaign_declaration");
                    check("warfare".equals(field(KingdomGuideScreen.class, screen, "category")), "Cross-references switch to the destination chapter");
                    check(((Screen) screen).keyPressed(GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_ALT) && "using_the_book".equals(selected(screen)), "Alt+Left restores the prior chapter and topic");
                    check(article(screen).scroll() == priorScroll, "Back restores the actual reading position after a chapter cross-reference");
                    layoutEveryTopic(screen);
                    mc.setScreen(ledgerFixture());
                    next();
                }
                case 24 -> {
                    shot("15-ledger-spread.png");
                    VillageLedgerScreen ledger = (VillageLedgerScreen) mc.screen;
                    inBounds(ledger);
                    @SuppressWarnings("unchecked") List<Button> rows = (List<Button>) field(VillageLedgerScreen.class, ledger, "rowButtons");
                    check(!rows.isEmpty(), "Ledger displays known settlements");
                    rows.get(0).onPress();
                    check(field(VillageLedgerScreen.class, ledger, "details") instanceof RichPanel, "Selecting a settlement opens its manuscript record");
                    next();
                }
                case 25 -> {
                    shot("16-ledger-record.png");
                    VillageLedgerScreen ledger = (VillageLedgerScreen) mc.screen;
                    inBounds(ledger);
                    check(!((List<?>) field(VillageLedgerScreen.class, ledger, "rowButtons")).isEmpty(), "Facing settlement index stays available while reading a record");
                    RichPanel details = (RichPanel) field(VillageLedgerScreen.class, ledger, "details");
                    check(details.targets().stream().anyMatch(t -> t.key().equals("government")) && details.targets().stream().anyMatch(t -> t.key().equals("tasks")), "Ledger government and task integrations remain linked");
                    mc.options.guiScale().set(1); mc.resizeDisplay();
                    mc.setScreen(new KingdomGuideScreen(null, "first_guild_steps")); next();
                }
                case 26 -> {
                    shot("17-book-scale-one.png");
                    inBounds(guide());
                    mc.options.guiScale().set(2); mc.resizeDisplay();
                    language("es_es"); next();
                }
                case 27 -> {
                    if (!languageReady) return;
                    GuideLibrary.invalidate();
                    mc.setScreen(new KingdomGuideScreen(null, "found_a_government")); next();
                }
                case 28 -> {
                    shot("18-book-spanish.png"); inBounds(guide()); layoutEveryTopic(guide());
                    check(!GuideLibrary.get().entries().get(0).title().equals("Start here"), "Spanish guide resources are active");
                    language("zh_cn"); next();
                }
                case 29 -> {
                    if (!languageReady) return;
                    GuideLibrary.invalidate(); mc.setScreen(new KingdomGuideScreen(null, "campaign_declaration")); next();
                }
                case 30 -> {
                    shot("19-book-chinese.png"); inBounds(guide()); layoutEveryTopic(guide());
                    language("en_us"); next();
                }
                case 31 -> {
                    if (!languageReady) return;
                    GuideLibrary.invalidate();
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 600);
                    mc.options.guiScale().set(2); mc.resizeDisplay();
                    mc.setScreen(new KingdomGuideScreen(null, "ledger_recipe")); next();
                }
                case 32 -> {
                    shot("20-book-small-window.png"); inBounds(guide());
                    check(((Boolean) field(KingdomGuideScreen.class, guide(), "narrow")), "A smaller real window uses one readable leaf");
                    mc.setScreen(ledgerFixture()); next();
                }
                case 33 -> {
                    shot("21-ledger-compact.png");
                    VillageLedgerScreen ledger = (VillageLedgerScreen) mc.screen;
                    Screen ledgerScreen = ledger;
                    inBounds(ledgerScreen);
                    LedgerPagePacket fixturePage = (LedgerPagePacket) field(VillageLedgerScreen.class, ledger, "page");
                    long beforeDragRequest = (Long) field(VillageLedgerScreen.class, ledger, "pendingRequestId");
                    BookLayout layout = BookLayout.fit(ledgerScreen.width, ledgerScreen.height);
                    int left = layout.leafX(false) - 10, top = layout.y() + 6, bottom = layout.bottom() - 10;
                    int trackX = left + layout.leafWidth() - 15, trackTop = top + 100, trackHeight = bottom - 54 - (top + 78) - 46;
                    check(ledgerScreen.mouseClicked(trackX + 3, trackTop + 1, 0), "Ledger local scrollbar accepts a native press");
                    check(ledgerScreen.mouseDragged(trackX + 3, trackTop + trackHeight - 1, 0, 0, trackHeight)
                                    && ledgerScreen.mouseReleased(trackX + 3, trackTop + trackHeight - 1, 0),
                            "Ledger local scrollbar accepts a native drag and release");
                    @SuppressWarnings("unchecked") List<Button> scrolledRows = (List<Button>) field(VillageLedgerScreen.class, ledger, "rowButtons");
                    check(scrolledRows.stream().anyMatch(row -> row.getMessage().getString().contains(fixturePage.settlements().get(fixturePage.settlements().size() - 1).displayName())),
                            "Dragging the local scrollbar reaches the final settlement row without requesting a server page");
                    check((Long) field(VillageLedgerScreen.class, ledger, "pendingRequestId") == beforeDragRequest && fixturePage.offset() == 0,
                            "Dragging stays within the current server result page");
                    Button picker = ledgerScreen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button -> button.getMessage().getString().startsWith("Kingdom:")).findFirst().orElseThrow();
                    check(ledgerScreen.mouseClicked(picker.getX() + picker.getWidth() / 2.0D, picker.getY() + picker.getHeight() / 2.0D, 0),
                            "Compact Ledger kingdom picker opens with native mouse input");
                    check(ledgerScreen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0) && (Integer) field(VillageLedgerScreen.class, ledger, "kingdomPage") == 0,
                            "Home returns the kingdom picker to its opening local page");
                    check(ledgerScreen.keyPressed(GLFW.GLFW_KEY_END, 0, 0), "End navigates to the final local kingdom-picker page");
                    @SuppressWarnings("unchecked") List<KingdomSummary> pickerRows = (List<KingdomSummary>) field(VillageLedgerScreen.class, ledger, "pickerRows");
                    @SuppressWarnings("unchecked") List<KingdomSummary> fixtureKingdoms = (List<KingdomSummary>) field(VillageLedgerScreen.class, ledger, "kingdoms");
                    KingdomSummary lastKingdom = fixtureKingdoms.get(fixtureKingdoms.size() - 1);
                    check(pickerRows.stream().anyMatch(kingdom -> kingdom.id().equals(lastKingdom.id())), "The final kingdom is reachable in the compact picker");
                    check(ledgerScreen.getFocused() instanceof Button focus && focus.active, "Kingdom paging keeps focus on a live native control");
                    check(ledgerScreen.keyPressed(GLFW.GLFW_KEY_PAGE_UP, 0, 0), "Page Up moves the kingdom picker locally");
                    check(ledgerScreen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0), "Page Down advances the kingdom picker locally");
                    @SuppressWarnings("unchecked") List<KingdomSummary> pagedRows = (List<KingdomSummary>) field(VillageLedgerScreen.class, ledger, "pickerRows");
                    check(pagedRows.stream().anyMatch(kingdom -> kingdom.id().equals(lastKingdom.id())), "Pager navigation returns to the final kingdom page");
                    inBounds(ledgerScreen); noButtonOverlaps(ledgerScreen);
                    Button last = ledgerScreen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button -> button.getMessage().getString().equals(Component.translatable(lastKingdom.translationKey()).getString())).findFirst().orElseThrow();
                    check(ledgerScreen.mouseClicked(last.getX() + last.getWidth() / 2.0D, last.getY() + last.getHeight() / 2.0D, 0),
                            "The final compact kingdom choice accepts native mouse input");
                    LedgerPagePacket prior = (LedgerPagePacket) field(VillageLedgerScreen.class, ledger, "page");
                    SettlementSummary selectedKingdomSettlement = prior.settlements().stream().filter(value -> value.kingdomId().equals(lastKingdom.id())).findFirst().orElseThrow();
                    long requestId = (Long) field(VillageLedgerScreen.class, ledger, "pendingRequestId");
                    check(ledger.receive(new LedgerPagePacket(requestId, prior.registryRevision(), Optional.of(lastKingdom.id()), 0, false, 0,
                            List.of(selectedKingdomSettlement), fixtureKingdoms, "", "", SettlementKnowledge.Sort.NAME, 1, "")),
                            "Selecting the final kingdom keeps its server-backed filter request");
                    Button filteredPicker = ledgerScreen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button -> button.getMessage().getString().startsWith("Kingdom:")).findFirst().orElseThrow();
                    filteredPicker.onPress();
                    click(label("screen.mcakingdoms.ledger.picker.back", "Back to the list"));
                    check(((Optional<?>) field(VillageLedgerScreen.class, ledger, "filter")).equals(Optional.of(lastKingdom.id())),
                            "Picker Back preserves the selected kingdom filter");
                    inBounds(ledgerScreen); noButtonOverlaps(ledgerScreen); next();
                }
                case 34 -> {
                    mc.setScreen(null);
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 800); mc.resizeDisplay();
                    mc.getSingleplayerServer().execute(this::seedLedger);
                    next();
                }
                case 35 -> {
                    if (!seededLedger || !mc.player.getMainHandItem().is(ForgeRegistries.ITEMS.getValue(LEDGER_ID))) return;
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); next();
                }
                case 36 -> {
                    if (!ledgerReady()) return;
                    LedgerPagePacket page = ledgerPage();
                    check(page.total() == 23 && page.settlements().size() == 21, "Real Ledger item receives the server's paged settlement records");
                    click(label("gui.mcakingdoms.next", "Next")); next();
                }
                case 37 -> {
                    if (!ledgerReady()) return;
                    check(ledgerPage().offset() == 20 && ledgerPage().settlements().size() == 3, "Next requests the server's final settlement page");
                    click(label("gui.mcakingdoms.previous", "Previous")); next();
                }
                case 38 -> {
                    if (!ledgerReady()) return;
                    check(ledgerPage().offset() == 0, "Previous requests the first settlement page");
                    ((EditBox) field(VillageLedgerScreen.class, mc.screen, "search")).setValue("Royal Record 07"); next();
                }
                case 39 -> {
                    if (!ledgerReady()) return;
                    check(ledgerPage().total() == 1 && ledgerPage().query().equals("Royal Record 07"), "Search is answered by the server and filters to one known record");
                    @SuppressWarnings("unchecked") List<Button> rows = (List<Button>) field(VillageLedgerScreen.class, mc.screen, "rowButtons");
                    rows.get(0).onPress(); next();
                }
                case 40 -> {
                    shot("22-ledger-live-record.png");
                    inBounds(mc.screen);
                    check(((SettlementSummary) field(VillageLedgerScreen.class, mc.screen, "selected")).displayName().equals("Royal Record 07"), "The facing record is the selected real server record");
                    click(label("gui.mcakingdoms.back", "Back to list"));
                    ((EditBox) field(VillageLedgerScreen.class, mc.screen, "search")).setValue(""); next();
                }
                case 41 -> {
                    if (!ledgerReady()) return;
                    check(ledgerPage().total() == 23, "Clearing search restores all known records");
                    click(label("screen.mcakingdoms.ledger.sort.name", "Sort: Name")); next();
                }
                case 42 -> {
                    if (!ledgerReady()) return;
                    check(ledgerPage().sort() == SettlementKnowledge.Sort.DISTANCE, "Distance ordering is returned by the server");
                    Button picker = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(b -> b.getMessage().getString().startsWith("Kingdom:")).findFirst().orElseThrow();
                    picker.onPress(); next();
                }
                case 43 -> { click("Madera"); next(); }
                case 44 -> {
                    if (!ledgerReady()) return;
                    check(ledgerPage().kingdomId().equals(Optional.of(new ResourceLocation("mcakingdoms", "madera"))) && ledgerPage().total() == 12,
                            "Heraldic kingdom picker applies the actual server filter");
                    click(label("screen.mcakingdoms.ledger.help", "Help")); next();
                }
                case 45 -> {
                    check("ledger_and_discovery".equals(selected(guide())), "Ledger Help opens the contextual Book topic");
                    ((Screen) guide()).keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); next();
                }
                case 46 -> {
                    check(mc.screen instanceof VillageLedgerScreen, "Closing contextual Help returns to the live Ledger");
                    click(net.minecraft.network.chat.CommonComponents.GUI_DONE.getString());
                    check(mc.screen == null, "Done closes the live Ledger and returns to play"); next();
                }
                case 47 -> finish();
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void createWorld() {
        creating = true;
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(3);
        mc.options.guiScale().set(2);
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        mc.resizeDisplay();
        mc.createWorldOpenFlows().createFreshLevel("book-guide-acceptance-" + started,
                new LevelSettings("Book guide acceptance", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(721946L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        log("Created isolated flat integrated world");
    }

    private void language(String locale) {
        languageReady = false;
        mc.getLanguageManager().setSelected(locale);
        mc.options.languageCode = locale;
        mc.reloadResourcePacks().thenRun(() -> languageReady = true);
    }

    private boolean ledgerReady() {
        return mc.screen instanceof VillageLedgerScreen && !((Boolean) field(VillageLedgerScreen.class, mc.screen, "loading"));
    }

    private LedgerPagePacket ledgerPage() { return (LedgerPagePacket) field(VillageLedgerScreen.class, mc.screen, "page"); }

    private void seedLedger() {
        try {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().get(0);
            var service = dev.otectus.mcakingdoms.api.McaKingdomsApi.get(server);
            for (int i = 0; i < 23; i++) {
                var position = new BlockPos(1000 + i * 80, 64, 1000);
                var candidate = dev.otectus.mcakingdoms.api.SettlementCandidate.manual(server.overworld().dimension(), position, 12,
                        new ResourceLocation("mcakingdoms", "book_acceptance"), "live-ledger-" + i, String.format(java.util.Locale.ROOT, "Royal Record %02d", i));
                var settlement = service.registerCandidate(server.overworld(), candidate);
                service.setKingdom(settlement.id(), new ResourceLocation("mcakingdoms", i % 2 == 0 ? "madera" : "serenum"));
                SettlementKnowledge.get(server).discover(player.getUUID(), settlement.id());
            }
            player.getInventory().setItem(0, new ItemStack(ForgeRegistries.ITEMS.getValue(LEDGER_ID)));
            player.containerMenu.broadcastChanges();
            seededLedger = true;
        } catch (Throwable failure) { fail(failure); }
    }

    /** Every packaged translation and topic at the minimum viewport and a normal spread; actual-scale shots are separate. */
    private void layoutEveryTopic(KingdomGuideScreen screen) {
        Screen vanilla = screen;
        int oldWidth = vanilla.width, oldHeight = vanilla.height;
        String oldTopic = selected(screen);
        for (int[] viewport : new int[][] {{320, 240}, {426, 240}, {640, 360}, {960, 540}}) {
            vanilla.resize(mc, viewport[0], viewport[1]);
            for (KingdomGuide.Entry entry : entries(screen)) {
                screen.openTopic(entry.id());
                for (var child : vanilla.children()) if (child instanceof AbstractWidget w && w.visible) {
                    if (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > vanilla.width || w.getY() + w.getHeight() > vanilla.height)
                        throw new AssertionError("Clipped " + entry.id() + " at " + vanilla.width + "x" + vanilla.height + ": " + w.getMessage().getString());
                }
                check(article(screen).pageCount() >= 1, "Laid out " + entry.id() + " at " + vanilla.width + "x" + vanilla.height);
            }
        }
        vanilla.resize(mc, oldWidth, oldHeight); screen.openTopic(oldTopic);
        log("PASS every topic laid out in " + mc.options.languageCode);
    }

    /** Presentation fixture only: it never claims gameplay/discovery acceptance for synthetic records. */
    private VillageLedgerScreen ledgerFixture() throws Exception {
        ResourceLocation kingdomId = new ResourceLocation("mcakingdoms", "madera");
        List<KingdomSummary> kingdoms = new ArrayList<>();
        kingdoms.add(new KingdomSummary(kingdomId, "kingdom.mcakingdoms.madera", kingdomId, 0xC9793E));
        for (int i = 1; i < 18; i++) {
            ResourceLocation id = new ResourceLocation("mcakingdoms", "book_fixture_kingdom_" + i);
            kingdoms.add(new KingdomSummary(id, "kingdom.mcakingdoms.book_fixture_kingdom_" + i, id, 0xC9793E + i));
        }
        List<SettlementSummary> settlements = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            ResourceLocation settlementKingdom = kingdoms.get(i).id();
            settlements.add(new SettlementSummary(UUID.nameUUIDFromBytes(("book-fixture-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    i == 0 ? "The Long-Named Royal Borough of Willowmere" : "Willowmere " + (i + 1), new ResourceLocation("mcakingdoms", "willowmere_" + i), settlementKingdom,
                    new ResourceLocation("minecraft", "overworld"), new BlockPos(i * 100, 64, i * 50), new ResourceLocation("minecraft", "plains"), AssignmentSource.MANUAL, 24000, 1));
        }
        var packet = new LedgerPagePacket(1, 1, Optional.empty(), 0, false, 0, settlements, kingdoms, "", "", SettlementKnowledge.Sort.NAME, 18, "");
        var ledger = new VillageLedgerScreen(packet);
        for (var value : Map.<String, Object>of("initialRequestSent", true, "loading", false).entrySet()) {
            Field field = VillageLedgerScreen.class.getDeclaredField(value.getKey()); field.setAccessible(true); field.set(ledger, value.getValue());
        }
        return ledger;
    }

    private void seedBookAndCheckRecipe() {
        try {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().get(0);
            Item book = book();
            var recipe = server.getRecipeManager().byKey(BOOK_ID).orElseThrow(() -> new AssertionError("Missing recipe " + BOOK_ID));
            check(recipe.getType() == RecipeType.CRAFTING, "Book recipe is registered as crafting");
            ItemStack result = recipe.getResultItem(server.registryAccess());
            check(result.is(book) && result.getCount() == 1, "Book recipe produces one registered guide item");
            recipeChecked = true;
            player.getInventory().setItem(0, new ItemStack(book));
            player.getInventory().selected = 0;
            player.containerMenu.broadcastChanges();
            seeded = true;
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /** A data pack replacing the ledger recipe, then /reload, as an operator would on a real server. */
    private void writePack(String recipe) {
        try {
            reloaded = false;
            var server = mc.getSingleplayerServer();
            Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("kingdoms-acceptance");
            Path file = pack.resolve("data/mcakingdoms/recipes/village_ledger.json");
            Files.createDirectories(file.getParent());
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"MCA: Kingdoms acceptance\"}}");
            Files.writeString(file, recipe);
            reload(server);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void removePack() {
        try {
            reloaded = false;
            var server = mc.getSingleplayerServer();
            Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("kingdoms-acceptance");
            try (var files = Files.walk(pack)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            reload(server);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void reload(net.minecraft.server.MinecraftServer server) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        log("Ran /reload");
        reloaded = true;
    }

    private Item book() {
        Item value = ForgeRegistries.ITEMS.getValue(BOOK_ID);
        if (value == null || value == Items.AIR) throw new AssertionError("Book item is not registered");
        return value;
    }

    private KingdomGuideScreen guide() {
        if (!(mc.screen instanceof KingdomGuideScreen screen)) throw new AssertionError("Expected real KingdomGuideScreen, found " + mc.screen);
        return screen;
    }

    private String selected(KingdomGuideScreen screen) {
        return (String) field(KingdomGuideScreen.class, screen, "selected");
    }

    @SuppressWarnings("unchecked")
    private List<KingdomGuide.Entry> entries(KingdomGuideScreen screen) {
        return (List<KingdomGuide.Entry>) field(KingdomGuideScreen.class, screen, "entries");
    }

    private RichPanel article(KingdomGuideScreen screen) {
        return (RichPanel) field(KingdomGuideScreen.class, screen, "article");
    }

    /** The model the article's recipe piece renders. */
    private GuideRecipe recipe(KingdomGuideScreen screen) {
        for (RichPanel.Piece piece : article(screen).pieces())
            if (piece.getClass().getSimpleName().equals("Recipe") && piece.anchorId().equals("recipe"))
                return (GuideRecipe) field(piece.getClass(), piece, "recipe");
        throw new AssertionError("No recipe piece in " + selected(screen));
    }

    private void inBounds(Screen vanilla) {
        for (var child : vanilla.children()) if (child instanceof AbstractWidget widget && widget.visible)
            check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget.getWidth() <= vanilla.width && widget.getY() + widget.getHeight() <= vanilla.height,
                    "Widget stays within viewport: " + widget.getMessage().getString());
    }

    private void noButtonOverlaps(Screen vanilla) {
        List<Button> controls = vanilla.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(button -> button.visible).toList();
        for (int i = 0; i < controls.size(); i++) for (int j = i + 1; j < controls.size(); j++) {
            Button a = controls.get(i), b = controls.get(j);
            boolean overlap = a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                    && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY();
            check(!overlap, "Ledger controls do not overlap: " + a.getMessage().getString() + " / " + b.getMessage().getString());
        }
    }

    private static String label(String key, String fallback) {
        return net.minecraft.network.chat.Component.translatableWithFallback(key, fallback).getString();
    }

    private void click(String label) {
        Button button = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + label + ": " + buttons()));
        check(button.active, "Button enabled: " + label);
        check(mc.screen.mouseClicked(button.getX() + button.getWidth() / 2.0D, button.getY() + button.getHeight() / 2.0D, 0), "Mouse click handled: " + label);
    }

    private List<String> buttons() {
        if (mc.screen == null) return List.of();
        return mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).map(button -> button.getMessage().getString()).toList();
    }

    private Object field(Class<?> type, Object instance, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private void shot(String name) {
        Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> log("Screenshot " + name + ": " + message.getString()));
    }

    private void finish() throws Exception {
        for (String name : SCREENSHOTS) {
            Path image = output.resolve("screenshots").resolve(name);
            check(Files.isRegularFile(image) && Files.size(image) > 0, "Screenshot written: " + name);
        }
        Files.createDirectories(output);
        Files.writeString(output.resolve("BOOK_NARRATION.txt"), String.join("\n\n", evidence) + "\n");
        Files.writeString(output.resolve("BOOK_GUIDE_PASS.txt"),
                "PASS isolated packaged client: actual item use; every topic parsed; live ledger and book recipes from the synchronized recipe manager; "
                        + "data-pack replacement and removal applied by /reload while open; restore; keyboard link activation; Back, Home, bookmarks in the local state file, "
                        + "Saved; synonym search; kingdom gallery from the server; collapsed commands; compact and narrow layouts; Escape; item-use reopen at the last topic; "
                        + "manuscript page turns; chapter cross-references and restored reading position; Ctrl+F and Alt+Left; all 70 topics at 320x240, 426x240, 640x360 and 960x540 "
                        + "in English, Spanish and Chinese; actual scale-one, scale-two and compact displays; smaller real window; Ledger presentation fixtures with long names and facing record links; "
                        + "real Ledger item use and 23 server records; server paging, search, distance sort, kingdom filtering, selection, contextual Help round-trip and Done.\n");
        finished = true;
        log("PASS all Book of Kingdoms client checks");
        mc.stop();
    }

    private void next() {
        stage++;
        entered = ticks;
        log("stage=" + stage);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        log("PASS " + message);
    }

    private void log(String message) {
        System.out.println("BOOK_GUIDE_CLIENT " + message);
    }

    private void fail(Throwable failure) {
        if (finished) return;
        finished = true;
        failure.printStackTrace();
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("BOOK_GUIDE_FAIL.txt"), "stage=" + stage + "\n" + failure + "\n");
        } catch (Exception writingFailure) {
            writingFailure.printStackTrace();
        }
        mc.execute(mc::stop);
    }
}
