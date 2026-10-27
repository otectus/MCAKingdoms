package dev.otectus.mcakingdoms.clienttest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.client.*;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Packaged-client acceptance for the authenticated named-task wizard. */
public final class InteractionClientHarness {
    private static final List<String> SCREENSHOTS=List.of("00-start-view.png","01-create-review.png","02-created-read.png","03-rename-result.png","04-compact-tasks.png","05-war-room-unavailable.png");
    private final Minecraft mc=Minecraft.getInstance();
    private final Path output=Path.of(System.getProperty("mcakingdoms.clientTest.output"));
    private final long started=System.currentTimeMillis();
    /** The interaction checks and each language's GUI tour get their own time budget. */
    private long deadline=started+240_000L;
    private int stage,ticks,entered;private boolean creating,finished;private volatile UUID settlement;private volatile String serverName="";

    private GuiStyleAcceptance guiStyle;
    /** Further client languages for the GUI tour after English, from {@code mcakingdoms.clientTest.languages} (comma separated). */
    private final java.util.ArrayDeque<String> languages=new java.util.ArrayDeque<>(java.util.Arrays.stream(System.getProperty("mcakingdoms.clientTest.languages","").split(",")).map(String::strip).filter(v->!v.isEmpty()).toList());
    private java.util.concurrent.CompletableFuture<Void> languageReload;
    /** Switches the client to the next requested language and reloads resources; false when none is left. */
    private boolean nextLanguage(){
        guiStyle=null;String language=languages.poll();if(language==null)return false;
        check(mc.getLanguageManager().getLanguages().containsKey(language),"Client offers language "+language);
        mc.getLanguageManager().setSelected(language);mc.options.languageCode=language;mc.setScreen(null);languageReload=mc.reloadResourcePacks();
        log("Switching language to "+language);return true;}

    private boolean startChecked;
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||finished)return;ticks++;
        try{
            if(mc.screen instanceof ChatScreen)throw new AssertionError("Named task workflow opened ChatScreen at stage "+stage);
            if(System.currentTimeMillis()>deadline)throw new AssertionError("Interaction client timeout at stage "+stage+" screen="+mc.screen+(mc.screen instanceof KingdomActionsScreen?" reply="+reply():""));
            if(!creating&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen))createWorld();
            if(!creating||ticks-entered<20)return;
            if(guiStyle!=null){if(guiStyle.tick(mc)){if(!nextLanguage())finish();}return;}
            if(languageReload!=null){if(!languageReload.isDone())return;languageReload.join();languageReload=null;
                log("Language "+mc.getLanguageManager().getSelected()+" loaded");guiStyle=new GuiStyleAcceptance(output,mc.getLanguageManager().getSelected()+"-");deadline=System.currentTimeMillis()+240_000L;return;}
            switch(stage){
                case 0->{if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;mc.setScreen(null);InputConstants.Key key=InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K);KeyMapping.click(key);next();}
                case 1->{if(!startChecked&&ready("start")){startView();return;}if(!ready("tasks"))return;check(startChecked,"K opened the start view first");check(mc.screen instanceof KingdomActionsScreen,"K key mapping opened Kingdom Actions");check(noInternalInputs(),"Initial catalogue has no editable UUID, revision, or fingerprint field");search("Register a settlement");next();}
                case 2->{if(!ready("tasks"))return;click("Register a settlement here");next();}
                case 3->{if(!ready("text"))return;check(reply().titleText().contains("Radius"),"Create wizard begins with bounded radius");enter("32");next();}
                case 4->{if(!ready("text"))return;check(reply().titleText().toLowerCase(Locale.ROOT).contains("name"),"Create wizard requests a readable name");enter("Wizard Reach");next();}
                case 5->{if(!ready("review"))return;check(!reply().detailText().toLowerCase(Locale.ROOT).matches(".*(uuid|fingerprint|revision [0-9]).*"),"Create review contains only player-facing terms");shot("01-create-review.png");apply();next();}
                case 6->{if(!ready("result"))return;check(reply().detailText().contains("Registered Wizard Reach"),"Reviewed create action completed once");seedDiscovery();click("All tasks");next();}
                case 7->{if(settlement==null||!ready("tasks"))return;search("Read a known settlement");next();}
                case 8->{if(!ready("tasks"))return;click("Read a known settlement");next();}
                case 9->{if(!ready("choice"))return;check(reply().options().stream().anyMatch(o->o.labelText().equals("Wizard Reach")),"Settlement picker uses the created name");click("Wizard Reach");next();}
                case 10->{if(!ready("result"))return;next();}
                case 11->{if(!ready("result"))return;check(reply().detailText().contains("Wizard Reach")&&reply().detailText().contains("Location"),"Read task returns usable settlement details");shot("02-created-read.png");click("All tasks");next();}
                case 12->{if(!ready("tasks"))return;search("Rename a settlement");next();}
                case 13->{if(!ready("tasks"))return;click("Rename a settlement");next();}
                case 14->{if(!ready("choice"))return;click("Wizard Reach");next();}
                case 15->{if(!ready("text"))return;enter("Wizard Harbor");next();}
                case 16->{if(!ready("review"))return;apply();next();}
                case 17->{if(!ready("result"))return;check(reply().detailText().contains("Wizard Harbor"),"Rename result uses the new readable name");verifyName();shot("03-rename-result.png");click("All tasks");next();}
                case 18->{if(!serverName.equals("Wizard Harbor")||!ready("tasks"))return;mc.getWindow().setGuiScale(mc.getWindow().getHeight()/240.0D);((Screen)mc.screen).resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());next();}
                case 19->{if(!ready("tasks"))return;Screen screen=(Screen)mc.screen;check(screen.height>=240&&screen.height<=241,"Compact wizard is 240 GUI pixels high");for(var child:screen.children())if(child instanceof AbstractWidget widget&&widget.visible)check(widget.getX()>=0&&widget.getY()>=0&&widget.getX()+widget.getWidth()<=screen.width&&widget.getY()+widget.getHeight()<=screen.height,"Compact widget stays in viewport: "+widget.getMessage().getString());
                    search("evolution");next();}
                case 20->{if(!ready("tasks"))return;check(reply().options().stream().allMatch(o->(o.labelText()+" "+o.detailText()).toLowerCase(Locale.ROOT).contains("evolution")),"Keyboard search filters named tasks");shot("04-compact-tasks.png");search("");next();}
                case 21->{if(!ready("tasks"))return;Button nextPage=button("Next page");check(nextPage.active,"Compact catalogue exposes paging");click(nextPage);check((Integer)field(KingdomActionsScreen.class,mc.screen,"localPage")==1,"Compact next-page mouse click advances local task rows");mc.options.guiScale().set(2);mc.resizeDisplay();mc.setScreen(new WarRoomScreen(null,settlement,"Wizard Harbor"));next();}
                case 22->{if(!(mc.screen instanceof WarRoomScreen)||field(WarRoomScreen.class,mc.screen,"snapshot")==null)return;
                    var panel=(dev.otectus.mcakingdoms.client.rich.RichPanel)field(WarRoomScreen.class,mc.screen,"intelligence");
                    var sections=((dev.otectus.mcakingdoms.warfare.WarfareNetwork.Snapshot)field(WarRoomScreen.class,mc.screen,"snapshot")).sections().stream().map(dev.otectus.mcakingdoms.warfare.WarfareNetwork.Section::key).toList();
                    check(sections.containsAll(java.util.List.of("control","campaigns","orders","civilian")),"The War Room is divided into control, campaigns, orders and civilian options: "+sections);
                    check(panel.targets().stream().anyMatch(t->t.key().equals("task:warfare.contract")),"Civilian recovery work is offered beside military actions");
                    // This client runs MCA: Kingdoms alone: campaigns act through Recruits factions, so the link says it cannot work
                    // here instead of leading into a form the server would refuse at the end.
                    var declare=panel.targets().stream().filter(t->t.key().equals("task:warfare.declare")).findFirst().orElseThrow(()->new AssertionError("No campaign link in the War Room"));
                    check(declare.label().getString().endsWith("not available here"),"Without Recruits the campaign link is marked unavailable: "+declare.label().getString());
                    check(panel.targets().stream().filter(t->t.key().equals("task:warfare.contract")).noneMatch(t->t.label().getString().endsWith("not available here")),"Civilian recovery work stays available");
                    declare.activate();next();}
                case 23->{if(!ready("unavailable"))return;check(!(mc.screen instanceof ChatScreen),"War-room Campaign opens the named task without chat");
                    check(reply().detailText().contains("Recruits"),"The campaign task names the missing companion mod before any form: "+reply().detailText());
                    check(!reply().learnOrEmpty().isEmpty(),"The explanation links its Book topic: "+reply().learnOrEmpty());
                    button("Learn more in the Book");button("Copy support details");next();}
                case 24->{shot("05-war-room-unavailable.png");if(Boolean.getBoolean("mcakingdoms.clientTest.guiStyle")){guiStyle=new GuiStyleAcceptance(output);deadline=System.currentTimeMillis()+240_000L;}else finish();}
            }
        }catch(Throwable failure){fail(failure);}
    }

    private void createWorld(){creating=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.guiScale().set(2);mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.resizeDisplay();
        mc.createWorldOpenFlows().createFreshLevel("interaction-acceptance-"+started,new LevelSettings("Interaction acceptance",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(9142026L,false,false),registry->registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());log("Created isolated flat integrated world");}
    private void seedDiscovery(){mc.getSingleplayerServer().execute(()->{try{var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);var found=McaKingdomsApi.get(mc.getSingleplayerServer()).getSettlementAt(player.serverLevel(),player.blockPosition()).orElseThrow();SettlementKnowledge.get(mc.getSingleplayerServer()).discover(player.getUUID(),found.id());settlement=found.id();serverName=found.displayName();}catch(Throwable failure){fail(failure);}});}
    private void verifyName(){mc.getSingleplayerServer().execute(()->{try{serverName=McaKingdomsApi.get(mc.getSingleplayerServer()).getSettlement(settlement).orElseThrow().displayName();check(serverName.equals("Wizard Harbor"),"Integrated server retained renamed settlement");}catch(Throwable failure){fail(failure);}});}
    private boolean ready(String mode){return mc.screen instanceof KingdomActionsScreen&&field(KingdomActionsScreen.class,mc.screen,"pending")==null&&reply()!=null&&reply().mode().equals(mode);}
    private InteractionNetwork.Reply reply(){return (InteractionNetwork.Reply)field(KingdomActionsScreen.class,mc.screen,"reply");}
    private boolean noInternalInputs(){return ((Screen)mc.screen).children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).noneMatch(e->e.getMessage().getString().toLowerCase(Locale.ROOT).matches(".*(uuid|revision|fingerprint).*"));}
    // The catalogue filters as you type and rebuilds its search box, so the live box is read again before Enter.
    private void search(String value){EditBox input=(EditBox)field(KingdomActionsScreen.class,mc.screen,"input");input.setValue(value);input=(EditBox)field(KingdomActionsScreen.class,mc.screen,"input");((Screen)mc.screen).setFocused(input);check(((Screen)mc.screen).keyPressed(GLFW.GLFW_KEY_ENTER,0,0),"Keyboard Enter submits task search");}
    /** The review's confirm button names its action ("Register a settlement here"); it is found by that name. */
    private void apply(){String label=reply().actionComponent().getString();check(!label.isBlank(),"The review names its action");click(label);}
    /** K opens the start view: no settlement here yet, a first-village suggestion, and the main destinations. */
    private void startView(){
        var json=com.google.gson.JsonParser.parseString(reply().value()).getAsJsonObject();
        check(!json.has("here"),"The start view knows the player is not in a settlement");
        check(reply().value().contains("ledger_recipe"),"A new player is pointed at the first-village guide");
        for(String key:new String[]{"interaction.mcakingdoms.all_tasks","interaction.mcakingdoms.categories","interaction.mcakingdoms.guide","interaction.mcakingdoms.settings"})
            button(net.minecraft.network.chat.Component.translatable(key).getString());
        shot("00-start-view.png");startChecked=true;
        click(net.minecraft.network.chat.Component.translatable("interaction.mcakingdoms.all_tasks").getString());}
    private void enter(String value){EditBox input=(EditBox)field(KingdomActionsScreen.class,mc.screen,"input");input.setValue(value);((Screen)mc.screen).setFocused(input);check(((Screen)mc.screen).keyPressed(GLFW.GLFW_KEY_ENTER,0,0),"Keyboard Enter advances text field");}
    private Button button(String label){Screen screen=(Screen)mc.screen;return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing button "+label+" among "+screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).map(w->w.getMessage().getString()).toList()));}
    private void click(String label){click(button(label));}
    private void click(Button button){check(button.active,"Button enabled: "+button.getMessage().getString());Screen screen=(Screen)mc.screen;check(screen.mouseClicked(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,0),"Mouse click handled: "+button.getMessage().getString());}
    private Object field(Class<?> type,Object instance,String name){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(instance);}catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}}
    private void shot(String name){Screenshot.grab(output.toFile(),name,mc.getMainRenderTarget(),message->log("Screenshot "+name+": "+message.getString()));}
    private void next(){stage++;entered=ticks;log("stage="+stage);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);log("PASS "+message);}
    private void log(String message){System.out.println("INTERACTION_CLIENT "+message);}
    private void finish()throws Exception{for(String name:SCREENSHOTS){Path image=output.resolve("screenshots").resolve(name);check(Files.isRegularFile(image)&&Files.size(image)>0,"Screenshot written: "+name);}Files.writeString(output.resolve("INTERACTION_PASS.txt"),"PASS packaged interaction client: K key opens the start view, All tasks, named search, create/read/rename review and named confirm, no ChatScreen, compact paging, screenshots, and the sectioned War Room, whose campaign link without Recruits says it is not available here and explains why with its Book topic.\n");finished=true;mc.stop();}
    private void fail(Throwable failure){if(finished)return;finished=true;failure.printStackTrace();try{Files.createDirectories(output);Files.writeString(output.resolve("INTERACTION_FAIL.txt"),"stage="+stage+"\n"+failure+"\n");}catch(Exception writing){writing.printStackTrace();}mc.execute(mc::stop);}
}
