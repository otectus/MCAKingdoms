package dev.otectus.mcakingdoms.clienttest;

import dev.otectus.mcakingdoms.client.*;
import dev.otectus.mcakingdoms.client.book.BookButton;
import dev.otectus.mcakingdoms.client.book.BookConfirmScreen;
import dev.otectus.mcakingdoms.client.book.BookCycleButton;
import dev.otectus.mcakingdoms.client.book.BookSlider;
import dev.otectus.mcakingdoms.client.politics.KingdomScreen;
import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.client.townstead.TownsteadBlueprintHeader;
import dev.otectus.mcakingdoms.compat.townstead.network.TownsteadCivicHeaderPacket.Header;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in visual tour of the real screens at three GUI scales (2, 3 and a 240-pixel-high compact view); never shipped in
 * runtime jars. Every visible widget must stay inside the screen and no two may overlap; each page is captured.
 */
final class GuiStyleAcceptance {
    private static final String[] PAGES={"ledger","settlement","guild","kingdom","form","blueprint-header","guide","war-room","entry","getting-started",
            "start","categories","settings","guide-recipe","settings-tasks","history-confirm","settings-politics","residence"};
    private static final int STEPS=PAGES.length*3;
    private final Path output;
    /** Screenshot name prefix, such as {@code es_es-} for a tour in another language; empty for English. */
    private final String prefix;
    private int step, ticks;
    private Screen screen;
    private boolean captured, behaviorChecked;
    private boolean artworkChecked;
    private boolean historyConfirmOpened, historyChecked;
    private List<String> historyBookmarks, historyTopics, historyTasks, historyFavorites;
    private String historyLastRead;
    private VillageLedgerScreen ledger;
    GuiStyleAcceptance(Path output) { this(output,""); }
    GuiStyleAcceptance(Path output,String prefix) { this.output=output; this.prefix=prefix; }
    private static InteractionNetwork.Reply reply(Screen screen)throws Exception{return (InteractionNetwork.Reply)field(screen,"reply");}
    boolean tick(Minecraft mc) throws Exception {
        if(step==STEPS)return true;
        if (!artworkChecked) {
            var hashes = new java.util.HashSet<String>();
            for (var artwork : dev.otectus.mcakingdoms.client.book.BookIllustrations.values()) {
                try (var stream = mc.getResourceManager().open(artwork.texture())) {
                    byte[] bytes = stream.readAllBytes();
                    if (!hashes.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))))
                        throw new AssertionError("Manuscripts must not reuse the same illustration bytes");
                }
                var translation = (net.minecraft.network.chat.contents.TranslatableContents) artwork.description().getContents();
                if (!net.minecraft.locale.Language.getInstance().has(translation.getKey()))
                    throw new AssertionError("Missing localized illustration description");
            }
            artworkChecked = true;
        }
        int page=step%PAGES.length,scale=step/PAGES.length;
        if(Boolean.getBoolean("mcakingdoms.clientTest.taskStyle")&&!java.util.Set.of(4,9,10,11).contains(page)){step++;return step==STEPS;}
        if(mc.getOverlay()!=null){ticks=0;return false;}
        if(screen==null) {
            mc.options.guiScale().set(2+scale);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            if(scale==2) mc.getWindow().setGuiScale(mc.getWindow().getHeight()/240.0D);
            if(page==15){historyConfirmOpened=false;historyChecked=false;historyBookmarks=historyTopics=historyTasks=historyFavorites=null;historyLastRead=null;}
            screen=switch(page) {
                case 0 -> ledger=new VillageLedgerScreen(null);
                case 1 -> ledger;
                case 2 -> new GuildScreen(null);
                case 3 -> new KingdomScreen(null,"mcakingdoms:serenum","");
                case 4 -> new KingdomActionsScreen(null,"settlement.create","");
                case 5 -> new HeaderPreview();
                case 6 -> new KingdomGuideScreen(null);
                case 7 -> {
                    var selected=(dev.otectus.mcakingdoms.presentation.SettlementSummary)field(ledger,"selected");
                    yield new WarRoomScreen(null,selected.id(),selected.displayName());
                }
                case 8 -> new EntryPreview((dev.otectus.mcakingdoms.presentation.SettlementSummary)field(ledger,"selected"));
                case 12 -> new KingdomsSettingsScreen(null);
                case 13 -> new KingdomGuideScreen(null,"ledger_recipe");
                case 14, 15, 16 -> new KingdomsSettingsScreen(null);
                case 17 -> new KingdomActionsScreen(null,"citizen.register_residence","");
                default -> new KingdomActionsScreen(null,"","");
            };
            mc.setScreen(screen);ticks=0;captured=false;behaviorChecked=false;
            if(page==1){
                // Select the first known settlement as a player would, by its row.
                var current=(dev.otectus.mcakingdoms.network.LedgerPagePacket)field(ledger,"page");
                var select=VillageLedgerScreen.class.getDeclaredMethod("select",dev.otectus.mcakingdoms.presentation.SettlementSummary.class);select.setAccessible(true);
                select.invoke(ledger,current.settlements().get(0));
            }
        }
        if(mc.screen!=screen)throw new AssertionError("GUI tour screen changed unexpectedly");
        if(++ticks>240)throw new AssertionError("GUI tour timed out: "+step);
        boolean ready=switch(page) {
            case 0 -> !((boolean)field(screen,"loading"))&&field(screen,"page")!=null;
            case 1 -> field(screen,"selected")!=null;
            case 2 -> field(screen,"view")!=null&&field(screen,"pending")==null;
            case 3 -> field(screen,"page")!=null&&field(screen,"pending")==null;
            case 4, 17 -> reply(screen)!=null&&field(screen,"pending")==null;
            case 7 -> field(screen,"snapshot")!=null;
            case 9, 10, 11 -> reply(screen)!=null&&field(screen,"pending")==null&&reply(screen).mode().equals("start");
            default -> true;
        };
        if(!ready||ticks<30)return false;
        if (!behaviorChecked) {
            if(screen instanceof KingdomActionsScreen){
                for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button
                        &&!(child instanceof dev.otectus.mcakingdoms.client.book.BookButton))throw new AssertionError("Task button lost its shared manuscript styling");
                if(page==4){
                    Object detail=field(screen,"detail");
                    if(!((boolean)field(detail,"manuscript")))throw new AssertionError("Task form has no paper content theme");
                    if(!screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL))throw new AssertionError("Ctrl+F did not focus the task input");
                }
                if(page==10){
                    var focused=screen.getFocused();
                    for(String name:new String[]{"startPanel","shortcutPanel"}){
                        var panel=(RichPanel)field(screen,name);
                        if(panel==null)continue;
                        var original=panel.pieces();
                        panel.setPieces(java.util.stream.IntStream.range(0,40).mapToObj(i->Pieces.text("scroll_"+i,Component.literal("Task folio line "+i))).toList());
                        screen.setFocused(panel);
                        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END,0,0);
                        if(panel.scroll()<=0)throw new AssertionError("Task folio End did not reach later content");
                        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                        if(panel.scroll()!=0)throw new AssertionError("Task folio Home did not return to the opening");
                        AbstractWidget field=panel;
                        field.mouseScrolled(field.getX()+12,field.getY()+12,-1);
                        if(panel.scroll()<=0)throw new AssertionError("Task folio wheel did not scroll");
                        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                        field.mouseClicked(field.getX()+field.getWidth()+4,field.getY()+4,0);
                        field.mouseDragged(field.getX()+field.getWidth()+4,field.getY()+field.getHeight()+1,0,0,field.getHeight());
                        if(panel.scroll()<=0)throw new AssertionError("Task folio scrollbar drag did not scroll");
                        field.mouseReleased(field.getX()+field.getWidth()+4,field.getY()+field.getHeight()+1,0);
                        panel.setPieces(original);
                    }
                    screen.setFocused(focused);
                }
            }
            if (page == 1) {
                var selected=(dev.otectus.mcakingdoms.presentation.SettlementSummary)field(screen,"selected");
                if(!selected.id().equals(field(screen,"selectedId")))throw new AssertionError("The Ledger keeps its selection by settlement id");
                var details=(RichPanel)field(screen,"details");
                if(details.targets().stream().noneMatch(t->t.key().equals("war_room"))||details.targets().stream().noneMatch(t->t.key().equals("government")))
                    throw new AssertionError("Settlement details lead to its government and War Room");
            }
            if (page == 2) {
                assertBookButtons(screen,"Guild");
                var record=(RichPanel)field(screen,"record");
                manuscript(record,"Guild record");
                var guild=(dev.otectus.mcakingdoms.civic.CivicViews.View)field(screen,"view");
                if(guild.guild()==null||guild.guild().services().isEmpty())throw new AssertionError("The Guilds screen shows each service on its own");
                var original=record.pieces();
                record.setPieces(java.util.stream.IntStream.range(0,40).mapToObj(i->Pieces.text("line"+i,Component.literal("Scrollable record line "+i))).toList());
                screen.setFocused(record);
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END,0,0);
                if (record.scroll() <= 0) throw new AssertionError("Guild record keyboard scroll did not advance");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                if (record.scroll() != 0) throw new AssertionError("Guild record Home did not reset scroll");
                AbstractWidget widget=record;
                widget.mouseScrolled(widget.getX()+12,widget.getY()+30,-1);
                if (record.scroll() <= 0) throw new AssertionError("Guild record wheel scroll did not advance");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                widget.mouseClicked(widget.getX()+widget.getWidth()+4,widget.getY()+4,0);
                widget.mouseDragged(widget.getX()+widget.getWidth()+4,widget.getY()+widget.getHeight()+1,0,0,widget.getHeight());
                if(record.scroll()<=0)throw new AssertionError("Native scrollbar drag did not advance");
                widget.mouseReleased(widget.getX()+widget.getWidth()+4,widget.getY()+widget.getHeight()+1,0);
                record.setPieces(original);
                screen.setFocused(null);
            }
            if (page == 3) {
                assertBookButtons(screen,"Kingdom");
                var detail=(RichPanel)field(screen,"detailPanel");
                manuscript(detail,"Kingdom record");
                var original=(dev.otectus.mcakingdoms.api.politics.Politics.Page)field(screen,"page");
                var rows=java.util.stream.IntStream.range(0,20).mapToObj(i->new dev.otectus.mcakingdoms.api.politics.Politics.Row("row"+i,"Record "+i,"Details "+i)).toList();
                var fixture=new dev.otectus.mcakingdoms.api.politics.Politics.Page(original.requestId(),original.revision(),original.kingdom(),original.tab(),original.offset(),false,rows,original.definitions(),original.actionDenials(),original.diagnostic());
                var pageField=KingdomScreen.class.getDeclaredField("page");pageField.setAccessible(true);pageField.set(screen,fixture);
                screen.resize(mc,screen.width,screen.height);
                press(screen,Component.translatable("politics.mcakingdoms.next").getString());
                if((int)field(screen,"rowScroll")<=0||field(screen,"pending")!=null)throw new AssertionError("Keyboard Next did not reach the next local records");
                press(screen,Component.translatable("politics.mcakingdoms.previous").getString());
                if((int)field(screen,"rowScroll")!=0)throw new AssertionError("Keyboard Previous did not return to the first records");
                var detailPieces=detail.pieces();
                detail.setPieces(java.util.stream.IntStream.range(0,40).mapToObj(i->Pieces.text("kingdom_scroll_"+i,Component.literal("Kingdom record line "+i))).toList());
                int rowScroll=(int)field(screen,"rowScroll");
                AbstractWidget detailWidget=detail;
                screen.mouseScrolled(detailWidget.getX()+8,detailWidget.getY()+8,-1);
                if(detail.scroll()<=0||rowScroll!=(int)field(screen,"rowScroll"))throw new AssertionError("Kingdom detail wheel did not stay in the manuscript record");
                detail.setPieces(detailPieces);
                pageField.set(screen,original);screen.resize(mc,screen.width,screen.height);
            }
            if (page == 7) {
                assertBookButtons(screen,"War Room");
                var intelligence=(RichPanel)field(screen,"intelligence");
                manuscript(intelligence,"War Room intelligence");
                var original=intelligence.pieces();
                intelligence.setPieces(java.util.stream.IntStream.range(0,40).mapToObj(i->Pieces.text("war_scroll_"+i,Component.literal("War Room intelligence line "+i))).toList());
                screen.setFocused(intelligence);
                if(!screen.keyPressed(GLFW.GLFW_KEY_END,0,0)||intelligence.scroll()<=0)throw new AssertionError("War Room End did not reach later intelligence");
                if(!screen.keyPressed(GLFW.GLFW_KEY_HOME,0,0)||intelligence.scroll()!=0)throw new AssertionError("War Room Home did not return to opening intelligence");
                AbstractWidget intelligenceWidget=intelligence;
                intelligenceWidget.mouseScrolled(intelligenceWidget.getX()+12,intelligenceWidget.getY()+12,-1);
                if(intelligence.scroll()<=0)throw new AssertionError("War Room wheel did not scroll intelligence");
                screen.keyPressed(GLFW.GLFW_KEY_HOME,0,0);
                intelligenceWidget.mouseClicked(intelligenceWidget.getX()+intelligenceWidget.getWidth()+4,intelligenceWidget.getY()+4,0);
                intelligenceWidget.mouseDragged(intelligenceWidget.getX()+intelligenceWidget.getWidth()+4,intelligenceWidget.getY()+intelligenceWidget.getHeight()+1,0,0,intelligenceWidget.getHeight());
                if(intelligence.scroll()<=0)throw new AssertionError("War Room scrollbar drag did not scroll intelligence");
                intelligenceWidget.mouseReleased(intelligenceWidget.getX()+intelligenceWidget.getWidth()+4,intelligenceWidget.getY()+intelligenceWidget.getHeight()+1,0);
                intelligence.setPieces(original);
                press(screen,Component.translatableWithFallback("menu.mcakingdoms.war_room.help","Help").getString());
                if(!(mc.screen instanceof KingdomGuideScreen))throw new AssertionError("War Room Help did not open the Book");
                Screen guide=mc.screen;
                guide.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
                if(mc.screen!=screen)throw new AssertionError("War Room Help did not return to the same War Room");
            }
            if (page == 9) {
                // Getting started opens from the start view by keyboard, in whatever language the client uses.
                press(screen,Component.translatableWithFallback("interaction.mcakingdoms.help.open","Getting started").getString());
                if(!((boolean)field(screen,"showHelp")))throw new AssertionError("Getting started did not open from the start view");
                for(String key:new String[]{"interaction.mcakingdoms.help.here","interaction.mcakingdoms.help.known","interaction.mcakingdoms.help.kingdom","interaction.mcakingdoms.help.controls","interaction.mcakingdoms.help.book"}){
                    String label=Component.translatable(key).getString();
                    if(screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).map(net.minecraft.client.gui.components.Button.class::cast).noneMatch(b->b.getMessage().getString().equals(label)))
                        throw new AssertionError("Getting started lacks its "+key+" button");
                }
                behaviorChecked=true;ticks=15;return false;
            }
            if (page == 11) {
                press(screen,Component.translatableWithFallback("interaction.mcakingdoms.categories","Categories…").getString());
                if(!((boolean)field(screen,"showingCategories")))throw new AssertionError("Categories did not open from the start view");
                behaviorChecked=true;ticks=15;return false;
            }
            if (page == 4) {
                var target=(net.minecraft.client.gui.components.EditBox)field(screen,"input");
                target.setValue("draft-retained");
                screen.resize(mc,screen.width,screen.height);
                target=(net.minecraft.client.gui.components.EditBox)field(screen,"input");
                if (!target.getValue().equals("draft-retained")) throw new AssertionError("Kingdom form lost its draft on resize");
                target.setValue("");
            }
            if (page == 12) {
                assertBookButtons(screen,"Settings");
                KingdomsSettingsScreen settings=(KingdomsSettingsScreen)screen;
                Screen settingsView=settings;
                boolean savedBanner=dev.otectus.mcakingdoms.presentation.PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get();
                int savedDuration=dev.otectus.mcakingdoms.presentation.PresentationConfig.OVERLAY_DURATION_TICKS.get();
                var cycles=settingsView.children().stream().filter(BookCycleButton.class::isInstance).map(AbstractWidget.class::cast).toList();
                AbstractWidget banner=cycles.get(0);
                settingsView.setFocused(banner);
                if(!settingsView.keyPressed(GLFW.GLFW_KEY_SPACE,0,0)||(boolean)field(settings,"showBanner")==savedBanner)
                    throw new AssertionError("Settings keyboard cycle did not change the unsaved banner draft");
                if(dev.otectus.mcakingdoms.presentation.PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get()!=savedBanner)
                    throw new AssertionError("Settings cycle wrote configuration before Apply");
                if((boolean)field(settings,"showBanner")) {
                    banner=settingsView.children().stream().filter(BookCycleButton.class::isInstance).map(AbstractWidget.class::cast).findFirst().orElseThrow();
                    settingsView.setFocused(banner);settingsView.keyPressed(GLFW.GLFW_KEY_SPACE,0,0);
                }
                var disabledCycles=settingsView.children().stream().filter(BookCycleButton.class::isInstance).map(AbstractWidget.class::cast).toList();
                if(disabledCycles.get(1).active||settingsView.children().stream().filter(BookSlider.class::isInstance).map(AbstractWidget.class::cast).anyMatch(widget->widget.active))
                    throw new AssertionError("Disabled banner controls remained interactive");
                banner=settingsView.children().stream().filter(BookCycleButton.class::isInstance).map(AbstractWidget.class::cast).findFirst().orElseThrow();
                settingsView.setFocused(banner);
                settingsView.keyPressed(GLFW.GLFW_KEY_SPACE,0,0);
                AbstractWidget slider=settingsView.children().stream().filter(BookSlider.class::isInstance).map(AbstractWidget.class::cast).findFirst().orElseThrow();
                int draftDuration=(int)field(settings,"duration");
                settingsView.mouseClicked(slider.getX()+slider.getWidth()-3,slider.getY()+slider.getHeight()/2.0,0);
                settingsView.mouseDragged(slider.getX()+slider.getWidth()-3,slider.getY()+slider.getHeight()/2.0,0,slider.getWidth(),0);
                settingsView.mouseReleased(slider.getX()+slider.getWidth()-3,slider.getY()+slider.getHeight()/2.0,0);
                if((int)field(settings,"duration")==draftDuration)throw new AssertionError("Settings slider did not update the draft");
                int resizedDraft=(int)field(settings,"duration");
                settingsView.resize(mc,settingsView.width,settingsView.height);
                if((int)field(settings,"duration")!=resizedDraft)throw new AssertionError("Settings resize lost its draft");
                press(settingsView,Component.translatableWithFallback("menu.mcakingdoms.settings.cancel","Cancel").getString());
                if(dev.otectus.mcakingdoms.presentation.PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get()!=savedBanner
                        ||dev.otectus.mcakingdoms.presentation.PresentationConfig.OVERLAY_DURATION_TICKS.get()!=savedDuration)
                    throw new AssertionError("Settings Cancel changed global configuration");
                screen=new KingdomsSettingsScreen(null);mc.setScreen(screen);
                behaviorChecked=true;ticks=15;return false;
            }
            if (page == 14) {
                assertBookButtons(screen,"Settings");
                press(screen,Component.translatableWithFallback("menu.mcakingdoms.settings.tab.tasks","Tasks and Book").getString());
                if((int)field(screen,"page")!=1)throw new AssertionError("Settings Tasks tab did not open");
                assertBookButtons(screen,"Settings Tasks tab");
                behaviorChecked=true;ticks=15;return false;
            }
            if (page == 16) {
                // 0.3.0 (A02): the Politics tab, by keyboard; a draft changes nothing until Apply and Cancel keeps the file.
                assertBookButtons(screen,"Settings");
                String politics=Component.translatableWithFallback("menu.mcakingdoms.settings.tab.politics","Politics").getString();
                press(screen,politics);
                if((int)field(screen,"page")!=2)throw new AssertionError("Settings Politics tab did not open");
                assertBookButtons(screen,"Settings Politics tab");
                var savedNotices=dev.otectus.mcakingdoms.presentation.PresentationConfig.POLITICAL_NOTICES.get();
                var cycles=screen.children().stream().filter(BookCycleButton.class::isInstance).map(AbstractWidget.class::cast).toList();
                var sliders=screen.children().stream().filter(BookSlider.class::isInstance).map(AbstractWidget.class::cast).toList();
                if(cycles.size()!=2||sliders.size()!=1)throw new AssertionError("Politics tab should offer notices, badges and badge distance");
                if(sliders.get(0).active!=(boolean)field(screen,"badges"))throw new AssertionError("Badge distance must follow the badge switch");
                screen.setFocused(cycles.get(0));
                if(!screen.keyPressed(GLFW.GLFW_KEY_SPACE,0,0)||field(screen,"notices")==savedNotices)throw new AssertionError("Notices cycle did not change the draft");
                if(dev.otectus.mcakingdoms.presentation.PresentationConfig.POLITICAL_NOTICES.get()!=savedNotices)throw new AssertionError("Notices cycle wrote configuration before Apply");
                press(screen,Component.translatableWithFallback("menu.mcakingdoms.settings.cancel","Cancel").getString());
                if(dev.otectus.mcakingdoms.presentation.PresentationConfig.POLITICAL_NOTICES.get()!=savedNotices)throw new AssertionError("Settings Cancel changed political notices");
                screen=new KingdomsSettingsScreen(null);mc.setScreen(screen);
                press(screen,politics);
                behaviorChecked=true;ticks=15;return false;
            }
            if (page == 17) {
                // 0.3.0 (A01): Register my residence opens as a real form and explains its evidence; nothing is applied here.
                var residence=reply(screen);
                if(residence.mode().equals("error")||residence.mode().equals("unavailable"))
                    throw new AssertionError("Register my residence did not open: "+residence.mode()+" "+residence.detail());
                System.out.println("RESIDENCE_FORM mode="+residence.mode()+" steps="+residence.steps()+" action="+residence.action());
            }
            if (page == 15 && !historyConfirmOpened) {
                assertBookButtons(screen,"Settings");
                press(screen,Component.translatableWithFallback("menu.mcakingdoms.settings.tab.tasks","Tasks and Book").getString());
                var state=ClientState.get();
                historyBookmarks=state.bookmarks();historyTopics=state.recentTopics();historyTasks=state.recentTasks();historyFavorites=state.favoriteTasks();historyLastRead=state.lastRead();
                press(screen,Component.translatableWithFallback("menu.mcakingdoms.settings.history","Clear bookmarks and history…").getString());
                if(!(mc.screen instanceof BookConfirmScreen))throw new AssertionError("Clear history did not open the manuscript confirmation");
                screen=mc.screen;historyConfirmOpened=true;captured=false;ticks=0;return false;
            }
            if (page == 15 && historyConfirmOpened) {
                if(!(screen instanceof BookConfirmScreen))throw new AssertionError("History confirmation was replaced before capture");
                assertBookButtons(screen,"History confirmation");
            }
            behaviorChecked=true;
        }
        for(var child:screen.children())if(child instanceof AbstractWidget widget&&widget.visible) {
            if(widget.getX()<0||widget.getY()<0||widget.getX()+widget.getWidth()>screen.width||widget.getY()+widget.getHeight()>screen.height)
                throw new AssertionError("GUI widget outside screen: "+widget.getMessage().getString()+" on "+PAGES[page]);
        }
        var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w->w.visible).toList();
        for(int a=0;a<widgets.size();a++)for(int b=a+1;b<widgets.size();b++){
            var one=widgets.get(a);var two=widgets.get(b);
            if(one.getX()<two.getX()+two.getWidth()&&one.getX()+one.getWidth()>two.getX()
                    &&one.getY()<two.getY()+two.getHeight()&&one.getY()+one.getHeight()>two.getY())
                throw new AssertionError("Overlapping widgets on "+PAGES[page]+": "+one.getMessage().getString()+" / "+two.getMessage().getString());
        }
        String name=prefix+"gui-"+(scale==2?"compact":2+scale)+"-"+PAGES[page]+".png";
        Path image=output.resolve("screenshots").resolve(name);
        if(!captured){Screenshot.grab(output.toFile(),name,mc.getMainRenderTarget(),message->{});captured=true;return false;}
        if(!Files.isRegularFile(image)||Files.size(image)==0)return false;
        if(page==15&&!historyChecked){
            Screen confirmation=screen;
            confirmation.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
            if(!(mc.screen instanceof KingdomsSettingsScreen))throw new AssertionError("History confirmation Escape did not return to Settings");
            assertHistoryUnchanged();
            Screen settings=mc.screen;
            press(settings,Component.translatableWithFallback("menu.mcakingdoms.settings.history","Clear bookmarks and history…").getString());
            if(!(mc.screen instanceof BookConfirmScreen))throw new AssertionError("History confirmation did not reopen for No");
            press(mc.screen,net.minecraft.network.chat.CommonComponents.GUI_NO.getString());
            if(!(mc.screen instanceof KingdomsSettingsScreen))throw new AssertionError("History confirmation No did not return to Settings");
            assertHistoryUnchanged();
            AtomicInteger answers=new AtomicInteger();
            var disposable=new BookConfirmScreen(confirmed->{if(confirmed)answers.incrementAndGet();},Component.literal("Disposable confirmation"),Component.literal("No local state is changed."));
            mc.setScreen(disposable);
            Screen disposableView=disposable;
            press(disposableView,net.minecraft.network.chat.CommonComponents.GUI_YES.getString());
            press(disposableView,net.minecraft.network.chat.CommonComponents.GUI_YES.getString());
            if(answers.get()!=1)throw new AssertionError("Book confirmation invoked Yes more than once");
            screen=(Screen)mc.screen;
            mc.setScreen((KingdomsSettingsScreen)settings);
            screen=settings;
            historyChecked=true;
            return false;
        }
        System.out.println("GUI_STYLE_PASS "+name);step++;screen=null;return step==STEPS;
    }
    private void assertHistoryUnchanged(){
        var state=ClientState.get();
        if(!historyBookmarks.equals(state.bookmarks())||!historyTopics.equals(state.recentTopics())||!historyTasks.equals(state.recentTasks())
                ||!historyFavorites.equals(state.favoriteTasks())||!historyLastRead.equals(state.lastRead()))
            throw new AssertionError("History confirmation changed local state without Yes");
    }
    private static void assertBookButtons(Screen screen,String owner){
        for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button&&!(child instanceof BookButton))
            throw new AssertionError(owner+" button lost manuscript styling: "+((net.minecraft.client.gui.components.Button)child).getMessage().getString());
    }
    private static void manuscript(RichPanel panel,String owner)throws Exception{
        if(!((boolean)field(panel,"manuscript")))throw new AssertionError(owner+" has no paper content theme");
    }
    private static void press(Screen screen,String label){
        var button=screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance)
                .map(net.minecraft.client.gui.components.Button.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing button "+label));
        if(!button.active)throw new AssertionError("Disabled button: "+label);
        screen.setFocused(button);
        if(!screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0))throw new AssertionError("Keyboard button activation failed: "+label);
    }
    private static final class EntryPreview extends Screen {
        private final dev.otectus.mcakingdoms.presentation.SettlementSummary settlement;
        EntryPreview(dev.otectus.mcakingdoms.presentation.SettlementSummary settlement){super(Component.literal("Entry banner"));this.settlement=settlement;}
        @Override public void render(GuiGraphics graphics,int x,int y,float tick){
            renderBackground(graphics);
            try {
                var type=Class.forName("dev.otectus.mcakingdoms.client.SettlementOverlayRenderer");
                var draw=type.getDeclaredMethod("render",GuiGraphics.class,net.minecraft.client.gui.Font.class,
                        dev.otectus.mcakingdoms.presentation.SettlementSummary.class,dev.otectus.mcakingdoms.presentation.KingdomSummary.class,
                        int.class,int.class,int.class,int.class,int.class,float.class);draw.setAccessible(true);
                draw.invoke(null,graphics,font,settlement,new dev.otectus.mcakingdoms.presentation.KingdomSummary(
                        new ResourceLocation("mcakingdoms:serenum"),"kingdom.mcakingdoms.serenum",new ResourceLocation("mcakingdoms:serenum"),0x426946),width,height,54,40,80,0.0F);
            }catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            super.render(graphics,x,y,tick);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
    private static Object field(Object instance,String name)throws Exception {var f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
    private static final class HeaderPreview extends Screen {
        HeaderPreview(){super(Component.literal("Blueprint header"));}
        @Override public void render(GuiGraphics graphics,int x,int y,float tick) {
            renderBackground(graphics);
            try {
                var draw=TownsteadBlueprintHeader.class.getDeclaredMethod("draw",GuiGraphics.class,Screen.class,Header.class);draw.setAccessible(true);
                draw.invoke(null,graphics,this,new Header("Willowbrook",new ResourceLocation("mcakingdoms:serenum"),"kingdom.mcakingdoms.serenum",new ResourceLocation("mcakingdoms:serenum"),0x426946,"respected"));
            }catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            super.render(graphics,x,y,tick);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
