package dev.otectus.mcakingdoms.clienttest;

import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.politics.PoliticalNetwork;
import dev.otectus.mcakingdoms.politics.PoliticalSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

public final class PoliticalMultiplayerHarness {
    private final Minecraft mc=Minecraft.getInstance();
    private final Path shared=Path.of(System.getProperty("mcakingdoms.clientTest.shared"));
    private final String role=System.getProperty("mcakingdoms.clientTest.role");
    private final long started=System.currentTimeMillis();
    private int stage,ticks,last;private boolean connecting,finished;private UUID pending;
    private PoliticalNetwork.Reply reply;
    private PoliticalNetwork.Query pendingQuery; private long sentAt;
    public PoliticalMultiplayerHarness(){PoliticalNetwork.receiver(r->{if(r.page().requestId().equals(pending))reply=r;});}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||finished)return;ticks++;
        try{
            if(System.currentTimeMillis()-started>240000)throw new AssertionError("Multiplayer timeout stage "+stage);
            if(!connecting&&(mc.screen instanceof TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)){connecting=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(2);
                String address=System.getProperty("mcakingdoms.clientTest.server");ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),new ServerData("Political acceptance",address,false),false);}
            if(ticks % 200 == 0) System.out.println("POLITICAL_MULTIPLAYER role="+role+" stage="+stage+" player="+(mc.player!=null)+" reply="+(reply!=null));
            if(mc.player==null||ticks-last<10)return;
            if(stage==1&&reply==null&&pendingQuery!=null&&System.currentTimeMillis()-sentAt>1000){
                PoliticalNetwork.send(pendingQuery);sentAt=System.currentTimeMillis();
            }
            if(stage==0){
                if(!Files.exists(shared.resolve("READY"))||role.equals("stranger")&&!Files.exists(shared.resolve("private.json")))return;
                query();stage=1;last=ticks;
            }else if(stage==1&&reply!=null){
                // The ledger channel is read-only (protocol 2): signing goes through Kingdom Tasks, so this harness
                // proves who can see the private proposal, and that no page ever carries a mutation result.
                Page page=reply.page();
                if(reply.result()!=null)throw new AssertionError("A read-only ledger page carried a mutation result");
                if(role.equals("leader")){
                    if(page.rows().size()!=1)throw new AssertionError("Leader did not receive private proposal");
                    Row row=page.rows().get(0);
                    Files.writeString(shared.resolve("private.json"),PoliticalSavedData.JSON.toJson(new Request(UUID.randomUUID(),page.revision(),Action.SIGN,"mcakingdoms:serenum","",row.id(),null,"","",row.termsHash(),0,0,0)));
                }else if(!page.rows().isEmpty())throw new AssertionError("Private proposal leaked to unrelated client");
                Files.writeString(shared.resolve(role+"-PASS.txt"),role.equals("leader")?"PASS real authorized client sees the private proposal\n":"PASS real unauthorized client receives no private proposal\n");
                stage=3;last=ticks;
            }else if(stage==3&&Files.exists(shared.resolve("stranger-PASS.txt"))){finished=true;mc.stop();}
        }catch(Throwable failure){finished=true;failure.printStackTrace();try{Files.writeString(shared.resolve(role+"-FAIL.txt"),failure.toString());}catch(Exception ignored){}mc.stop();}
    }
    private void query(){PoliticalNetwork.receiver(r->{System.out.println("POLITICAL_MULTIPLAYER reply="+r.page().requestId()+" expected="+pending);if(r.page().requestId().equals(pending))reply=r;});reply=null;pending=UUID.randomUUID();pendingQuery=new PoliticalNetwork.Query(pending,"mcakingdoms:serenum","agreements",0);sentAt=System.currentTimeMillis();PoliticalNetwork.send(pendingQuery);}
}
