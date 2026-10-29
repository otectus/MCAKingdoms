package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.PoliticalPartyRef;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Agreements between realms, and between a realm and a kingdom government that represents one by explicit association.
 * Living Villages' own opinion score stays the input to AI decisions; Kingdoms does not run a second diplomacy. One
 * arbitration point (the provider's own relation step) keeps an agreement in force from being overturned by a periodic
 * recomputation, and the bridge's own writes are told apart from them, so nothing writes back and forth.
 */
public final class LvDiplomacy {
    /** Set while the bridge writes a native relation itself, so the arbitration hook acknowledges instead of judging it. */
    private static boolean ownWrite;

    private LvDiplomacy() {
    }

    // ---- parties ----------------------------------------------------------------------------------------------------------

    static PoliticalPartyRef party(String value) {
        return PoliticalPartyRef.parse(value).orElseThrow(() -> LvText.refuse("party_unknown", "That party is not known."));
    }

    /** The native Recruits teams a party commands: a realm's own team, or the realms and mapped factions of a kingdom. */
    static Set<String> teams(LvRuntime rt, LvState state, PoliticalPartyRef party) {
        var out = new LinkedHashSet<String>();
        if (party instanceof PoliticalPartyRef.Realm realm) {
            var r = state.realms.get(realm.realmId());
            if (r != null && r.status() == RealmStatus.ACTIVE && !r.team().isEmpty()) out.add(r.team());
        } else if (party instanceof PoliticalPartyRef.Kingdom kingdom) {
            for (var association : state.associations.values()) {
                if (!association.kingdom().equals(kingdom.kingdomId())) continue;
                var r = state.realms.get(association.realm());
                if (r != null && r.status() == RealmStatus.ACTIVE && !r.team().isEmpty()) out.add(r.team());
            }
            out.addAll(LegacyMappings.teams(rt, kingdom.kingdomId()));
        }
        return out;
    }

    /** Villages a party speaks for. */
    static Set<UUID> villages(LvState state, PoliticalPartyRef party) {
        var out = new LinkedHashSet<UUID>();
        if (party instanceof PoliticalPartyRef.Realm realm) {
            var r = state.realms.get(realm.realmId());
            if (r != null) out.addAll(r.members());
        } else if (party instanceof PoliticalPartyRef.Kingdom kingdom) {
            state.associations.values().stream().filter(a -> a.kingdom().equals(kingdom.kingdomId()))
                    .map(a -> state.realms.get(a.realm())).filter(Objects::nonNull).forEach(r -> out.addAll(r.members()));
        }
        return out;
    }

    static String name(LvRuntime rt, LvState state, PoliticalPartyRef party) {
        if (party instanceof PoliticalPartyRef.Realm realm) return Optional.ofNullable(state.realms.get(realm.realmId())).map(Realm::name).orElse("?");
        return dev.otectus.mcakingdoms.interaction.Names.kingdomName(rt.server, ((PoliticalPartyRef.Kingdom) party).kingdomId()).getString();
    }

    static boolean aiLed(LvState state, PoliticalPartyRef party) {
        return party instanceof PoliticalPartyRef.Realm realm && Optional.ofNullable(state.realms.get(realm.realmId()))
                .filter(r -> r.leadership() == Leadership.AI && !state.associations.containsKey(r.id())).isPresent();
    }

    /** Whether {@code player} may speak for {@code party}: its native owner, or a ratifier of the kingdom that represents it. */
    static boolean authority(LvRuntime rt, LvState state, ServerPlayer player, PoliticalPartyRef party) {
        var politics = KingdomsPoliticsApi.get(rt.server);
        if (party instanceof PoliticalPartyRef.Realm realm) {
            var r = state.realms.get(realm.realmId());
            if (r == null) return false;
            if (r.leadership() == Leadership.PLAYER && player.getUUID().equals(r.owner())) return true;
            var association = state.associations.get(r.id());
            return association != null && association.scope().contains("DIPLOMACY")
                    && politics.authorized(player, association.kingdom(), Politics.Permission.RATIFY);
        }
        return politics.authorized(player, ((PoliticalPartyRef.Kingdom) party).kingdomId(), Politics.Permission.RATIFY);
    }

    // ---- proposals -----------------------------------------------------------------------------------------------------------

    /**
     * Proposes {@code term} between {@code proposer} and {@code counterpart}. An AI realm decides at once, by the recorded
     * policy; a player-led party must sign. A term the bridge cannot enforce for these parties is refused here, so no
     * agreement is ever shown as in force while its terms are unimplemented.
     */
    public static String propose(LvRuntime rt, ServerPlayer actor, UUID request, String proposerValue, String counterpartValue, Term term,
                                 long durationTicks, String reason) {
        var proposer = party(proposerValue);
        var counterpart = party(counterpartValue);
        var state = rt.data.snapshot();
        var prior = state.treaties.get(request);
        if (prior != null) return LvText.text("treaty_replayed", "That proposal was already made: %s.", prior.status().name());
        if (proposer.equals(counterpart)) throw LvText.refuse("treaty_self", "An agreement needs two different parties.");
        if (!authority(rt, state, actor, proposer)) throw LvText.refuse("treaty_authority", "You do not speak for %s.", name(rt, state, proposer));
        if (reason.length() > 512) throw LvText.refuse("treaty_reason_long", "Keep the terms under 512 characters.");
        if (durationTicks < 1200 || durationTicks > 2_400_000) throw LvText.refuse("treaty_duration", "Choose a term from 1200 to 2400000 game ticks.");
        enforceable(rt, state, proposer, counterpart, term);
        if (state.treaties.values().stream().anyMatch(t -> t.term() == term && (t.status() == TreatyStatus.PROPOSED || t.inForce(rt.now()))
                && t.binds(proposer.format(), counterpart.format())))
            throw LvText.refuse("treaty_exists", "That agreement is already proposed or in force between these parties.");
        if (state.treaties.size() >= LvState.TREATIES - 1) throw LvText.refuse("treaty_capacity", "No more agreements can be recorded.");
        var covered = new ArrayList<UUID>();
        covered.addAll(villages(state, proposer));
        covered.addAll(villages(state, counterpart));
        var treaty = new Treaty(request, proposer.format(), counterpart.format(), term, TreatyStatus.PROPOSED,
                Map.of(proposer.format(), new Signature(ActorKind.PLAYER, actor.getUUID(), rt.now(), "proposed")),
                covered.stream().distinct().limit(1024).toList(), rt.now(), 0, rt.now() + durationTicks,
                0, reason, "", 0, "");
        state.treaties.put(request, treaty);
        if (!rt.commit(state)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        if (aiLed(rt.view(), counterpart) && LvConfig.aiTreaties()) return decideForAi(rt, request, counterpart);
        return LvText.text("treaty_proposed", "Proposed: %s with %s. It takes effect once %s signs.", term.name().toLowerCase(Locale.ROOT),
                name(rt, rt.view(), counterpart), name(rt, rt.view(), counterpart));
    }

    private static UUID realmOf(PoliticalPartyRef party) {
        return party instanceof PoliticalPartyRef.Realm realm ? realm.realmId() : null;
    }

    private static void enforceable(LvRuntime rt, LvState state, PoliticalPartyRef a, PoliticalPartyRef b, Term term) {
        switch (term) {
            case PEACE, CEASEFIRE, ALLIANCE -> {
                if (teams(rt, state, a).isEmpty())
                    throw LvText.refuse("treaty_no_army_first", "%s commands no Recruits faction, so a military agreement could not be enforced.", name(rt, state, a));
                if (teams(rt, state, b).isEmpty())
                    throw LvText.refuse("treaty_no_army_second", "%s commands no Recruits faction, so a military agreement could not be enforced.", name(rt, state, b));
            }
            case CEDE -> {
                boolean any = state.bindings.values().stream().anyMatch(binding -> binding.status() == BindingStatus.BOUND && binding.occupied()
                        && (isParty(state, a, binding.realm()) && isParty(state, b, binding.recognizedRealm())
                        || isParty(state, b, binding.realm()) && isParty(state, a, binding.recognizedRealm())));
                if (!any) throw LvText.refuse("treaty_nothing_to_cede", "Neither party occupies a settlement the other is recognized for.");
            }
        }
    }

    private static boolean isParty(LvState state, PoliticalPartyRef party, UUID realm) {
        if (realm == null) return false;
        if (party instanceof PoliticalPartyRef.Realm r) return r.realmId().equals(realm);
        String kingdom = ((PoliticalPartyRef.Kingdom) party).kingdomId();
        return Optional.ofNullable(state.associations.get(realm)).filter(x -> x.kingdom().equals(kingdom)).isPresent();
    }

    /** The AI realm's answer from bounded facts; stored with its reason, never rerolled by asking again. */
    static String decideForAi(LvRuntime rt, UUID treatyId, PoliticalPartyRef aiParty) {
        var state = rt.data.snapshot();
        var treaty = state.treaties.get(treatyId);
        var ai = state.realms.get(((PoliticalPartyRef.Realm) aiParty).realmId());
        var other = party(treaty.first().equals(aiParty.format()) ? treaty.second() : treaty.first());
        var aiCapital = rt.access.village(rt.server, ai.capital()).orElse(null);
        Object otherCapital = otherCapital(rt, state, other);
        int opinion = aiCapital != null && otherCapital != null ? rt.access.opinion(rt.overworld(), aiCapital, otherCapital) : 0;
        int ours = strength(rt, state, aiParty), theirs = Math.max(1, strength(rt, state, other));
        var aiVillages = villages(state, aiParty);
        var otherVillages = villages(state, other);
        boolean atWar = state.conflicts.values().stream().anyMatch(c -> c.open() && (aiVillages.contains(c.attackerVillage()) && otherVillages.contains(c.defenderVillage())
                || otherVillages.contains(c.attackerVillage()) && aiVillages.contains(c.defenderVillage())));
        int losses = (int) state.conflicts.values().stream().filter(c -> rt.now() - c.updated() < 24000
                && (c.phase() == Phase.REPELLED && aiVillages.contains(c.attackerVillage()) || c.phase() == Phase.COMPLETED && aiVillages.contains(c.defenderVillage()))).count();
        boolean beneficiary = treaty.term() == Term.CEDE && state.bindings.values().stream()
                .anyMatch(b -> b.occupied() && ai.id().equals(b.realm()) && isParty(state, other, b.recognizedRealm()));
        var answer = LvPolicy.consider(new LvPolicy.Proposal(treaty.term(), atWar, opinion, (double) ours / theirs, losses, beneficiary,
                rt.access.number("REL_ALLY_SCORE", 60), rt.access.number("REL_ENEMY_SCORE", -60)));
        String decision = "AI " + (answer.accept() ? "accepted" : "refused") + ": " + answer.code() + " (opinion " + opinion + ", strength " + ours + ":" + theirs
                + (atWar ? ", at war" : "") + (losses > 0 ? ", " + losses + " recent loss(es)" : "") + ")";
        if (!answer.accept()) {
            state.treaties.put(treatyId, treaty.to(TreatyStatus.REJECTED, decision, rt.now()));
            state.record(EventKind.TREATY_REJECTED, rt.now(), null, ai.id(), realmOf(other), null, treaty.term() + ": " + answer.code(), true);
            rt.commit(state);
            return LvText.text("treaty_ai_refused", "%s refused the %s: %s.", ai.name(), treaty.term().name().toLowerCase(Locale.ROOT),
                    LvText.tr("ai." + answer.code(), answer.code().replace('_', ' ')).getString());
        }
        treaty = treaty.signed(aiParty.format(), new Signature(ActorKind.PROVIDER_REALM, ai.id(), rt.now(), answer.code()));
        state.treaties.put(treatyId, treaty.to(TreatyStatus.PROPOSED, decision, rt.now()));
        if (!rt.commit(state)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return apply(rt, treatyId);
    }

    private static Object otherCapital(LvRuntime rt, LvState state, PoliticalPartyRef party) {
        UUID village;
        if (party instanceof PoliticalPartyRef.Realm realm) village = Optional.ofNullable(state.realms.get(realm.realmId())).map(Realm::capital).orElse(null);
        else {
            String kingdom = ((PoliticalPartyRef.Kingdom) party).kingdomId();
            village = state.associations.values().stream().filter(a -> a.kingdom().equals(kingdom))
                    .map(a -> state.realms.get(a.realm())).filter(Objects::nonNull).map(Realm::capital).findFirst().orElse(null);
        }
        return village == null ? null : rt.access.village(rt.server, village).orElse(null);
    }

    private static int strength(LvRuntime rt, LvState state, PoliticalPartyRef party) {
        int total = 0;
        for (UUID village : villages(state, party)) total += rt.access.village(rt.server, village).map(rt.access::recruits).orElse(0);
        return total;
    }

    /** The counterpart's legitimate authority signs; a fully signed agreement is applied at once. */
    public static String sign(LvRuntime rt, ServerPlayer actor, UUID treatyId) {
        var state = rt.data.snapshot();
        var treaty = state.treaties.get(treatyId);
        if (treaty == null || treaty.status() != TreatyStatus.PROPOSED) throw LvText.refuse("treaty_not_open", "That agreement is not open for signature.");
        String side = null;
        for (String party : List.of(treaty.first(), treaty.second()))
            if (!treaty.signatures().containsKey(party) && authority(rt, state, actor, party(party))) side = party;
        if (side == null) throw LvText.refuse("treaty_not_signatory", "You do not speak for a party that still has to sign.");
        state.treaties.put(treatyId, treaty.signed(side, new Signature(ActorKind.PLAYER, actor.getUUID(), rt.now(), "signed")));
        if (!rt.commit(state)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return apply(rt, treatyId);
    }

    public static String reject(LvRuntime rt, ServerPlayer actor, UUID treatyId) {
        var state = rt.data.snapshot();
        var treaty = state.treaties.get(treatyId);
        if (treaty == null || treaty.status() != TreatyStatus.PROPOSED) throw LvText.refuse("treaty_not_open", "That agreement is not open for signature.");
        boolean speaks = authority(rt, state, actor, party(treaty.first())) || authority(rt, state, actor, party(treaty.second()));
        if (!speaks) throw LvText.refuse("treaty_not_signatory", "You do not speak for a party that still has to sign.");
        state.treaties.put(treatyId, treaty.to(TreatyStatus.REJECTED, "Rejected by " + actor.getName().getString(), rt.now()));
        state.record(EventKind.TREATY_REJECTED, rt.now(), null, realmOf(party(treaty.first())), realmOf(party(treaty.second())), null, treaty.term().name(), true);
        rt.commit(state);
        return LvText.text("treaty_rejected", "The agreement was rejected; native relations were not changed.");
    }

    /** Ends an agreement early on behalf of one party. Peace and ceasefire leave their truce in place. */
    public static String end(LvRuntime rt, ServerPlayer actor, UUID treatyId) {
        var state = rt.data.snapshot();
        var treaty = state.treaties.get(treatyId);
        if (treaty == null || !treaty.inForce(rt.now())) throw LvText.refuse("treaty_not_active", "That agreement is not in force.");
        boolean speaks = authority(rt, state, actor, party(treaty.first())) || authority(rt, state, actor, party(treaty.second()));
        if (!speaks) throw LvText.refuse("treaty_not_signatory", "You do not speak for a party that still has to sign.");
        state.treaties.put(treatyId, treaty.to(TreatyStatus.EXPIRED, "Ended by " + actor.getName().getString(), rt.now()));
        state.record(EventKind.TREATY_EXPIRED, rt.now(), null, realmOf(party(treaty.first())), realmOf(party(treaty.second())), null,
                treaty.term() + " ended early", true);
        rt.commit(state);
        return LvText.text("treaty_ended", "The agreement has ended. Any truce it set still runs its course.");
    }

    /**
     * Puts a fully signed agreement into practice: peace and ceasefire end every war between the parties through the provider
     * and set both native directions to neutral; an alliance sets both to ally; a cession records the controller as the
     * recognized sovereign of the covered settlements. The agreement is reported in force only after the native state reads
     * back as agreed.
     */
    static String apply(LvRuntime rt, UUID treatyId) {
        var next = rt.data.snapshot();
        var treaty = next.treaties.get(treatyId);
        if (treaty == null || treaty.signatures().size() < 2) return LvText.text("treaty_waiting", "The agreement is waiting for the other party's signature.");
        var a = party(treaty.first());
        var b = party(treaty.second());
        long now = rt.now();
        switch (treaty.term()) {
            case PEACE, CEASEFIRE, ALLIANCE -> {
                var teamsA = teams(rt, next, a);
                var teamsB = teams(rt, next, b);
                if (teamsA.isEmpty() || teamsB.isEmpty()) {
                    next.treaties.put(treatyId, treaty.to(TreatyStatus.SUSPENDED, "A party no longer commands a Recruits faction", now));
                    rt.commit(next);
                    return LvText.text("treaty_unenforceable", "The agreement could not be applied: a party no longer commands a Recruits faction.");
                }
                if (treaty.term() != Term.ALLIANCE) LvWarBridge.endBetween(rt, next, villages(next, a), villages(next, b), "treaty");
                int status = treaty.term() == Term.ALLIANCE ? LvPolicy.ALLY : LvPolicy.NEUTRAL;
                boolean confirmed = true;
                ownWrite = true;
                try {
                    for (String x : teamsA) for (String y : teamsB) {
                        rt.access.writeRelation(rt.overworld(), x, y, status);
                        rt.access.writeRelation(rt.overworld(), y, x, status);
                        confirmed &= rt.access.readRelation(x, y) == status && rt.access.readRelation(y, x) == status;
                    }
                } finally {
                    ownWrite = false;
                }
                if (!confirmed) {
                    next.treaties.put(treatyId, treaty.to(TreatyStatus.SUSPENDED, "Recruits did not keep the relation change", now));
                    rt.commit(next);
                    return LvText.text("treaty_native_refused", "Recruits did not keep the relation change, so the agreement is not in force.");
                }
                long truce = treaty.term() == Term.ALLIANCE ? 0 : now + Math.max(LvConfig.truceTicks(), 0);
                next.treaties.put(treatyId, new Treaty(treaty.id(), treaty.first(), treaty.second(), treaty.term(), TreatyStatus.ACTIVE, treaty.signatures(),
                        treaty.covered(), treaty.proposed(), now, treaty.expires(), Math.max(truce, treaty.expires()), treaty.reason(), treaty.decision(), 0, ""));
            }
            case CEDE -> {
                int ceded = 0;
                for (var binding : List.copyOf(next.bindings.values())) {
                    if (binding.status() != BindingStatus.BOUND || !binding.occupied()) continue;
                    boolean covered = isParty(next, a, binding.realm()) && isParty(next, b, binding.recognizedRealm())
                            || isParty(next, b, binding.realm()) && isParty(next, a, binding.recognizedRealm());
                    if (!covered) continue;
                    next.bindings.put(binding.village(), binding.recognize(binding.realm()));
                    next.record(EventKind.SOVEREIGNTY_CEDED, now, binding.settlement(), binding.realm(), binding.recognizedRealm(), null, binding.providerName(), true);
                    ceded++;
                }
                next.treaties.put(treatyId, treaty.to(TreatyStatus.ACTIVE, treaty.decision() + "; " + ceded + " settlement(s) ceded", now));
            }
        }
        next.record(EventKind.TREATY_ACTIVE, now, null, realmOf(a), realmOf(b), null, treaty.term().name(), true);
        UUID op = UUID.randomUUID();
        next.journal.put(op, new Operation(op, OperationKind.PEACE, treatyId, null, OperationState.APPLIED, "", treaty.term().name(), now, now, 0,
                false, treaty.first() + " / " + treaty.second(), ActorKind.PLAYER, null));
        if (!rt.commit(next)) return LvText.text("treaty_save_failed", "The agreement was applied in Recruits but could not be recorded; an operator should inspect it.");
        rt.awaitingSave.add(op);
        return LvText.text("treaty_active", "In force: %s between %s and %s.", treaty.term().name().toLowerCase(Locale.ROOT),
                name(rt, rt.view(), a), name(rt, rt.view(), b));
    }

    // ---- arbitration -----------------------------------------------------------------------------------------------------------

    /**
     * Living Villages proposes a relation between two capitals. Returns false to suppress it: an agreement in force between their
     * realms forbids it. The provider retries at its own cadence; the bridge never writes back on every tick.
     */
    static boolean arbitrate(LvRuntime rt, Object capitalA, Object capitalB, int proposed) {
        if (ownWrite || !LvConfig.arbitration()) return true;
        var state = rt.view();
        UUID a = rt.access.id(capitalA), b = rt.access.id(capitalB);
        var treaties = LvWarBridge.treatiesBetween(rt, state, a, b);
        if (treaties.isEmpty()) {
            if (proposed != LvPolicy.NEUTRAL || rt.access.relation(capitalA, capitalB) != LvPolicy.NEUTRAL) {
                var next = rt.data.snapshot();
                next.record(EventKind.RELATION_CHANGED, rt.now(), null, state.realmOfVillage(a).map(Realm::id).orElse(null),
                        state.realmOfVillage(b).map(Realm::id).orElse(null), null, rt.access.name(capitalA) + " / " + rt.access.name(capitalB) + ": " + proposed, false);
                rt.data.markChanged(next);
            }
            return true;
        }
        var terms = treaties.stream().map(Treaty::term).toList();
        if (LvPolicy.permits(terms, proposed)) return true;
        var next = rt.data.snapshot();
        for (var treaty : treaties) next.treaties.put(treaty.id(), next.treaties.get(treaty.id()).suppressedOnce());
        rt.data.markChanged(next);
        rt.once("suppressed:" + a + ":" + b, "Living Villages' relation change between {} and {} was held back by an agreement in force",
                rt.access.name(capitalA), rt.access.name(capitalB));
        return false;
    }

    // ---- upkeep -------------------------------------------------------------------------------------------------------------

    /** Expiry, breach detection and stale proposals. A breach is recorded and announced; nothing is written back automatically. */
    static void maintain(LvRuntime rt) {
        var state = rt.view();
        long now = rt.now();
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var treaty : List.copyOf(state.treaties.values())) {
            if (treaty.status() == TreatyStatus.PROPOSED && now >= treaty.expires()) {
                next.treaties.put(treaty.id(), treaty.to(TreatyStatus.EXPIRED, "Not signed in time", now));
                changed = true;
                continue;
            }
            if (treaty.status() != TreatyStatus.ACTIVE) continue;
            if (treaty.expires() > 0 && now >= treaty.expires()) {
                next.treaties.put(treaty.id(), treaty.to(TreatyStatus.EXPIRED, "Its term ended", now));
                next.record(EventKind.TREATY_EXPIRED, now, null, realmOf(party(treaty.first())), realmOf(party(treaty.second())), null, treaty.term().name(), true);
                changed = true;
                continue;
            }
            if (treaty.term() == Term.CEDE) continue;
            var teamsA = teams(rt, state, party(treaty.first()));
            var teamsB = teams(rt, state, party(treaty.second()));
            for (String x : teamsA) for (String y : teamsB) {
                int forward = rt.access.readRelation(x, y), backward = rt.access.readRelation(y, x);
                boolean breached = treaty.term() == Term.ALLIANCE ? forward == LvPolicy.ENEMY || backward == LvPolicy.ENEMY
                        : forward == LvPolicy.ENEMY || backward == LvPolicy.ENEMY;
                if (!breached || next.treaties.get(treaty.id()).status() != TreatyStatus.ACTIVE) continue;
                String who = forward == LvPolicy.ENEMY ? x : y;
                next.treaties.put(treaty.id(), next.treaties.get(treaty.id()).breached("Recruits relation set to enemy by " + who, now));
                next.record(EventKind.TREATY_BREACHED, now, null, realmOf(party(treaty.first())), realmOf(party(treaty.second())), null,
                        treaty.term() + " breached by " + who, true);
                changed = true;
            }
        }
        if (changed) rt.commit(next);
    }

    /** An operator's explicit association of a kingdom government with a realm; never inferred from names or culture. */
    public static String associate(LvRuntime rt, UUID realm, String kingdom, String scope, UUID operator) {
        var next = rt.data.snapshot();
        var r = next.realms.get(realm);
        if (r == null) throw LvText.refuse("realm_unknown", "That realm is not known.");
        if (rt.kingdoms().getKingdom(net.minecraft.resources.ResourceLocation.tryParse(kingdom)).filter(k -> k.defined()).isEmpty())
            throw LvText.refuse("kingdom_unknown", "That kingdom is not defined.");
        if (!Set.of("DIPLOMACY", "DIPLOMACY_AND_WAR").contains(scope)) throw LvText.refuse("scope_unknown", "Scope must be DIPLOMACY or DIPLOMACY_AND_WAR.");
        long revision = Optional.ofNullable(next.associations.get(realm)).map(a -> a.revision() + 1).orElse(1L);
        next.associations.put(realm, new Association(realm, kingdom, scope, operator, rt.now(), revision));
        next.record(EventKind.RECOVERY, rt.now(), null, realm, null, null, "Associated with " + kingdom + " (" + scope + ")", false);
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return LvText.text("associated", "%s is now represented by the government of %s for %s.", r.name(),
                dev.otectus.mcakingdoms.interaction.Names.kingdomName(rt.server, kingdom), scope.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    public static String dissociate(LvRuntime rt, UUID realm) {
        var next = rt.data.snapshot();
        if (next.associations.remove(realm) == null) throw LvText.refuse("not_associated", "That realm has no association.");
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return LvText.text("dissociated", "The association was removed; agreements already signed stay as they are.");
    }

    /** Legacy Recruits faction mappings of a kingdom (warfare control mappings), read without writing. */
    static final class LegacyMappings {
        private LegacyMappings() {
        }

        static Set<String> teams(LvRuntime rt, String kingdom) {
            var out = new LinkedHashSet<String>();
            try {
                for (var mapping : dev.otectus.mcakingdoms.warfare.WarfareRuntime.get(rt.server).mappings())
                    if (mapping.kingdom().equals(kingdom)) out.add(mapping.nativeFaction());
            } catch (RuntimeException unavailable) {
                // No legacy warfare records on this server.
            }
            return out;
        }
    }
}
