package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.event.SettlementKingdomChangedEvent;
import dev.otectus.mcakingdoms.api.factions.event.FactionStandingChangedEvent;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded semantic dispatch (spec 10.1); Townstead remains responsible for reaction effects, animation and its own
 * cooldown, lock and chance gates (a {@code context} cause, never {@code command}). Nothing is searched when reactions
 * cannot play (no animation backend, Townstead's reactions switched off, or the reaction is not registered), the number of
 * villagers inspected is bounded as well as the number that react, the area searched is limited, and a burst of
 * allegiance changes in one settlement dispatches once.
 */
final class TownsteadPoliticalReactions {
    private static final int PLAYER_RADIUS = 24;
    private static final int MAX_PLAYER_TARGETS = 32;
    private static final int MAX_PLAYER_CANDIDATES = 96;
    private static final int SETTLEMENT_RADIUS = 48;
    private static final int MAX_SETTLEMENT_TARGETS = 64;
    private static final int MAX_SETTLEMENT_CANDIDATES = 128;
    private static final int SETTLEMENT_COALESCE_TICKS = 100;
    private static final ResourceLocation STANDING_IMPROVED =
            new ResourceLocation("mcakingdoms", "standing_improved");
    private static final ResourceLocation STANDING_WORSENED =
            new ResourceLocation("mcakingdoms", "standing_worsened");
    private static final ResourceLocation ALLEGIANCE_CHANGED =
            new ResourceLocation("mcakingdoms", "settlement_allegiance_changed");

    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private final Map<UUID, Integer> lastAllegianceDispatch = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) { return size() > 256; }
    };
    private long inspected;
    private long dispatched;
    private long skipped;

    TownsteadPoliticalReactions(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    /** Whether a reaction could play at all right now; a closed gate costs no entity search. */
    private boolean playable(ResourceLocation reaction) {
        if (!TownsteadIntegrationConfig.ENABLE_REACTIONS.get()) return false;
        TownsteadBackend backend = townstead.backendImpl();
        if (!townstead.states().usable(TownsteadCapability.DISPATCH_REACTION) || !backend.reactionsPlayable()) return false;
        Set<ResourceLocation> known = backend.reactionIds();
        // The 0.7.x backend cannot list reactions; Townstead refuses an unknown id itself there.
        return known.isEmpty() && !"v1".equals(backend.id()) || known.contains(reaction);
    }

    @SubscribeEvent
    public void standingChanged(FactionStandingChangedEvent event) {
        if (!event.result().applied() || event.result().appliedDelta() == 0 || event.request().quiet()) return;
        ResourceLocation reactionId = event.result().appliedDelta() > 0
                ? TownsteadMappings.reaction("standing_improved").orElse(STANDING_IMPROVED)
                : TownsteadMappings.reaction("standing_worsened").orElse(STANDING_WORSENED);
        if (!playable(reactionId)) { skipped++; return; }
        ServerPlayer player = server.getPlayerList().getPlayer(event.request().playerId());
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        ResourceLocation kingdomId = event.result().standing().kingdomId();
        Set<String> tags = Set.of(
                "kingdom:" + kingdomId,
                "standing:" + event.result().standing().tierId(),
                "cause:" + event.request().cause().name().toLowerCase(Locale.ROOT),
                "context:faction");
        int fired = 0;
        int looked = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(PLAYER_RADIUS), candidate -> candidate != player)) {
            if (fired >= MAX_PLAYER_TARGETS || looked >= MAX_PLAYER_CANDIDATES) break;
            looked++;
            if (!belongsToKingdom(entity, kingdomId) || townstead.villager(entity).isEmpty()) continue;
            if (townstead.fireReaction(level, entity, Optional.of(player), reactionId, tags)) fired++;
        }
        inspected += looked;
        dispatched += fired;
    }

    @SubscribeEvent
    public void settlementChanged(SettlementKingdomChangedEvent event) {
        ResourceLocation allegiance = TownsteadMappings.reaction("settlement_allegiance_changed").orElse(ALLEGIANCE_CHANGED);
        if (!playable(allegiance)) { skipped++; return; }
        SettlementView settlement = event.settlement();
        ServerLevel level = server.getLevel(settlement.dimension());
        if (level == null) return;
        int now = server.getTickCount();
        Integer last = lastAllegianceDispatch.get(settlement.id());
        if (last != null && now - last >= 0 && now - last < SETTLEMENT_COALESCE_TICKS) { skipped++; return; }
        lastAllegianceDispatch.put(settlement.id(), now);
        var bounds = settlement.bounds();
        var anchor = settlement.anchor();
        // Around the anchor, inside the settlement: never the whole of an enormous settlement for one change.
        double minX = Math.max(bounds.minX(), anchor.getX() - SETTLEMENT_RADIUS);
        double maxX = Math.min(bounds.maxX() + 1.0D, anchor.getX() + SETTLEMENT_RADIUS + 1.0D);
        double minZ = Math.max(bounds.minZ(), anchor.getZ() - SETTLEMENT_RADIUS);
        double maxZ = Math.min(bounds.maxZ() + 1.0D, anchor.getZ() + SETTLEMENT_RADIUS + 1.0D);
        if (minX >= maxX || minZ >= maxZ) return;
        AABB area = new AABB(minX, Math.max(level.getMinBuildHeight(), anchor.getY() - SETTLEMENT_RADIUS), minZ,
                maxX, Math.min(level.getMaxBuildHeight(), anchor.getY() + SETTLEMENT_RADIUS), maxZ);
        Set<String> tags = Set.of(
                "kingdom_old:" + event.oldKingdom(),
                "kingdom_new:" + event.newKingdom(),
                "reason:" + event.reason().name().toLowerCase(Locale.ROOT),
                "context:settlement");
        int fired = 0;
        int looked = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (fired >= MAX_SETTLEMENT_TARGETS || looked >= MAX_SETTLEMENT_CANDIDATES) break;
            looked++;
            if (!residesIn(entity, settlement.id()) || townstead.villager(entity).isEmpty()) continue;
            if (townstead.fireReaction(level, entity, Optional.empty(), allegiance, tags)) fired++;
        }
        inspected += looked;
        dispatched += fired;
    }

    record Stats(long inspected, long dispatched, long skipped) {
    }

    Stats stats() {
        return new Stats(inspected, dispatched, skipped);
    }

    private boolean belongsToKingdom(LivingEntity entity, ResourceLocation kingdomId) {
        return kingdoms.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement)
                .flatMap(kingdoms::getSettlement)
                .map(SettlementView::kingdomId)
                .filter(kingdomId::equals)
                .isPresent();
    }

    private boolean residesIn(LivingEntity entity, UUID settlementId) {
        return kingdoms.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement)
                .filter(settlementId::equals).isPresent();
    }
}
