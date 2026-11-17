package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.persistence.FaultPoints;
import dev.otectus.mcakingdoms.persistence.StoreStatus;
import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.function.Function;

/**
 * Every store that keeps its own copy of a settlement id and must follow an identity change (AUD-01), by stable name.
 * Each consumer is idempotent (running it again after a crash finds nothing left to move) and reports {@code DONE} only
 * once its change is on disk. A read-only or unavailable store reports {@code PENDING} with its reason and is retried;
 * it is never replaced by an empty store to make recovery look complete. Stores not listed here resolve settlement ids
 * through redirects when they read (Townstead decisions, MCA Capitals and Living Villages bindings, residence, documents).
 */
public final class IdentityConsumers {
    private IdentityConsumers() {
    }

    public enum Result { DONE, PENDING }

    /** One consumer's answer; {@code reason} explains a pending result to operators. */
    public record Outcome(Result result, String reason) {
        static final Outcome DONE = new Outcome(Result.DONE, "");
        static Outcome pending(String reason) { return new Outcome(Result.PENDING, reason); }
    }

    @FunctionalInterface
    public interface Consumer {
        Outcome apply(MinecraftServer server, IdentityOperation operation);
    }

    private static final Map<String, Consumer> MERGE = new LinkedHashMap<>();
    private static final Map<String, Consumer> REASSIGN = new LinkedHashMap<>();

    static {
        merge("discovery", dev.otectus.mcakingdoms.knowledge.SettlementKnowledge::status, (server, op) -> {
            var knowledge = dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(server);
            knowledge.merge(op.source(), op.target());
            return !knowledge.isDirty() || dev.otectus.mcakingdoms.persistence.DurableSave.now(server, knowledge, dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.NAME);
        });
        merge("civic", s -> dev.otectus.mcakingdoms.civic.CivicRuntime.storeStatus(s).get(0),
                (server, op) -> { dev.otectus.mcakingdoms.civic.CivicService.settlementMerged(server, op.source(), op.target()); return true; });
        merge("campaigns", dev.otectus.mcakingdoms.warfare.CampaignSavedData::status,
                (server, op) -> { dev.otectus.mcakingdoms.warfare.CampaignSavedData.settlementMerged(server, op.source(), op.target()); return true; });
        merge("military_control", dev.otectus.mcakingdoms.warfare.ControlSavedData::status,
                (server, op) -> { dev.otectus.mcakingdoms.warfare.ControlSavedData.settlementMerged(server, op.source(), op.target()); return true; });
        merge("civilian_contracts", dev.otectus.mcakingdoms.warfare.contracts.CivilianContractService::storeStatus,
                (server, op) -> { dev.otectus.mcakingdoms.warfare.contracts.CivilianContractService.settlementMerged(server, op.source(), op.target()); return true; });
        merge("world_evolution", dev.otectus.mcakingdoms.evolution.EvolutionSavedData::status,
                (server, op) -> { dev.otectus.mcakingdoms.evolution.EvolutionSavedData.settlementMerged(server, op.source(), op.target()); return true; });
        merge("protection_pacts", dev.otectus.mcakingdoms.evolution.ProtectionSavedData::status,
                (server, op) -> { dev.otectus.mcakingdoms.evolution.ProtectionSavedData.settlementMerged(server, op.source(), op.target()); return true; });
        merge("dramas", dev.otectus.mcakingdoms.evolution.drama.DramaSavedData::status,
                (server, op) -> { dev.otectus.mcakingdoms.evolution.drama.DramaSavedData.settlementMerged(server, op.source(), op.target()); return true; });
        merge("world_context", dev.otectus.mcakingdoms.worldcontext.WorldContextSavedData::status,
                (server, op) -> { dev.otectus.mcakingdoms.worldcontext.WorldContextSavedData.settlementMerged(server, op.source(), op.target()); return true; });
        merge("politics", dev.otectus.mcakingdoms.politics.PoliticalSavedData::status, (server, op) -> {
            if (!(dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(server) instanceof dev.otectus.mcakingdoms.politics.GovernmentService government))
                throw new IllegalStateException("political records are not loaded yet");
            return government.settlementMerged(op.source(), op.target());
        });
        merge("development", dev.otectus.mcakingdoms.development.DevelopmentSavedData::status,
                (server, op) -> dev.otectus.mcakingdoms.development.DevelopmentService.settlementMerged(server, op.source(), op.target(), op.label()));
        REASSIGN.put("development", guarded(dev.otectus.mcakingdoms.development.DevelopmentSavedData::status,
                (server, op) -> dev.otectus.mcakingdoms.development.DevelopmentService.settlementReassigned(server, op.target(), op.kingdomBefore(), op.kingdomAfter())));
    }

    @FunctionalInterface
    private interface Step { boolean run(MinecraftServer server, IdentityOperation operation); }

    private static void merge(String name, Function<MinecraftServer, StoreStatus> status, Step step) {
        MERGE.put(name, guarded(status, step));
    }

    /** A consumer that waits while its store is read-only, and is done only when its step says its change is written. */
    private static Consumer guarded(Function<MinecraftServer, StoreStatus> status, Step step) {
        return (server, operation) -> {
            StoreStatus store = status.apply(server);
            if (!store.writable()) return Outcome.pending("read-only: " + store.diagnostic());
            return step.run(server, operation) ? Outcome.DONE : Outcome.pending("could not be written; retried");
        };
    }

    /** The consumer names an operation of {@code kind} waits for. */
    public static Set<String> names(IdentityOperation.Kind kind) {
        return Collections.unmodifiableSet(new LinkedHashSet<>((kind == IdentityOperation.Kind.MERGE ? MERGE : REASSIGN).keySet()));
    }

    /** Runs one consumer; a failure or exception is a pending outcome with its reason, never thrown to the caller. */
    static Outcome run(MinecraftServer server, IdentityOperation operation, String name) {
        Consumer consumer = (operation.kind() == IdentityOperation.Kind.MERGE ? MERGE : REASSIGN).get(name);
        if (consumer == null) return Outcome.DONE; // a consumer removed by a later version has nothing left to do
        try {
            FaultPoints.reach("identity.consumer." + name);
            return consumer.apply(server, operation);
        } catch (RuntimeException failure) {
            return Outcome.pending(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }
}
