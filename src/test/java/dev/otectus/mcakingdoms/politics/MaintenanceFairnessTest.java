package dev.otectus.mcakingdoms.politics;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F05: political deadline maintenance gives each domain its own bounded batch, oldest deadline first. The shapes from
 * the review — 16 timed records beside 8 elections, and 17/9/3 — used to starve half of the timed records under one
 * shared cursor; here every due record transitions exactly once within ceil(d / 8) passes.
 */
class MaintenanceFairnessTest {
    /** A record with a deadline; {@code done} flips when maintenance transitions it, which makes it no longer due. */
    private static final class Timed {
        final UUID id = UUID.randomUUID();
        final long deadline;
        int transitions;
        Timed(long deadline) { this.deadline = deadline; }
        boolean due(long now) { return transitions == 0 && now >= deadline; }
    }

    private static List<Timed> domain(int size, long firstDeadline) {
        List<Timed> records = new ArrayList<>();
        for (int i = 0; i < size; i++) records.add(new Timed(firstDeadline + (i % 3))); // shared deadlines exercise the id tie-break
        return records;
    }

    /** Runs maintenance passes over independent domains until nothing is due; returns the passes taken. */
    private static int run(long now, List<List<Timed>> domains, int maxPasses) {
        for (int pass = 1; pass <= maxPasses; pass++) {
            boolean any = false;
            for (List<Timed> domain : domains) {
                for (Timed record : GovernmentService.due(domain, r -> r.due(now), r -> r.deadline, r -> r.id)) {
                    record.transitions++;
                    any = true;
                }
            }
            if (!any) return pass - 1;
        }
        return maxPasses;
    }

    private static void assertAllOnce(List<List<Timed>> domains) {
        for (List<Timed> domain : domains)
            for (Timed record : domain) assertEquals(1, record.transitions, "every due record transitions exactly once");
    }

    @Test
    void sixteenTimedBesideEightElectionsAllExpire() {
        var timed = domain(16, 100);
        var elections = domain(8, 100);
        var domains = List.of(timed, elections, new ArrayList<Timed>());
        int passes = run(1_000, domains, 100);
        assertAllOnce(domains);
        assertEquals(2, passes, "ceil(16 / 8) passes; the elections do not slow the timed records");
    }

    @Test
    void seventeenNineAndThreeAllExpireWithinTheirOwnBound() {
        var timed = domain(17, 50);
        var elections = domain(9, 50);
        var regencies = domain(3, 50);
        var domains = List.of(timed, elections, regencies);
        int passes = run(1_000, domains, 100);
        assertAllOnce(domains);
        assertEquals(3, passes, "ceil(17 / 8) = 3 for the largest domain");
    }

    @Test
    void zeroRecordsDoNothing() {
        assertEquals(0, run(1_000, List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()), 5));
        assertTrue(GovernmentService.due(List.<Timed>of(), r -> true, r -> r.deadline, r -> r.id).isEmpty());
    }

    @Test
    void recordsNotYetDueAreNeverTouched() {
        var future = domain(5, 10_000);
        run(1_000, List.of(future), 10);
        future.forEach(record -> assertEquals(0, record.transitions));
    }

    @Test
    void aGrowingDomainCannotStarveRecordsAlreadyDue() {
        long now = 1_000;
        var timed = domain(20, 10);
        Timed oldest = timed.get(19);
        for (int pass = 1; pass <= 3; pass++) {
            // Each pass, more records come due, all with later deadlines than those already waiting.
            for (int i = 0; i < 8; i++) timed.add(new Timed(500 + pass));
            for (Timed record : GovernmentService.due(timed, r -> r.due(now), r -> r.deadline, r -> r.id)) record.transitions++;
        }
        assertEquals(1, oldest.transitions, "the first 20 due records finish within ceil(20 / 8) = 3 passes despite arrivals");
        for (int i = 0; i < 20; i++) assertEquals(1, timed.get(i).transitions);
    }

    @Test
    void aShrinkingDomainKeepsTheRestOnSchedule() {
        long now = 1_000;
        var timed = domain(24, 10);
        var withdrawn = new ArrayList<>(timed.subList(0, 6));
        timed.removeAll(withdrawn); // withdrawn by their parties before maintenance reached them
        int passes = run(now, List.of(timed), 10);
        assertEquals(3, passes, "ceil(18 / 8) = 3");
        timed.forEach(record -> assertEquals(1, record.transitions));
        withdrawn.forEach(record -> assertEquals(0, record.transitions));
    }

    @Test
    void theBatchIsBoundedAndOrderedByDeadlineThenId() {
        var records = domain(30, 0);
        var batch = GovernmentService.due(records, r -> true, r -> r.deadline, r -> r.id);
        assertEquals(GovernmentService.MAINTENANCE_BUDGET, batch.size());
        for (int i = 1; i < batch.size(); i++) {
            Timed a = batch.get(i - 1), b = batch.get(i);
            assertTrue(a.deadline < b.deadline || a.deadline == b.deadline && a.id.compareTo(b.id) < 0, "ordered by deadline, then id");
        }
    }
}
