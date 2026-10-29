package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.Politics.Action;

import java.util.Set;

/**
 * Who governs a kingdom's crown (C2). {@link Mode#NATIVE} is MCA: Kingdoms' own government; {@link Mode#CAPITALS} is a
 * kingdom whose crown MCA Capitals governs; {@link Mode#UNMAPPED} is a kingdom touched by a Capitals capital that is not
 * yet mapped or is ambiguous; {@link Mode#UNSUPPORTED} is every kingdom when the installed Capitals cannot be read.
 * Outside {@code NATIVE} only crown actions are refused; civic offices, agreements, petitions, institutions and honors
 * keep working, so there is never a second writer of the crown and never a silent loss of the civic layer.
 */
public record Sovereignty(Mode mode, String statusKey) {
    public enum Mode { NATIVE, CAPITALS, UNMAPPED, UNSUPPORTED }

    public static final Sovereignty NATIVE = new Sovereignty(Mode.NATIVE, "politics.mcakingdoms.sovereignty.native");

    /** Actions that found, seat, crown, succeed, house or elect a ruler: the crown's own processes. */
    private static final Set<Action> CROWN = Set.of(Action.BOOTSTRAP, Action.SEAT, Action.ABDICATE, Action.NAME_SUCCESSOR, Action.SUCCEED,
            Action.HOUSE, Action.ADOPT_TRANSITION_RULE, Action.OPEN_ELECTION, Action.CAST_BALLOT, Action.CLOSE_ELECTION,
            Action.APPOINT_REGENT, Action.END_REGENCY);

    public boolean native_() {
        return mode == Mode.NATIVE;
    }

    /** True when {@code action} on {@code office} is the crown's: the actions above, and appointing or removing the leader. */
    public static boolean crown(Action action, String office) {
        if (CROWN.contains(action)) return true;
        // Without a named office the action is a generic one (some office); only the leader's office is the crown's.
        return (action == Action.APPOINT || action == Action.REMOVE_OFFICE) && GovernmentActionPolicy.LEADER.equals(office);
    }

    /** The refusal for a crown action of a kingdom this mod does not govern. */
    public String refusal() {
        return switch (mode) {
            case NATIVE -> "";
            case CAPITALS -> "politics.mcakingdoms.refusal.capitals_managed";
            case UNMAPPED -> "politics.mcakingdoms.refusal.capitals_unmapped";
            case UNSUPPORTED -> "politics.mcakingdoms.refusal.capitals_unsupported";
        };
    }
}
