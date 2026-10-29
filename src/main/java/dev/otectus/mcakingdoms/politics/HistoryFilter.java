package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.PoliticalFact;

import java.util.Optional;

/**
 * A history page filter (H10), carried in the page's tab name so the request format is unchanged:
 * {@code history:<group>:<days>:<visibility>}. Group is {@code government}, {@code succession}, {@code agreements},
 * {@code petitions}, {@code institutions} or empty for all; days is 0 (any time), 7 or 30; visibility is {@code public}
 * or {@code all} (everything the viewer may already see). It narrows the authorized facts and never widens them.
 */
public record HistoryFilter(String group, int days, boolean publicOnly) {
    public static final java.util.List<String> GROUPS = java.util.List.of("", "government", "succession", "agreements", "petitions", "institutions");
    public static final java.util.List<Integer> DAYS = java.util.List.of(0, 7, 30);

    /** The filter a tab names, or empty for any other tab (including plain {@code history}). */
    public static Optional<HistoryFilter> parse(String tab) {
        if (tab == null || !tab.startsWith("history:")) return Optional.empty();
        String[] parts = tab.split(":", -1);
        if (parts.length != 4 || !GROUPS.contains(parts[1]) || !parts[3].equals("public") && !parts[3].equals("all")) return Optional.empty();
        int days;
        try { days = Integer.parseInt(parts[2]); } catch (NumberFormatException invalid) { return Optional.empty(); }
        if (!DAYS.contains(days)) return Optional.empty();
        return Optional.of(new HistoryFilter(parts[1], days, parts[3].equals("public")));
    }

    public String tab() { return "history:" + group + ":" + days + ":" + (publicOnly ? "public" : "all"); }

    public boolean unfiltered() { return group.isEmpty() && days == 0 && !publicOnly; }

    boolean accepts(PoliticalFact fact, long now) {
        if (!group.isEmpty() && !group.equals(group(fact.type()))) return false;
        if (days > 0 && fact.gameTime() < now - days * 24_000L) return false;
        return !publicOnly || fact.visibility() == PoliticalFact.Visibility.PUBLIC;
    }

    public static String group(PoliticalFact.Type type) {
        return switch (type) {
            case GOVERNMENT_FOUNDED, CAPITAL_MOVED, OFFICE_APPOINTED, OFFICE_REMOVED, MANDATE_GRANTED, MANDATE_REVOKED, NPC_OFFICE_VACATED, HOUSE_UPDATED -> "government";
            case LEADER_ABDICATED, SUCCESSOR_NAMED, SUCCESSION_CONFIRMED, TRANSITION_RULE_ADOPTED, ELECTION_OPENED, BALLOT_CAST, ELECTION_GRACE, ELECTION_RESOLVED,
                 ELECTION_EXPIRED, REGENCY_APPOINTED, REGENCY_ENDED, REGENCY_EXPIRED -> "succession";
            case AGREEMENT_PROPOSED, AGREEMENT_SIGNED, AGREEMENT_DECLINED, AGREEMENT_WITHDRAWN, AGREEMENT_TERMINATED, AGREEMENT_EXPIRED -> "agreements";
            case PETITION_SUBMITTED, PETITION_REVIEWED, PETITION_APPROVED, PETITION_REJECTED, PETITION_WITHDRAWN, PETITION_EXPIRED -> "petitions";
            case INSTITUTION_RECOGNIZED, INSTITUTION_REVALIDATED, INSTITUTION_SUSPENDED, HONOR_GRANTED, HONOR_REVOKED -> "institutions";
        };
    }
}
