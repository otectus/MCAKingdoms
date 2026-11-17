package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Production and supply visibility (spec 9.2, 9.3). Worksites and their orders are read on demand; advisory stock counts
 * are cached per settlement and metric, shared by every viewer and dropped when a worksite or building changes, and a
 * count is only ever made for a level whose dimension is the village's own. A count reserves nothing and pays nothing;
 * a zero may be incomplete evidence. Work-done notices are tallied per settlement and day for the page's narrative
 * only: a {@code WorkCompletedEvent} names no order, payer or delivered quantity, so it never completes or pays a
 * commission.
 */
final class TownsteadWork {
    private static final int MAX_WORKSITES = 32;
    private static final int MAX_ORDERS = 16;
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private final Map<String, Stock> stock = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Stock> eldest) { return size() > 512; }
    };
    private final Map<UUID, Tally> work = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Tally> eldest) { return size() > 512; }
    };
    private long stockReads;

    private record Stock(OptionalInt count, long at) {
    }

    private record Tally(long day, int units) {
    }

    record Site(TownsteadBackend.Worksite worksite, List<TownsteadBackend.Order> orders, int villageId) {
    }

    TownsteadWork(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    /** Worksites with their first orders (top priority first), bounded for a page. */
    List<Site> sites(UUID settlementId) {
        if (!townstead.states().usable(TownsteadCapability.READ_WORK_ORDERS)) return List.of();
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return List.of();
        ResourceLocation dimension = settlement.get().dimension().location();
        List<Site> out = new ArrayList<>();
        for (int villageId : townstead.villageIds(settlement.get(), dimension)) {
            for (TownsteadBackend.Worksite site : townstead.backendImpl().worksites(server, dimension, villageId)) {
                if (out.size() >= MAX_WORKSITES) return out;
                List<TownsteadBackend.Order> orders = townstead.backendImpl().orders(server, site.id());
                out.add(new Site(site, orders.subList(0, Math.min(MAX_ORDERS, orders.size())), villageId));
            }
        }
        return out;
    }

    /** An advisory count of an item ({@code tag} false) or tag across the settlement's villages' recognized storage. */
    OptionalInt count(UUID settlementId, ResourceLocation id, boolean tag) {
        if (!townstead.states().usable(TownsteadCapability.READ_STORAGE)) return OptionalInt.empty();
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return OptionalInt.empty();
        ServerLevel level = server.getLevel(settlement.get().dimension());
        if (level == null) return OptionalInt.empty();
        String key = settlement.get().id() + "|" + (tag ? "#" : "") + id;
        long now = server.getTickCount();
        Stock cached = stock.get(key);
        if (cached != null && now - cached.at() < TownsteadIntegrationConfig.STOCK_REFRESH_TICKS.get()) return cached.count();
        int total = 0;
        boolean any = false;
        for (int villageId : townstead.villageIds(settlement.get(), level.dimension().location())) {
            // The level handed over is the village's own dimension by construction; Townstead does not check it (9.3).
            OptionalInt counted = tag ? townstead.backendImpl().countTag(level, villageId, id) : townstead.backendImpl().countItem(level, villageId, id);
            if (counted.isPresent()) {
                any = true;
                total += Math.max(0, counted.getAsInt());
            }
        }
        stockReads++;
        OptionalInt result = any ? OptionalInt.of(total) : OptionalInt.empty();
        stock.put(key, new Stock(result, now));
        return result;
    }

    void workDone(UUID settlement) {
        if (settlement == null) return;
        long day = server.overworld().getDayTime() / 24_000L;
        Tally tally = work.get(settlement);
        work.put(settlement, tally == null || tally.day() != day ? new Tally(day, 1) : new Tally(day, tally.units() + 1));
    }

    /** Units of work Townstead reported for the settlement's residents today, narrative only. */
    int workToday(UUID settlement) {
        Tally tally = work.get(settlement);
        long day = server.overworld().getDayTime() / 24_000L;
        return tally == null || tally.day() != day ? 0 : tally.units();
    }

    void invalidate(UUID settlement) {
        if (settlement == null) return;
        String prefix = settlement + "|";
        stock.keySet().removeIf(key -> key.startsWith(prefix));
    }

    void clear() {
        stock.clear();
        work.clear();
    }

    long stockReads() {
        return stockReads;
    }

    int cachedCounts() {
        return stock.size();
    }
}
