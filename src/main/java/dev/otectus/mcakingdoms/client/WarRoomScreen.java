package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.client.book.*;
import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import dev.otectus.mcakingdoms.warfare.WarfareNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * A settlement's War Room, in sections: who holds it (its kingdom, the recognized civilian government and military
 * control, kept apart), campaigns and control agreements with their stage and time left, your temporary troop orders,
 * and civilian and travel options. Each task link sits in its section and opens the reviewed Kingdom Tasks workflow with
 * this settlement chosen. Intelligence is authorized by the server; nothing here changes records.
 */
public final class WarRoomScreen extends Screen {
    private final Screen parent;
    private BookLayout folio;
    private final UUID settlement;
    private final String settlementName;
    private UUID request;
    private WarfareNetwork.Snapshot snapshot;
    private int timeout;
    private Component error;
    private RichPanel intelligence;
    private Button refresh;
    public WarRoomScreen(Screen parent,UUID settlement,String settlementName){super(Component.translatable("menu.mcakingdoms.war_room"));this.parent=parent;this.settlement=settlement;this.settlementName=settlementName;}
    private int panelWidth(){return folio.width();}
    private int panelHeight(){return folio.height();}
    private int left(){return (width-panelWidth())/2;}
    private int top(){return (height-panelHeight())/2;}
    private int bottom(){return top()+panelHeight();}
    private static Component label(String key){return Component.translatable("menu.mcakingdoms."+key);}
    private static Component t(String key,String fallback,Object... args){return Component.translatableWithFallback("menu.mcakingdoms.war_room."+key,fallback,args);}
    @Override protected void init(){
        folio=BookLayout.folio(width,height,620,430);
        int third=(panelWidth()-52)/3;
        addRenderableWidget(BookButton.builder(Component.translatable("gui.back"),b->onClose()).bounds(left()+22,bottom()-40,third,20).build());
        addRenderableWidget(BookButton.builder(t("help","Help"),b->minecraft.setScreen(new KingdomGuideScreen(this,"identity_government_control"))).bounds(left()+26+third,bottom()-40,third,20)
                .tooltip(Tooltip.create(t("help.tip","Kingdom, government and military control, in the Book."))).build());
        refresh=addRenderableWidget(BookButton.builder(Component.translatable("menu.mcakingdoms.refresh"),b->request()).bounds(left()+30+2*third,bottom()-40,panelWidth()-52-2*third,20).build());
        refresh.active=snapshot!=null||error!=null;
        if(intelligence==null)intelligence=new RichPanel(left()+22,top()+54,panelWidth()-44,panelHeight()-104,label("local_intelligence")).manuscript();
        else intelligence.place(left()+22,top()+54,panelWidth()-44,panelHeight()-104);
        intelligence.setPieces(pieces(),intelligence.anchor());
        addRenderableWidget(intelligence);
        if(snapshot==null&&timeout==0&&error==null)request();
    }
    private void request(){
        request=UUID.randomUUID();snapshot=null;error=null;timeout=100;
        if(refresh!=null)refresh.active=false;
        WarfareNetwork.request(request,settlement);
        if(intelligence!=null)intelligence.setPieces(pieces());
    }
    public static boolean receive(WarfareNetwork.Snapshot value){
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof WarRoomScreen screen&&value.request().equals(screen.request)&&screen.settlement.equals(value.settlement())){
            screen.snapshot=value;screen.timeout=0;screen.error=null;screen.rebuildWidgets();return true;
        }return false;
    }
    private Component heading(String key){
        return switch(key){
            case "control"->t("section.control","Who holds this settlement");
            case "campaigns"->t("section.campaigns","Campaigns and control agreements");
            case "orders"->t("section.orders","Your temporary troop orders");
            case "civilian"->t("section.civilian","Civilian and travel options");
            case "realms"->t("section.realms","Living Villages realms");
            default->Component.literal(key);
        };
    }
    /** Which section a task link belongs in, so each action sits next to what it changes. */
    private static String sectionFor(String task){
        return switch(task){case "warfare.accord"->"control";case "warfare.declare"->"campaigns";case "warfare.muster_nearby"->"orders";case "warfare.realm_peace"->"realms";default->"civilian";};
    }
    private List<RichPanel.Piece> pieces(){
        var out=new ArrayList<RichPanel.Piece>();
        if(snapshot==null){out.add(Pieces.text("status",error!=null?error:Component.translatableWithFallback("menu.mcakingdoms.war_room.requesting","Requesting authorized local intelligence…"),Pieces.MUTED,0));return out;}
        for(var section:snapshot.sections()){
            out.add(Pieces.heading(section.key(),heading(section.key())));
            int n=0;
            for(String line:section.lines())out.add(Pieces.text(section.key()+"_"+(n++),InteractionNetwork.component(line)));
            var actions=new ArrayList<RichPanel.Target>();
            for(var command:snapshot.commands())if(sectionFor(command.value()).equals(section.key())){
                Component label=Component.translatableWithFallback("menu.mcakingdoms.war_room.command."+command.value(),command.label());
                // A task this server cannot run says so on the link; opening it shows why and the Book topic, never a form.
                boolean unavailable=!command.unavailable().isEmpty();
                actions.add(Pieces.button("task:"+command.value(),unavailable?Component.translatableWithFallback("interaction.mcakingdoms.unavailable.row","%s — not available here",label):label,
                        unavailable?InteractionNetwork.component(command.unavailable())
                                :command.group().equals("civilian")?t("civilian_hint","A civilian option: choose named targets and review it; no fighting is involved.")
                                :Component.translatableWithFallback("menu.mcakingdoms.war_room.action_hint","Choose named targets and review this action."),
                        ()->InteractionClient.open(this,command.value(),"",InteractionClient.prefill("settlement",settlement)),true));
            }
            if(!actions.isEmpty())out.add(Pieces.actions(section.key()+"_actions",actions));
        }
        return out;
    }
    @Override public void tick(){super.tick();if(snapshot==null&&timeout>0&&--timeout==0){error=Component.translatableWithFallback("menu.mcakingdoms.war_room.timeout","War-room request timed out. Refresh to retry.");rebuildWidgets();}}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        renderBackground(graphics);
        BookSkin.cover(graphics,left(),top(),panelWidth(),panelHeight(),false,BookSkin.Binding.TASKS);
        BookSkin.title(graphics,font,title,left()+22,top()+18,panelWidth()-44);
        BookSkin.title(graphics,font,Component.literal(settlementName),left()+22,top()+32,panelWidth()-44);
        BookSkin.divider(graphics,left()+22,top()+47,panelWidth()-44);
        BookSkin.divider(graphics,left()+22,bottom()-46,panelWidth()-44);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
