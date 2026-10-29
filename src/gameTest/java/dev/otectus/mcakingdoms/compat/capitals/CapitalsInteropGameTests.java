package dev.otectus.mcakingdoms.compat.capitals;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import dev.otectus.mcakingdoms.politics.PoliticalSavedData;
import dev.otectus.mcakingdoms.politics.Sovereignty;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/**
 * C: with a governing MCA Capitals capital in a kingdom, only that kingdom's crown actions defer to Capitals; its civic
 * offices keep working and other kingdoms are untouched. Ambiguity quarantines, an operator's acceptance settles it, and
 * a mapping is released only once its capital stops governing. Runs in its own batch: it changes kingdom-wide answers.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class CapitalsInteropGameTests {
    private static final String CROWN = "mcakingdoms:madera", OTHER = "mcakingdoms:anemosia";

    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    private static SettlementView settlement(GameTestHelper helper, KingdomsService api, String key, int dx, int village, String kingdom) {
        // Its own lane far from other tests' settlements, so the layout of a run never makes two overlap.
        BlockPos anchor = helper.absolutePos(new BlockPos(dx, 2, 2)).offset(0, 0, 59_000);
        String ref = new McaCommunityRef(helper.getLevel().dimension().location(), village).format();
        var made = api.registerCandidate(helper.getLevel(), new SettlementCandidate(helper.getLevel().dimension(), anchor, 4, SettlementBounds.around(anchor, 4),
                new ResourceLocation("mcakingdoms", "capitals_test"), key + anchor, DetectionSource.EXTERNAL, Optional.empty(), Optional.empty(),
                Map.of(McaCommunityRef.EXTERNAL_REF_NAMESPACE, ref), Optional.empty()));
        return api.setKingdom(made.id(), new ResourceLocation(kingdom));
    }

    private static CapitalsBridge.CapitalView capital(UUID id, GameTestHelper helper, int village, String state, UUID sovereign) {
        return new CapitalsBridge.CapitalView(id, helper.getLevel().dimension().location(), village, state, sovereign, false, null, null,
                List.of(), Set.of(), Set.of(), Set.of(), false, false);
    }

    private static Request req(GovernmentService politics, Action action, String kingdom, String definition, String target, ServerPlayer person) {
        return new Request(UUID.randomUUID(), politics.revision(), action, kingdom, definition, target, person == null ? null : new Person(person.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0);
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "capitals_interop")
    public static void crownFollowsTheGoverningCapitalAndCivicWorkContinues(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var api = McaKingdomsApi.get(server);
        var ruler = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "CapitalsRuler")) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
        var minister = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "CapitalsMinister"));
        var known = Set.of(ruler.getUUID(), minister.getUUID());
        var politics = new GovernmentService(server, api, McaKingdoms.POLITICS, new PoliticalSavedData(), false, known::contains);
        var first = settlement(helper, api, "capitals-first", 0, 77_101, CROWN);
        var second = settlement(helper, api, "capitals-second", 600, 77_102, CROWN);
        var elsewhere = settlement(helper, api, "capitals-other", 1200, 77_103, OTHER);
        for (var made : List.of(first, second, elsewhere)) {
            var now = api.findSettlement(made.id().toString());
            check(now.map(v -> v.id().equals(made.id()) && v.kingdomId().equals(made.kingdomId())).orElse(false),
                    "Test settlement changed after registration: " + made.id() + " " + made.kingdomId() + " -> " + now.map(v -> v.id() + " " + v.kingdomId()).orElse("missing"));
        }
        var founded = politics.execute(ruler, req(politics, Action.BOOTSTRAP, CROWN, CROWN + "_charter", first.id().toString(), ruler));
        check(founded.success(), "Founding before Capitals failed: " + founded.message());
        UUID x = UUID.randomUUID(), y = UUID.randomUUID();
        var live = new ArrayList<CapitalsBridge.CapitalView>(List.of(capital(x, helper, 77_101, "ACTIVE", UUID.randomUUID())));
        CapitalsBridge.reset(new CapitalsBridge.Capabilities(CapitalsBridge.Availability.AVAILABLE, CapitalsBridge.TESTED_VERSION, true, Set.of("read.capitals"), ""));
        CapitalsBridge.testSource = s -> List.copyOf(live);
        var interop = CapitalsInterop.get(server);
        try {
            interop.refresh();
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.UNMAPPED, "A governing capital must hold the crown before it is mapped");
            check(politics.sovereignty(OTHER).native_(), "A kingdom without a capital stays native");
            interop.reconcile();
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.CAPITALS, "One governing capital maps automatically: " + politics.sovereignty(CROWN));
            var refused = politics.execute(ruler, req(politics, Action.NAME_SUCCESSOR, CROWN, "", "", minister));
            check(!refused.success() && refused.message().equals("politics.mcakingdoms.refusal.capitals_managed"), "Succession stayed writable: " + refused.message());
            var leader = politics.execute(ruler, req(politics, Action.REMOVE_OFFICE, CROWN, "mcakingdoms:leader", "", null));
            check(!leader.success() && leader.message().equals("politics.mcakingdoms.refusal.capitals_managed"), "The leader's office stayed writable: " + leader.message());
            var civic = politics.execute(ruler, req(politics, Action.APPOINT, CROWN, "mcakingdoms:chancellor", "", minister));
            check(civic.success(), "A civic office must still be appointed: " + civic.message());
            check(politics.execute(ruler, req(politics, Action.BOOTSTRAP, OTHER, OTHER + "_charter", elsewhere.id().toString(), ruler)).success(), "Another kingdom must still be founded natively");

            live.add(capital(y, helper, 77_102, "FOUNDED", UUID.randomUUID())); interop.refresh(); interop.reconcile();
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.UNMAPPED, "Two capitals in one kingdom must be quarantined");
            interop.accept(ruler.getUUID(), x); interop.refresh(); interop.reconcile();
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.CAPITALS, "An accepted capital settles the kingdom");
            check(CapitalsSavedData.get(server).binding(y).map(b -> b.status() == CapitalsSavedData.Status.QUARANTINED).orElse(false), "The rival stays quarantined");
            boolean blocked = false;
            try { interop.release(ruler.getUUID(), x); } catch (IllegalArgumentException expected) { blocked = true; }
            check(blocked, "A governing capital's mapping was released, leaving two rulers");

            live.clear(); interop.refresh();
            check(politics.sovereignty(CROWN).mode() == Sovereignty.Mode.CAPITALS, "A capital that stopped governing keeps the crown read-only until review");
            interop.release(ruler.getUUID(), x); interop.release(ruler.getUUID(), y); interop.refresh();
            check(politics.sovereignty(CROWN).native_(), "Released mappings return the crown: " + politics.sovereignty(CROWN));
            check(politics.execute(ruler, req(politics, Action.NAME_SUCCESSOR, CROWN, "", "", minister)).success(), "The released crown must be writable again");
            check(first.id() != null && second.id() != null, "settlements registered");
            helper.succeed();
        } finally {
            CapitalsBridge.testSource = null;
            CapitalsBridge.reset(null);
            CapitalsSavedData.get(server).commit(server, Map.of());
            interop.refresh();
        }
    }
}
