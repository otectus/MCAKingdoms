package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.api.DetectionSource;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.SettlementView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F13: paging through every settlement sorts once per store revision instead of once per page. Timings for 1k, 10k and
 * 25k settlements are printed to the test report (system-out) as the profile record; the assertions are on work done
 * (sorts) and on correctness, not on wall time.
 */
class OrderedSettlementsTest {
    private static SettlementRecord record(int index) {
        SettlementRecord r = new SettlementRecord();
        r.id = UUID.nameUUIDFromBytes(("settlement-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        r.dimension = TestKeys.overworld();
        r.anchor = new BlockPos(index * 64, 64, (index % 97) * 64);
        r.radius = 32;
        r.bounds = SettlementBounds.around(r.anchor, 32);
        r.kingdomId = new ResourceLocation("mcakingdoms", index % 3 == 0 ? "serenum" : "lunari");
        r.displayName = "Place " + index;
        r.slug = new ResourceLocation("mcakingdoms", "place_" + Integer.toString(index * 7919 % 100_003, 36));
        r.biomeAtCreation = new ResourceLocation("minecraft:plains");
        r.assignmentSource = AssignmentSource.EXACT_BIOME;
        r.detectionSource = DetectionSource.MANUAL;
        r.sourceId = new ResourceLocation("test:manual");
        r.sourceKey = "k" + index;
        return r;
    }

    private static SettlementSavedData store(int count) {
        SettlementSavedData data = new SettlementSavedData();
        for (int i = 0; i < count; i++) data.add(record(i));
        return data;
    }

    @Test
    void pagingThroughEverySettlementSortsOncePerRevision() {
        for (int count : new int[]{1_000, 10_000, 25_000}) {
            SettlementSavedData data = store(count);
            OrderedSettlements ordered = new OrderedSettlements();
            long start = System.nanoTime();
            List<SettlementView> seen = new ArrayList<>();
            for (int offset = 0; ; offset += 64) {
                var page = ordered.page(data, Optional.empty(), offset, 64);
                seen.addAll(page);
                if (page.size() < 64) break;
            }
            long paged = System.nanoTime() - start;
            assertEquals(count, seen.size(), "every settlement appears once");
            assertEquals(1, ordered.sorts(), count + " settlements: one sort for " + (count / 64 + 1) + " pages");
            for (int i = 1; i < seen.size(); i++)
                assertTrue(OrderedSettlements.ORDER.compare(seen.get(i - 1), seen.get(i)) < 0, "page order is the documented order");

            // The previous implementation re-sorted the whole collection for every page; measure it for the record.
            start = System.nanoTime();
            int pages = 0;
            for (int offset = 0; offset < Math.min(count, 64 * 40); offset += 64, pages++) {
                data.records().stream().map(r -> (SettlementView) r.snapshot()).sorted(OrderedSettlements.ORDER).skip(offset).limit(64).toList();
            }
            long legacy = (System.nanoTime() - start) / Math.max(1, pages) * (count / 64 + 1);
            System.out.printf("F13 profile: %d settlements, all %d pages: %.1f ms (one sort); previous per-page sorting: about %.1f ms%n",
                    count, count / 64 + 1, paged / 1e6, legacy / 1e6);

            // A change moves the revision and the order is rebuilt once more.
            SettlementRecord first = data.records().iterator().next();
            first.displayName = "Renamed";
            data.changed(first);
            ordered.page(data, Optional.empty(), 0, 64);
            ordered.page(data, Optional.empty(), 64, 64);
            assertEquals(2, ordered.sorts());
            assertTrue(ordered.all(data).stream().anyMatch(s -> s.displayName().equals("Renamed")), "the rebuilt order holds the change");
        }
    }

    @Test
    void kingdomFilteringKeepsThePageOrder() {
        SettlementSavedData data = store(500);
        OrderedSettlements ordered = new OrderedSettlements();
        var serenum = ordered.of(data, Optional.of(new ResourceLocation("mcakingdoms", "serenum")));
        assertEquals(167, serenum.size());
        assertTrue(serenum.stream().allMatch(s -> s.kingdomId().getPath().equals("serenum")));
        var page = ordered.page(data, Optional.of(new ResourceLocation("mcakingdoms", "serenum")), 64, 64);
        assertEquals(serenum.subList(64, 128), page);
        assertEquals(1, ordered.sorts());
    }
}
