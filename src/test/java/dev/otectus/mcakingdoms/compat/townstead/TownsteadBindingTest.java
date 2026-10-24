package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownsteadBindingTest {

    @Test
    void failPolicyWaitsWhenCapabilityIsUnknownButFailsConfirmedDeletion() {
        assertEquals(dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryStatus.WAITING,
                TownsteadServiceImpl.missingBuildingStatus(
                        dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryPolicy.FAIL_WITH_REASON, false));
        assertEquals(dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryStatus.FAILED,
                TownsteadServiceImpl.missingBuildingStatus(
                        dev.otectus.mcakingdoms.api.townstead.BuildingRecoveryPolicy.FAIL_WITH_REASON, true));
    }
    /**
     * Without Townstead only the capabilities that read MCA itself can bind: the loaded-village building
     * list, and the single-building query answered from it. Everything else says why it is missing.
     */
    @Test
    void absentTownsteadKeepsOnlyMcaBackedCapabilitiesAndCompleteDiagnostics() {
        TownsteadBinding binding = TownsteadBinding.resolve(false, true);
        java.util.Set<TownsteadCapability> mcaBacked =
                java.util.EnumSet.of(TownsteadCapability.READ_BUILDINGS, TownsteadCapability.READ_BUILDING);
        assertTrue(mcaBacked.containsAll(binding.capabilities()), binding.capabilities().toString());
        boolean buildings = binding.capabilities().contains(TownsteadCapability.READ_BUILDINGS);
        assertEquals(buildings, binding.capabilities().contains(TownsteadCapability.READ_BUILDING),
                "the single-building query is the MCA fallback exactly when MCA's building list is readable");
        assertEquals(buildings, binding.buildingFallback());
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            if (binding.capabilities().contains(capability)) continue;
            String diagnostic = binding.diagnostics().get(capability);
            assertTrue(diagnostic.contains("not installed") || diagnostic.contains("MCA"), capability + ": " + diagnostic);
        }
    }

    @Test
    void installedPublicApiProbeEnablesPublishedReadCapabilities() {
        Assumptions.assumeTrue(classPresent("com.aetherianartificer.townstead.api.TownsteadAPI"));
        TownsteadBinding binding = TownsteadBinding.resolve(true, false);
        assertTrue(binding.capabilities().containsAll(List.of(
                TownsteadCapability.READ_VILLAGER,
                TownsteadCapability.READ_NEEDS,
                TownsteadCapability.READ_SCHEDULE,
                TownsteadCapability.READ_CALENDAR,
                TownsteadCapability.READ_BUILDING,
                TownsteadCapability.READ_ORIGIN,
                TownsteadCapability.READ_GENE)), binding.diagnostics().toString());
        assertTrue(binding.diagnostics().get(TownsteadCapability.READ_SPIRIT).contains("disabled"));
        assertTrue(binding.diagnostics().get(TownsteadCapability.DISPATCH_REACTION).contains("disabled"));
    }

    @Test
    void sameFamilyRecoveryIsDeterministic() {
        TownsteadBuildingView high = building(19, "townstead:kitchen_l3");
        TownsteadBuildingView other = building(2, "townstead:mine_l1");
        TownsteadBuildingView low = building(4, "townstead:kitchen_l1");

        assertEquals(4, TownsteadServiceImpl.sameFamilyCandidate(
                List.of(high, other, low), "townstead:kitchen").orElseThrow().buildingId());
        assertTrue(TownsteadServiceImpl.sameFamilyCandidate(
                List.of(high, low), "townstead:library").isEmpty());
    }

    private static TownsteadBuildingView building(int id, String type) {
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        UUID settlement = UUID.fromString("221a4d27-a055-4434-a5ea-c3bdc28bc088");
        String family = TownsteadBuildingView.familyOf(type);
        return new TownsteadBuildingView(dimension, settlement,
                TownsteadBuildingView.bindingId(dimension, settlement, 8, id),
                8, id, type, family, TownsteadBuildingView.levelOf(type), 12,
                10, 64, 10, 8, 63, 8, 12, 67, 12);
    }


    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, TownsteadBindingTest.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
