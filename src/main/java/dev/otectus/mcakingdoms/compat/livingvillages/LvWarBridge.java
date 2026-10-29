package dev.otectus.mcakingdoms.compat.livingvillages;

import com.google.gson.JsonObject;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import dev.otectus.mcakingdoms.warfare.WarfareConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Living Villages stays the combat executor; this is the policy and the record. Every path that can start, move or end a
 * native war passes the same preflight ({@link LvPolicy#war}), and each accepted war is one durable conflict from
 * declaration to outcome, whether it is fought live, abstractly, or both in turn. Outcomes are taken from the provider's
 * own occupation and repel operations, never computed here.
 */
public final class LvWarBridge {
    /** Per-pass caches for the AI's target selection, so one decision loop costs map lookups, not repeated scans. */
    static final class AiPass {
        final Map<UUID, UUID> realmOf = new HashMap<>();
        final Set<String> refused = new HashSet<>();
        Object attacker;
    }

    static AiPass aiPass;
    /** Abstract-siege tick context: the sieges present before the provider's step, and what the step produced. */
    static Map<String, LvSnapshots.Siege> tickBefore;
    static final Set<String> tickOutcomes = new HashSet<>();
    static boolean inAbstractTick;
    /** The pair whose abstract resolution is running, and whether it ended in occupation. */
    static String resolving;
    static boolean resolvedByOccupation;
    /** Before-state of an occupation in progress, keyed by conquered village. */
    static final Map<UUID, JsonObject> occupationBefore = new HashMap<>();

    private LvWarBridge() {
    }

    static void clearTransient() {
        aiPass = null;
        tickBefore = null;
        tickOutcomes.clear();
        inAbstractTick = false;
        resolving = null;
        resolvedByOccupation = false;
        occupationBefore.clear();
    }

    static String pair(UUID attacker, UUID defender) {
        return attacker + ">" + defender;
    }

    // ---- facts ---------------------------------------------------------------------------------------------------------

    static LvPolicy.Rules rules(LvRuntime rt) {
        return new LvPolicy.Rules(rt.data.writable(), WarfareConfig.enabled(), WarfareConfig.MILITARY.get(), LvConfig.aiWars(),
                LvConfig.playerTargets(), LvConfig.capitals(), LvConfig.respectCapitals() && WarfareConfig.PROTECT_CAPITALS.get(),
                WarfareConfig.REQUIRE_DEFENDER_ONLINE.get());
    }

    static Optional<Realm> realm(LvState state, UUID village) {
        return state.realmOfVillage(village);
    }

    /** Agreements in force between the two villages' realms (or a kingdom associated with either). */
    static List<Treaty> treatiesBetween(LvRuntime rt, LvState state, UUID villageA, UUID villageB) {
        var a = parties(state, villageA);
        var b = parties(state, villageB);
        long now = rt.now();
        var out = new ArrayList<Treaty>();
        for (var treaty : state.treaties.values()) {
            if (!treaty.inForce(now)) continue;
            for (String x : a) for (String y : b) if (treaty.binds(x, y) && !out.contains(treaty)) out.add(treaty);
        }
        return out;
    }

    /** Every party a village speaks for: its realm, and any kingdom explicitly associated with that realm. */
    static List<String> parties(LvState state, UUID village) {
        var out = new ArrayList<String>();
        state.realmOfVillage(village).ifPresent(realm -> {
            out.add("realm:" + realm.id());
            var association = state.associations.get(realm.id());
            if (association != null) out.add("kingdom:" + association.kingdom());
        });
        return out;
    }

    static boolean truce(LvRuntime rt, LvState state, UUID villageA, UUID villageB) {
        var a = parties(state, villageA);
        var b = parties(state, villageB);
        long now = rt.now();
        for (var treaty : state.treaties.values()) {
            if (treaty.truceUntil() <= now) continue;
            for (String x : a) for (String y : b) if (treaty.binds(x, y)) return true;
        }
        return false;
    }

    static LvPolicy.Side side(LvRuntime rt, Optional<Realm> realm) {
        if (realm.isEmpty()) return new LvPolicy.Side(null, false, true);
        var r = realm.get();
        return new LvPolicy.Side(r.id(), r.leadership() == Leadership.PLAYER, r.owner() != null && rt.online(r.owner()));
    }

    static LvPolicy.Attack attack(LvRuntime rt, LvState state, Object attacker, Object defender, ServerPlayer initiator) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var binding = state.bindings.get(d);
        boolean bound = binding != null && binding.status() == BindingStatus.BOUND;
        boolean kingdomCapital = false;
        if (bound && binding.settlement() != null) {
            try { kingdomCapital = KingdomsPoliticsApi.get(rt.server).isCapital(binding.settlement()); }
            catch (RuntimeException unavailable) { kingdomCapital = false; }
        }
        var attackerRealm = realm(state, a);
        boolean commands = initiator == null || attackerRealm.map(r -> initiator.getUUID().equals(r.owner())).orElse(false)
                || rt.access.playerRealm(initiator).map(v -> rt.access.id(v).equals(rt.access.factionId(attacker))
                && initiator.getUUID().equals(rt.access.owner(v))).orElse(false);
        var association = attackerRealm.map(r -> state.associations.get(r.id())).orElse(null);
        boolean mandateRequired = initiator != null && association != null && association.scope().contains("WAR");
        boolean mandateHeld = !mandateRequired || KingdomsPoliticsApi.get(rt.server).authorized(initiator, association.kingdom(),
                dev.otectus.mcakingdoms.api.politics.Politics.Permission.PROPOSE);
        return new LvPolicy.Attack(side(rt, attackerRealm), side(rt, realm(state, d)), bound, rt.access.virtual(defender),
                rt.access.isCapital(defender), kingdomCapital, !treatiesBetween(rt, state, a, d).isEmpty(), truce(rt, state, a, d),
                initiator != null, commands, mandateRequired, mandateHeld);
    }

    static LvPolicy.Decision decide(LvRuntime rt, Object attacker, Object defender, ServerPlayer initiator) {
        return LvPolicy.war(rules(rt), attack(rt, rt.view(), attacker, defender, initiator));
    }

    /** Only the agreement, truce and switch checks: what may stop a war that already started. */
    static LvPolicy.Decision continuing(LvRuntime rt, UUID attacker, UUID defender) {
        var state = rt.view();
        if (!rt.data.writable()) return LvPolicy.Decision.refuse("store_read_only", LvPolicy.Source.BRIDGE);
        var binding = state.bindings.get(defender);
        if (binding == null || binding.status() != BindingStatus.BOUND) return LvPolicy.Decision.ALLOW;
        if (!WarfareConfig.enabled() || !WarfareConfig.MILITARY.get()) return LvPolicy.Decision.refuse("warfare_off", LvPolicy.Source.KINGDOMS);
        if (!treatiesBetween(rt, state, attacker, defender).isEmpty()) return LvPolicy.Decision.refuse("treaty", LvPolicy.Source.BRIDGE);
        if (truce(rt, state, attacker, defender)) return LvPolicy.Decision.refuse("truce", LvPolicy.Source.BRIDGE);
        return LvPolicy.Decision.ALLOW;
    }

    private static void refused(LvRuntime rt, UUID attacker, UUID defender, LvPolicy.Decision decision) {
        String key = pair(attacker, defender) + ":" + decision.code();
        Long last = rt.refusalSeen.get(key);
        if (last != null && rt.now() - last < 6000) return;
        rt.refusalSeen.put(key, rt.now());
        if (rt.refusalSeen.size() > 2048) rt.refusalSeen.clear();
        var next = rt.data.snapshot();
        next.refuse(rt.now(), attacker, defender, decision.code());
        rt.data.markChanged(next);
        LvRuntime.LOGGER.debug("[MCA: Kingdoms] Living Villages war {} refused: {} ({})", key, decision.code(), decision.source());
    }

    // ---- conflicts -------------------------------------------------------------------------------------------------------

    static Optional<Conflict> open(LvState state, UUID attacker, UUID defender) {
        return state.conflicts.values().stream().filter(c -> c.open() && c.defenderVillage().equals(defender)
                && (c.attackerVillage().equals(attacker) || c.attackers().contains(attacker))).findFirst();
    }

    static Optional<Conflict> openFor(LvState state, UUID village) {
        return state.conflicts.values().stream().filter(c -> c.open() && (c.defenderVillage().equals(village)
                || c.attackerVillage().equals(village) || c.attackers().contains(village) || c.defenders().contains(village))).findFirst();
    }

    static boolean conquestPending(LvState state, UUID village) {
        return state.journal.values().stream().anyMatch(o -> o.kind() == OperationKind.CONQUEST && village.equals(o.subject())
                && o.state() != OperationState.ABANDONED && o.state() != OperationState.SUSPENDED);
    }

    private static Conflict create(LvRuntime rt, LvState next, Object attacker, Object defender, Phase phase, Path path, boolean undeclared,
                                   UUID initiator, String reason) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var binding = next.bindings.get(d);
        var conflict = new Conflict(UUID.randomUUID(), realm(next, a).map(Realm::id).orElse(null), a, d, realm(next, d).map(Realm::id).orElse(null),
                binding == null ? null : binding.settlement(), binding == null ? null : binding.claim(), List.of(a), List.of(d), List.of(),
                phase, path, undeclared, initiator, rt.now(), rt.now(), rt.access.recruits(attacker), rt.access.recruits(defender), reason, null,
                List.of(new Transition(rt.now(), phase, path, reason)));
        next.conflicts.put(conflict.id(), conflict);
        return conflict;
    }

    private static void update(LvRuntime rt, LvState next, Conflict conflict, Phase phase, Path path, String detail) {
        next.conflicts.put(conflict.id(), conflict.to(phase, path, rt.now(), detail));
    }

    // ---- declaration (live path) -------------------------------------------------------------------------------------------

    /** Before {@code WarManager.declareWar}: refuse, or record the accepted intent. Returns true to refuse. */
    static boolean beforeDeclare(LvRuntime rt, Object attacker, Object defender, ServerPlayer initiator) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var state = rt.view();
        var transition = inAbstractTick ? open(state, a, d).filter(c -> c.phase() == Phase.ACTIVE_ABSTRACT) : Optional.<Conflict>empty();
        var decision = transition.isPresent() ? continuing(rt, a, d) : decide(rt, attacker, defender, initiator);
        if (!decision.allowed()) {
            if (transition.isPresent()) {
                var next = rt.data.snapshot();
                update(rt, next, transition.get(), Phase.WITHDRAWN, Path.ABSTRACT, decision.code());
                next.record(EventKind.WITHDRAWN, rt.now(), transition.get().settlement(), transition.get().attackerRealm(),
                        transition.get().defenderRealm(), transition.get().id(), decision.code(), true);
                tickOutcomes.add(pair(a, d));
                rt.commit(next);
            } else refused(rt, a, d, decision);
            return true;
        }
        var next = rt.data.snapshot();
        if (transition.isPresent()) {
            update(rt, next, transition.get(), Phase.PROPOSED, Path.LIVE, "players_near");
        } else if (open(next, a, d).isEmpty()) {
            create(rt, next, attacker, defender, Phase.PROPOSED, Path.LIVE, false, initiator == null ? null : initiator.getUUID(),
                    initiator == null ? "ai" : "player");
        }
        rt.commit(next);
        return false;
    }

    /** After {@code declareWar}: confirm the native war, or record why the provider declined it. */
    static void afterDeclare(LvRuntime rt, Object attacker, Object defender, boolean declared) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var next = rt.data.snapshot();
        var conflict = open(next, a, d).orElse(null);
        if (conflict == null) return;
        boolean transition = inAbstractTick && conflict.transitions().stream().anyMatch(t -> t.path() == Path.ABSTRACT);
        if (!declared) {
            if (transition && tickBefore != null && tickBefore.containsKey(pair(a, d))) {
                // A failed abstract-to-live start leaves the abstract siege in place: the war continues, nothing is lost.
                rt.access.restoreSiege(tickBefore.get(pair(a, d)));
                update(rt, next, conflict, Phase.ACTIVE_ABSTRACT, Path.ABSTRACT, "live_start_declined");
                tickOutcomes.add(pair(a, d));
            } else {
                update(rt, next, conflict, Phase.CANCELLED, Path.LIVE, "provider_declined");
            }
            rt.commit(next);
            return;
        }
        if (transition) tickOutcomes.add(pair(a, d));
        // Reconcile coalitions: a village allied to both sides sits the war out; its troops are not committed twice.
        Object war = latestWar(rt, a, d);
        if (war != null) {
            var sides = LvPolicy.reconcile(rt.access.attackerSet(war), rt.access.defenderSet(war), a, d);
            rt.access.attackerSet(war).retainAll(sides.attackers());
            rt.access.defenderSet(war).retainAll(sides.defenders());
            conflict = conflict.sides(sides.attackers().stream().sorted().toList(), sides.defenders().stream().sorted().toList(),
                    sides.neutral().stream().sorted().toList());
        }
        update(rt, next, conflict, Phase.NOTICE, Path.LIVE, transition ? "players_near" : "declared");
        if (!transition) next.record(EventKind.WAR_DECLARED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(),
                conflict.id(), rt.access.name(rt.access.capitalOf(rt.server, attacker)) + " → " + rt.access.name(defender), true);
        else next.record(EventKind.PATH_CHANGED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(),
                conflict.id(), "abstract → live", false);
        rt.commit(next);
    }

    private static Object latestWar(LvRuntime rt, UUID attacker, UUID defender) {
        var wars = rt.access.liveWars();
        for (int i = wars.size() - 1; i >= 0; i--) {
            var snapshot = rt.access.war(wars.get(i));
            if (snapshot.defender().equals(defender) && (snapshot.attacker().equals(attacker) || snapshot.attackers().contains(attacker))) return wars.get(i);
        }
        return null;
    }

    /** At the end of the notice window: a peace or switch change since the declaration withdraws the war before any battle. */
    static boolean beforeBattle(LvRuntime rt, Object war) {
        var snapshot = rt.access.war(war);
        var decision = continuing(rt, snapshot.attacker(), snapshot.defender());
        var next = rt.data.snapshot();
        var conflict = open(next, snapshot.attacker(), snapshot.defender()).orElse(null);
        if (!decision.allowed()) {
            rt.access.dropPendingWar(war);
            if (conflict != null) {
                update(rt, next, conflict, Phase.WITHDRAWN, Path.LIVE, decision.code());
                next.record(EventKind.WITHDRAWN, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(),
                        conflict.id(), decision.code(), true);
                rt.commit(next);
            }
            return true;
        }
        return false;
    }

    static void afterBattle(LvRuntime rt, Object war) {
        var snapshot = rt.access.war(war);
        if (!snapshot.active() || !rt.access.liveWars().contains(war)) return; // resolved abstractly or dropped; those hooks record it
        var next = rt.data.snapshot();
        var conflict = open(next, snapshot.attacker(), snapshot.defender()).orElse(null);
        if (conflict == null || conflict.phase() == Phase.ACTIVE_LIVE) return;
        update(rt, next, conflict, Phase.ACTIVE_LIVE, Path.LIVE, "battle " + snapshot.attackerForce() + " v " + snapshot.defenderForce());
        next.record(EventKind.BATTLE_BEGAN, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(),
                snapshot.attackerForce() + " v " + snapshot.defenderForce(), true);
        rt.commit(next);
    }

    // ---- AI selection -------------------------------------------------------------------------------------------------------

    static void beginAiPass() {
        aiPass = new AiPass();
    }

    static void endAiPass() {
        aiPass = null;
    }

    static void aiAttacker(Object attacker) {
        if (aiPass != null) aiPass.attacker = attacker;
    }

    /** Whether the AI should skip this defender: a refused target is passed over, so it does not monopolise the selection. */
    static boolean aiExcluded(LvRuntime rt, Object defender) {
        var pass = aiPass;
        if (pass == null || pass.attacker == null) return false;
        UUID a = rt.access.id(pass.attacker), d = rt.access.id(defender);
        String key = pair(a, d);
        if (pass.refused.contains(key)) return true;
        var decision = decide(rt, pass.attacker, defender, null);
        if (decision.allowed()) return false;
        pass.refused.add(key);
        refused(rt, a, d, decision);
        return true;
    }

    // ---- abstract path ------------------------------------------------------------------------------------------------------

    /** Before {@code startAbstractSiege}. Returns true to refuse. */
    static boolean beforeAbstract(LvRuntime rt, Object attacker, Object defender) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var decision = decide(rt, attacker, defender, null);
        if (!decision.allowed()) { refused(rt, a, d, decision); return true; }
        return false;
    }

    static void afterAbstract(LvRuntime rt, Object attacker, Object defender) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var siege = rt.access.sieges().stream().filter(s -> s.attacker().equals(a) && s.defender().equals(d)).findFirst().orElse(null);
        if (siege == null) return;
        var next = rt.data.snapshot();
        var conflict = open(next, a, d).orElse(null);
        if (conflict == null) conflict = create(rt, next, attacker, defender, Phase.ACTIVE_ABSTRACT, Path.ABSTRACT, false, null, "ai");
        else update(rt, next, conflict, Phase.ACTIVE_ABSTRACT, Path.ABSTRACT, "siege");
        next.record(EventKind.SIEGE_LAID, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(),
                rt.access.name(rt.access.capitalOf(rt.server, attacker)) + " → " + rt.access.name(defender) + " (" + siege.attackerStart() + " v " + siege.defenderStart() + ")", true);
        rt.commit(next);
    }

    /** Before each abstract casualty step: a peace or truce now in force stops the siege before another loss is taken. */
    static void beforeAbstractTick(LvRuntime rt) {
        var before = new LinkedHashMap<String, LvSnapshots.Siege>();
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var siege : rt.access.sieges()) {
            var decision = continuing(rt, siege.attacker(), siege.defender());
            if (!decision.allowed()) {
                rt.access.removeSiege(siege.attacker(), siege.defender());
                var conflict = open(next, siege.attacker(), siege.defender()).orElse(null);
                if (conflict != null) {
                    update(rt, next, conflict, Phase.WITHDRAWN, Path.ABSTRACT, decision.code());
                    next.record(EventKind.WITHDRAWN, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(),
                            conflict.id(), decision.code(), true);
                    changed = true;
                }
                continue;
            }
            before.put(pair(siege.attacker(), siege.defender()), siege);
        }
        if (changed) rt.commit(next);
        tickBefore = before;
        tickOutcomes.clear();
        inAbstractTick = true;
    }

    /** After the provider's step: a siege that ended without a recorded occupation or live start ended in a repel. */
    static void afterAbstractTick(LvRuntime rt) {
        var before = tickBefore;
        inAbstractTick = false;
        tickBefore = null;
        if (before == null || before.isEmpty()) return;
        var now = new HashSet<String>();
        rt.access.sieges().forEach(s -> now.add(pair(s.attacker(), s.defender())));
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var entry : before.entrySet()) {
            if (now.contains(entry.getKey()) || tickOutcomes.contains(entry.getKey())) continue;
            var siege = entry.getValue();
            var conflict = open(next, siege.attacker(), siege.defender()).orElse(null);
            if (conflict == null) continue;
            boolean exists = rt.access.village(rt.server, siege.attacker()).isPresent() && rt.access.village(rt.server, siege.defender()).isPresent();
            var phase = exists ? Phase.REPELLED : Phase.CANCELLED;
            update(rt, next, conflict, phase, Path.ABSTRACT, exists ? "held_off" : "village_gone");
            next.record(exists ? EventKind.REPELLED : EventKind.CANCELLED, rt.now(), conflict.settlement(), conflict.attackerRealm(),
                    conflict.defenderRealm(), conflict.id(), exists ? "abstract" : "village_gone", true);
            changed = true;
        }
        tickOutcomes.clear();
        if (changed) rt.commit(next);
    }

    /** Before a direct abstract resolution (a declared war nobody is near, or a live war leaving view). Returns true to refuse. */
    static boolean beforeResolve(LvRuntime rt, Object attacker, Object defender) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        var decision = continuing(rt, a, d);
        var next = rt.data.snapshot();
        var conflict = open(next, a, d).orElse(null);
        if (!decision.allowed()) {
            if (conflict != null) {
                update(rt, next, conflict, Phase.WITHDRAWN, Path.ABSTRACT, decision.code());
                rt.commit(next);
            }
            return true;
        }
        if (conflict == null) conflict = create(rt, next, attacker, defender, Phase.RESOLVING, Path.ABSTRACT, false, null, "resolution");
        else update(rt, next, conflict, Phase.RESOLVING, Path.ABSTRACT, "resolving");
        rt.commit(next);
        resolving = pair(a, d);
        resolvedByOccupation = false;
        return false;
    }

    static void afterResolve(LvRuntime rt, Object attacker, Object defender) {
        UUID a = rt.access.id(attacker), d = rt.access.id(defender);
        boolean occupied = resolvedByOccupation && pair(a, d).equals(resolving);
        resolving = null;
        resolvedByOccupation = false;
        if (occupied) return; // the occupation receipt closed the conflict
        var next = rt.data.snapshot();
        var conflict = open(next, a, d).orElse(null);
        if (conflict == null) return;
        update(rt, next, conflict, Phase.REPELLED, Path.ABSTRACT, "resolved");
        next.record(EventKind.REPELLED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(), "abstract", true);
        rt.commit(next);
    }

    /** A live war leaving view: a peace in force ends it here, through the provider's recall; otherwise its path changes. */
    static boolean beforeSettle(LvRuntime rt, UUID village) {
        for (Object war : List.copyOf(rt.access.liveWars())) {
            var snapshot = rt.access.war(war);
            if (!snapshot.active() || !snapshot.involves(village)) continue;
            var decision = continuing(rt, snapshot.attacker(), snapshot.defender());
            var next = rt.data.snapshot();
            var conflict = open(next, snapshot.attacker(), snapshot.defender()).orElse(null);
            if (!decision.allowed()) {
                rt.access.endLiveWar(rt.overworld(), war);
                if (conflict != null) {
                    update(rt, next, conflict, Phase.WITHDRAWN, Path.LIVE, decision.code());
                    rt.commit(next);
                }
                return true;
            }
            if (conflict != null) {
                update(rt, next, conflict, Phase.ACTIVE_LIVE, Path.ABSTRACT, "left_view");
                next.record(EventKind.PATH_CHANGED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(),
                        conflict.id(), "live → abstract", false);
                rt.commit(next);
            }
            return false;
        }
        return false;
    }

    // ---- outcomes -------------------------------------------------------------------------------------------------------------

    private static JsonObject controlFacts(LvRuntime rt, Object village) {
        var json = new JsonObject();
        UUID id = rt.access.id(village);
        Object capital = rt.access.capitalOf(rt.server, village);
        json.addProperty("village", id.toString());
        json.addProperty("name", rt.access.name(village));
        json.addProperty("team", rt.access.team(village));
        json.addProperty("capital", rt.access.id(capital).toString());
        json.addProperty("isCapital", rt.access.isCapital(village));
        json.addProperty("recruits", rt.access.recruits(village));
        json.addProperty("treasury", rt.access.treasury(village));
        json.addProperty("food", rt.access.food(village));
        rt.access.claimOf(village).ifPresent(claim -> json.addProperty("claim", claim.toString()));
        rt.access.claimOwner(village).ifPresent(owner -> json.addProperty("claimOwner", owner));
        rt.view().realmOfVillage(id).ifPresent(realm -> json.addProperty("realm", realm.id().toString()));
        return json;
    }

    static void beforeOccupy(LvRuntime rt, Object conquered, Object attackerCapital) {
        var json = controlFacts(rt, conquered);
        if (attackerCapital != null) {
            json.addProperty("winner", rt.access.id(attackerCapital).toString());
            json.addProperty("winnerTreasury", rt.access.treasury(attackerCapital));
        }
        json.addProperty("at", rt.now());
        occupationBefore.put(rt.access.id(conquered), json);
        // Intent before the provider's occupation runs, so a crash between the two leaves evidence to reconcile.
        var next = rt.data.snapshot();
        UUID village = rt.access.id(conquered);
        var conflict = openFor(next, village).orElse(null);
        UUID op = UUID.randomUUID();
        next.journal.put(op, new Operation(op, OperationKind.CONQUEST, village, conflict == null ? null : conflict.id(), OperationState.INTENT,
                json.toString(), "", rt.now(), 0, 0, false, "occupation", conflict != null && conflict.initiator() != null ? ActorKind.PLAYER : ActorKind.PROVIDER_REALM,
                conflict == null ? null : conflict.initiator() != null ? conflict.initiator() : conflict.attackerRealm()));
        rt.commit(next);
    }

    static void afterOccupy(LvRuntime rt, Object conquered, boolean abstractPath) {
        UUID village = rt.access.id(conquered);
        var before = occupationBefore.remove(village);
        var after = controlFacts(rt, conquered);
        Object capital = rt.access.capitalOf(rt.server, conquered);
        after.addProperty("winner", rt.access.id(capital).toString());
        after.addProperty("winnerTreasury", rt.access.treasury(capital));
        var next = rt.data.snapshot();
        var op = next.journal.values().stream().filter(o -> o.kind() == OperationKind.CONQUEST && village.equals(o.subject())
                && o.state() == OperationState.INTENT).reduce((x, y) -> y).orElse(null);
        String beforeTeam = before == null || !before.has("team") ? "" : before.get("team").getAsString();
        if (op == null) {
            UUID id = UUID.randomUUID();
            op = new Operation(id, OperationKind.CONQUEST, village, null, OperationState.INTENT, before == null ? "" : before.toString(), "",
                    rt.now(), 0, 0, false, "occupation", ActorKind.PROVIDER_REALM, null);
        }
        if (beforeTeam.equals(rt.access.team(conquered))) {
            // The provider's occupation changed nothing (a cancelled or partial operation): recovery evidence, not a victory.
            next.journal.put(op.id(), op.state(OperationState.SUSPENDED, after.toString(), rt.now()));
            next.record(EventKind.RECOVERY, rt.now(), LvBindings.settlementOf(next, village), null, null, op.conflict(),
                    "Living Villages reported an occupation that did not change control", false);
            rt.commit(next);
            return;
        }
        next.journal.put(op.id(), op.state(OperationState.APPLIED, after.toString(), rt.now()));
        var conflict = op.conflict() == null ? openFor(next, village).orElse(null) : next.conflicts.get(op.conflict());
        if (conflict != null) {
            next.conflicts.put(conflict.id(), conflict.to(Phase.COMPLETED, abstractPath ? Path.ABSTRACT : Path.LIVE, rt.now(), "occupied").receipt(op.id()));
            tickOutcomes.add(pair(conflict.attackerVillage(), conflict.defenderVillage()));
        }
        String winnerRealm = next.realmOfVillage(rt.access.id(capital)).map(r -> r.id().toString()).orElse("");
        var binding = next.bindings.get(village);
        if (binding != null && !winnerRealm.isEmpty()) {
            next.bindings.put(village, new Binding(binding.village(), binding.settlement(), binding.dimension(), binding.status(),
                    binding.providerName(), binding.aliases(), binding.claim(), binding.retiredClaims(), UUID.fromString(winnerRealm),
                    binding.recognizedRealm(), binding.adopted(), binding.importedAt(), rt.now(), binding.reason(), binding.candidates()));
        }
        next.record(EventKind.CONQUERED, rt.now(), binding == null ? null : binding.settlement(),
                winnerRealm.isEmpty() ? null : UUID.fromString(winnerRealm), binding == null ? null : binding.recognizedRealm(),
                conflict == null ? null : conflict.id(), rt.access.name(capital) + " took " + rt.access.name(conquered), true);
        if (rt.commit(next)) rt.awaitingSave.add(op.id());
        if (abstractPath && resolving != null) resolvedByOccupation = true;
        rt.touch(village);
        rt.touch(rt.access.id(capital));
    }

    static void repelled(LvRuntime rt, Object war) {
        var snapshot = rt.access.war(war);
        var next = rt.data.snapshot();
        var conflict = open(next, snapshot.attacker(), snapshot.defender()).orElse(null);
        if (conflict == null) return;
        update(rt, next, conflict, Phase.REPELLED, Path.LIVE, "repelled");
        next.record(EventKind.REPELLED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(), "live", true);
        rt.commit(next);
    }

    /** An undeclared live siege that Recruits ended without a conquest: the attackers left, so the defenders held. */
    static void siegeEnded(LvRuntime rt, UUID claim) {
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var conflict : List.copyOf(next.conflicts.values())) {
            if (!conflict.open() || !conflict.undeclared() || !claim.equals(conflict.claim())) continue;
            update(rt, next, conflict, Phase.REPELLED, Path.LIVE, "siege_ended");
            next.record(EventKind.REPELLED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(), "undeclared siege ended", true);
            changed = true;
        }
        if (changed) rt.commit(next);
    }

    /** The realm a combatant serves: its Living Villages unit tag, else its Recruits team. */
    private static Optional<Realm> combatantRealm(LvState state, net.minecraft.world.entity.Entity entity) {
        var data = entity.getPersistentData();
        if (data.hasUUID(LvResidents.UNIT_TAG)) {
            var realm = state.realmOfVillage(data.getUUID(LvResidents.UNIT_TAG));
            if (realm.isPresent()) return realm;
        }
        return entity.getTeam() == null ? Optional.empty() : state.realmByTeam(entity.getTeam().getName());
    }

    /**
     * Whether an agreement in force forbids this attack between two realms' combatants (peace, ceasefire or alliance). The
     * same agreements the war preflight honours, so a treaty stops the fighting it says it stops.
     */
    public static boolean combatForbidden(net.minecraft.server.MinecraftServer server, net.minecraft.world.entity.Entity attacker, net.minecraft.world.entity.Entity victim) {
        var rt = LvRuntime.get(server).orElse(null);
        if (rt == null || attacker == null || victim == null) return false;
        var state = rt.view();
        var a = combatantRealm(state, attacker).orElse(null);
        var b = combatantRealm(state, victim).orElse(null);
        if (a == null || b == null || a.id().equals(b.id())) return false;
        long now = rt.now();
        for (var treaty : state.treaties.values())
            if (treaty.inForce(now) && treaty.term() != Term.CEDE && treaty.binds("realm:" + a.id(), "realm:" + b.id())) return true;
        return false;
    }

    /** A player's recruits on a temporary MCA: Kingdoms deployment are not also sent to a Living Villages battle. */
    static void withoutLeased(LvRuntime rt, List<?> recruits) {
        var leases = dev.otectus.mcakingdoms.warfare.mobilization.MobilizationSavedData.get(rt.server);
        recruits.removeIf(r -> r instanceof net.minecraft.world.entity.Entity entity && leases.transferBlocked(entity.getUUID()));
    }

    // ---- Recruits siege policy for integrated claims ----------------------------------------------------------------------------

    /**
     * Whether a native siege on {@code claim} may register, progress or complete; empty when the claim is not a bound Living
     * Villages village (then the legacy campaign rules decide). The attacking teams must belong to an accepted conflict
     * against this village, or pass the same preflight as a declared war, in which case the undeclared siege becomes one.
     */
    public static Optional<Boolean> siegeAllowed(net.minecraft.server.MinecraftServer server, UUID claim, List<String> attackers) {
        var rt = LvRuntime.get(server).orElse(null);
        if (rt == null) return Optional.empty();
        var state = rt.view();
        var binding = state.bindings.values().stream().filter(b -> b.status() == BindingStatus.BOUND && claim.equals(b.claim())).findFirst().orElse(null);
        if (binding == null) return Optional.empty();
        var defender = rt.access.village(rt.server, binding.village()).orElse(null);
        if (defender == null) return Optional.of(false);
        if (attackers.isEmpty()) return Optional.of(false);
        var conflict = openFor(state, binding.village()).filter(c -> c.defenderVillage().equals(binding.village())).orElse(null);
        if (conflict != null) {
            var teams = new HashSet<String>();
            for (UUID village : conflict.attackers()) rt.access.village(rt.server, village).ifPresent(v -> teams.add(rt.access.team(v)));
            if (conflict.attackerRealm() != null && state.realms.containsKey(conflict.attackerRealm())) teams.addAll(state.realms.get(conflict.attackerRealm()).teams());
            if (teams.containsAll(attackers)) return Optional.of(continuing(rt, conflict.attackerVillage(), conflict.defenderVillage()).allowed());
        }
        // An undeclared siege: each attacking team is judged as if it had declared war on this village.
        for (String team : attackers) {
            var realm = state.realmByTeam(team).orElse(null);
            LvPolicy.Decision decision;
            Object attackerVillage = realm == null ? null : rt.access.village(rt.server, realm.capital()).orElse(null);
            if (attackerVillage != null) decision = decide(rt, attackerVillage, defender, null);
            else {
                // A Recruits team that is not a Living Villages realm: target protections still apply.
                var attack = attack(rt, state, defender, defender, null);
                decision = LvPolicy.war(rules(rt), new LvPolicy.Attack(new LvPolicy.Side(null, true, true), attack.defender(), true,
                        attack.targetVirtual(), attack.targetRealmCapital(), attack.targetKingdomCapital(), false, false, false, true, false, true));
            }
            if (!decision.allowed()) {
                refused(rt, attackerVillage == null ? binding.village() : rt.access.id(attackerVillage), binding.village(), decision);
                return Optional.of(false);
            }
            if (conflict == null && attackerVillage != null) {
                var next = rt.data.snapshot();
                conflict = create(rt, next, attackerVillage, defender, Phase.ACTIVE_LIVE, Path.LIVE, true, null, "undeclared_siege");
                next.record(EventKind.SIEGE_LAID, rt.now(), binding.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(),
                        rt.access.name(attackerVillage) + " → " + rt.access.name(defender) + " (undeclared)", true);
                rt.commit(next);
            }
        }
        return Optional.of(true);
    }

    // ---- peace, restart and upkeep -----------------------------------------------------------------------------------------------

    /**
     * Ends every native war and siege between the two realms through the provider's own operations, with no conquest and no
     * reward: survivors are recalled home once, casualties already taken stay taken. Returns the conflicts it closed.
     */
    static List<UUID> endBetween(LvRuntime rt, LvState next, Set<UUID> sideA, Set<UUID> sideB, String reason) {
        var closed = new ArrayList<UUID>();
        for (Object war : List.copyOf(rt.access.liveWars())) {
            var w = rt.access.war(war);
            boolean between = (sideA.contains(w.attacker()) || w.attackers().stream().anyMatch(sideA::contains)) && (sideB.contains(w.defender()) || w.defenders().stream().anyMatch(sideB::contains))
                    || (sideB.contains(w.attacker()) || w.attackers().stream().anyMatch(sideB::contains)) && (sideA.contains(w.defender()) || w.defenders().stream().anyMatch(sideA::contains));
            if (!between) continue;
            if (w.active()) rt.access.endLiveWar(rt.overworld(), war);
            else rt.access.dropPendingWar(war);
        }
        for (var siege : rt.access.sieges()) {
            boolean between = sideA.contains(siege.attacker()) && sideB.contains(siege.defender()) || sideB.contains(siege.attacker()) && sideA.contains(siege.defender());
            if (between) rt.access.removeSiege(siege.attacker(), siege.defender());
        }
        for (var conflict : List.copyOf(next.conflicts.values())) {
            if (!conflict.open()) continue;
            boolean between = sideA.contains(conflict.attackerVillage()) && sideB.contains(conflict.defenderVillage())
                    || sideB.contains(conflict.attackerVillage()) && sideA.contains(conflict.defenderVillage());
            if (!between) continue;
            next.conflicts.put(conflict.id(), conflict.to(Phase.WITHDRAWN, conflict.path(), rt.now(), reason));
            next.record(EventKind.WITHDRAWN, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(), reason, true);
            closed.add(conflict.id());
        }
        return closed;
    }

    /**
     * After a restart Living Villages has forgotten its running wars (they live in memory only). A recorded abstract siege is
     * restored exactly; a declared or live war continues as an abstract siege from the troops now on its rosters (the provider
     * stood deployed soldiers down into the model at shutdown), and turns live again when players approach. Nothing is rerolled
     * or granted; a war a peace now forbids is withdrawn.
     */
    static void restore(LvRuntime rt) {
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var conflict : List.copyOf(next.conflicts.values())) {
            if (!conflict.open()) continue;
            var attacker = rt.access.village(rt.server, conflict.attackerVillage()).orElse(null);
            var defender = rt.access.village(rt.server, conflict.defenderVillage()).orElse(null);
            if (attacker == null || defender == null) {
                next.conflicts.put(conflict.id(), conflict.to(Phase.CANCELLED, conflict.path(), rt.now(), "village_gone_after_restart"));
                changed = true;
                continue;
            }
            // Only this exact war already running in Living Villages is left alone. A village may be attacking one neighbour
            // while another besieges it (the provider allows both), so being at war elsewhere does not skip a restore.
            UUID attackerId = conflict.attackerVillage(), defenderId = conflict.defenderVillage();
            if (rt.access.warBetween(attackerId, defenderId) || rt.access.sieges().stream()
                    .anyMatch(s -> s.attacker().equals(attackerId) && s.defender().equals(defenderId))) continue;
            var decision = continuing(rt, conflict.attackerVillage(), conflict.defenderVillage());
            if (!decision.allowed()) {
                next.conflicts.put(conflict.id(), conflict.to(Phase.WITHDRAWN, conflict.path(), rt.now(), decision.code()));
                changed = true;
                continue;
            }
            if (conflict.phase() == Phase.RESOLVING) {
                // A resolution was running when the process stopped; whatever the provider saved stands, nothing is replayed.
                next.conflicts.put(conflict.id(), conflict.to(Phase.RECOVERY_REQUIRED, conflict.path(), rt.now(), "interrupted_resolution"));
                changed = true;
                continue;
            }
            int a = committed(rt, attacker), d = committed(rt, defender);
            rt.access.restoreSiege(new LvSnapshots.Siege(conflict.attackerVillage(), conflict.defenderVillage(), a, d));
            next.conflicts.put(conflict.id(), conflict.to(Phase.ACTIVE_ABSTRACT, Path.ABSTRACT, rt.now(), "restored_after_restart"));
            next.record(EventKind.PATH_CHANGED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(),
                    conflict.id(), "restored after restart", false);
            changed = true;
        }
        // Conquests interrupted between intent and the provider's change: compare with what the provider saved.
        for (var op : List.copyOf(next.journal.values())) {
            if (op.kind() != OperationKind.CONQUEST || op.state() != OperationState.INTENT) continue;
            var village = rt.access.village(rt.server, op.subject()).orElse(null);
            String beforeTeam = op.before().contains("\"team\":\"") ? op.before().replaceAll(".*\"team\":\"([^\"]*)\".*", "$1") : "";
            if (village != null && !beforeTeam.equals(rt.access.team(village))) {
                next.journal.put(op.id(), op.state(OperationState.ACKNOWLEDGED, controlFacts(rt, village).toString(), rt.now()));
                next.record(EventKind.RECOVERY, rt.now(), LvBindings.settlementOf(next, op.subject()), null, null, op.conflict(),
                        "An interrupted conquest was found applied by Living Villages; acknowledged without repeating it", false);
            } else {
                next.journal.put(op.id(), op.state(OperationState.ABANDONED, village == null ? "" : controlFacts(rt, village).toString(), rt.now()));
            }
            changed = true;
        }
        if (changed) rt.commit(next);
    }

    private static int committed(LvRuntime rt, Object village) {
        double fraction = rt.access.decimal("COMMITTED_FRACTION", 0.5);
        return (int) Math.ceil(rt.access.recruits(village) * fraction);
    }

    /** Conflicts whose native war ended without a recorded result are closed, not left open forever. */
    static void maintain(LvRuntime rt) {
        var state = rt.view();
        var live = rt.access.wars();
        var sieges = rt.access.sieges();
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var conflict : List.copyOf(state.conflicts.values())) {
            if (!conflict.open() || rt.now() - conflict.updated() < 200) continue;
            boolean present = switch (conflict.phase()) {
                case NOTICE, ACTIVE_LIVE -> live.stream().anyMatch(w -> w.defender().equals(conflict.defenderVillage()))
                        || sieges.stream().anyMatch(s -> s.defender().equals(conflict.defenderVillage()));
                case ACTIVE_ABSTRACT -> sieges.stream().anyMatch(s -> s.defender().equals(conflict.defenderVillage()))
                        || live.stream().anyMatch(w -> w.defender().equals(conflict.defenderVillage()));
                case PROPOSED, RESOLVING -> rt.now() - conflict.updated() < 1200;
                default -> true;
            };
            if (present) continue;
            next.conflicts.put(conflict.id(), conflict.to(Phase.CANCELLED, conflict.path(), rt.now(), "ended_without_result"));
            next.record(EventKind.CANCELLED, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), conflict.id(),
                    "ended_without_result", false);
            changed = true;
        }
        if (changed) rt.commit(next);
    }
}
