package dev.otectus.mcakingdoms.client;

import com.google.gson.JsonParser;
import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.book.BookButton;
import dev.otectus.mcakingdoms.client.book.BookLayout;
import dev.otectus.mcakingdoms.client.book.BookPieces;
import dev.otectus.mcakingdoms.client.book.BookSearchBox;
import dev.otectus.mcakingdoms.client.book.BookSkin;
import dev.otectus.mcakingdoms.client.book.BookIllustrations;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import dev.otectus.mcakingdoms.interaction.PlayerWords;
import dev.otectus.mcakingdoms.interaction.SearchText;
import dev.otectus.mcakingdoms.presentation.PresentationConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * Named task browser and server-reviewed forms, with no chat or command execution path.
 *
 * <p>The key opens a start view: where you are, up to three next steps the server derives from your own state, your
 * favourite and recent tasks, and the Guide, Guilds and Settings. The catalogue arrives as an index of the tasks the
 * server authorizes for this player and is searched here, in the player's own language (resource packs included) as well
 * as English, including goal phrases ("paid work") and categories. A task this server cannot run stays listed with its
 * reason. Opening a task, every choice list and every action stays server-side and is validated there.
 */
public final class KingdomActionsScreen extends Screen {
    static final int ROW=24;
    /** One authorized task from the index: {@code group} is its stable category; {@code unavailable} names why it cannot run here. */
    private record IndexEntry(String id,boolean consequential,String title,String category,String help,String group,String unavailable,String reason,String learn) {}
    private static final List<String> GROUPS=List.of("PLACES","GUILDS","GOVERNMENT","EXPLORATION","CONFLICT","WORLD_EVENTS","HELP","OPERATOR");
    private final Screen parent;
    private final String initialTask,initialSearch,prefill;
    private InteractionNetwork.Reply reply;
    /** The last start view from the server, shown again by Start without asking. */
    private InteractionNetwork.Reply start;
    private UUID pending,lateRequest,actionRequest;
    private InteractionNetwork.Request lastSent;
    private int timeout,localPage,debounce;
    private EditBox input;
    /** What the player typed in a choice search since the last request, kept over a reply for an older query. */
    private String typedSearch;
    private MenuTextPanel detail;
    private RichPanel startPanel, shortcutPanel;
    private BookLayout folio;
    private int pageHeight;
    private Component notice=null;
    private int left,w,rows;
    private final List<Button> rowButtons=new ArrayList<>();
    private final Map<Button,String> rowIds=new HashMap<>();
    private final Map<String,Button> controls=new LinkedHashMap<>();
    /** The authorized catalogue from the server, searched locally; null until it arrives. */
    private List<IndexEntry> index;
    private boolean operatorIndex,showHelp,choosingEdit,showingCategories,wantStart;
    private String catalogueSearch="",relatedPrefix="",group="";
    /** What had keyboard focus before a request, restored by stable identity after the reply. */
    private String focusedRow,focusedControl;
    private int caret=-1,highlight=-1;
    private String narrated="";
    public KingdomActionsScreen(Screen parent,String initialTask,String initialSearch){this(parent,initialTask,initialSearch,"");}
    /** {@code prefill} is a JSON object of task field keys to values known by the opening screen, such as the settlement it shows. */
    public KingdomActionsScreen(Screen parent,String initialTask,String initialSearch,String prefill){
        super(text("catalogue.title","Kingdom Tasks"));this.parent=parent;this.initialTask=initialTask;this.initialSearch=initialSearch==null?"":initialSearch;this.prefill=prefill;this.catalogueSearch=this.initialSearch;
        wantStart=initialTask.isEmpty()&&this.initialSearch.isEmpty()&&PresentationConfig.TASKS_START.get()==PresentationConfig.TasksStart.START;
    }
    static MutableComponent text(String key,String fallback,Object... args){return Component.translatableWithFallback("interaction.mcakingdoms."+key,fallback,args);}
    @Override public void resize(Minecraft client,int width,int height){
        String draft=input==null?null:input.getValue();
        var anchor=startPanel==null?null:startPanel.anchor();
        var shortcuts=shortcutPanel==null?null:shortcutPanel.anchor();
        boolean readingShortcuts=shortcutPanel!=null&&getFocused()==shortcutPanel;
        super.resize(client,width,height);
        if(draft!=null&&input!=null)input.setValue(draft);
        if(anchor!=null&&startPanel!=null)startPanel.setPieces(startPanel.pieces(),shortcutPanel==null&&readingShortcuts?shortcuts:anchor);
        if(shortcuts!=null&&shortcutPanel!=null)shortcutPanel.setPieces(shortcutPanel.pieces(),shortcuts);
    }
    private <T extends AbstractWidget> T addPageWidget(T widget){
        widget.setY(widget.getY()+folio.y());
        return addRenderableWidget(widget);
    }
    private <T extends AbstractWidget> T control(String role,T widget){controls.put(role,widget instanceof Button button?button:null);addPageWidget(widget);return widget;}
    private boolean listing(){return reply!=null&&!showHelp&&!showingCategories&&(reply.mode().equals("tasks")||reply.mode().equals("choice"));}
    @Override protected void init(){
        input=null;rowButtons.clear();rowIds.clear();controls.clear();startPanel=null;shortcutPanel=null;
        folio=BookLayout.fit(width,height);pageHeight=folio.height()-8;
        w=folio.width()-28;left=folio.x()+14;rows=Math.max(1,(pageHeight-166)/ROW);int third=(w-32)/3;
        if(reply==null){detail=addPageWidget(new MenuTextPanel(left+8,42,w-16,pageHeight-84,text("connecting","Connecting")).manuscript());detail.setContent(List.of(notice==null?text("requesting","Requesting your available tasks…"):notice));
            control("close",BookButton.builder(text("close","Close"),b->onClose()).bounds(left+8,pageHeight-32,80,20).build());
            if(pending==null)send(initialTask.isEmpty()?"INDEX":"TASK",initialTask,"",0);return;}
        if(showHelp){initHelp(third);return;}
        if(showingCategories){initCategories(third);restoreFocus(false);return;}
        if(reply.mode().equals("start")){initStart(third);restoreFocus(false);return;}
        boolean tasks=reply.mode().equals("tasks"),listing=listing();
        if(listing){
            localPage=Math.min(localPage,Math.max(0,(reply.options().size()-1)/rows));
            input=addPageWidget(new BookSearchBox(font,left+8,37,w-162,20,text("search.hint","Search by name or task")));input.setMaxLength(100);
            input.setValue(!tasks&&typedSearch!=null?typedSearch:reply.value());input.setHint(text("search.hint","Search by name or task"));
            if(caret>=0&&input.getValue().length()>=caret){input.setCursorPosition(caret);input.setHighlightPos(Math.min(Math.max(0,highlight),input.getValue().length()));}
            input.setResponder(value->{var clear=controls.get("clear");if(clear!=null)clear.active=!value.isEmpty()&&pending==null;
                // The catalogue is searched here as you type; record choices are searched on the server after a pause.
                if(reply!=null&&reply.mode().equals("tasks")){if(!value.equals(catalogueSearch)){catalogueSearch=value;caret=input.getCursorPosition();highlight=caret;focusedControl="search_box";localPage=0;showCatalogue();}}
                else{typedSearch=value;debounce=8;}});
            var clear=control("clear",BookButton.builder(text("search.clear","Clear"),b->clearSearch()).bounds(left+w-150,37,60,20).tooltip(Tooltip.create(text("search.clear.help","Clear the search and show every entry again."))).build());clear.active=!input.getValue().isEmpty();
            control("search",BookButton.builder(text("search","Search"),b->search()).bounds(left+w-86,37,78,20).build());
            int start=localPage*rows;
            for(int i=0;i<rows&&start+i<reply.options().size();i++){
                var option=reply.options().get(start+i);
                Component label=option.labelComponent(),detailText=option.detailComponent();
                Component first=tasks?label:option.selected()?Component.literal("✓ ").append(label):label;
                Component second=tasks&&option.selected()?text("changes","Changes records").append(" · ").append(detailText):detailText;
                var row=BookButton.builder(first,b->{if(!reply.multi())localPage=0;if(tasks)open(option.key());else send("PICK",option.key(),"",0);})
                        .bounds(left+8,87+i*ROW,w-16,20)
                        .tooltip(Tooltip.create(label.copy().append("\n").append(second)))
                        .createNarration(supplier->supplier.get().append(". ").append(second)).build().kind(BookButton.Kind.INDEX).selected(!tasks&&option.selected());
                rowButtons.add(addPageWidget(row));rowIds.put(row,option.id()==null?option.key():option.id());
            }
            if(reply.options().isEmpty()){detail=addPageWidget(new MenuTextPanel(left+8,87,w-16,pageHeight-160,emptyHeading()).manuscript());detail.setContent(paragraphs(reply.detailComponent()));}
            int y=pageHeight-61;
            var previous=control("previous",BookButton.builder(text("previous","Previous page"),b->{if(localPage>0){localPage--;rebuildWidgets();}else send("FILTER","",input.getValue(),reply.page()-1);}).bounds(left+8,y,Math.min(125,third),20).build());previous.active=localPage>0||!tasks&&reply.page()>0;
            var next=control("next",BookButton.builder(text("next","Next page"),b->{if((localPage+1)*rows<reply.options().size()){localPage++;rebuildWidgets();}else{localPage=0;send("FILTER","",input.getValue(),reply.page()+1);}}).bounds(left+w-Math.min(125,third)-8,y,Math.min(125,third),20).build());next.active=(localPage+1)*rows<reply.options().size()||!tasks&&reply.more();
            if(reply.multi())control("use",BookButton.builder(text("use_selections","Use selections"),b->send("NEXT","","",0)).bounds(left+w/3,y,w/3,20).build());
            else if(tasks)control("categories",BookButton.builder(group.isEmpty()?text("categories","Categories…"):text("category.current","Category: %s",groupLabel(group)),b->{showingCategories=true;localPage=0;rebuildWidgets();}).bounds(left+w/3,y,w/3,20)
                    .tooltip(Tooltip.create(text("categories.help","Show only one kind of task: places, guilds and jobs, government, exploration, conflict or world events."))).build());
        }else if(reply.mode().equals("text")){
            detail=addPageWidget(new MenuTextPanel(left+8,40,w-16,pageHeight-145,reply.titleComponent()).manuscript());detail.setContent(paragraphs(reply.detailComponent()));
            input=addPageWidget(new BookSearchBox(font,left+8,pageHeight-96,w-16,20,reply.titleComponent()));input.setMaxLength(512);input.setValue(reply.value());
            control("continue",BookButton.builder(text("continue","Continue"),b->send("NEXT",input.getValue(),"",0)).bounds(left+w-108,pageHeight-61,100,20).build());setInitialFocus(input);
        }else if(reply.mode().equals("review")&&choosingEdit){
            // Choose which answer to change; the server returns that field with the earlier answer kept as the draft.
            localPage=Math.min(localPage,Math.max(0,(reply.options().size()-1)/rows));int start=localPage*rows;
            for(int i=0;i<rows&&start+i<reply.options().size();i++){var field=reply.options().get(start+i);
                var row=BookButton.builder(text("edit.row","Edit: %s — %s",field.labelComponent(),field.detailComponent()),b->{choosingEdit=false;localPage=0;send("EDIT",field.key(),"",0);})
                        .bounds(left+8,87+i*ROW,w-16,20).tooltip(Tooltip.create(text("edit.row.help","Change this answer. Answers that depend on it are asked again; the review is rebuilt before you can apply."))).build().kind(BookButton.Kind.INDEX);
                rowButtons.add(addPageWidget(row));rowIds.put(row,field.id());}
            int y=pageHeight-61;
            control("previous",BookButton.builder(text("previous","Previous page"),b->{localPage--;rebuildWidgets();}).bounds(left+8,y,Math.min(125,third),20).build()).active=localPage>0;
            control("cancel_edit",BookButton.builder(text("edit.cancel","Back to review"),b->{choosingEdit=false;localPage=0;rebuildWidgets();}).bounds(left+w/3,y,w/3,20).build());
            control("next",BookButton.builder(text("next","Next page"),b->{localPage++;rebuildWidgets();}).bounds(left+w-Math.min(125,third)-8,y,Math.min(125,third),20).build()).active=(localPage+1)*rows<reply.options().size();
            if(focusedRow==null&&focusedControl==null&&!rowButtons.isEmpty())focusedRow=rowIds.get(rowButtons.get(0));
        }else{
            var next=nextStep();int panelHeight=pageHeight-109-(next.isPresent()?24:0);
            detail=addPageWidget(new MenuTextPanel(left+8,40,w-16,panelHeight,reply.titleComponent()).manuscript());detail.setContent(withStatus(paragraphs(reply.detailComponent())));
            next.ifPresent(step->control("next_step",BookButton.builder(text("next_step","Next step: %s",InteractionNetwork.component(step.get("label"))),b->{localPage=0;sendTask(step.get("task"),step.get("prefill"));})
                    .bounds(left+8,pageHeight-85,w-16,20).tooltip(Tooltip.create(text("next_step.help","Opens a related task. The server checks your access and every choice again."))).build()));
            int y=pageHeight-61,quarter=(w-40)/4;
            if(reply.mode().equals("error")&&lastSent!=null){
                control("check",BookButton.builder(text("check","Check this action"),b->send("CHECK",(actionRequest==null?lastSent.request():actionRequest).toString(),"",0)).bounds(left+8,y,third,20)
                        .tooltip(Tooltip.create(text("check.help","Asks for the outcome of the action you sent, without sending it again."))).build());
                control("copy",copyButton(left+w-8-third,y,third));
            }
            if(reply.mode().equals("unavailable")){
                String learn=reply.learnOrEmpty();
                var learnButton=control("learn",BookButton.builder(text("learn_more","Learn more in the Book"),b->minecraft.setScreen(new KingdomGuideScreen(this,learn))).bounds(left+8,y,third,20)
                        .tooltip(Tooltip.create(text("learn_more.help","Opens the Book topic about this feature."))).build());learnButton.active=!learn.isEmpty();
                control("copy",copyButton(left+16+third,y,third));
            }
            if(reply.mode().equals("review")){
                boolean editable=!reply.options().isEmpty();
                if(editable)control("edit",BookButton.builder(text("edit","Edit an answer"),b->{choosingEdit=true;rebuildWidgets();}).bounds(left+8,y,third,20)
                        .tooltip(Tooltip.create(text("edit.help","Change one answer without starting over."))).build());
                Component action=reply.actionComponent().getString().isBlank()?text("apply","Apply reviewed action"):reply.actionComponent();
                control("apply",BookButton.builder(action,b->send("APPLY","","",0)).bounds(editable?left+16+third:left+w/2-100,y,editable?2*third+8:200,20)
                        .tooltip(Tooltip.create(text("apply.help","Performs this action once, as reviewed above. The server checks everything again."))).build());
            }
            if(reply.mode().equals("result")){
                String task=reply.value();
                // Only an action has an outcome to check; a read-only result is already the current record.
                boolean action=!reply.statusOrEmpty().equals("read");
                int cells=action?5:4,cell=(w-16-8*(cells-1))/cells,at=0;
                if(action)control("check",BookButton.builder(text("check","Check this action"),b->send("CHECK",(actionRequest==null?lastSent.request():actionRequest).toString(),"",0)).bounds(left+8+(cell+8)*at++,y,cell,20)
                        .tooltip(Tooltip.create(text("check.help","Asks for the outcome of the action you sent, without sending it again."))).build()).active=lastSent!=null;
                control("again",BookButton.builder(action?text("again","Same task again"):text("again.read","Read again"),b->{localPage=0;open(task);}).bounds(left+8+(cell+8)*at++,y,cell,20).build()).active=!task.isEmpty();
                control("related",BookButton.builder(text("related","Related tasks"),b->{localPage=0;showRelated(task);}).bounds(left+8+(cell+8)*at++,y,cell,20).build()).active=!task.isEmpty();
                boolean pinned=ClientState.get().pinnedTask().map(task::equals).orElse(false);
                control("pin",BookButton.builder(pinned?text("pin.remove","Unpin"):text("pin.add","Pin to screen"),b->{PinnedTask.toggle(task);focusedControl="pin";rebuildWidgets();}).bounds(left+8+(cell+8)*at++,y,cell,20)
                        .tooltip(Tooltip.create(pinned?text("pin.remove.help","Stop showing this task at the top of your screen."):text("pin.add.help","Shows this task at the top of your screen until you unpin it. Pinning never runs it."))).build()).active=!task.isEmpty();
                boolean favorite=ClientState.get().favorite(task);
                control("favorite",BookButton.builder(favorite?text("favorite.remove","★ Favourite"):text("favorite.add","☆ Favourite"),b->{ClientState.get().toggleFavorite(task);focusedControl="favorite";rebuildWidgets();}).bounds(left+8+(cell+8)*at,y,w-16-(cell+8)*at,20)
                        .tooltip(Tooltip.create(favorite?text("favorite.remove.help","Remove this task from the favourites on your start view."):text("favorite.add.help","Show this task on your start view."))).build()).active=!task.isEmpty();
            }
        }
        // Help for the task on screen: the Book topic that explains it, returning here when closed.
        String taskId=reply.mode().equals("tasks")?"":reply.mode().equals("review")||reply.mode().equals("result")||reply.mode().equals("unavailable")?reply.value():lastTask;
        var topic=taskId==null||taskId.isEmpty()?Optional.<String>empty():dev.otectus.mcakingdoms.client.guide.GuideLibrary.topicForTask(taskId);
        if(topic.isPresent())control("help_topic",BookButton.builder(text("help.topic","Help"),b->minecraft.setScreen(new KingdomGuideScreen(this,topic.get()))).bounds(left+w-58,10,50,20)
                .tooltip(Tooltip.create(text("help.topic.tip","Opens the Book topic about this task; you return here when you close it."))).build());
        var back=control("back",BookButton.builder(listing&&reply.mode().equals("tasks")?text("start.button","Start"):text("back","Back"),b->{localPage=0;if(reply.mode().equals("tasks"))showStart();else send("BACK","","",0);}).bounds(left+8,pageHeight-32,third,20)
                .tooltip(Tooltip.create(reply.mode().equals("tasks")?text("start.button.help","Where you are, suggested next steps and your shortcuts."):text("back.help","The previous question; your answers are kept."))).build());
        back.active=reply.mode().equals("tasks")||reply.back();
        control("all",BookButton.builder(text("all_tasks","All tasks"),b->{localPage=0;allTasks();}).bounds(left+16+third,pageHeight-32,third,20).build());
        control("close",BookButton.builder(text("close","Close"),b->onClose()).bounds(left+24+2*third,pageHeight-32,third,20)
                .tooltip(Tooltip.create(parent==null?text("close.game","Return to the game. An action already sent still completes."):text("close.parent","Return to the previous screen. An action already sent still completes."))).build());
        if(pending!=null)children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);
        restoreFocus(listing);
    }
    private Button copyButton(int x,int y,int width){
        return BookButton.builder(text("copy","Copy support details"),b->copySupportDetails()).bounds(x,y,width,20)
                .tooltip(Tooltip.create(text("copy.help","Copies versions, this task and its request reference to the clipboard for a bug report or an operator. Nothing is sent anywhere."))).build();
    }
    /** A result's outcome first, in words with a mark, so it never depends on colour or on parsing the message. */
    private List<Component> withStatus(List<Component> lines){
        if(reply==null||!reply.mode().equals("result"))return lines;
        Component status=switch(reply.statusOrEmpty()){
            case "done"->text("status.done","✓ Done: the server confirmed this action.");
            case "waiting"->text("status.waiting","… Waiting: the action is recorded and waits for another step. Use Check this action later.");
            case "refused"->text("status.refused","✗ Not done: the server refused it for the reason below.");
            case "unknown"->text("status.unknown","? Not confirmed: check its status before trying again.");
            default->null;};
        if(status==null)return lines;
        var out=new ArrayList<Component>();out.add(status.copy().withStyle(ChatFormatting.BOLD));out.addAll(lines);return out;
    }
    private void copySupportDetails(){
        var mod=net.minecraftforge.fml.ModList.get().getModContainerById("mcakingdoms").map(c->c.getModInfo().getVersion().toString()).orElse("?");
        var text=new StringBuilder();
        text.append("MCA: Kingdoms ").append(mod).append(" · Minecraft ").append(net.minecraft.SharedConstants.getCurrentVersion().getName())
                .append(" · Forge ").append(net.minecraftforge.versions.forge.ForgeVersion.getVersion()).append('\n');
        if(reply!=null){text.append("Page: ").append(reply.mode()).append(" · ").append(reply.titleText()).append('\n');
            if(!reply.value().isBlank()&&!reply.mode().equals("tasks"))text.append("Task: ").append(reply.value()).append('\n');
            if(!reply.statusOrEmpty().isEmpty())text.append("Status: ").append(reply.statusOrEmpty()).append('\n');}
        if(lastSent!=null)text.append("Request: ").append(actionRequest==null?lastSent.request():actionRequest).append('\n');
        if(reply!=null)text.append('\n').append(reply.detailText());
        minecraft.keyboardHandler.setClipboard(text.toString());notice=text("copy.done","Support details copied to the clipboard.");
        detail.setContent(withStatus(paragraphs(reply.detailComponent())));
    }
    /** The start view: where you are, next steps from your own state, favourites, recent tasks and the main destinations. */
    private void initStart(int third){
        input=addPageWidget(new BookSearchBox(font,left+8,37,w-102,20,text("search.hint","Search by name or task")));input.setMaxLength(100);input.setHint(text("start.search.hint","Search for a goal, such as paid work or where am I"));
        input.setResponder(value->{if(!value.isEmpty()){catalogueSearch=value;caret=value.length();highlight=caret;focusedControl="search_box";group="";relatedPrefix="";localPage=0;showCatalogue();}});
        control("search",BookButton.builder(text("search","Search"),b->search()).bounds(left+w-86,37,78,20).build());
        boolean columns=folio.spread();
        int panelWidth=columns?(w-40)/2:w-16;
        startPanel=addPageWidget(new RichPanel(left+8,62,panelWidth,pageHeight-62-66,text("start.title","Start")).manuscript());
        var pieces=startPieces();
        int split=pieces.size();
        if(columns){
            for(int i=0;i<pieces.size();i++)if(Set.of("favorites_heading","recent_heading","server_heading").contains(pieces.get(i).anchorId())){split=i;break;}
            shortcutPanel=addPageWidget(new RichPanel(left+32+panelWidth,62,panelWidth,pageHeight-62-66,text("start.shortcuts","Your shortcuts")).manuscript());
            var shortcuts=new ArrayList<RichPanel.Piece>();
            shortcuts.add(Pieces.heading("shortcuts_heading",text("start.shortcuts","Your shortcuts")));
            if(folio.height()>=320)shortcuts.add(BookIllustrations.TASKS.piece("landscape",panelWidth-16));
            shortcuts.addAll(pieces.subList(split,pieces.size()));
            shortcutPanel.setPieces(shortcuts);
        }
        startPanel.setPieces(pieces.subList(0,split));
        int y=pageHeight-56,quarter=(w-40)/4;
        control("all",BookButton.builder(text("all_tasks","All tasks"),b->{localPage=0;allTasks();}).bounds(left+8,y,third,20).build());
        control("categories",BookButton.builder(text("categories","Categories…"),b->{showingCategories=true;localPage=0;rebuildWidgets();}).bounds(left+16+third,y,third,20)
                .tooltip(Tooltip.create(text("categories.help","Show only one kind of task: places, guilds and jobs, government, exploration, conflict or world events."))).build());
        control("help",BookButton.builder(text("help.open","Getting started"),b->{showHelp=true;rebuildWidgets();}).bounds(left+24+2*third,y,third,20)
                .tooltip(Tooltip.create(text("help.open.tooltip","First steps: what to read first, the two books and their recipes."))).build());
        y=pageHeight-32;
        control("guide",BookButton.builder(text("guide","Guide"),b->minecraft.setScreen(new KingdomGuideScreen(this,"start_here"))).bounds(left+8,y,quarter,20)
                .tooltip(Tooltip.create(text("guide.help","Opens the Book of Kingdoms here; you do not need to own the book."))).build());
        control("guilds",BookButton.builder(Component.translatable("civic.mcakingdoms.title"),b->minecraft.setScreen(new GuildScreen(this))).bounds(left+16+quarter,y,quarter,20)
                .tooltip(Tooltip.create(text("guilds.help","Your guild memberships, progress and the services you qualify for."))).build());
        control("settings",BookButton.builder(text("settings","Settings"),b->minecraft.setScreen(new KingdomsSettingsScreen(this))).bounds(left+24+2*quarter,y,quarter,20)
                .tooltip(Tooltip.create(text("settings.help","Entry banner, start view, introduction and Book options, kept on this computer."))).build());
        control("close",BookButton.builder(text("close","Close"),b->onClose()).bounds(left+32+3*quarter,y,w-40-3*quarter,20).build());
        if(pending!=null)children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);
    }
    private List<RichPanel.Piece> startPieces(){
        var pieces=new ArrayList<RichPanel.Piece>();
        if(PresentationConfig.SHOW_INTRO.get()){
            pieces.add(Pieces.heading("intro_heading",text("start.intro.title","Welcome")));
            pieces.add(BookPieces.opening("intro",text("start.intro","Kingdom Tasks is where you read records and act. Tasks that change records end on a review page; nothing happens until you confirm. Press %s to come back here.",Component.keybind("key.mcakingdoms.actions"))));
            pieces.add(Pieces.actions("intro_actions",List.of(Pieces.button("hide_intro",text("start.intro.hide","Hide this introduction"),text("start.intro.hide.help","You can show it again in Settings."),()->{PresentationConfig.SHOW_INTRO.set(false);PresentationConfig.SPEC.save();focusedControl=null;rebuildWidgets();},true))));
        }
        try{
            var json=JsonParser.parseString(start.value()).getAsJsonObject();
            pieces.add(Pieces.heading("where_heading",text("start.where","Where you are")));
            if(json.has("here")){var here=json.getAsJsonObject("here");
                pieces.add(Pieces.text("where",text("start.here.line","%s, a settlement of %s.",here.get("name").getAsString(),InteractionNetwork.component(here.get("kingdom").getAsString()))));
                String id=here.get("id").getAsString();
                pieces.add(Pieces.actions("where_actions",List.of(Pieces.button("inspect_here",text("start.inspect","Read this settlement"),text("start.inspect.help","Its kingdom, location and what you can do there."),()->open("settlement.here"),true),
                        Pieces.button("ledger_here",text("start.war_room","Open its War Room"),text("start.war_room.help","Who controls it, current campaigns and civilian options."),()->minecraft.setScreen(new WarRoomScreen(this,UUID.fromString(id),here.get("name").getAsString())),true))));
            }else pieces.add(Pieces.text("where",text("start.nowhere","You are not inside a recognized settlement. Walk into a village; the first time, a banner names it."),Pieces.MUTED,0));
            pieces.add(Pieces.heading("next_heading",text("start.next","Suggested next steps")));
            var suggestions=json.getAsJsonArray("suggestions");
            if(suggestions.isEmpty())pieces.add(Pieces.text("next_none",start.detailComponent(),Pieces.MUTED,0));
            int n=0;
            for(var element:suggestions){var o=element.getAsJsonObject();n++;
                String kind=o.get("kind").getAsString(),target=o.get("target").getAsString(),prefill=o.get("prefill").getAsString();
                Component label=InteractionNetwork.component(o.get("label").getAsString()),reason=InteractionNetwork.component(o.get("reason").getAsString());
                pieces.add(Pieces.text("next_reason_"+n,reason,Pieces.MUTED,0));
                pieces.add(Pieces.actions("next_"+n,List.of(Pieces.button("suggestion:"+target,label,reason,()->{if(kind.equals("guide"))minecraft.setScreen(new KingdomGuideScreen(this,target));else sendTask(target,prefill);},true))));
            }
        }catch(RuntimeException malformed){pieces.add(Pieces.text("start_unreadable",text("start.unreadable","The start view could not be read; use All tasks."),Pieces.MUTED,0));}
        var favorites=shortcuts(ClientState.get().favoriteTasks());
        if(!favorites.isEmpty()){pieces.add(Pieces.heading("favorites_heading",text("start.favorites","Your favourite tasks")));pieces.add(Pieces.actions("favorites",favorites));}
        var recent=shortcuts(ClientState.get().recentTasks());
        if(!recent.isEmpty()){pieces.add(Pieces.heading("recent_heading",text("start.recent","Recent tasks")));pieces.add(Pieces.actions("recent",recent));}
        pieces.add(Pieces.heading("server_heading",text("start.server","This server")));
        pieces.add(Pieces.actions("server",List.of(Pieces.button("features",text("start.features","Features on this server"),text("start.features.help","Which systems and companion mods this server has, and your own access."),()->open("help.available"),true))));
        return pieces;
    }
    /** Shortcut buttons for task ids still in this server's authorized index; others (another server's) are skipped. */
    private List<RichPanel.Target> shortcuts(List<String> ids){
        var out=new ArrayList<RichPanel.Target>();if(index==null)return out;
        for(String id:ids)index.stream().filter(e->e.id().equals(id)).findFirst().ifPresent(e->out.add(Pieces.button("shortcut:"+id,taskTitle(e),e.unavailable().isEmpty()?helpText(e):InteractionNetwork.component(e.reason()),()->open(id),true)));
        return out;
    }
    private void initCategories(int third){
        // Two columns, so every category fits even at the compact 240-pixel height.
        int top=56,half=(w-20)/2;
        var all=BookButton.builder(text("category.all","All categories"),b->{group="";showingCategories=false;catalogueSearch="";relatedPrefix="";showCatalogue();}).bounds(left+8,top,half,20).build();
        rowButtons.add(addPageWidget(all));rowIds.put(all,"group:");
        int i=1;
        for(String g:GROUPS){
            long count=index==null?0:index.stream().filter(e->e.group().equals(g)).count();
            if(count==0)continue;
            var row=BookButton.builder(text("category.row","%s (%s)",groupLabel(g),count),b->{group=g;showingCategories=false;catalogueSearch="";relatedPrefix="";localPage=0;showCatalogue();})
                    .bounds(left+8+(i%2)*(half+4),top+(i/2)*ROW,half,20).build();
            row.active=!g.equals(group);
            rowButtons.add(addPageWidget(row));rowIds.put(row,"group:"+g);i++;
        }
        control("back",BookButton.builder(text("back","Back"),b->{showingCategories=false;rebuildWidgets();}).bounds(left+8,pageHeight-32,third,20).build());
        control("all",BookButton.builder(text("all_tasks","All tasks"),b->{group="";showingCategories=false;allTasks();}).bounds(left+16+third,pageHeight-32,third,20).build());
        control("close",BookButton.builder(text("close","Close"),b->onClose()).bounds(left+24+2*third,pageHeight-32,third,20).build());
        if(focusedRow==null&&focusedControl==null&&!rowButtons.isEmpty())focusedRow=rowIds.get(rowButtons.get(0));
    }
    private static Component groupLabel(String group){
        return switch(group){case "PLACES"->text("group.places","Places");case "GUILDS"->text("group.guilds","Guilds and jobs");case "GOVERNMENT"->text("group.government","Government");
            case "EXPLORATION"->text("group.exploration","Exploration");case "CONFLICT"->text("group.conflict","Conflict");case "WORLD_EVENTS"->text("group.world_events","World events");
            case "HELP"->text("group.help","Help");case "OPERATOR"->text("group.operator","Operator tools");default->Component.literal(group);};
    }
    /** The Getting started panel: what to do first, before any book is crafted. Entirely client text; actions still go through the server. */
    private void initHelp(int third){
        detail=addPageWidget(new MenuTextPanel(left+8,40,w-16,pageHeight-109,text("help.title","Getting started")).manuscript());
        detail.setContent(GettingStarted.paragraphs());
        int y=pageHeight-61,quarter=(w-40)/4;
        control("help_here",BookButton.builder(text("help.here","Read this settlement"),b->{showHelp=false;open("settlement.here");}).bounds(left+8,y,quarter,20)
                .tooltip(Tooltip.create(text("help.here.tooltip","Names the settlement you are standing in, if it is recognized."))).build());
        control("help_known",BookButton.builder(text("help.known","Your known settlements"),b->{showHelp=false;open("settlement.inspect");}).bounds(left+16+quarter,y,quarter,20)
                .tooltip(Tooltip.create(text("help.known.tooltip","Lists the settlements you have discovered or that are public."))).build());
        control("help_kingdom",BookButton.builder(text("help.kingdom","Read a kingdom"),b->{showHelp=false;open("kingdom.inspect");}).bounds(left+24+2*quarter,y,quarter,20)
                .tooltip(Tooltip.create(text("help.kingdom.tooltip","Describes a kingdom and how many of its settlements you know."))).build());
        control("help_book",BookButton.builder(text("help.book","First steps in the Book"),b->{showHelp=false;minecraft.setScreen(new KingdomGuideScreen(this,"start_here"));}).bounds(left+32+3*quarter,y,w-40-3*quarter,20)
                .tooltip(Tooltip.create(text("help.book.tooltip","Opens the Book's first steps, with both recipes; you do not need to own the book."))).build());
        control("controls",BookButton.builder(text("help.controls","Controls…"),b->minecraft.setScreen(new KeyBindsScreen(this,minecraft.options))).bounds(left+8,pageHeight-32,third,20)
                .tooltip(Tooltip.create(text("help.controls.tooltip","Opens Minecraft's key bindings; the Kingdom Tasks key is under MCA: Kingdoms."))).build());
        control("all",BookButton.builder(text("all_tasks","All tasks"),b->{showHelp=false;allTasks();}).bounds(left+16+third,pageHeight-32,third,20).build());
        control("close",BookButton.builder(text("close","Close"),b->onClose()).bounds(left+24+2*third,pageHeight-32,third,20).build());
        setInitialFocus(detail);
    }
    private Component emptyHeading(){
        if(!reply.value().isBlank())return text("empty.no_match","No matches");
        return reply.mode().equals("tasks")?text("empty.no_tasks","No tasks"):text("no_choices","No choices");
    }
    /** The next step offered by the server for this result, if any. */
    private Optional<Map<String,String>> nextStep(){
        if(reply==null||reply.nextOrEmpty().isBlank())return Optional.empty();
        try{var json=JsonParser.parseString(reply.nextOrEmpty()).getAsJsonObject();var out=new HashMap<String,String>();for(var key:List.of("task","prefill","label"))out.put(key,json.has(key)?json.get(key).getAsString():"");
            return out.get("task").isBlank()?Optional.empty():Optional.of(out);}catch(RuntimeException ignored){return Optional.empty();}
    }
    /**
     * Splits rendered text into paragraphs for the panel, keeping each part's style; translation happens on this client.
     * Blank lines are dropped: the panel already separates paragraphs, so they would only widen the gaps.
     */
    static List<Component> paragraphs(Component component){
        List<Component> lines=new ArrayList<>();MutableComponent[] line={Component.empty()};
        component.visit((style,piece)->{String[] parts=piece.split("\n",-1);for(int i=0;i<parts.length;i++){if(i>0){lines.add(line[0]);line[0]=Component.empty();}if(!parts[i].isEmpty())line[0].append(Component.literal(parts[i]).withStyle(style));}return Optional.empty();},Style.EMPTY);
        lines.add(line[0]);
        var kept=lines.stream().filter(paragraph->!paragraph.getString().isBlank()).toList();
        return kept.isEmpty()?List.of(Component.empty()):kept;
    }
    private void search(){
        localPage=0;
        if(reply.mode().equals("tasks")||reply.mode().equals("start")){catalogueSearch=input.getValue();relatedPrefix="";caret=input.getCursorPosition();highlight=caret;showCatalogue();return;}
        caret=input.getCursorPosition();highlight=caret;debounce=0;typedSearch=null;send("FILTER","",input.getValue(),0);
    }
    private void clearSearch(){if(input!=null)input.setValue("");caret=0;highlight=0;focusedControl="search_box";search();}
    private void open(String task){sendTask(task,"");}
    /** The task whose form is open, for its Help topic (review and result pages name it themselves). */
    private String lastTask="";
    private void sendTask(String task,String prefillJson){
        if(pending!=null)return;lastTask=task;showHelp=false;choosingEdit=false;showingCategories=false;lateRequest=null;pending=UUID.randomUUID();timeout=200;notice=null;typedSearch=null;
        // The opening screen's context (for example the settlement it shows) applies to every task opened from here.
        lastSent=new InteractionNetwork.Request(pending,InteractionNetwork.NONE,InteractionNetwork.NONE,"TASK",task,"",0,prefillJson==null||prefillJson.isBlank()?prefill:prefillJson);
        actionRequest=pending;ClientState.get().taskOpened(task);InteractionNetwork.send(lastSent);disable();
    }
    private void allTasks(){catalogueSearch="";relatedPrefix="";showHelp=false;choosingEdit=false;showingCategories=false;if(index!=null)showCatalogue();else send("INDEX","","",0);}
    private void showStart(){showHelp=false;choosingEdit=false;showingCategories=false;if(start!=null){reply=start;rebuildWidgets();}send("START","","",0);}
    private void showRelated(String task){relatedPrefix=task.contains(".")?task.substring(0,task.indexOf('.')+1):"";catalogueSearch="";group="";send("INDEX","","",0);}
    private void send(String operation,String value,String search,int page){
        if(pending!=null)return;rememberFocus();if(operation.equals("TASK")){sendTask(value,"");return;}
        lateRequest=null;pending=UUID.randomUUID();timeout=200;notice=null;
        lastSent=new InteractionNetwork.Request(pending,reply==null?InteractionNetwork.NONE:reply.session(),reply==null?InteractionNetwork.NONE:reply.state(),operation,value,search,page,"");
        InteractionNetwork.send(lastSent);disable();
    }
    private void disable(){children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);}
    private void rememberFocus(){
        GuiEventListener focused=getFocused();focusedRow=null;focusedControl=null;
        if(focused instanceof Button button&&rowIds.containsKey(button))focusedRow=rowIds.get(button);
        else if(focused==input&&input!=null){focusedControl="search_box";caret=input.getCursorPosition();highlight=caret;}
        else for(var entry:controls.entrySet())if(entry.getValue()!=null&&entry.getValue()==focused)focusedControl=entry.getKey();
    }
    /** After a rebuild, focus the same record (moving to its local page) or the same control; otherwise a predictable one. */
    private void restoreFocus(boolean listing){
        if(pending!=null)return;
        if(focusedRow!=null){
            String wanted=focusedRow;focusedRow=null;
            for(var entry:rowIds.entrySet())if(entry.getKey().visible&&wanted.equals(entry.getValue())){focus(entry.getKey());return;}
            if(reply!=null&&(listing||choosingEdit))for(int i=0;i<reply.options().size();i++){var option=reply.options().get(i);
                if(wanted.equals(option.id()==null?option.key():option.id())&&i/rows!=localPage){localPage=i/rows;focusedRow=wanted;rebuildWidgets();return;}}
            if(listing&&!rowButtons.isEmpty()){notice=text("focus.moved","The list changed; the entry you were on is no longer shown.");focus(rowButtons.get(0));return;}
        }
        if(focusedControl!=null){String role=focusedControl;focusedControl=null;
            if(role.equals("search_box")&&input!=null){focus(input);return;}
            var control=controls.get(role);if(control!=null&&control.active){focus(control);return;}
            if(!rowButtons.isEmpty()){focus(rowButtons.get(0));return;}}
        if(listing&&input!=null&&reply!=null&&(reply.mode().equals("tasks")||reply.mode().equals("choice")))setInitialFocus(input);
        else if(startPanel!=null)setInitialFocus(startPanel);
        else if(showingCategories&&!rowButtons.isEmpty())focus(rowButtons.get(0));
    }
    private void focus(GuiEventListener widget){setFocused(widget);if(widget instanceof AbstractWidget w)w.setFocused(true);}
    private static Component taskTitle(IndexEntry entry){return Component.translatableWithFallback("task.mcakingdoms."+entry.id()+".title",entry.title());}
    private static Component helpText(IndexEntry entry){return Component.translatableWithFallback("task.mcakingdoms."+entry.id()+".help",entry.help());}
    /** Builds the catalogue page locally from the authorized index: translated and English titles, goal phrases and categories, ranked title-first. */
    private void showCatalogue(){
        if(index==null){send("INDEX","","",0);return;}
        var tokens=SearchText.tokens(catalogueSearch);var ranked=new ArrayList<Map.Entry<Integer,InteractionNetwork.Option>>();
        int order=0;
        for(var entry:index){
            order++;
            if(!relatedPrefix.isEmpty()&&!entry.id().startsWith(relatedPrefix))continue;
            if(!group.isEmpty()&&!entry.group().equals(group))continue;
            Component title=taskTitle(entry);
            Component category=Component.translatableWithFallback("task.mcakingdoms.category."+slug(entry.category()),entry.category());
            Component help=helpText(entry);
            String aliasKey="task.mcakingdoms."+entry.id()+".aliases";
            var aliases=new ArrayList<String>();String translatedAliases=Component.translatableWithFallback(aliasKey,"").getString();if(!translatedAliases.isBlank())aliases.add(translatedAliases);if(PlayerWords.isKey(aliasKey))aliases.add(PlayerWords.text(aliasKey));
            var other=new ArrayList<String>(List.of(help.getString(),english("task.mcakingdoms."+entry.id()+".help",entry.help())));if(operatorIndex)other.add(entry.id());
            var rank=SearchText.rank(tokens,List.of(title.getString(),english("task.mcakingdoms."+entry.id()+".title",entry.title())),aliases,
                    List.of(category.getString(),english("task.mcakingdoms.category."+slug(entry.category()),entry.category()),groupLabel(entry.group()).getString()),other);
            if(rank==SearchText.Rank.NONE)continue;
            boolean unavailable=!entry.unavailable().isEmpty();
            Component label=unavailable?text("unavailable.row","%s — not available here",title):title;
            Component detailLine=unavailable?InteractionNetwork.component(entry.reason()):category.copy().append(" · ").append(help);
            // Unavailable tasks sort after available ones of the same rank; they stay listed so players learn why.
            int score=SearchText.phrase(catalogueSearch,aliases)?-1:rank.ordinal();
            ranked.add(Map.entry(score*100_000+(unavailable?50_000:0)+order,new InteractionNetwork.Option(entry.id(),InteractionNetwork.encode(label),InteractionNetwork.encode(detailLine),entry.consequential()&&!unavailable,entry.id())));
        }
        ranked.sort(Map.Entry.comparingByKey());
        var options=ranked.stream().map(Map.Entry::getValue).toList();
        Component body=options.isEmpty()&&!tokens.isEmpty()?text("catalogue.no_match","No tasks match “%s”. Try fewer or different words, or clear the search.",catalogueSearch)
                :options.isEmpty()&&!group.isEmpty()?text("catalogue.no_group","No tasks in %s are available to you.",groupLabel(group))
                :!relatedPrefix.isEmpty()?text("catalogue.related","Tasks related to the one you just used. Use All tasks to see everything.")
                :text("catalogue.help","Choose a task. Search by what you want to do. No commands are entered or sent.");
        reply=new InteractionNetwork.Reply(UUID.randomUUID(),InteractionNetwork.NONE,InteractionNetwork.NONE,"tasks",InteractionNetwork.encode(text("catalogue.title","Kingdom Tasks")),InteractionNetwork.encode(body),options,catalogueSearch,0,false,false,false);
        rebuildWidgets();
    }
    private static String english(String key,String fallback){return PlayerWords.isKey(key)?PlayerWords.text(key):fallback==null?"":fallback;}
    private static String slug(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");}
    private void acceptIndex(InteractionNetwork.Reply value){
        try{var json=JsonParser.parseString(value.value()).getAsJsonObject();var out=new ArrayList<IndexEntry>();
            // Each distinct unavailability reason is sent once; an entry names it by its position.
            var reasons=json.has("reasons")?json.getAsJsonArray("reasons"):new com.google.gson.JsonArray();
            for(var element:json.getAsJsonArray("tasks")){var o=element.getAsJsonObject();
                String reason=o.has("q")?reasons.get(o.get("q").getAsInt()).getAsString():o.has("r")?o.get("r").getAsString():"";
                out.add(new IndexEntry(o.get("i").getAsString(),o.get("c").getAsBoolean(),o.get("t").getAsString(),o.get("g").getAsString(),o.has("h")?o.get("h").getAsString():"",
                        o.has("p")?o.get("p").getAsString():"HELP",o.has("a")?o.get("a").getAsString():"",reason,o.has("l")?o.get("l").getAsString():""));}
            index=List.copyOf(out);operatorIndex=json.has("operator")&&json.get("operator").getAsBoolean();
            var availability=new java.util.HashMap<String,String>();for(var e:index)availability.put(e.id(),e.unavailable().isEmpty()?"":e.reason());
            PinnedTask.catalogue(availability,true);
        }catch(RuntimeException malformed){index=List.of();}
        if(wantStart){wantStart=false;send("START","","",0);return;}
        showCatalogue();
    }
    public static void receive(InteractionNetwork.Reply value){
        if(Minecraft.getInstance().screen instanceof KingdomActionsScreen screen&&(value.request().equals(screen.pending)||value.request().equals(screen.lateRequest))){
            screen.pending=null;screen.lateRequest=null;screen.timeout=0;
            if(value.mode().equals("index")){screen.acceptIndex(value);screen.announce();return;}
            if(value.mode().equals("start"))screen.start=value;
            if(!value.mode().equals("review"))screen.choosingEdit=false;
            // A record search answered for older text: keep what the player has typed since, and search again after the pause.
            if(value.mode().equals("choice")&&screen.typedSearch!=null&&screen.typedSearch.equals(value.value()))screen.typedSearch=null;
            screen.reply=value;screen.rebuildWidgets();screen.announce();
        }
    }
    /** Narrates a new page once: its title and step. Re-renders and unchanged replies say nothing. */
    private void announce(){
        if(reply==null)return;String key=reply.mode()+"|"+reply.titleText()+"|"+reply.step()+"|"+reply.detailText().hashCode()+"|"+reply.statusOrEmpty();
        if(key.equals(narrated))return;narrated=key;triggerImmediateNarration(false);
    }
    @Override public Component getNarrationMessage(){
        if(reply==null)return getTitle();
        var title=reply.titleComponent().copy();
        if(reply.steps()>0)title.append(". ").append(stepLabel());
        var status=withStatus(List.of());if(!status.isEmpty())title.append(". ").append(status.get(0));
        return title;
    }
    private Component stepLabel(){return reply.step()>=reply.steps()&&reply.mode().equals("review")?text("step.review","Review (step %s of %s)",reply.step(),reply.steps()):text("step","Step %s of %s",reply.step(),reply.steps());}
    @Override public void tick(){
        if(input!=null)input.tick();
        // A record search runs once typing pauses for 8 ticks; a reply for older text never replaces newer typing.
        if(debounce>0&&pending==null&&--debounce==0&&reply!=null&&reply.mode().equals("choice")&&input!=null&&!input.getValue().equals(reply.value())){
            caret=input.getCursorPosition();highlight=caret;focusedControl="search_box";send("FILTER","",input.getValue(),0);}
        if(pending!=null&&--timeout<=0){lateRequest=pending;pending=null;notice=text("timeout","No reply yet. Inspect current status before repeating an action.");reply=new InteractionNetwork.Reply(UUID.randomUUID(),reply==null?InteractionNetwork.NONE:reply.session(),reply==null?InteractionNetwork.NONE:reply.state(),"error",InteractionNetwork.encode(text("timeout.title","No result received yet")),InteractionNetwork.encode(text("timeout.body","No result received yet. Check this action before trying again; sending it again could repeat it.")),List.of(),"",0,false,false,false);rebuildWidgets();announce();}
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_F&&(Screen.hasControlDown()||(modifiers&GLFW.GLFW_MOD_CONTROL)!=0)&&input!=null&&pending==null){focus(input);return true;}
        if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)&&input!=null&&input.isFocused()&&pending==null){if(reply.mode().equals("text"))send("NEXT",input.getValue(),"",0);else search();return true;}
        if(key==GLFW.GLFW_KEY_DOWN&&input!=null&&input.isFocused()&&!rowButtons.isEmpty()){setFocused(rowButtons.get(0));rowButtons.get(0).setFocused(true);input.setFocused(false);return true;}
        if(key==GLFW.GLFW_KEY_UP&&input!=null&&!rowButtons.isEmpty()&&getFocused()==rowButtons.get(0)){rowButtons.get(0).setFocused(false);setFocused(input);input.setFocused(true);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    private void paperTitle(GuiGraphics g,Component title,int y,int maxWidth){
        BookSkin.title(g,font,title,left+(w-maxWidth)/2,folio.y()+y,maxWidth);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        renderBackground(g);
        BookSkin.cover(g,folio.x(),folio.y(),folio.width(),folio.height(),false,BookSkin.Binding.TASKS);
        Component heading=reply==null?text("catalogue.title","Kingdom Tasks"):showHelp?text("help.title","Getting started"):showingCategories?text("categories.title","Choose a category")
                :reply.mode().equals("start")?text("start.heading","Kingdom Tasks"):reply.steps()>0?reply.titleComponent().copy().append(" · ").append(stepLabel()):reply.titleComponent();
        int headingWidth=w-24-(controls.containsKey("help_topic")?100:0);
        paperTitle(g,heading.copy().withStyle(ChatFormatting.BOLD),18,Math.max(80,headingWidth));
        BookSkin.divider(g,left+8,folio.y()+30,w-16);
        BookSkin.divider(g,left+8,folio.y()+pageHeight-65,w-16);
        if(shortcutPanel!=null)g.fill(left+w/2,folio.y()+66,left+w/2+1,folio.y()+pageHeight-70,0x55000000|BookSkin.LINE);
        if(reply!=null&&!showHelp&&!showingCategories&&choosingEdit&&reply.mode().equals("review"))paperTitle(g,text("edit.choose","Choose the answer to change"),67,w-24);
        if(listing())paperTitle(g,pending!=null?text("loading","Loading…"):notice!=null?notice:reply.multi()?text("hint.multi","Select choices, then use selections")
                :!group.isEmpty()&&reply.mode().equals("tasks")?text("hint.category","%s · choose a task; arrow keys move, Enter selects",groupLabel(group))
                :text("hint.choose","Choose an entry; arrow keys move, Enter selects"),67,w-24);
        if(showingCategories)paperTitle(g,text("categories.hint","Tasks are grouped by what they are about."),38,w-24);
        super.render(g,mx,my,partial);
        if(pending!=null&&lastSent!=null&&lastSent.operation().equals("APPLY")&&reply!=null){
            Component action=reply.actionComponent().getString().isBlank()?reply.titleComponent():reply.actionComponent();
            paperTitle(g,text("sending","Sending: %s…",action),pageHeight-76,w-24);
        }
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
