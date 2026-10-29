package dev.otectus.mcakingdoms.warfare;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.worldcontext.WorldContextApi;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.warfare.mobilization.MobilizationEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Consumer;

/**
 * Authenticated war-room snapshots with direct links to server-reviewed GUI tasks. Protocol 4 sends the snapshot in
 * sections (control, campaigns, temporary orders, civilian options) and marks each task link military or civilian.
 */
@Mod.EventBusSubscriber(modid=McaKingdomsApi.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class WarfareNetwork {
    private static SimpleChannel CHANNEL;
    public record Request(UUID request,UUID settlement){ }
    /**
     * A task link: {@code group} is {@code military} or {@code civilian}, so civilian recovery stays easy to find.
     * {@code unavailable} is the serialized reason the task cannot work on this server (a switch or a missing companion
     * mod), empty when it can; the link stays so the player can read why and open the Book topic.
     */
    public record Command(String label,String value,String group,String unavailable){
        public Command{if(label==null||label.length()>64||value==null||value.length()>512||group==null||group.length()>16||unavailable==null||unavailable.length()>LINE_LIMIT)throw new IllegalArgumentException("invalid war-room command");}
        public Command(String label,String value,String group){this(label,value,group,"");}
        public Command(String label,String value){this(label,value,"military");}
    }
    /** One section: {@code key} is control, campaigns, orders or civilian; lines are serialized components, each bounded. */
    public record Section(String key,List<String> lines){
        public Section{if(key==null||key.length()>16||lines==null)throw new IllegalArgumentException("invalid war-room section");lines=List.copyOf(lines);}
    }
    public record Snapshot(UUID request,UUID settlement,String title,long revision,List<Section> sections,List<Command> commands){
        public Snapshot{if(title==null||title.length()>128||revision<0||sections==null||sections.size()>8||commands==null||commands.size()>8)throw new IllegalArgumentException("invalid war-room snapshot");
            if(sections.stream().mapToInt(s->s.lines().size()).sum()>32)throw new IllegalArgumentException("invalid war-room snapshot");
            if(sections.stream().flatMap(s->s.lines().stream()).anyMatch(line->line==null||line.length()>LINE_LIMIT))throw new IllegalArgumentException("invalid war-room line");sections=List.copyOf(sections);commands=List.copyOf(commands);}
        /** One flat section, for callers with only lines. */
        public Snapshot(UUID request,UUID settlement,String title,long revision,List<String> lines,List<Command> commands,boolean flat){this(request,settlement,title,revision,List.of(new Section("control",lines)),commands);}
        /** Every line in order, for tests and plain readers. */
        public List<String> lines(){return sections.stream().flatMap(s->s.lines().stream()).toList();}
    }
    static final int LINE_LIMIT=2048;
    private static final Map<UUID,Integer> LAST=new HashMap<>();private static Consumer<Snapshot> receiver=ignored->{};private static boolean initialized;
    private WarfareNetwork(){ }
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event){event.enqueueWork(WarfareNetwork::init);}
    public static synchronized void init(){if(initialized)return;
        CHANNEL=NetworkRegistry.ChannelBuilder.named(new ResourceLocation("mcakingdoms","war_room"))
                .networkProtocolVersion(()->"4").clientAcceptedVersions("4"::equals).serverAcceptedVersions("4"::equals).simpleChannel();
        initialized=true;
        CHANNEL.messageBuilder(Request.class,0,NetworkDirection.PLAY_TO_SERVER)
                .encoder((q,b)->{b.writeUUID(q.request());b.writeUUID(q.settlement());}).decoder(b->new Request(b.readUUID(),b.readUUID()))
                .consumerMainThread((q,ctx)->{var player=ctx.get().getSender();if(player==null)return;int now=player.getServer().getTickCount();Integer prior=LAST.get(player.getUUID());
                    if(prior!=null&&now-prior<10)return;LAST.put(player.getUUID(),now);try{Snapshot snapshot=build(player,q);CHANNEL.send(PacketDistributor.PLAYER.with(()->player),snapshot);}
                    catch(IllegalArgumentException|IllegalStateException ignored){}}).add();
        CHANNEL.messageBuilder(Snapshot.class,1,NetworkDirection.PLAY_TO_CLIENT).encoder(WarfareNetwork::encode).decoder(WarfareNetwork::decode)
                .consumerMainThread((reply,ctx)->receiver.accept(reply)).add();net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(WarfareNetworkCleanup.class);
    }
    private static Snapshot build(net.minecraft.server.level.ServerPlayer viewer,Request request){var server=viewer.getServer();var kingdoms=McaKingdomsApi.get(server);
        var settlement=kingdoms.getSettlement(request.settlement()).orElseThrow(()->new IllegalArgumentException("Settlement unavailable"));
        if(!SettlementKnowledge.get(server).visible(viewer,settlement.id()))throw new IllegalArgumentException("Settlement undiscovered");
        var campaigns=CampaignService.get(server);
        var sections=new LinkedHashMap<String,List<Component>>(campaigns.roomSections(viewer,settlement.id()));
        // A Recruits: Living Villages village: the realm in control, the recognized government and the realms' own wars.
        var realms=dev.otectus.mcakingdoms.compat.livingvillages.LvPresentation.warRoom(server,viewer,settlement.id());
        if(!realms.isEmpty())sections.put("realms",realms);
        var orders=new ArrayList<Component>();
        try{var mobilization=MobilizationEvents.get(server).statusLines(viewer);
            orders.add(mobilization.isEmpty()?Component.translatableWithFallback("warfare.mcakingdoms.room.orders.none","You have no temporary troop orders. A temporary order restores the recruit's own orders when it ends.")
                    :Component.translatableWithFallback("warfare.mcakingdoms.room.deployments","Active deployments: %s",mobilization.size()));
            mobilization.stream().limit(3).forEach(orders::add);}
        catch(IllegalArgumentException unavailable){orders.add(Component.translatableWithFallback("warfare.mcakingdoms.room.mobilization_unavailable","The Recruits deployment provider is unavailable; retained deployments are not reported as restored."));}
        sections.put("orders",orders);
        var civilian=new ArrayList<Component>();
        civilian.add(WarfareConfig.enabled()&&WarfareConfig.CONTRACTS.get()
                ?Component.translatableWithFallback("warfare.mcakingdoms.room.contracts.on","Civilian recovery work is open here when the settlement needs it: Choose civilian recovery work.")
                :Component.translatableWithFallback("warfare.mcakingdoms.room.contracts.off","Civilian recovery work is turned off on this server."));
        WorldContextApi.get(server).ifPresent(world->{var routes=world.routes(viewer,0,8);civilian.add(Component.translatableWithFallback("warfare.mcakingdoms.room.routes","Known institution routes: %s",routes.size()));
            // Routes are named by their institutions, never by record ids.
            var names=new HashMap<UUID,Component>();
            try{for(var institution:dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(server).knownInstitutions(viewer,0,32))
                names.put(institution.id(),Component.translatableWithFallback(institution.titleKey(),dev.otectus.mcakingdoms.interaction.PlayerWords.text(institution.titleKey())));}catch(RuntimeException ignored){}
            Component unknown=Component.translatableWithFallback("warfare.mcakingdoms.room.institution","a known institution");
            routes.stream().limit(3).forEach(r->civilian.add(Component.translatableWithFallback("warfare.mcakingdoms.room.route","Route %s → %s · %s · checkpoint %s of %s",
                    names.getOrDefault(r.fromInstitution(),unknown),names.getOrDefault(r.toInstitution(),unknown),r.status(),r.nextCheckpoint()+1,r.checkpoints().size())));});
        sections.put("civilian",civilian);
        var encoded=new ArrayList<Section>();int budget=32;
        for(var entry:sections.entrySet()){
            var lines=new ArrayList<String>();
            for(Component line:entry.getValue()){if(budget<=0)break;budget--;String json=Component.Serializer.toJson(line);
                lines.add(json.length()<=LINE_LIMIT?json:Component.Serializer.toJson(Component.literal(line.getString().substring(0,Math.min(500,line.getString().length())))));}
            encoded.add(new Section(entry.getKey(),lines));
        }
        long revision=campaigns.revision();
        var commands=new ArrayList<Command>();
        var links=new ArrayList<>(List.of(new Command("Declare campaign","warfare.declare","military"),
                new Command("Propose control agreement","warfare.accord","military"),new Command("Deploy nearby recruits","warfare.muster_nearby","military"),
                new Command("Choose civilian work","warfare.contract","civilian"),new Command("Browse routes","world.routes","civilian"),new Command("Request resource access","world.resource","civilian")));
        if(!realms.isEmpty())links.add(new Command("War and peace with a realm","warfare.realm_peace","military"));
        for(var link:links){
            // The same feature check Kingdom Tasks applies, so a link never leads into a form that cannot succeed here.
            var blocked=dev.otectus.mcakingdoms.interaction.ActionRegistry.find(link.value()).map(task->dev.otectus.mcakingdoms.interaction.TaskMeta.availability(task,server))
                    .filter(availability->!availability.available()).map(dev.otectus.mcakingdoms.interaction.TaskMeta.Availability::reason);
            String reason=blocked.map(Component.Serializer::toJson).orElse("");
            if(reason.length()>LINE_LIMIT){String plain=blocked.get().getString();reason=Component.Serializer.toJson(Component.literal(plain.substring(0,Math.min(500,plain.length()))));}
            commands.add(new Command(link.label(),link.value(),link.group(),reason));
        }
        return new Snapshot(request.request(),settlement.id(),settlement.displayName(),revision,encoded,commands);
    }
    static void encode(Snapshot value,net.minecraft.network.FriendlyByteBuf out){out.writeUUID(value.request());out.writeUUID(value.settlement());out.writeUtf(value.title(),128);out.writeVarLong(value.revision());
        out.writeVarInt(value.sections().size());value.sections().forEach(section->{out.writeUtf(section.key(),16);out.writeVarInt(section.lines().size());section.lines().forEach(line->out.writeUtf(line,LINE_LIMIT));});
        out.writeVarInt(value.commands().size());value.commands().forEach(c->{out.writeUtf(c.label(),64);out.writeUtf(c.value(),512);out.writeUtf(c.group(),16);out.writeUtf(c.unavailable(),LINE_LIMIT);});}
    static Snapshot decode(net.minecraft.network.FriendlyByteBuf in){UUID request=in.readUUID(),settlement=in.readUUID();String title=in.readUtf(128);long revision=in.readVarLong();
        int sectionCount=in.readVarInt();if(sectionCount<0||sectionCount>8)throw new IllegalArgumentException("oversized war-room sections");List<Section> sections=new ArrayList<>();int total=0;
        for(int s=0;s<sectionCount;s++){String key=in.readUtf(16);int lineCount=in.readVarInt();total+=lineCount;if(lineCount<0||total>32)throw new IllegalArgumentException("oversized war-room lines");
            List<String> lines=new ArrayList<>();for(int i=0;i<lineCount;i++)lines.add(in.readUtf(LINE_LIMIT));sections.add(new Section(key,lines));}
        int commandCount=in.readVarInt();if(commandCount<0||commandCount>8)throw new IllegalArgumentException("oversized war-room commands");List<Command> commands=new ArrayList<>();for(int i=0;i<commandCount;i++)commands.add(new Command(in.readUtf(64),in.readUtf(512),in.readUtf(16),in.readUtf(LINE_LIMIT)));
        return new Snapshot(request,settlement,title,revision,sections,commands);}
    public static void receiver(Consumer<Snapshot> value){receiver=Objects.requireNonNull(value);}public static void request(UUID request,UUID settlement){CHANNEL.sendToServer(new Request(request,settlement));}
    static void forget(UUID player) { LAST.remove(player); }
    static void clear() { LAST.clear(); }
}
