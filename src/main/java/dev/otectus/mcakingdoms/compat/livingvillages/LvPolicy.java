package dev.otectus.mcakingdoms.compat.livingvillages;

import java.util.*;
import java.util.function.Supplier;

/**
 * The bridge's decisions, as pure functions of the facts the services gather. Nothing here reads the world, a provider
 * or a config file, so each rule is tested on its own and the same facts always give the same answer.
 */
public final class LvPolicy {
    private LvPolicy() {
    }

    public enum Source { KINGDOMS, BRIDGE, PROVIDER }

    /** {@code code} is a stable key fragment; {@code source} says who refused, so players are told whether to ask an operator. */
    public record Decision(boolean allowed, String code, Source source) {
        public static final Decision ALLOW = new Decision(true, "allowed", Source.BRIDGE);
        public static Decision refuse(String code, Source source) { return new Decision(false, code, source); }
    }

    public enum PlayerTargets { PROVIDER_AND_KINGDOMS, PROVIDER_ONLY }
    public enum Capitals { ALLOW_UNLESS_PROTECTED, PROTECT_ALL }

    /** Server switches and policies that apply to every war decision. */
    public record Rules(boolean bridgeWritable, boolean warfareEnabled, boolean militaryEnabled, boolean aiWars,
                        PlayerTargets playerTargets, Capitals capitals, boolean protectKingdomCapitals, boolean requireDefenderOnline) {
    }

    /** One side's realm as the bridge knows it. {@code ownerOnline} matters only for a player-led realm. */
    public record Side(UUID realm, boolean playerLed, boolean ownerOnline) {
    }

    /**
     * A proposed or running attack. {@code initiatorPlayer} is true when a player asked for it (a /rvwar request or a
     * Living Villages screen), and then {@code initiatorCommands} says whether that player commands the attacking realm.
     */
    public record Attack(Side attacker, Side defender, boolean targetBound, boolean targetVirtual, boolean targetRealmCapital,
                         boolean targetKingdomCapital, boolean treatyInForce, boolean truce, boolean initiatorPlayer,
                         boolean initiatorCommands, boolean mandateRequired, boolean mandateHeld) {
    }

    /** Whether this attack may start, continue, change execution path or end in occupation. Checked at every one of those points. */
    public static Decision war(Rules rules, Attack attack) {
        if (!rules.bridgeWritable()) return Decision.refuse("store_read_only", Source.BRIDGE);
        if (!attack.targetBound()) return Decision.ALLOW; // outside integration scope: native behaviour stands
        if (!rules.warfareEnabled() || !rules.militaryEnabled()) return Decision.refuse("warfare_off", Source.KINGDOMS);
        if (attack.targetVirtual()) return Decision.refuse("virtual_capital", Source.PROVIDER);
        if (!attack.attacker().playerLed() && !attack.defender().playerLed() && !rules.aiWars())
            return Decision.refuse("ai_wars_off", Source.KINGDOMS);
        if (attack.treatyInForce()) return Decision.refuse("treaty", Source.BRIDGE);
        if (attack.truce()) return Decision.refuse("truce", Source.BRIDGE);
        if (attack.targetKingdomCapital() && rules.protectKingdomCapitals()) return Decision.refuse("kingdom_capital", Source.KINGDOMS);
        if (attack.targetRealmCapital() && rules.capitals() == Capitals.PROTECT_ALL) return Decision.refuse("realm_capital", Source.KINGDOMS);
        if (attack.defender().playerLed() && rules.playerTargets() == PlayerTargets.PROVIDER_AND_KINGDOMS
                && rules.requireDefenderOnline() && !attack.defender().ownerOnline())
            return Decision.refuse("defender_offline", Source.KINGDOMS);
        if (attack.initiatorPlayer() && !attack.initiatorCommands()) return Decision.refuse("not_commander", Source.BRIDGE);
        if (attack.mandateRequired() && !attack.mandateHeld()) return Decision.refuse("mandate", Source.KINGDOMS);
        return Decision.ALLOW;
    }

    // ---- treaties and relations -----------------------------------------------------------------------------------------

    /** Native relation values, as Living Villages reads them. */
    public static final int NEUTRAL = 0, ALLY = 1, ENEMY = 2;

    /**
     * Whether a native relation change between two parties may stand while {@code terms} are in force: peace and ceasefire
     * forbid enmity, an alliance requires alliance. With no terms in force, anything goes.
     */
    public static boolean permits(Collection<LvState.Term> terms, int proposed) {
        for (LvState.Term term : terms) {
            switch (term) {
                case PEACE, CEASEFIRE -> { if (proposed == ENEMY) return false; }
                case ALLIANCE -> { if (proposed != ALLY) return false; }
                case CEDE -> { }
            }
        }
        return true;
    }

    /** The relation the terms require the bridge to write, if any: an alliance needs ALLY, peace needs at least NEUTRAL. */
    public static OptionalInt required(Collection<LvState.Term> terms, int current) {
        if (terms.contains(LvState.Term.ALLIANCE)) return current == ALLY ? OptionalInt.empty() : OptionalInt.of(ALLY);
        if ((terms.contains(LvState.Term.PEACE) || terms.contains(LvState.Term.CEASEFIRE)) && current == ENEMY) return OptionalInt.of(NEUTRAL);
        return OptionalInt.empty();
    }

    /** What an AI realm knows when it considers a proposal. Opinion is Living Villages' own score of the proposer. */
    public record Proposal(LvState.Term term, boolean atWar, int opinion, double strengthRatio, int recentLosses,
                           boolean beneficiary, int allyThreshold, int enemyThreshold) {
    }

    /** {@code code} names the deciding factor; the reason shown to players is keyed from it. */
    public record Answer(boolean accept, String code) {
    }

    /**
     * An AI realm's answer, from bounded provider facts only. The same facts give the same answer, so a refused proposal is
     * not rerolled by asking again; asking again after the facts changed is a new decision.
     */
    public static Answer consider(Proposal p) {
        return switch (p.term()) {
            case PEACE, CEASEFIRE -> {
                if (!p.atWar()) yield p.opinion() > p.enemyThreshold() ? new Answer(true, "no_quarrel") : new Answer(false, "hostile");
                if (p.strengthRatio() >= 1.5 && p.opinion() < 0) yield new Answer(false, "winning");
                if (p.recentLosses() > 0) yield new Answer(true, "losses");
                if (p.strengthRatio() < 0.8) yield new Answer(true, "outmatched");
                yield p.opinion() >= 0 ? new Answer(true, "opinion") : new Answer(false, "hostile");
            }
            case ALLIANCE -> p.atWar() ? new Answer(false, "at_war")
                    : p.opinion() >= p.allyThreshold() ? new Answer(true, "friendship") : new Answer(false, "not_friendly");
            case CEDE -> p.beneficiary() ? new Answer(true, "gains")
                    : p.atWar() && p.strengthRatio() < 0.5 ? new Answer(true, "outmatched") : new Answer(false, "refuses_cession");
        };
    }

    // ---- coalitions -------------------------------------------------------------------------------------------------------

    public record Sides(Set<UUID> attackers, Set<UUID> defenders, Set<UUID> neutral) {
        public Sides { attackers = Set.copyOf(attackers); defenders = Set.copyOf(defenders); neutral = Set.copyOf(neutral); }
    }

    /**
     * A village listed on both sides (an ally of both realms) sits the war out. The principal attacker and defender always
     * keep their side; nothing else is reordered.
     */
    public static Sides reconcile(Set<UUID> attackers, Set<UUID> defenders, UUID attacker, UUID defender) {
        var both = new TreeSet<UUID>(attackers);
        both.retainAll(defenders);
        both.remove(attacker);
        both.remove(defender);
        var a = new LinkedHashSet<>(attackers);
        var d = new LinkedHashSet<>(defenders);
        a.removeAll(both);
        d.removeAll(both);
        a.remove(defender);
        d.remove(attacker);
        return new Sides(a, d, both);
    }

    // ---- realm identity -----------------------------------------------------------------------------------------------------

    /** A native capital as observed now: its team and its member villages (itself included). */
    public record Capital(UUID village, String team, Set<UUID> members, boolean playerLed) {
        public Capital { members = Set.copyOf(members); team = team == null ? "" : team; }
    }

    /**
     * Gives each current capital a realm id. A realm keeps its id while its capital stands; when the capital falls, the
     * surviving villages that kept its team carry the realm on under their new capital. Only a faction with nothing in
     * common with any earlier realm gets a new id. Each earlier realm is used once; ties break by village id.
     */
    public static Map<UUID, UUID> assign(Collection<LvState.Realm> previous, Collection<Capital> capitals, Supplier<UUID> fresh) {
        var order = capitals.stream().sorted(Comparator.comparing(Capital::village)).toList();
        var taken = new HashSet<UUID>();
        var out = new LinkedHashMap<UUID, UUID>();
        var known = previous.stream().filter(r -> r.status() != LvState.RealmStatus.DISBANDED).sorted(Comparator.comparing(LvState.Realm::id)).toList();
        // 1. The same capital.
        for (Capital c : order)
            known.stream().filter(r -> !taken.contains(r.id()) && c.village().equals(r.capital())).findFirst()
                    .ifPresent(r -> { taken.add(r.id()); out.put(c.village(), r.id()); });
        // 2. The same team and at least one shared village (a capital change inside the realm).
        for (Capital c : order) {
            if (out.containsKey(c.village()) || c.team().isEmpty()) continue;
            known.stream().filter(r -> !taken.contains(r.id()) && r.teams().contains(c.team())
                            && r.members().stream().anyMatch(c.members()::contains)).findFirst()
                    .ifPresent(r -> { taken.add(r.id()); out.put(c.village(), r.id()); });
        }
        // 3. Shared villages only, with the most overlap, when the earlier realm's capital is no longer a capital.
        var current = new HashSet<UUID>();
        order.forEach(c -> current.add(c.village()));
        for (Capital c : order) {
            if (out.containsKey(c.village())) continue;
            known.stream().filter(r -> !taken.contains(r.id()) && !current.contains(r.capital()))
                    .max(Comparator.<LvState.Realm>comparingLong(r -> r.members().stream().filter(c.members()::contains).count())
                            .thenComparing(LvState.Realm::id, Comparator.reverseOrder()))
                    .filter(r -> r.members().stream().anyMatch(c.members()::contains))
                    .ifPresent(r -> { taken.add(r.id()); out.put(c.village(), r.id()); });
        }
        for (Capital c : order) out.computeIfAbsent(c.village(), ignored -> fresh.get());
        return out;
    }
}
