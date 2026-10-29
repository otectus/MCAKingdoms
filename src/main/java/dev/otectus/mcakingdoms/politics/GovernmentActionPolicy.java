package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.Politics.*;

import java.util.*;
import java.util.function.Predicate;

/**
 * One rule set for whether a government action may proceed (H02). The Kingdom page, every Kingdom Tasks preview and the
 * mutation itself ask this evaluator, so a preview never offers what the mutation refuses: the elected leader's office,
 * for one, is protected whether or not other offices exist. It is pure. The service gathers the facts on the server
 * thread, and a refusal is a stable bundled key with no arguments. The mutation still checks what this does not cover
 * (the people, places and terms a request names) when it runs.
 */
public final class GovernmentActionPolicy {
    public static final String LEADER = "mcakingdoms:leader";
    private static final String KEY = "politics.mcakingdoms.refusal.";

    /** What an action names: an office key ({@code definition} or {@code definition@settlement}), or nothing in particular. */
    public record Target(String office) {
        public static final Target ANY = new Target(null);
        public static Target office(String key) { return new Target(Objects.requireNonNull(key)); }
        boolean any() { return office == null; }
    }

    /**
     * The facts one decision reads. {@code holds} answers whether the actor holds a kingdom-wide responsibility now
     * (office, mandate or active regency); {@code scopedRecognition} whether a settlement office lets them recognize
     * buildings there. {@code sovereignty} says who governs this kingdom's crown (C2): crown actions of a kingdom MCA
     * Capitals governs, or may govern, are refused here and stay with Capitals.
     */
    public record Facts(UUID actor, boolean operator, boolean writable, Sovereignty sovereignty, Government government,
                        boolean profileAvailable, UUID electedHolder, boolean regencyActive, Predicate<Permission> holds,
                        boolean scopedRecognition, boolean openFounding, boolean resident) {
        public Facts {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(holds);
            sovereignty = sovereignty == null ? Sovereignty.NATIVE : sovereignty;
        }
    }

    private GovernmentActionPolicy() {
    }

    /** The bundled key explaining why {@code action} cannot proceed for this actor and target, or empty when it may. */
    public static Optional<String> denial(Facts f, Action action, Target target) {
        if (!f.writable()) return key("records_read_only");
        if (!f.sovereignty().native_() && Sovereignty.crown(action, target.office())) return Optional.of(f.sovereignty().refusal());
        Government g = f.government();
        if (action == Action.BOOTSTRAP) {
            if (g != null) return key("government_exists");
            if (f.operator() || f.openFounding() && f.resident()) return Optional.empty();
            return key(f.openFounding() ? "founding_resident" : "founding_closed");
        }
        if (g == null) return key("no_government");
        if (!f.profileAvailable()) return key("dormant");
        Person self = new Person(f.actor(), Kind.PLAYER);
        switch (action) {
            case PETITION, WITHDRAW -> { return Optional.empty(); }
            case ABDICATE -> {
                Office leader = g.offices().get(LEADER);
                return leader != null && leader.holder().equals(self) ? Optional.empty() : key("abdicate_holder");
            }
            case SUCCEED -> {
                if (g.state() != State.INTERREGNUM || g.successor() == null) return key("no_succession");
                return f.operator() || g.successor().equals(self) ? Optional.empty() : key("succeed_actor");
            }
            case NAME_SUCCESSOR -> {
                if (elected(f, g.successor())) return key("elected_successor");
                if (f.regencyActive()) return key("successor_frozen");
            }
            case REMOVE_OFFICE -> {
                if (target.any()) {
                    if (g.offices().isEmpty()) return key("no_occupied_office");
                    if (g.offices().keySet().stream().allMatch(office -> protectedOffice(f, g, office))) return key("elected_leader");
                } else {
                    if (!g.offices().containsKey(target.office())) return key("office_vacant");
                    if (protectedOffice(f, g, target.office())) return key("elected_leader");
                }
            }
            case APPOINT -> {
                if (!target.any() && g.offices().containsKey(target.office())) return key("office_occupied");
                // A vacant leadership without a confirmed successor is filled by an operator only; other offices wait for
                // a leader or a regent holding the appointment responsibility.
                if (g.state() == State.INTERREGNUM && (target.any() || LEADER.equals(target.office()))) {
                    if (f.operator()) return Optional.empty();
                    if (!target.any()) return key("leader_operator");
                }
            }
            case DELEGATE -> {
                Office leader = g.offices().get(LEADER);
                if (f.holds().test(Permission.DELEGATE) && (leader == null || !leader.holder().equals(self))) return key("delegate_leader");
            }
            default -> { }
        }
        Permission permission = permission(action);
        if (f.holds().test(permission)) return Optional.empty();
        if (permission == Permission.RECOGNIZE && f.scopedRecognition() && g.state() == State.ACTIVE) return Optional.empty();
        return Optional.of(permissionKey(permission));
    }

    /** Throws the refusal for the mutation, with the same key a preview shows. */
    public static void require(Facts f, Action action, Target target) {
        denial(f, action, target).ifPresent(reason -> { throw new IllegalArgumentException(reason); });
    }

    /** The responsibility an action needs; actions decided above never reach this. */
    public static Permission permission(Action action) {
        return switch (action) {
            case SEAT -> Permission.SEAT;
            case APPOINT, REMOVE_OFFICE, NAME_SUCCESSOR, HOUSE, ADOPT_TRANSITION_RULE, OPEN_ELECTION, CLOSE_ELECTION, CAST_BALLOT,
                 APPOINT_REGENT, END_REGENCY -> Permission.APPOINT;
            case DELEGATE, REVOKE -> Permission.DELEGATE;
            case PROPOSE -> Permission.PROPOSE;
            case SIGN, DECLINE, TERMINATE -> Permission.RATIFY;
            case REVIEW, APPROVE, REJECT -> Permission.REVIEW;
            case RECOGNIZE, REVALIDATE, SUSPEND_RECOGNITION -> Permission.RECOGNIZE;
            case HONOR, REVOKE_HONOR -> Permission.HONOR;
            default -> throw new IllegalStateException("Unmapped political action " + action);
        };
    }

    public static String permissionKey(Permission permission) {
        return KEY + "requires." + permission.name().toLowerCase(Locale.ROOT);
    }

    /** True when removing this office would end a mandate only a new election or abdication may end. */
    static boolean protectedOffice(Facts f, Government g, String office) {
        Office held = g.offices().get(office);
        return LEADER.equals(office) && held != null && elected(f, held.holder());
    }

    private static boolean elected(Facts f, Person person) {
        return person != null && person.kind() == Kind.PLAYER && person.id().equals(f.electedHolder());
    }

    private static Optional<String> key(String reason) {
        return Optional.of(KEY + reason);
    }
}
