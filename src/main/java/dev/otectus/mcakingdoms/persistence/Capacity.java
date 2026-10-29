package dev.otectus.mcakingdoms.persistence;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * How full one bounded collection of a saved-data store is, and what relieves it (F12). Bounds are defensive limits that
 * keep saves, packets and ticks bounded; when one is reached the owning service refuses new records rather than dropping
 * old ones. Relief differs by collection: some are pruned automatically, two (finished recruit transfers and finished
 * opportunities) can be archived by an operator, and the rest keep refusing until the documented offline procedure is
 * run, because their records are still referenced by history, clauses or receipts.
 */
public record Capacity(String store, String collection, long used, long limit, Relief relief) {
    public enum Relief {
        /** Pruned automatically by retention; nothing to do. */
        RETENTION,
        /** {@code /kingdoms maintenance compact transfers} archives finished records. */
        COMPACT_TRANSFERS,
        /** {@code /kingdoms maintenance compact opportunities} archives finished records and lapsed cooldowns. */
        COMPACT_OPPORTUNITIES,
        /** New records are refused at the bound; docs/Capacity-and-Maintenance.md describes the offline procedure. */
        OFFLINE_PROCEDURE,
        /** Evidence that must be kept (it stops a receipt or proof being used twice); the bound is the guarantee. */
        KEPT_AS_EVIDENCE
    }

    public enum Level { NORMAL, NEAR, FULL }

    /** At least 80% is near capacity; the bound itself is full. */
    public Level level() {
        if (limit <= 0) return Level.NORMAL;
        if (used >= limit) return Level.FULL;
        return used * 5 >= limit * 4 ? Level.NEAR : Level.NORMAL;
    }

    public int percent() {
        return limit <= 0 ? 0 : (int) Math.min(100, used * 100 / limit);
    }

    /** Every bounded collection this mod keeps, reading each store without writing anything. */
    public static List<Capacity> all(MinecraftServer server) {
        List<Function<MinecraftServer, List<Capacity>>> stores = List.of(
                dev.otectus.mcakingdoms.evolution.RecruitTransferData::capacity,
                dev.otectus.mcakingdoms.evolution.EvolutionSavedData::capacity,
                dev.otectus.mcakingdoms.politics.PoliticalSavedData::capacity,
                dev.otectus.mcakingdoms.warfare.CampaignSavedData::capacity,
                dev.otectus.mcakingdoms.evolution.drama.DramaSavedData::capacity,
                dev.otectus.mcakingdoms.evolution.ProtectionSavedData::capacity,
                dev.otectus.mcakingdoms.civic.CivicRuntime::capacity,
                dev.otectus.mcakingdoms.knowledge.SettlementKnowledge::capacity,
                dev.otectus.mcakingdoms.document.DocumentLedger::capacity,
                dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData::capacity,
                dev.otectus.mcakingdoms.development.DevelopmentSavedData::capacity,
                dev.otectus.mcakingdoms.compat.livingvillages.LvBridgeSavedData::capacity);
        List<Capacity> out = new ArrayList<>();
        for (var store : stores) {
            try {
                out.addAll(store.apply(server));
            } catch (RuntimeException unreadable) {
                // A store that cannot be read reports through StoreStatus; capacity simply omits it.
            }
        }
        return out;
    }
}
