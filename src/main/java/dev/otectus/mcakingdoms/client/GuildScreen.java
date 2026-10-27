package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.civic.CivicNetwork;
import dev.otectus.mcakingdoms.civic.CivicViews;
import dev.otectus.mcakingdoms.client.book.*;
import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.rich.RichPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * The player's own guild record, in sections: membership, progress (standing, different jobs, next rank), each service
 * with its own requirement rows and route, nearby contact, quest status and recent service. Joining, leaving and using a
 * service open the same reviewed Kingdom Tasks workflow as the catalogue, with the guild already chosen; reading stays
 * here. The server remains authoritative and is asked again when you return.
 */
public final class GuildScreen extends Screen {
    private final Screen parent;
    private BookLayout folio;
    private CivicViews.View view;
    private UUID pending;
    private int timeout, index;
    private Component outcome = Component.empty();
    private boolean refreshOnReturn;
    private RichPanel record;
    public GuildScreen(Screen parent) { super(Component.translatable("civic.mcakingdoms.title")); this.parent=parent; }
    private static Component label(String key) { return Component.translatable("menu.mcakingdoms." + key); }
    private static Component t(String key, String fallback, Object... args) { return Component.translatableWithFallback("civic.mcakingdoms.screen." + key, fallback, args); }
    private int panelWidth() { return folio.width(); }
    private int panelHeight() { return folio.height(); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int bottom() { return top() + panelHeight(); }
    @Override protected void init() {
        folio=BookLayout.folio(width,height,620,430);
        int x=left(), y=top(), w=panelWidth(), footer=bottom()-40;
        boolean ready=pending==null&&view!=null&&view.total()>0;
        addRenderableWidget(BookButton.builder(label("previous_guild"), b -> request(index-1)).bounds(x+22,y+32,24,20)
                .tooltip(Tooltip.create(label("previous_guild"))).build().glyph("‹")).active=ready&&index>0;
        addRenderableWidget(BookButton.builder(label("next_guild"), b -> request(index+1)).bounds(x+w-46,y+32,24,20)
                .tooltip(Tooltip.create(label("next_guild"))).build().glyph("›")).active=ready&&view!=null&&index+1<view.total();
        if(record==null) record=new RichPanel(x+22,y+64,w-44,panelHeight()-122,label("guild_record")).manuscript();
        else record.place(x+22,y+64,w-44,panelHeight()-122);
        record.setPieces(pieces(),record.anchor());
        addRenderableWidget(record);
        int quarter=(w-56)/4;
        var guild=view==null?null:view.guild();
        boolean member=guild!=null&&guild.member();
        Component name=guild==null?Component.empty():CivicViews.decode(guild.name());
        var membership=addRenderableWidget(BookButton.builder(member?t("leave","Leave %s",name):t("join","Join %s",name),b->openTask(member?"civic.leave":"civic.join",Map.of("organization",view.organization())))
                .bounds(x+22,footer,quarter+20,20).tooltip(Tooltip.create(member?t("leave.help","Opens Leave a guild with this guild chosen; the review shows what you keep."):t("join.help","Opens Join a guild with this guild chosen; nothing happens until you confirm the review."))).build());
        membership.active=ready&&guild!=null;
        addRenderableWidget(BookButton.builder(Component.translatable("civic.mcakingdoms.refresh"), b -> request(index)).bounds(x+26+quarter+20,footer,quarter-8,20).build()).active=pending==null;
        addRenderableWidget(BookButton.builder(t("help","Help"), b -> {refreshOnReturn=true;minecraft.setScreen(new KingdomGuideScreen(this,"guild_requirements"));}).bounds(x+30+2*quarter+12,footer,quarter-8,20)
                .tooltip(Tooltip.create(t("help.tip","Standing, jobs and rank, in the Book."))).build());
        addRenderableWidget(BookButton.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x+34+3*quarter+4,footer,w-56-3*quarter-4,20).build());
        if(refreshOnReturn&&pending==null){refreshOnReturn=false;request(index);}
        else if(view==null&&pending==null) request(index);
    }
    private void openTask(String task, Map<String,String> prefill) {
        var json=new com.google.gson.JsonObject();prefill.forEach(json::addProperty);
        refreshOnReturn=true;InteractionClient.open(this,task,"",json.toString());
    }
    private static Pieces.Check check(String value) {
        return switch(value){case "MET"->Pieces.Check.MET;case "UNMET"->Pieces.Check.UNMET;case "NOTE"->Pieces.Check.NOTE;default->Pieces.Check.UNKNOWN;};
    }
    private List<RichPanel.Piece> pieces() {
        var out=new ArrayList<RichPanel.Piece>();
        if(view==null){out.add(Pieces.text("loading",pending!=null?Component.translatable("civic.mcakingdoms.loading"):outcome));return out;}
        if(view.guild()==null){view.lines().forEach(line->out.add(Pieces.text("",line)));return out;}
        var g=view.guild();
        if(!outcome.getString().isEmpty())out.add(Pieces.callout("outcome",Pieces.Tone.NOTE,null,outcome));
        out.add(Pieces.heading("membership",t("section.membership","Membership")));
        out.add(Pieces.text("membership_text",CivicViews.decode(g.membership())));
        var description=CivicViews.decode(g.description());
        if(!description.getString().isBlank())out.add(Pieces.text("description",description,Pieces.MUTED,0));
        out.add(Pieces.heading("progress",t("section.progress","Progress")));
        if(g.nextRankStanding()>=0)out.add(Pieces.progress("standing",t("progress.standing","Guild standing toward %s",CivicViews.decode(g.nextRank())),g.standing(),g.nextRankStanding(),null));
        else out.add(Pieces.text("standing",t("progress.standing.max","Guild standing: %s (no higher rank)",g.standing())));
        out.add(Pieces.fields("progress_fields",List.of(new Pieces.Field(t("progress.deeds","Different service jobs completed"),Component.literal(Integer.toString(g.deeds()))),
                new Pieces.Field(t("progress.rank","Rank"),CivicViews.decode(g.rank())))));
        out.add(Pieces.text("progress_note",t("progress.note","Standing and different jobs are checked separately; each service below shows the numbers it needs."),Pieces.MUTED,0));
        out.add(Pieces.heading("services",t("section.services","Services")));
        if(g.services().isEmpty())out.add(Pieces.text("services_none",t("services.none","This guild offers no services here."),Pieces.MUTED,0));
        int n=0;
        for(var service:g.services()){
            n++;
            Component status=switch(service.decision()){case "ALLOW"->t("service.ready","Ready to use");case "UNAVAILABLE"->t("service.unavailable","Unavailable");default->t("service.not_yet","Not yet");};
            var rows=service.rows().stream().map(r->new Pieces.Row(r.labelComponent(),check(r.check()),r.detailComponent().getString().isEmpty()?null:r.detailComponent())).toList();
            out.add(Pieces.requirements("service_"+n,t("service.title","%s — %s",service.nameComponent(),status),rows,null));
            var actions=new ArrayList<RichPanel.Target>();
            if(!service.task().isEmpty())actions.add(Pieces.button("use_"+n,t("service.use","Use: %s",service.nameComponent()),t("service.use.help","Opens the task with this guild chosen; its review shows what happens before anything does."),
                    ()->openTask(service.task(),Map.of("organization",view.organization())),pending==null));
            if(!service.permission().endsWith(":hospitality")&&!service.permission().endsWith(":workshop"))
                actions.add(Pieces.button("explain_"+n,t("service.explain","Explain this service's requirements"),t("service.explain.help","The current decision and every requirement, from the guild's rules."),
                        ()->openTask("civic.explain",Map.of("organization",view.organization(),"permission",service.permission())),pending==null));
            if(!actions.isEmpty())out.add(Pieces.actions("service_actions_"+n,actions));
        }
        out.add(Pieces.heading("contact",t("section.contact","Nearby contact")));
        out.add(Pieces.text("contact_text",CivicViews.decode(g.contact()),g.contactFound()?Pieces.TEXT:Pieces.MUTED,0));
        out.add(Pieces.heading("quests",t("section.quests","Guild jobs")));
        out.add(Pieces.text("quests_text",CivicViews.decode(g.quests())));
        if(!g.recent().isEmpty()){out.add(Pieces.heading("recent",t("section.recent","Recent service")));g.recent().forEach(line->out.add(Pieces.text("",CivicViews.decode(line))));}
        g.notes().forEach(line->out.add(Pieces.text("",CivicViews.decode(line),Pieces.MUTED,0)));
        return out;
    }
    private void request(int page) {
        index=Math.max(0,page); pending=UUID.randomUUID(); timeout=60; outcome=Component.empty();
        CivicNetwork.send(new CivicNetwork.Query(pending,index,CivicNetwork.Action.READ,view==null?"":view.organization()));
        if(minecraft!=null)rebuildWidgets();
    }
    public static void receive(CivicNetwork.Reply reply) {
        if (!(Minecraft.getInstance().screen instanceof GuildScreen screen) || !reply.request().equals(screen.pending)) return;
        screen.view=reply.view(); screen.index=reply.view().index(); screen.pending=null; screen.timeout=0;
        screen.outcome=reply.outcome().isEmpty()?Component.empty():dev.otectus.mcakingdoms.civic.CivicText.reason(reply.outcome());
        screen.rebuildWidgets();
    }
    @Override public void tick() {
        if (pending!=null && --timeout<=0) { pending=null; outcome=Component.translatable("civic.mcakingdoms.timeout"); rebuildWidgets(); }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        renderBackground(graphics);
        int x=left(),y=top(),w=panelWidth();
        BookSkin.cover(graphics,x,y,w,panelHeight(),false,BookSkin.Binding.LEDGER);
        BookSkin.title(graphics,font,title,x+22,y+18,w-44);
        if(view!=null&&view.guild()!=null) {
            BookSkin.title(graphics,font,CivicViews.decode(view.guild().name()),x+50,y+32,w-100);
            BookSkin.title(graphics,font,t("page","Guild %s of %s",view.total()==0?0:view.index()+1,view.total()),x+50,y+44,w-100);
        }
        BookSkin.divider(graphics,x+22,y+57,w-44);
        BookSkin.divider(graphics,x+22,bottom()-46,w-44);
        if(pending!=null)BookSkin.title(graphics,font,Component.translatable("civic.mcakingdoms.loading"),x+22,bottom()-56,w-44);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
