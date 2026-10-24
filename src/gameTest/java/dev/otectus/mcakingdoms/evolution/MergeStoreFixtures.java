package dev.otectus.mcakingdoms.evolution;

import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Seeds and reads an evolution scenario and region for the settlement-merge GameTest. The scenario is expired, so inert. */
public final class MergeStoreFixtures {
    private MergeStoreFixtures() {
    }

    public static UUID seedScenario(MinecraftServer server, UUID settlement) {
        var data = EvolutionSavedData.get(server);
        var next = data.snapshot();
        UUID id = UUID.randomUUID();
        var terms = new EvolutionState.Template(1, "Merge fixture", EvolutionState.Trigger.DEMAND,
                List.of(EvolutionState.Outcome.AID, EvolutionState.Outcome.DECLINE), 1_200, 2_400, 1, false);
        next.scenarios.put(id, new EvolutionState.Scenario(id, "mcakingdoms:merge_fixture", terms, settlement, null,
                "mcakingdoms:serenum", new EvolutionState.Evidence("fixture", "merge", "DEMAND", 1, "Merge fixture"), null,
                1, 1_201, 1, EvolutionState.Phase.EXPIRED, Map.of(), null, ""));
        next.eligible.add(settlement);
        next.revision++;
        if (!data.commit(server, next)) throw new IllegalStateException("Evolution fixture was not saved");
        return id;
    }

    public static UUID scenarioSettlement(MinecraftServer server, UUID scenario) {
        return EvolutionSavedData.get(server).snapshot().scenarios.get(scenario).settlement();
    }

    public static boolean eligible(MinecraftServer server, UUID settlement) {
        return EvolutionSavedData.get(server).snapshot().eligible.contains(settlement);
    }

    public static void removeScenario(MinecraftServer server, UUID scenario, UUID settlement) {
        var data = EvolutionSavedData.get(server);
        var next = data.snapshot();
        next.scenarios.remove(scenario);
        next.eligible.remove(settlement);
        next.revision++;
        data.commit(server, next);
    }
}
