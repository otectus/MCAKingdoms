package dev.otectus.mcakingdoms.civic;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;
import java.util.function.Consumer;

/**
 * The Guilds screen's read channel. Requests cannot name another player and run on the authenticated server thread.
 *
 * <p>Protocol 4 is read-only and carries the structured {@link CivicViews.Guild}: joining, leaving and every service go
 * through Kingdom Tasks' reviewed workflow (the screen opens it with the guild chosen), so there is one consequential path.
 * The older mutation actions are refused.
 */
public final class CivicNetwork {
    private static final String PROTOCOL = "4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(new ResourceLocation("mcakingdoms", "civic"))
            .networkProtocolVersion(() -> PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
    private static final com.google.gson.Gson JSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    /** Only {@code READ} is accepted; the rest stay so an older peer's message decodes and is refused. */
    public enum Action { READ, JOIN, LEAVE, INTRODUCE, COMMISSIONS, HOSPITALITY, WORKSHOP }
    public record Query(UUID request, int index, Action action, String organization) { }
    public record Reply(UUID request, CivicViews.View view, String outcome) { }
    private static final Map<UUID, Integer> LAST = new HashMap<>();
    private static final Map<UUID,Integer> LAST_MUTATION=new HashMap<>();
    private static Consumer<Reply> receiver = ignored -> { };
    private CivicNetwork() { }
    public static void init() {
        CHANNEL.messageBuilder(Query.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((q,b) -> { b.writeUUID(q.request()); b.writeVarInt(q.index()); b.writeEnum(q.action()); b.writeUtf(q.organization(),128); })
                .decoder(b -> new Query(b.readUUID(), b.readVarInt(), b.readEnum(Action.class), b.readUtf(128)))
                .consumerMainThread((q,ctx) -> {
                    var player = ctx.get().getSender(); if (player == null || q.index() < 0 || q.index() > 4096) return;
                    var throttle=q.action()==Action.READ?LAST:LAST_MUTATION;
                    int now = player.getServer().getTickCount(); var before = throttle.get(player.getUUID());
                    if (before != null && now - before < 4) return;
                    throttle.put(player.getUUID(), now);
                    try {
                        var view = CivicViews.own(player, q.index());
                        // Consequential actions are reviewed in Kingdom Tasks; this channel only reads.
                        String outcome = q.action() == Action.READ ? "" : "civic.use_tasks";
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),new Reply(q.request(),view,outcome));
                    } catch (IllegalArgumentException | IllegalStateException ignored) { }
                }).add();
        CHANNEL.messageBuilder(Reply.class,1,NetworkDirection.PLAY_TO_CLIENT)
                .encoder((r,b) -> { b.writeUUID(r.request()); var v=r.view(); b.writeUtf(v.organization(),128); b.writeUtf(v.nameKey(),256);
                    b.writeVarInt(v.index()); b.writeVarInt(v.total()); b.writeBoolean(v.active());
                    b.writeCollection(v.lines(),(out,s) -> out.writeUtf(net.minecraft.network.chat.Component.Serializer.toJson(s),2048)); b.writeUtf(r.outcome(),512);
                    b.writeUtf(v.guild()==null?"":JSON.toJson(v.guild()),60000); })
                .decoder(b -> { UUID id=b.readUUID(); String org=b.readUtf(128), name=b.readUtf(256); int index=b.readVarInt(), total=b.readVarInt();
                    boolean active=b.readBoolean(); int count=b.readVarInt(); if(count<0||count>32) throw new IllegalArgumentException("Oversized guild view");
                    List<net.minecraft.network.chat.Component> lines=new ArrayList<>(); for(int i=0;i<count;i++) lines.add(Objects.requireNonNull(net.minecraft.network.chat.Component.Serializer.fromJson(b.readUtf(2048))));
                    String outcome=b.readUtf(512); String guild=b.readUtf(60000);
                    return new Reply(id,new CivicViews.View(org,name,index,total,active,lines,guild.isEmpty()?null:JSON.fromJson(guild,CivicViews.Guild.class)),outcome); })
                .consumerMainThread((r,ctx) -> receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(CivicNetwork.class);
    }
    public static void receiver(Consumer<Reply> value) { receiver=value; }
    public static void send(Query query) { CHANNEL.sendToServer(query); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { LAST.remove(e.getEntity().getUUID()); LAST_MUTATION.remove(e.getEntity().getUUID()); }
    @SubscribeEvent public static void stop(ServerStoppedEvent e) { LAST.clear(); LAST_MUTATION.clear(); }
}
