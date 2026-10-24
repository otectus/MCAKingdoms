package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;
import java.util.function.Consumer;

/**
 * Read-only political ledger pages. Mutations travel only through the reviewed Kingdom Tasks protocol, so a
 * query carries no request at all (protocol 2; protocol 1 carried an ignored one the server still parsed).
 */
public final class PoliticalNetwork {
    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(new ResourceLocation("mcakingdoms", "politics"))
            .networkProtocolVersion(() -> PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
    private static final Map<UUID, Integer> LAST_REQUEST = new HashMap<>();
    private static Consumer<Reply> receiver = ignored -> { };
    public record Query(UUID id, String kingdom, String tab, int offset) { }
    public record Reply(Page page, Result result) { }
    private PoliticalNetwork() { }
    public static void init() {
        CHANNEL.messageBuilder(Query.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((q, b) -> { b.writeUUID(q.id()); b.writeUtf(q.kingdom(), 128); b.writeUtf(q.tab(), 32); b.writeVarInt(q.offset()); })
                .decoder(b -> new Query(b.readUUID(), b.readUtf(128), b.readUtf(32), b.readVarInt()))
                .consumerMainThread((q, ctx) -> {
                    var player = ctx.get().getSender(); if (player == null) return;
                    int now = player.getServer().getTickCount(); Integer before = LAST_REQUEST.get(player.getUUID());
                    if (before != null && now - before < 4) return;
                    LAST_REQUEST.put(player.getUUID(), now);
                    try {
                        PoliticalService service = KingdomsPoliticsApi.get(player.getServer());
                        Page page = service.page(player, q.id(), q.kingdom(), q.tab(), q.offset());
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Reply(page, null));
                    } catch (IllegalArgumentException | IllegalStateException ignored) { }
                }).add();
        CHANNEL.messageBuilder(Reply.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((r, b) -> b.writeUtf(PoliticalSavedData.JSON.toJson(r), 60000))
                .decoder(b -> PoliticalSavedData.JSON.fromJson(b.readUtf(60000), Reply.class))
                .consumerMainThread((r, ctx) -> receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(PoliticalNetwork.class);
    }
    public static void receiver(Consumer<Reply> value) { receiver = value; }
    public static void send(Query query) { CHANNEL.sendToServer(query); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { LAST_REQUEST.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LAST_REQUEST.clear(); }
}
