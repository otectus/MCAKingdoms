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
 * Protocol 3 adds each row's translated title and detail, so the screen reads in the player's language. Protocol 4 adds
 * each row's labelled fields, the viewer's header (status, leader, capital, elections, regency and their own
 * responsibilities), the kingdom's heraldry and whether a government exists, for the sectioned Kingdom page.
 */
public final class PoliticalNetwork {
    private static final String PROTOCOL = "4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(new ResourceLocation("mcakingdoms", "politics"))
            .networkProtocolVersion(() -> PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
    private static final Map<UUID, Integer> LAST_REQUEST = new HashMap<>();
    private static Consumer<Reply> receiver = ignored -> { };
    public record Query(UUID id, String kingdom, String tab, int offset) { }
    /**
     * {@code titles}/{@code details} are serialized components, one per page row, in row order; {@code fields} gives each
     * row's labelled facts as alternating serialized label and value. {@code header} is the viewer's summary of the kingdom.
     */
    public record Reply(Page page, Result result, List<String> titles, List<String> details, String kingdomName,
                        List<List<String>> fields, List<String> header, String heraldry, int color, boolean governed) {
        public Reply(Page page, Result result) { this(page, result, List.of(), List.of(), "", List.of(), List.of(), "", 0, false); }
    }
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
                        List<String> titles = new ArrayList<>(), details = new ArrayList<>(), header = new ArrayList<>();
                        List<List<String>> fields = new ArrayList<>();
                        if (service instanceof GovernmentService government) {
                            for (var row : government.present(player, page)) {
                                titles.add(net.minecraft.network.chat.Component.Serializer.toJson(row.title()));
                                details.add(net.minecraft.network.chat.Component.Serializer.toJson(row.detail()));
                                var pairs = new ArrayList<String>();
                                for (var field : row.fields()) { pairs.add(net.minecraft.network.chat.Component.Serializer.toJson(field.label())); pairs.add(net.minecraft.network.chat.Component.Serializer.toJson(field.value())); }
                                fields.add(pairs);
                            }
                            government.header(player, q.kingdom()).forEach(line -> header.add(net.minecraft.network.chat.Component.Serializer.toJson(line)));
                        }
                        var kingdom = dev.otectus.mcakingdoms.api.McaKingdomsApi.get(player.getServer()).getKingdom(ResourceLocation.tryParse(q.kingdom()) == null ? new ResourceLocation("minecraft", "missing") : new ResourceLocation(q.kingdom()));
                        String name = kingdom.map(k -> net.minecraft.network.chat.Component.Serializer.toJson(net.minecraft.network.chat.Component.translatable(k.translationKey()))).orElse("");
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Reply(page, null, titles, details, name, fields, header,
                                kingdom.map(k -> k.heraldryIcon().toString()).orElse(""), kingdom.map(dev.otectus.mcakingdoms.api.KingdomView::uiColor).orElse(0),
                                service.government(q.kingdom()).isPresent()));
                    } catch (IllegalArgumentException | IllegalStateException ignored) { }
                }).add();
        CHANNEL.messageBuilder(Reply.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((r, b) -> b.writeUtf(bounded(r), LIMIT))
                .decoder(b -> PoliticalSavedData.JSON.fromJson(b.readUtf(60000), Reply.class))
                .consumerMainThread((r, ctx) -> receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(PoliticalNetwork.class);
    }
    static final int LIMIT = 60000;
    /**
     * The reply within the packet bound. Fields repeat what details say, so details go first, then fields; the rows and
     * titles always stay, so a long page still reads (with less detail) instead of failing to arrive.
     */
    static String bounded(Reply r) {
        String json = PoliticalSavedData.JSON.toJson(r);
        if (json.length() <= LIMIT) return json;
        json = PoliticalSavedData.JSON.toJson(new Reply(r.page(), r.result(), r.titles(), List.of(), r.kingdomName(), r.fields(), r.header(), r.heraldry(), r.color(), r.governed()));
        if (json.length() <= LIMIT) return json;
        return PoliticalSavedData.JSON.toJson(new Reply(r.page(), r.result(), r.titles(), List.of(), r.kingdomName(), List.of(), r.header(), r.heraldry(), r.color(), r.governed()));
    }
    public static void receiver(Consumer<Reply> value) { receiver = value; }
    public static void send(Query query) { CHANNEL.sendToServer(query); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { LAST_REQUEST.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LAST_REQUEST.clear(); }
}
