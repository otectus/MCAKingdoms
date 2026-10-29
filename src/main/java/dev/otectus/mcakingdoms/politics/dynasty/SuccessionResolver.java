package dev.otectus.mcakingdoms.politics.dynasty;

import java.util.*;
import java.util.function.Predicate;

/**
 * Decides an heir under a law (A2), purely: the same family, law, decisions and renunciations always give the same
 * answer, and every candidate carries the reason it is or is not eligible. Ties are broken by stable person id because
 * MCA records no birth order; nothing is random. Death is only what a source recorded; an unknown person or stage
 * blocks for review instead of being guessed, so the answer never depends on who happens to be loaded.
 */
public final class SuccessionResolver {
    /** The visit bound: at most this many relatives are considered. */
    static final int LIMIT = 64;

    /** One person considered: how they relate, their verified stage, and a bundled key saying why they are or are not eligible. */
    public record Candidate(UUID id, Lineage.Relation relation, Lineage.Stage stage, boolean eligible, String reasonKey, String name) {
        public Candidate { Objects.requireNonNull(id); Objects.requireNonNull(relation); Objects.requireNonNull(stage); name = name == null ? "" : name; }
    }

    /**
     * What the law decides. {@code HEIR} has an adult heir to seat; {@code MINOR_HEIR} an heir to keep under a regency
     * until adulthood; {@code CONFIRMATION} an heir the council must confirm; {@code ELECTION} leaves it to the election;
     * {@code MANUAL} leaves it to the officials; {@code BLOCKED} cannot decide from the evidence; {@code NO_HEIR} found
     * nobody eligible.
     */
    public enum Outcome { HEIR, MINOR_HEIR, CONFIRMATION, ELECTION, MANUAL, BLOCKED, NO_HEIR }

    public record Resolution(Outcome outcome, Optional<Candidate> heir, List<Candidate> candidates, String reasonKey) {
        public Resolution { candidates = List.copyOf(candidates); }
    }

    private SuccessionResolver() {
    }

    /**
     * @param ruler        the ruler whose office fell vacant
     * @param named        people placed ahead of lineage, in order: an elected successor, an upheld claimant, the named
     *                     successor (the caller includes the named successor only when the law puts them first)
     * @param recognized   people the crown recognized as members of the ruling house
     * @param renounced    people who renounced their place
     * @param eligibleKind whether a person may hold the office at all (a known player, or an NPC resident in the kingdom)
     */
    public static Resolution resolve(SuccessionLaw law, Lineage.Source family, UUID ruler, List<Map.Entry<UUID, Lineage.Relation>> named,
                                     Set<UUID> recognized, Set<UUID> renounced, Predicate<UUID> eligibleKind) {
        if (law.mode() == SuccessionLaw.Mode.MANUAL_ONLY) return new Resolution(Outcome.MANUAL, Optional.empty(), List.of(), "dynasty.mcakingdoms.outcome.manual");
        if (law.mode() == SuccessionLaw.Mode.ELECTIVE_MONARCHY) return new Resolution(Outcome.ELECTION, Optional.empty(), List.of(), "dynasty.mcakingdoms.outcome.election");
        var candidates = new ArrayList<Candidate>();
        var seen = new HashSet<UUID>();
        if (ruler != null) seen.add(ruler);
        for (var entry : named) if (entry.getKey() != null && seen.add(entry.getKey()))
            candidates.add(candidate(family, entry.getKey(), entry.getValue(), law, renounced, eligibleKind));
        boolean rulerKnown = ruler != null && family.node(ruler).isPresent();
        if (!rulerKnown && candidates.stream().noneMatch(Candidate::eligible))
            return new Resolution(Outcome.BLOCKED, Optional.empty(), candidates, "dynasty.mcakingdoms.outcome.no_family_record");
        if (rulerKnown) for (var relative : Lineage.relatives(family, ruler, LIMIT)) {
            if (!law.relations().contains(relative.getValue()) || !seen.add(relative.getKey())) continue;
            candidates.add(candidate(family, relative.getKey(), relative.getValue(), law, renounced, eligibleKind));
        }
        if (law.relations().contains(Lineage.Relation.RECOGNIZED))
            for (UUID member : new TreeSet<>(recognized)) if (candidates.size() < LIMIT && seen.add(member))
                candidates.add(candidate(family, member, Lineage.Relation.RECOGNIZED, law, renounced, eligibleKind));
        for (var candidate : candidates) {
            if (candidate.eligible()) {
                if (candidate.stage() == Lineage.Stage.MINOR)
                    return new Resolution(law.minorRegency() ? Outcome.MINOR_HEIR : Outcome.BLOCKED, Optional.of(candidate), candidates,
                            law.minorRegency() ? "dynasty.mcakingdoms.outcome.minor_regency" : "dynasty.mcakingdoms.outcome.minor_no_regency");
                // An elected successor is the electorate's choice: no council confirms it again.
                boolean council = law.confirmation() == SuccessionLaw.Confirmation.COUNCIL && candidate.relation() != Lineage.Relation.ELECTED;
                return new Resolution(council ? Outcome.CONFIRMATION : Outcome.HEIR, Optional.of(candidate), candidates,
                        council ? "dynasty.mcakingdoms.outcome.council" : candidate.relation() == Lineage.Relation.ELECTED ? "dynasty.mcakingdoms.outcome.elected" : "dynasty.mcakingdoms.outcome.heir");
            }
            // An heir whose stage or life cannot be verified is never skipped silently: the order would depend on what happens to be loaded.
            if (candidate.reasonKey().equals("dynasty.mcakingdoms.candidate.stage_unknown") || candidate.reasonKey().equals("dynasty.mcakingdoms.candidate.unknown_person"))
                return new Resolution(Outcome.BLOCKED, Optional.of(candidate), candidates, "dynasty.mcakingdoms.outcome.unverified");
        }
        return new Resolution(Outcome.NO_HEIR, Optional.empty(), candidates, "dynasty.mcakingdoms.outcome.no_heir");
    }

    private static Candidate candidate(Lineage.Source family, UUID id, Lineage.Relation relation, SuccessionLaw law, Set<UUID> renounced, Predicate<UUID> eligibleKind) {
        var node = family.node(id);
        if (node.isEmpty()) return new Candidate(id, relation, Lineage.Stage.UNKNOWN, false, "dynasty.mcakingdoms.candidate.unknown_person", "");
        var n = node.get();
        if (n.deceased()) return new Candidate(id, relation, n.stage(), false, "dynasty.mcakingdoms.candidate.deceased", n.name());
        if (renounced.contains(id)) return new Candidate(id, relation, n.stage(), false, "dynasty.mcakingdoms.candidate.renounced", n.name());
        if (n.player() ? !law.playersEligible() : !law.npcsEligible()) return new Candidate(id, relation, n.stage(), false, "dynasty.mcakingdoms.candidate.kind_excluded", n.name());
        if (n.stage() == Lineage.Stage.UNKNOWN) return new Candidate(id, relation, n.stage(), false, "dynasty.mcakingdoms.candidate.stage_unknown", n.name());
        if (!eligibleKind.test(id)) return new Candidate(id, relation, n.stage(), false, "dynasty.mcakingdoms.candidate.not_eligible", n.name());
        return new Candidate(id, relation, n.stage(), true, n.stage() == Lineage.Stage.MINOR ? "dynasty.mcakingdoms.candidate.minor" : "dynasty.mcakingdoms.candidate.eligible", n.name());
    }
}
