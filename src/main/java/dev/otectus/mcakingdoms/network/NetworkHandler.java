package dev.otectus.mcakingdoms.network;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class NetworkHandler {
    /**
     * 6: ledger search, sort and position (LedgerRequestPacket/LedgerPagePacket) and the Book's kingdom gallery.
     * 7 (0.3.0): the gallery-stale notice (GalleryStalePacket), political notices and office badges.
     */
    private static final String PROTOCOL = "7";
    private static final int PAGE_SIZE_WITH_SENTINEL = 21;
    private static final int MAX_OFFSET = 1_000_000;
    private static final int REQUEST_COOLDOWN_TICKS = 4;
    private static final AtomicLong CLIENT_REQUEST_IDS = new AtomicLong();
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(McaKingdomsApi.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static final Map<UUID, Integer> LAST_LEDGER_REQUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_RETRY_RESPONSE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_GALLERY_REQUEST = new ConcurrentHashMap<>();

    private static volatile Consumer<LedgerPagePacket> ledgerPageReceiver = ignored -> { };
    private static volatile Consumer<OverlayPacket> overlayReceiver = ignored -> { };
    private static volatile Runnable openLedgerReceiver = () -> { };
    private static volatile Consumer<GuideKingdomsPacket> galleryReceiver = ignored -> { };
    private static volatile Consumer<GalleryStalePacket> galleryStaleReceiver = ignored -> { };
    private static volatile Consumer<PoliticalNoticePacket> noticeReceiver = ignored -> { };
    private static volatile Consumer<OfficeBadgePacket> badgeReceiver = ignored -> { };
    private static boolean initialized;

    private NetworkHandler() {
    }

    public static synchronized void init() {
        if (initialized) return;
        int id = 0;
        CHANNEL.messageBuilder(LedgerRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(LedgerRequestPacket::encode)
                .decoder(LedgerRequestPacket::decode)
                .consumerMainThread((packet, context) -> handleLedgerRequest(packet, context.get().getSender()))
                .add();
        CHANNEL.messageBuilder(LedgerPagePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LedgerPagePacket::encode)
                .decoder(LedgerPagePacket::decode)
                .consumerMainThread((packet, ignored) -> ledgerPageReceiver.accept(packet))
                .add();
        CHANNEL.messageBuilder(OpenLedgerPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenLedgerPacket::encode)
                .decoder(OpenLedgerPacket::decode)
                .consumerMainThread((ignoredPacket, ignoredContext) -> openLedgerReceiver.run())
                .add();
        CHANNEL.messageBuilder(OverlayPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OverlayPacket::encode)
                .decoder(OverlayPacket::decode)
                .consumerMainThread((packet, ignored) -> overlayReceiver.accept(packet))
                .add();
        CHANNEL.messageBuilder(GuideKingdomsPacket.Request.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(GuideKingdomsPacket.Request::encode)
                .decoder(GuideKingdomsPacket.Request::decode)
                .consumerMainThread((packet, context) -> handleGalleryRequest(packet, context.get().getSender()))
                .add();
        CHANNEL.messageBuilder(GuideKingdomsPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GuideKingdomsPacket::encode)
                .decoder(GuideKingdomsPacket::decode)
                .consumerMainThread((packet, ignored) -> galleryReceiver.accept(packet))
                .add();
        CHANNEL.messageBuilder(GalleryStalePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GalleryStalePacket::encode)
                .decoder(GalleryStalePacket::decode)
                .consumerMainThread((packet, ignored) -> galleryStaleReceiver.accept(packet))
                .add();
        CHANNEL.messageBuilder(PoliticalNoticePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PoliticalNoticePacket::encode)
                .decoder(PoliticalNoticePacket::decode)
                .consumerMainThread((packet, ignored) -> noticeReceiver.accept(packet))
                .add();
        CHANNEL.messageBuilder(OfficeBadgePacket.class, id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OfficeBadgePacket::encode)
                .decoder(OfficeBadgePacket::decode)
                .consumerMainThread((packet, ignored) -> badgeReceiver.accept(packet))
                .add();
        dev.otectus.mcakingdoms.politics.PoliticalNetwork.init();
        initialized = true;
    }

    public static void setClientReceivers(Consumer<LedgerPagePacket> ledgerPages,
                                          Consumer<OverlayPacket> overlays,
                                          Runnable openLedger) {
        ledgerPageReceiver = ledgerPages;
        overlayReceiver = overlays;
        openLedgerReceiver = openLedger;
    }

    /** The Book's kingdom gallery receiver; set by the client bootstrap. */
    public static void setGalleryReceiver(Consumer<GuideKingdomsPacket> receiver) {
        galleryReceiver = receiver;
    }

    /** The Book's gallery-stale receiver; set by the client bootstrap. */
    public static void setGalleryStaleReceiver(Consumer<GalleryStalePacket> receiver) {
        galleryStaleReceiver = receiver;
    }

    public static void setPoliticalReceivers(Consumer<PoliticalNoticePacket> notices, Consumer<OfficeBadgePacket> badges) {
        noticeReceiver = notices;
        badgeReceiver = badges;
    }

    public static void sendNotice(ServerPlayer player, PoliticalNoticePacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendBadge(ServerPlayer player, OfficeBadgePacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** To every player tracking {@code entity} (and the entity itself when it is a player): the office badge sync of A02. */
    public static void sendBadgeToTrackers(net.minecraft.world.entity.Entity entity, OfficeBadgePacket packet) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), packet);
    }

    /** Tells one player that their Book gallery changed; the client decides whether and when to ask again. */
    public static void galleryChanged(ServerPlayer player, GalleryStalePacket.Reason reason) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GalleryStalePacket(reason));
    }

    public static long requestKingdomGallery() {
        long requestId = CLIENT_REQUEST_IDS.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1);
        CHANNEL.sendToServer(new GuideKingdomsPacket.Request(requestId));
        return requestId;
    }

    public static long requestLedgerPage(Optional<ResourceLocation> kingdomId, int offset,
                                         long expectedRegistryRevision, String query,
                                         dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.Sort sort) {
        long requestId = CLIENT_REQUEST_IDS.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1);
        CHANNEL.sendToServer(new LedgerRequestPacket(requestId, expectedRegistryRevision, kingdomId, offset, query, sort));
        return requestId;
    }

    public static void openLedger(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenLedgerPacket());
    }

    public static void sendOverlay(ServerPlayer player, OverlayPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void forgetPlayer(UUID playerId) {
        LAST_LEDGER_REQUEST.remove(playerId);
        LAST_RETRY_RESPONSE.remove(playerId);
        LAST_GALLERY_REQUEST.remove(playerId);
    }

    /** Public kingdom definitions plus this viewer's own count of known settlements per kingdom. Read-only. */
    public static GuideKingdomsPacket gallery(ServerPlayer player, long requestId) {
        KingdomsService service = McaKingdomsApi.get(player.getServer());
        var knowledge = dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer());
        List<KingdomSummary> kingdoms = new java.util.ArrayList<>();
        List<Integer> known = new java.util.ArrayList<>();
        service.getKingdoms().stream().filter(dev.otectus.mcakingdoms.api.KingdomView::defined)
                .sorted(Comparator.comparing(view -> view.id().toString())).limit(64).forEach(view -> {
                    kingdoms.add(KingdomSummary.from(view));
                    known.add((int) Math.min(Integer.MAX_VALUE, knowledge.count(player, service, view.id())));
                });
        return new GuideKingdomsPacket(requestId, kingdoms, known);
    }

    private static void handleGalleryRequest(GuideKingdomsPacket.Request packet, ServerPlayer player) {
        if (player == null) return;
        int now = player.getServer().getTickCount();
        Integer previous = LAST_GALLERY_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < 20) return;
        LAST_GALLERY_REQUEST.put(player.getUUID(), now);
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), gallery(player, packet.requestId()));
        } catch (IllegalStateException unavailable) {
            // No live Kingdoms service yet: the Book shows that the gallery is unavailable after its timeout.
        }
    }

    private static void handleLedgerRequest(LedgerRequestPacket packet, ServerPlayer player) {
        if (player == null) return;
        int now = player.getServer().getTickCount();
        int offset = packet.offset();
        if (offset < 0 || offset > MAX_OFFSET || offset % 20 != 0) return;
        KingdomsService service;
        try {
            service = McaKingdomsApi.get(player.getServer());
        } catch (IllegalStateException ignored) {
            return;
        }
        Optional<ResourceLocation> kingdom = packet.kingdomId();
        // A filter naming a kingdom the server no longer defines is answered for all kingdoms, and the screen says why.
        String notice = "";
        if (kingdom.isPresent() && service.getKingdom(kingdom.get()).isEmpty()) {
            kingdom = Optional.empty();
            notice = "kingdom_missing";
        }
        final Optional<ResourceLocation> filter = kingdom;

        Integer previous = LAST_LEDGER_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < REQUEST_COOLDOWN_TICKS) {
            int retryAfter = REQUEST_COOLDOWN_TICKS - (now - previous);
            Integer lastRetry = LAST_RETRY_RESPONSE.get(player.getUUID());
            if (lastRetry == null || lastRetry != now) {
                LAST_RETRY_RESPONSE.put(player.getUUID(), now);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new LedgerPagePacket(packet.requestId(), service.revision(), packet.kingdomId(), offset,
                                false, retryAfter, List.of(), List.of(), "", packet.query(), packet.sort(), 0, ""));
            }
            return;
        }
        LAST_LEDGER_REQUEST.put(player.getUUID(), now);

        var knowledge = dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer());
        service.getSettlementAt(player.serverLevel(), player.blockPosition())
                .ifPresent(s -> knowledge.discover(player.getUUID(), s.id()));
        long revision = service.revision() + knowledge.revision();
        boolean revisionReset = packet.expectedRegistryRevision() != 0
                && packet.expectedRegistryRevision() != revision;
        int actualOffset = revisionReset ? 0 : offset;
        var search = knowledge.search(player, service, filter, packet.query(), packet.sort(), actualOffset, PAGE_SIZE_WITH_SENTINEL);
        List<SettlementSummary> settlements = search.settlements().stream().map(SettlementSummary::from).toList();
        List<KingdomSummary> kingdoms = service.getKingdoms().stream()
                .sorted(Comparator.comparing(view -> view.id().toString()))
                .limit(64)
                .map(KingdomSummary::from)
                .toList();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new LedgerPagePacket(packet.requestId(), revision, packet.kingdomId(), actualOffset,
                        revisionReset, 0, settlements, kingdoms, knowledge.diagnostic(), packet.query(), packet.sort(), search.total(), notice));
    }
}
