package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.development.DevelopmentSavedData.Requirement;

import java.util.*;

/**
 * The pure accounting of development (D2), so conservation and caps are tested without a server: which offered items a
 * delivery takes (never more than a demand still needs, never a stack with extra data), the tier a settlement's completed
 * works earn, and the abstract ledger's production and trade, which are capped and never touch a real item.
 */
public final class Development {
    /** The most of one abstract commodity a settlement's ledger holds. */
    public static final long ABSTRACT_CAP = 10_000;
    private static final int[] TIERS = {1, 3, 6, 10, 15};

    private Development() {
    }

    public static int tier(int points) {
        int tier = 0;
        for (int threshold : TIERS) if (points >= threshold) tier++;
        return tier;
    }

    /** One stack a player offers: its inventory slot, item id, the item tags it carries, and how many. */
    public record Offer(int slot, String item, Set<String> tags, int count) { }
    /** Take {@code count} of {@code item} from {@code slot} for demand number {@code requirement}. */
    public record Take(int slot, String item, int count, int requirement) { }

    static boolean matches(String ingredient, Offer offer) {
        return ingredient.startsWith("#") ? offer.tags().contains(ingredient.substring(1)) : ingredient.equals(offer.item());
    }

    /** What a delivery takes: demands in order, slots in order, each up to what still remains. Deterministic. */
    public static List<Take> plan(List<Requirement> demand, List<Offer> offers) {
        var left = new int[offers.size()];
        for (int i = 0; i < offers.size(); i++) left[i] = Math.max(0, offers.get(i).count());
        var takes = new ArrayList<Take>();
        for (int r = 0; r < demand.size(); r++) {
            int need = demand.get(r).remaining();
            for (int i = 0; i < offers.size() && need > 0; i++) {
                if (left[i] == 0 || !matches(demand.get(r).ingredient(), offers.get(i))) continue;
                int take = Math.min(need, left[i]);
                takes.add(new Take(offers.get(i).slot(), offers.get(i).item(), take, r));
                left[i] -= take; need -= take;
            }
        }
        return takes;
    }

    /** The demands after {@code takes}: each delivered count rises by exactly what was taken for it, and never past the demand. */
    public static List<Requirement> apply(List<Requirement> demand, List<Take> takes) {
        var out = new ArrayList<Requirement>();
        for (int r = 0; r < demand.size(); r++) {
            final int index = r;
            int added = takes.stream().filter(t -> t.requirement() == index).mapToInt(Take::count).sum();
            var d = demand.get(r);
            if (d.delivered() + added > d.count()) throw new IllegalArgumentException("A delivery would exceed its demand");
            out.add(new Requirement(d.ingredient(), d.count(), d.delivered() + added));
        }
        return out;
    }

    /** One day of abstract production: each commodity rises by its output, capped. Never more than one day per call (no offline catch-up). */
    public static Map<String, Long> produce(Map<String, Long> goods, Map<String, Integer> output) {
        var out = new TreeMap<>(goods);
        output.forEach((commodity, amount) -> out.merge(commodity, (long) amount, (a, b) -> Math.min(ABSTRACT_CAP, a + b)));
        return out;
    }

    /** One abstract transfer between two ledgers. */
    public record Transfer(String commodity, boolean firstGives, long amount) { }

    /**
     * One day of abstract trade between two settlements: for each commodity, the richer ledger sends the poorer up to half
     * the difference, at most {@code cap}. The total of each commodity is unchanged (nothing is created or destroyed).
     */
    public static List<Transfer> trade(Map<String, Long> first, Map<String, Long> second, int cap) {
        var out = new ArrayList<Transfer>();
        if (cap <= 0) return out;
        var commodities = new TreeSet<String>(); commodities.addAll(first.keySet()); commodities.addAll(second.keySet());
        for (String commodity : commodities) {
            long a = first.getOrDefault(commodity, 0L), b = second.getOrDefault(commodity, 0L);
            long amount = Math.min(cap, Math.abs(a - b) / 2);
            if (amount > 0) out.add(new Transfer(commodity, a > b, amount));
        }
        return out;
    }

    /** {@code ledger} after {@code transfers}, from the point of view of the first or second party. */
    public static Map<String, Long> after(Map<String, Long> ledger, List<Transfer> transfers, boolean first) {
        var out = new TreeMap<>(ledger);
        for (var t : transfers) out.merge(t.commodity(), (t.firstGives() == first ? -1 : 1) * t.amount(), Long::sum);
        out.values().removeIf(v -> v == 0);
        return out;
    }
}
