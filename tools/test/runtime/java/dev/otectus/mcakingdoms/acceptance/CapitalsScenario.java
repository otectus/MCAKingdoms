package dev.otectus.mcakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsReport;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import dev.otectus.mcakingdoms.politics.Sovereignty;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import java.util.*;
import java.util.function.Consumer;

import static dev.otectus.mcakingdoms.acceptance.IntegrationScenario.*;

/**
 * C on a packaged server with the real MCA Capitals 1.3.7 jar: the bridge reads Capitals through its public accessors, a
 * capital created through Capitals' own public constructor and manager maps to the kingdom of the settlement holding its
 * MCA village, that kingdom's crown actions defer while another kingdom stays native, and releasing the mapping after
 * the capital is removed returns the crown. Nothing of Capitals is changed except the fixture's own test capital.
 */
final class CapitalsScenario {
    private static final String CROWN = "mcakingdoms:madera", OTHER = "mcakingdoms:yew";

    private CapitalsScenario() { }

    static void run(MinecraftServer server, Consumer<String> finish) throws Exception {
        var capabilities = CapitalsBridge.capabilities();
        check(capabilities.readable(), "bridge reads the installed MCA Capitals " + capabilities.version() + " " + capabilities.diagnostic());
        check(capabilities.tested(), "installed Capitals " + capabilities.version() + " is a tested build " + CapitalsBridge.TESTED_VERSIONS);
        var level = server.overworld(); var api = McaKingdomsApi.get(server);
        var politics = (GovernmentService) KingdomsPoliticsApi.get(server);
        int village = 7771;
        var anchor = new BlockPos(320, 64, 320);
        var ref = new McaCommunityRef(level.dimension().location(), village).format();
        var made = api.registerCandidate(level, new SettlementCandidate(level.dimension(), anchor, 4, SettlementBounds.around(anchor, 4),
                new ResourceLocation("mcakingdoms", "capitals_acceptance"), "capitals-acceptance", DetectionSource.EXTERNAL, Optional.empty(), Optional.empty(),
                Map.of(McaCommunityRef.EXTERNAL_REF_NAMESPACE, ref), Optional.empty()));
        var settlement = api.setKingdom(made.id(), new ResourceLocation(CROWN));
        var interop = CapitalsInterop.get(server);
        interop.refresh();
        check(politics.sovereignty(CROWN).native_(), "without a capital the kingdom is governed natively");

        UUID capitalId = UUID.randomUUID(), sovereign = UUID.randomUUID();
        Class<?> recordType = Class.forName("com.majesttyx.mcacapitals.capital.CapitalRecord");
        Class<?> stateType = Class.forName("com.majesttyx.mcacapitals.capital.CapitalState");
        Class<?> managerType = Class.forName("com.majesttyx.mcacapitals.capital.CapitalManager");
        Object record = recordType.getConstructor(UUID.class, Integer.class).newInstance(capitalId, village);
        // Capitals 1.1.x records no dimension (its capitals are overworld villages); later builds name it.
        try { recordType.getMethod("setVillageDimensionId", String.class).invoke(record, level.dimension().location().toString()); } catch (NoSuchMethodException older) { }
        @SuppressWarnings({"unchecked", "rawtypes"}) Object active = Enum.valueOf((Class) stateType, "ACTIVE");
        recordType.getMethod("setState", stateType).invoke(record, active);
        recordType.getMethod("setSovereign", UUID.class).invoke(record, sovereign);
        managerType.getMethod("putCapital", recordType).invoke(null, record);
        try {
            interop.refresh();
            check(interop.capitals().stream().anyMatch(c -> c.id().equals(capitalId) && c.governing() && c.villageId() == village), "the real bridge reads the governing test capital");
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.UNMAPPED, "a governing capital holds the crown before its mapping is written");
            interop.reconcile();
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.CAPITALS, "the capital maps to the kingdom of its MCA village's settlement");
            check(politics.sovereignty(OTHER).native_(), "a kingdom without a capital stays native");
            var operator = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "CapitalsOperator")) { @Override public boolean hasPermissions(int n) { return true; } };
            var founding = politics.execute(operator, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.BOOTSTRAP, CROWN, CROWN + "_charter",
                    settlement.id().toString(), new Politics.Person(operator.getUUID(), Politics.Kind.PLAYER), "", "", "", 0, 0, 0));
            check(!founding.success() && founding.message().equals("politics.mcakingdoms.refusal.capitals_managed"), "founding a crown Capitals governs is refused: " + founding.message());
            check(!CapitalsReport.lines(server).isEmpty(), "the dry-run report lists the capital");
        } finally {
            managerType.getMethod("removeCapital", UUID.class).invoke(null, capitalId);
        }
        interop.refresh();
        check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.CAPITALS, "a removed capital keeps the crown read-only until review");
        interop.release(new UUID(0, 0), capitalId);
        interop.refresh();
        check(politics.sovereignty(CROWN).native_(), "releasing the mapping returns the crown");
        finish.accept("PASS integration MCA Capitals " + capabilities.version() + " read-only co-install");
    }
}
