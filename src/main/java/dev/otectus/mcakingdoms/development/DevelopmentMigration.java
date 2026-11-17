package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;

import java.util.*;

/**
 * Development records following settlement identity (AUD-06): a merge moves every work, open or finished, every asset,
 * the economy and trade history from the retired settlement to the survivor; a reassignment leaves records where they
 * are but stops construction until the new kingdom's officials review it. Pure over a {@link Records} transaction and
 * idempotent: run again (after a crash, at the next start, or from {@code /kingdoms economy reconcile}) it finds nothing
 * left to move and changes nothing.
 *
 * <p>Aggregation: points and roles add up; abstract goods add up to the ledger cap and anything above it is kept in the
 * survivor's overflow rather than truncated; the later production day wins. Consent is conservative: the merged
 * settlement allows construction only if both did, since the retired settlement's land never agreed to the survivor's
 * construction. Physical works the retired settlement brought along are paused for the survivor's officials to review.
 */
final class DevelopmentMigration {
    private static final String P = DevelopmentService.P;

    private DevelopmentMigration() {
    }

    /** Whether any record still names {@code source}. */
    static boolean mentions(Records r, UUID source) {
        return r.economies.containsKey(source) || r.projects.values().stream().anyMatch(p -> p.settlement().equals(source))
                || r.assets.values().stream().anyMatch(a -> a.settlement().equals(source))
                || r.trades.stream().anyMatch(t -> t.from().equals(source) || t.to().equals(source));
    }

    /** Moves everything of {@code source} to {@code target}; returns whether anything changed. {@code sourceName} explains paused works. */
    static boolean merge(Records next, UUID source, UUID target, String sourceName, long now) {
        if (source.equals(target) || !mentions(next, source)) return false;
        for (var p : List.copyOf(next.projects.values())) {
            if (!p.settlement().equals(source)) continue;
            var moved = p.movedTo(target, next.revision);
            if (moved.construction() && moved.state().open() && moved.state() != State.PAUSED && moved.state() != State.ROLLING_BACK) {
                var blocker = new Blocker(Cause.INHERITED, null, sourceName);
                moved = moved.blocked(State.PAUSED, DevelopmentText.blocker(blocker), List.of(blocker), now, next.revision);
            }
            next.projects.put(p.id(), moved);
        }
        for (var a : List.copyOf(next.assets.values())) if (a.settlement().equals(source)) next.assets.put(a.work(), a.movedTo(target));
        Economy from = next.economies.remove(source);
        if (from != null) {
            Economy into = next.economies.get(target);
            next.economies.put(target, combine(into == null ? Economy.empty(target) : into, from, into != null));
        } else if (next.economies.containsKey(target) && next.economies.get(target).optedIn()) {
            // The retired settlement never agreed to construction: the merged land does not either until an official says so.
            next.economies.put(target, next.economies.get(target).withConsent(false));
        }
        for (int i = 0; i < next.trades.size(); i++) {
            var t = next.trades.get(i);
            if (t.from().equals(source) || t.to().equals(source))
                next.trades.set(i, new Trade(t.from().equals(source) ? target : t.from(), t.to().equals(source) ? target : t.to(), t.commodity(), t.amount(), t.at()));
        }
        return true;
    }

    static Economy combine(Economy into, Economy from, boolean intoExisted) {
        long points = (long) into.points() + from.points();
        var roles = new TreeMap<>(into.roles());
        from.roles().forEach((role, count) -> roles.merge(role, count, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b)));
        var goods = new TreeMap<String, Long>();
        var overflow = new TreeMap<>(into.overflow());
        from.overflow().forEach((k, v) -> overflow.merge(k, v, Long::sum));
        var all = new TreeSet<String>(); all.addAll(into.abstractGoods().keySet()); all.addAll(from.abstractGoods().keySet());
        for (String commodity : all) {
            long total = into.abstractGoods().getOrDefault(commodity, 0L) + from.abstractGoods().getOrDefault(commodity, 0L);
            goods.put(commodity, Math.min(total, Development.ABSTRACT_CAP));
            if (total > Development.ABSTRACT_CAP) overflow.merge(commodity, total - Development.ABSTRACT_CAP, Long::sum);
        }
        goods.values().removeIf(v -> v == 0);
        boolean consent = into.optedIn() && from.optedIn() && intoExisted;
        return new Economy(into.settlement(), (int) Math.min(Integer.MAX_VALUE, points), roles, consent, goods, Math.max(into.lastProduced(), from.lastProduced()), overflow);
    }

    /**
     * A settlement moved to another kingdom (AUD-06): its construction stops before the next block until an official of
     * the new kingdom reviews and resumes it; funded and approved works wait for that review too. Returns whether anything changed.
     */
    static boolean reassigned(Records next, UUID settlement, String newKingdomName, long now) {
        boolean changed = false;
        for (var p : List.copyOf(next.projects.values())) {
            if (!p.settlement().equals(settlement) || !p.construction() || !p.state().open() || p.state() == State.PAUSED || p.state() == State.ROLLING_BACK) continue;
            if (p.state() != State.READY && p.state() != State.BUILDING && p.state() != State.FUNDED && p.state() != State.APPROVED) continue;
            var blocker = new Blocker(Cause.KINGDOM_CHANGED, null, newKingdomName);
            next.projects.put(p.id(), p.blocked(State.PAUSED, DevelopmentText.blocker(blocker), List.of(blocker), now, next.revision));
            changed = true;
        }
        return changed;
    }
}
