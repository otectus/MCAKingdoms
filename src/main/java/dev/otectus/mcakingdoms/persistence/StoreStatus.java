package dev.otectus.mcakingdoms.persistence;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Whether one saved-data store accepts changes. A store that met unsupported or damaged data keeps it untouched and
 * refuses writes ("read-only"); this reports that state for server health without changing it.
 */
public record StoreStatus(String name, boolean writable, String diagnostic) {
    public static StoreStatus of(String name, boolean writable, String diagnostic) {
        return new StoreStatus(name, writable, writable ? "" : diagnostic == null || diagnostic.isBlank()
                ? name + ".dat holds data this version cannot write safely; it is kept read-only and unchanged" : diagnostic);
    }

    /** Every store of this mod, loading any not yet loaded this run (reading only; nothing is written). */
    public static List<StoreStatus> all(MinecraftServer server) {
        List<Function<MinecraftServer, StoreStatus>> stores = List.of(
                dev.otectus.mcakingdoms.knowledge.SettlementKnowledge::status,
                dev.otectus.mcakingdoms.politics.PoliticalSavedData::status,
                dev.otectus.mcakingdoms.factions.FactionsRuntime::storeStatus,
                dev.otectus.mcakingdoms.factions.organization.OrganizationRuntime::storeStatus,
                s -> dev.otectus.mcakingdoms.civic.CivicRuntime.storeStatus(s).get(0),
                s -> dev.otectus.mcakingdoms.civic.CivicRuntime.storeStatus(s).get(1),
                dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge::storeStatus,
                dev.otectus.mcakingdoms.warfare.CampaignSavedData::status,
                dev.otectus.mcakingdoms.warfare.ControlSavedData::status,
                dev.otectus.mcakingdoms.warfare.contracts.CivilianContractService::storeStatus,
                dev.otectus.mcakingdoms.warfare.mobilization.MobilizationSavedData::status,
                dev.otectus.mcakingdoms.worldcontext.WorldContextSavedData::status,
                dev.otectus.mcakingdoms.evolution.EvolutionSavedData::status,
                dev.otectus.mcakingdoms.evolution.drama.DramaSavedData::status,
                dev.otectus.mcakingdoms.evolution.ProtectionSavedData::status,
                dev.otectus.mcakingdoms.evolution.RecruitTransferData::status);
        List<StoreStatus> out = new ArrayList<>();
        for (var store : stores) {
            try {
                out.add(store.apply(server));
            } catch (RuntimeException unreadable) {
                out.add(new StoreStatus(store.toString(), false, "could not be inspected: " + unreadable.getMessage()));
            }
        }
        return out;
    }
}
