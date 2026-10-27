package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry.Outcome;
import dev.otectus.mcakingdoms.interaction.Outcomes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * F07: a saved proposal is reported as recorded and waiting, never as in effect; a signature that completes the pair is
 * in effect; a petition waits for review until a decision; and records stay private to those entitled to them.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class PoliticalOutcomeGameTests {
    private static final String SERENUM = "mcakingdoms:serenum", LUNARI = "mcakingdoms:lunari";

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static SettlementView settlement(GameTestHelper helper, KingdomsService service, String key, int dx, String kingdom) {
        BlockPos position = helper.absolutePos(new BlockPos(dx, 2, 2));
        var result = service.registerCandidate(helper.getLevel(), SettlementCandidate.structure(helper.getLevel().dimension(), position, 4,
                new ResourceLocation("mcakingdoms:outcome_test"), key + position, null));
        return service.setKingdom(result.id(), new ResourceLocation(kingdom));
    }

    private static Request req(GovernmentService service, Action action, String kingdom, String definition, String target,
                               ServerPlayer person, String counterpart, String text, String hash) {
        return new Request(UUID.randomUUID(), service.revision(), action, kingdom, definition, target,
                person == null ? null : new Person(person.getUUID(), Kind.PLAYER), counterpart, text, hash, 0, 0, 0);
    }

    private static String key(Outcome outcome) {
        return outcome.message() instanceof net.minecraft.network.chat.Component c
                && c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : String.valueOf(outcome.message());
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void proposalsWaitAndSignedAgreementsAreInEffect(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var identity = McaKingdomsApi.get(server);
        var data = new PoliticalSavedData();
        var known = new HashSet<UUID>();
        var politics = new GovernmentService(server, identity, McaKingdoms.POLITICS, data, false, known::contains);
        var one = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "OutcomeOne")) {
            @Override public boolean hasPermissions(int level) { return level <= 2; }
        };
        var two = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "OutcomeTwo"));
        var stranger = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "OutcomeStranger"));
        known.addAll(List.of(one.getUUID(), two.getUUID(), stranger.getUUID()));
        var a = settlement(helper, identity, "outcome-a", 0, SERENUM);
        var b = settlement(helper, identity, "outcome-b", 48, LUNARI);
        check(politics.execute(one, req(politics, Action.BOOTSTRAP, SERENUM, SERENUM + "_charter", a.id().toString(), one, "", "", "")).success(), "Serenum founding failed");
        check(politics.execute(one, req(politics, Action.BOOTSTRAP, LUNARI, LUNARI + "_charter", b.id().toString(), two, "", "", "")).success(), "Lunari founding failed");

        Result proposal = politics.execute(one, req(politics, Action.PROPOSE, SERENUM, "mcakingdoms:diplomatic_recognition", "", null, LUNARI, "Mutual recognition", ""));
        check(proposal.success(), proposal.message());
        var saved = Outcomes.political("propose", proposal.recordId(), politics.recordState(one, proposal.recordId()));
        check(saved.state() == Outcome.State.PENDING, "A saved unsigned proposal was reported as " + saved.state());
        check(key(saved).endsWith("politics.proposal_saved"), "Unexpected proposal wording: " + key(saved));
        check(politics.recordState(stranger, proposal.recordId()).isEmpty(), "A private proposal was readable by a bystander");

        String hash = data.records().agreements.get(UUID.fromString(proposal.recordId())).termsHash();
        check(politics.execute(one, req(politics, Action.SIGN, SERENUM, "", proposal.recordId(), null, "", "", hash)).success(), "First signature failed");
        var half = Outcomes.political("sign", proposal.recordId(), politics.recordState(one, proposal.recordId()));
        check(half.state() == Outcome.State.PENDING && key(half).endsWith("politics.signature_waiting"), "One signature is not in effect: " + half.state() + " " + key(half));
        check(politics.execute(two, req(politics, Action.SIGN, LUNARI, "", proposal.recordId(), null, "", "", hash)).success(), "Counterpart signature failed");
        var signed = Outcomes.political("sign", proposal.recordId(), politics.recordState(two, proposal.recordId()));
        check(signed.state() == Outcome.State.APPLIED && key(signed).endsWith("politics.agreement_active"), "A signed agreement is in effect: " + signed.state());
        // Re-reading the proposer's record later (Check) now reports it in effect, without repeating anything.
        long revision = politics.revision();
        var rechecked = Outcomes.political("propose", proposal.recordId(), politics.recordState(one, proposal.recordId()));
        check(rechecked.state() == Outcome.State.APPLIED && politics.revision() == revision, "Check changed state or misread the agreement");

        Result petition = politics.execute(stranger, req(politics, Action.PETITION, SERENUM, "mcakingdoms:honor_petition", a.id().toString(), stranger, "", "Service nomination", ""));
        check(petition.success(), petition.message());
        var waiting = Outcomes.political("petition", petition.recordId(), politics.recordState(stranger, petition.recordId()));
        check(waiting.state() == Outcome.State.PENDING, "A submitted petition is waiting for review, got " + waiting.state());
        check(politics.recordState(two, petition.recordId()).isEmpty(), "A private petition was readable by another government's leader");
        check(politics.execute(one, req(politics, Action.APPROVE, SERENUM, "", petition.recordId(), null, "", "Recognized civic service", "")).success(), "Approval failed");
        var approved = Outcomes.political("petition", petition.recordId(), politics.recordState(stranger, petition.recordId()));
        check(approved.state() == Outcome.State.APPLIED, "An approved petition is in effect, got " + approved.state());
        helper.succeed();
    }
}
