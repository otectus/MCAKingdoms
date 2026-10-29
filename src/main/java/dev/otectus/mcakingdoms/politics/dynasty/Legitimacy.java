package dev.otectus.mcakingdoms.politics.dynasty;

import java.util.*;

/**
 * An explainable reading of a ruler's claim (A3): a few bounded facts, each with its configured weight, summed into a
 * level. It is recomputed from current records whenever it is read, so it always follows the facts, and it changes no
 * authority: a low reading never unseats anyone, and no rumour, opinion or world event enters it.
 */
public record Legitimacy(int score, Level level, List<Factor> factors) {
    public enum Level { STRONG, ESTABLISHED, CONTESTED }

    /** One counted fact: a bundled key, its English fallback, the weight it added (negative for contests) and a count for the wording. */
    public record Factor(String key, String english, int weight, int count) { }

    /** The facts read: how the ruler was seated, what the kingdom's records show around them. */
    public record Facts(boolean seatedByLaw, boolean lineage, boolean councilConfirmed, boolean elected, int agreementsInForce, int officials, int openContests) { }

    public Legitimacy {
        factors = List.copyOf(factors);
    }

    public static Legitimacy assess(Facts facts, DynastyConfig.Weights weights) {
        var factors = new ArrayList<Factor>();
        if (facts.elected()) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.elected", "Chosen by the government's election", weights.election(), 0));
        else if (facts.seatedByLaw()) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.law", "Seated under the kingdom's law of succession", weights.law(), 0));
        else factors.add(new Factor("dynasty.mcakingdoms.legitimacy.appointed", "Seated by the officials or as the founder", weights.appointment(), 0));
        if (facts.seatedByLaw() && facts.lineage()) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.lineage", "Heir by a family relation MCA records", weights.lineage(), 0));
        if (facts.councilConfirmed()) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.council", "Confirmed by the council", weights.council(), 0));
        if (facts.agreementsInForce() > 0) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.recognition", "Party to %s agreement(s) in force with other kingdoms", weights.recognition(), facts.agreementsInForce()));
        if (facts.officials() >= 2) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.governance", "Governs with %s other officials seated", weights.governance(), facts.officials()));
        int contests = Math.min(2, Math.max(0, facts.openContests()));
        if (contests > 0) factors.add(new Factor("dynasty.mcakingdoms.legitimacy.contested", "Contested by %s open claim(s)", -weights.contest() * contests, contests));
        int score = factors.stream().mapToInt(Factor::weight).sum();
        return new Legitimacy(score, score >= 4 ? Level.STRONG : score >= 2 ? Level.ESTABLISHED : Level.CONTESTED, factors);
    }
}
