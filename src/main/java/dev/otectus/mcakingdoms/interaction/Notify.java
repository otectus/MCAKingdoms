package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/**
 * Chat notices to the other parties of a proposal, so a counterpart learns that something awaits them without polling
 * menus. A notice names the exact read-only Kingdom Tasks entry that reviews the record; it carries no click action, so
 * nothing is accepted, signed, consented to or applied from chat.
 */
public final class Notify {
    private static Component line(Object text,String reviewTask){
        Component body=text instanceof Component component?component:Component.literal(String.valueOf(text));
        Component keybind=Component.keybind("key.mcakingdoms.actions");
        Component notice=reviewTask==null||ActionRegistry.find(reviewTask).isEmpty()
                ?ActionRegistry.tr("notice.mcakingdoms.party","[Kingdoms] %s Open Kingdom Tasks (%s) to respond.",body,keybind)
                :ActionRegistry.tr("notice.mcakingdoms.party.task","[Kingdoms] %s To review it, press %s and open “%s”.",body,keybind,InteractionNetwork.title(ActionRegistry.find(reviewTask).get()));
        return notice.copy().withStyle(style->style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                ActionRegistry.tr("notice.mcakingdoms.party.hover","Notices only inform you. Nothing is accepted, signed or applied from chat; review it in Kingdom Tasks."))));
    }
    /** Tells one connected player; offline players learn from the next digest or menu. */
    public static void player(MinecraftServer server,UUID id,UUID except,String text){player(server,id,except,text,null);}
    /** As {@link #player(MinecraftServer,UUID,UUID,String)}, naming the task that reviews the record; {@code text} may be a component. */
    public static void player(MinecraftServer server,UUID id,UUID except,Object text,String reviewTask){
        if(id==null||id.equals(except))return;var player=server.getPlayerList().getPlayer(id);if(player!=null)player.sendSystemMessage(line(text,reviewTask));
    }
    public static void players(MinecraftServer server,Collection<UUID> ids,UUID except,String text){players(server,ids,except,text,null);}
    public static void players(MinecraftServer server,Collection<UUID> ids,UUID except,Object text,String reviewTask){
        if(ids==null)return;var seen=new HashSet<UUID>();for(UUID id:ids)if(seen.add(id))player(server,id,except,text,reviewTask);
    }
    /** Tells the office and mandate holders of a kingdom's government. */
    public static void government(MinecraftServer server,String kingdom,UUID except,String text){government(server,kingdom,except,text,null);}
    public static void government(MinecraftServer server,String kingdom,UUID except,Object text,String reviewTask){
        if(kingdom==null||kingdom.isBlank())return;
        try{var government=KingdomsPoliticsApi.get(server).government(kingdom).orElse(null);if(government==null)return;
            var ids=new LinkedHashSet<UUID>(government.mandates().keySet());
            government.offices().values().stream().map(Politics.Office::holder).filter(p->p.kind()==Politics.Kind.PLAYER).map(Politics.Person::id).forEach(ids::add);
            players(server,ids,except,text,reviewTask);
        }catch(RuntimeException ignored){}
    }
    private Notify(){}
}
