package dev.otectus.mcakingdoms.core;

import dev.otectus.mcakingdoms.api.SettlementView;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every settlement snapshot in page order (slug, then id), built once per store revision (F13). Every record change
 * goes through {@link SettlementSavedData#changed}, which moves the revision, so a page never sees a stale snapshot,
 * and paging through N settlements costs one sort instead of one per page. Snapshots are immutable records; the list is
 * shared read-only on the server thread. Viewer filtering happens after, so nothing here depends on who asks.
 */
final class OrderedSettlements {
    static final Comparator<SettlementView> ORDER = Comparator.comparing((SettlementView settlement) -> settlement.slug().toString())
            .thenComparing(SettlementView::id);

    private List<SettlementView> ordered = List.of();
    private long revision = -1;
    private int sorts;

    List<SettlementView> all(SettlementSavedData data) {
        if (data.revision() != revision) {
            ordered = data.records().stream().map(record -> (SettlementView) record.snapshot()).sorted(ORDER).toList();
            revision = data.revision();
            sorts++;
        }
        return ordered;
    }

    List<SettlementView> of(SettlementSavedData data, Optional<ResourceLocation> kingdom) {
        var all = all(data);
        return kingdom.isEmpty() ? all : all.stream().filter(settlement -> settlement.kingdomId().equals(kingdom.get())).toList();
    }

    List<SettlementView> page(SettlementSavedData data, Optional<ResourceLocation> kingdom, int offset, int limit) {
        if (kingdom.isEmpty()) {
            var all = all(data);
            int from = Math.min(offset, all.size());
            return all.subList(from, Math.min(all.size(), from + limit));
        }
        return of(data, kingdom).stream().skip(offset).limit(limit).toList();
    }

    /** How many times the order was rebuilt; for tests and the profile record. */
    int sorts() {
        return sorts;
    }
}
