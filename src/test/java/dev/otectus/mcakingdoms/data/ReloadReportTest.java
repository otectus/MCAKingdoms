package dev.otectus.mcakingdoms.data;

import dev.otectus.mcakingdoms.data.ReloadReport.Domain;
import dev.otectus.mcakingdoms.data.ReloadReport.State;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** A reload is described from its own attempt: accepted, partially rejected or failed, never inferred from completion. */
class ReloadReportTest {
    @BeforeEach
    void freshServer() {
        ReloadReport.reset();
    }

    private static String text(ReloadReport.Attempt attempt) {
        return ReloadReport.describe(Optional.of(attempt), null).stream().map(Component::getString).collect(Collectors.joining("\n"));
    }

    private static void loadEverything() {
        ReloadReport.begin();
        for (Domain domain : Domain.values()) ReloadReport.accepted(domain, Map.of("kingdoms", 6));
        ReloadReport.committed();
    }

    @Test
    void acceptedReloadReportsItsDomainCounts() {
        var attempt = ReloadReport.begin();
        ReloadReport.accepted(Domain.CORE, Map.of("kingdoms", 6, "name_pools", 6));
        ReloadReport.accepted(Domain.CIVIC, Map.of("civic_rule_sets", 1));
        assertEquals(State.IN_PROGRESS, attempt.state(), "Nothing is accepted before the definitions commit");
        ReloadReport.committed();
        assertEquals(State.ACCEPTED, attempt.state());
        String text = text(attempt);
        assertTrue(text.startsWith("Reload complete."), text);
        assertTrue(text.contains("6 kingdoms") && text.contains("1 civic rule sets"), text);
    }

    @Test
    void domainRejectionIsPartialAndALaterValidReloadClearsIt() {
        loadEverything();
        var rejected = ReloadReport.begin();
        ReloadReport.accepted(Domain.CORE, Map.of("kingdoms", 6));
        ReloadReport.rejected(Domain.CIVIC, List.of("data/mcakingdoms/mcakingdoms_factions/civic_network/x.json: unsupported schema 2"));
        ReloadReport.rejected(Domain.WORLD_CONTEXT, List.of("data/mcakingdoms/warfare/world/y.json: structures must be an array"));
        ReloadReport.committed();
        assertEquals(State.PARTIAL, rejected.state());
        String text = text(rejected);
        assertTrue(text.contains("Reload completed with rejected definitions; previous definitions remain active for the affected domains."), text);
        assertTrue(text.contains("civic_network/x.json: unsupported schema 2") && text.contains("structures must be an array"), text);
        assertTrue(text.contains("Civic network rules: the previous definitions remain active."), text);

        var fixed = ReloadReport.begin();
        for (Domain domain : Domain.values()) ReloadReport.accepted(domain, Map.of("kingdoms", 6));
        ReloadReport.committed();
        assertEquals(State.ACCEPTED, fixed.state());
        assertFalse(text(fixed).contains("rejected"), "A valid reload must not repeat the previous attempt's rejection");
        assertSame(fixed, ReloadReport.latest().orElseThrow());
    }

    @Test
    void fatalRejectionFailsTheWholeReloadAndChangesNothing() {
        loadEverything();
        var attempt = ReloadReport.begin();
        ReloadReport.accepted(Domain.POLITICS, Map.of("political_definitions", 35));
        ReloadReport.rejected(Domain.CORE, List.of("Invalid MCA: Kingdoms definition mcakingdoms:mcakingdoms/kingdoms/serenum.json: unsupported schema 2"));
        // The reload future fails, so no datapack sync commits it.
        ReloadReport.failed(attempt, new java.util.concurrent.CompletionException(new IllegalArgumentException("unsupported schema 2")));
        assertEquals(State.FAILED, attempt.state());
        String text = text(attempt);
        assertTrue(text.startsWith("Reload failed. No definitions changed; the definitions that were active before remain in use."), text);
        assertTrue(text.contains("kingdoms/serenum.json: unsupported schema 2"), text);
        assertFalse(text.contains("35 political definitions"), "A failed reload must not list definitions as in use");
    }

    @Test
    void initialLoadDoesNotClaimThatEarlierDefinitionsWereKept() {
        var attempt = ReloadReport.begin();
        assertTrue(attempt.initial());
        ReloadReport.accepted(Domain.CORE, Map.of("kingdoms", 6));
        ReloadReport.rejected(Domain.CIVIC, List.of("data/mcakingdoms/mcakingdoms_factions/civic_network/x.json: unknown field z"));
        ReloadReport.committed();
        String text = text(attempt);
        assertTrue(text.contains("the affected domains have no definitions until a valid reload"), text);
        assertTrue(text.contains("Civic network rules: no earlier definitions were active"), text);
        assertFalse(text.contains("previous definitions remain active"), text);

        // Civic still has nothing committed, so a second rejection cannot claim to keep earlier civic rules either.
        var again = ReloadReport.begin();
        ReloadReport.rejected(Domain.CIVIC, List.of("still invalid"));
        ReloadReport.committed();
        assertTrue(text(again).contains("Civic network rules: no earlier definitions were active"), text(again));
    }

    @Test
    void anAttemptThatNeverCommittedIsReportedAsFailedOnceTheNextBegins() {
        loadEverything();
        var lost = ReloadReport.begin();
        ReloadReport.accepted(Domain.CORE, Map.of("kingdoms", 6));
        var next = ReloadReport.begin();
        assertEquals(State.FAILED, lost.state());
        assertTrue(text(lost).contains("did not finish"), text(lost));
        assertEquals(State.IN_PROGRESS, next.state());
        assertTrue(ReloadReport.since(lost.id()).filter(found -> found == next).isPresent());
        assertTrue(ReloadReport.since(next.id()).isEmpty());
    }

    @Test
    void playerFacingProblemsAreBounded() {
        loadEverything();
        var attempt = ReloadReport.begin();
        ReloadReport.rejected(Domain.CIVIC, IntStream.range(0, 9).mapToObj(i -> "problem " + i + " " + "x".repeat(2000)).toList());
        ReloadReport.committed();
        var lines = ReloadReport.describe(Optional.of(attempt), null).stream().map(Component::getString).toList();
        assertTrue(lines.stream().allMatch(line -> line.length() <= ReloadReport.PROBLEM_LENGTH + 80), "A problem line exceeded its bound");
        assertEquals(ReloadReport.SHOWN_PROBLEMS, lines.stream().filter(line -> line.contains("problem ")).count());
        assertTrue(lines.stream().anyMatch(line -> line.contains("6 more problem(s) are in the server log")), lines.toString());
    }

    @Test
    void reloadThatNeverReadTheseDefinitionsIsNotCalledSuccessful() {
        String unobserved = ReloadReport.describe(Optional.empty(), null).get(0).getString();
        assertTrue(unobserved.contains("without reading MCA: Kingdoms definitions"), unobserved);
        assertEquals(State.IN_PROGRESS, new DefinitionReloads.Result(Optional.empty(), null).state());
        assertEquals(State.FAILED, new DefinitionReloads.Result(Optional.empty(), new IllegalStateException("pack missing")).state());
    }
}
