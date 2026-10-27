package dev.otectus.mcakingdoms.compat.quests.receipts;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F04: paid institutional work is offered only when the installed MCA: Quests exposes the complete institutional
 * contract and names this mod's consumer itself. The probe reads method signatures; it never calls anything that changes
 * provider state.
 */
class QuestContractProbeTest {
    /** MCA: Quests 1.7.2 and later. */
    public static final class Current {
        public static ResourceLocation institutionalCompletionConsumer() { return new ResourceLocation("mcakingdoms", "regional_civic_network"); }
        public static boolean renewInstitutionalCompletionConsumer(ServerPlayer player) { return false; }
        public static boolean openInstitutionalCommissionMenu(ServerPlayer player, Entity giver, Set<?> quests, String token) { return false; }
    }

    /** MCA: Quests 1.7.1 as published: it requires the pre-rename consumer and does not name it. */
    public static final class Legacy {
        public static boolean renewInstitutionalCompletionConsumer(ServerPlayer player) { return false; }
        public static boolean openInstitutionalCommissionMenu(ServerPlayer player, Entity giver, Set<?> quests, String token) { return false; }
    }

    /** A provider paired with the pre-rename jar names that jar's consumer. */
    public static final class Foreign {
        public static ResourceLocation institutionalCompletionConsumer() { return new ResourceLocation("ultima_kingdoms", "regional_civic_network"); }
        public static boolean renewInstitutionalCompletionConsumer(ServerPlayer player) { return false; }
        public static boolean openInstitutionalCommissionMenu(ServerPlayer player, Entity giver, Set<?> quests, String token) { return false; }
    }

    /** Older still: no institutional commissions at all. */
    public static final class NoInstitutions {
        public static ResourceLocation institutionalCompletionConsumer() { return new ResourceLocation("mcakingdoms", "regional_civic_network"); }
    }

    @Test
    void theCompleteCurrentContractIsAvailable() {
        assertEquals("available", QuestCompletionBridge.institutionalContract(Current.class));
    }

    @Test
    void aPreRenameProviderIsReportedBeforeAnyPaidWorkIsAccepted() {
        assertEquals("legacy_consumer_contract", QuestCompletionBridge.institutionalContract(Legacy.class));
        assertEquals("foreign_consumer_namespace", QuestCompletionBridge.institutionalContract(Foreign.class));
    }

    @Test
    void missingMethodsAreUnsupportedNotAssumed() {
        assertEquals("missing_institutional_methods", QuestCompletionBridge.institutionalContract(NoInstitutions.class));
    }

    @Test
    void theConsumerFallsBackOnlyWhenTheProviderNamesNone() {
        assertEquals(new ResourceLocation("mcakingdoms", "regional_civic_network"), QuestCompletionBridge.providerConsumer(Current.class));
        assertEquals(QuestCompletionBridge.DEFAULT_CONSUMER, QuestCompletionBridge.providerConsumer(Legacy.class));
    }
}
