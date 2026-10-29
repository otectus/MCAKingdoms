package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import dev.otectus.mcakingdoms.interaction.Names;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * What players and operators read about Living Villages villages, in their own language. Players see what they are entitled
 * to know (a settlement they know; garrison and treasury only for its own realm's members); raw ids, fingerprints and adapter
 * details are for operators.
 */
public final class LvPresentation {
    private LvPresentation() {
    }

    static Component t(String key, String english, Object... args) {
        return LvText.tr(key, english, args);
    }

    static String realmName(LvState state, UUID realm) {
        return realm == null ? "—" : Optional.ofNullable(state.realms.get(realm)).map(Realm::name).orElse("—");
    }

    static boolean member(LvRuntime rt, ServerPlayer viewer, UUID realm) {
        if (viewer.hasPermissions(2)) return true;
        if (realm == null) return false;
        var r = rt.view().realms.get(realm);
        if (r == null) return false;
        if (viewer.getUUID().equals(r.owner())) return true;
        return viewer.getTeam() != null && r.teams().contains(viewer.getTeam().getName());
    }

    static Component phase(Phase phase) {
        return t("phase." + phase.name().toLowerCase(Locale.ROOT), phase.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    // ---- settlement page ------------------------------------------------------------------------------------------------

    public static List<Component> settlement(LvRuntime rt, ServerPlayer viewer, UUID settlementId) {
        var out = new ArrayList<Component>();
        var settlement = rt.kingdoms().getSettlement(settlementId).orElse(null);
        if (settlement == null || !SettlementKnowledge.get(rt.server).visible(viewer, settlement.id())) {
            out.add(t("page.unknown", "You have not discovered that settlement."));
            return out;
        }
        var state = rt.view();
        var binding = state.bindingForSettlement(settlement.id()).orElse(null);
        if (binding == null) {
            out.add(t("page.not_village", "%s is not a Living Villages village.", settlement.displayName()));
            return out;
        }
        out.add(t("page.title", "%s — %s", settlement.displayName(), Names.kingdomName(rt.server, settlement.kingdomId().toString())));
        out.add(t("page.culture", "Kingdom (who its people are): %s. War does not change it.", Names.kingdomName(rt.server, settlement.kingdomId().toString())));
        if (!binding.providerName().equals(settlement.displayName()) && !binding.providerName().isBlank())
            out.add(t("page.provider_name", "Living Villages calls it %s.", binding.providerName()));
        out.add(t("page.controller", "Held by the realm of %s.", realmName(state, binding.realm())));
        if (binding.occupied())
            out.add(t("page.occupied", "Occupied: %s's government is still the recognized one until a cession is agreed.", realmName(state, binding.recognizedRealm())));
        var provider = rt.access.village(rt.server, binding.village()).orElse(null);
        if (provider == null) {
            out.add(t("page.provider_gone", "Living Villages no longer lists this village; its history is kept."));
            return out;
        }
        out.add(rt.access.isCapital(provider) ? t("page.capital", "It is its realm's capital.")
                : t("page.subordinate", "It answers to %s.", rt.access.name(rt.access.capitalOf(rt.server, provider))));
        out.add(t("page.population", "%s people, %s beds; %s working, %s idle.", rt.access.population(provider), rt.access.beds(provider),
                rt.access.workers(provider), rt.access.jobless(provider)));
        var census = LvResidents.census(rt, binding.village());
        int kept = census.getOrDefault(Lifecycle.MATERIALIZED, 0) + census.getOrDefault(Lifecycle.UNLOADED, 0) + census.getOrDefault(Lifecycle.EXTERNAL, 0);
        if (kept > 0) out.add(t("page.residents", "%s residents are kept as the same people (%s away or unloaded right now).", kept,
                census.getOrDefault(Lifecycle.UNLOADED, 0) + census.getOrDefault(Lifecycle.AWAY, 0)));
        if (member(rt, viewer, binding.realm())) {
            out.add(t("page.treasury", "Treasury: %s %s; food %s.", rt.access.treasury(provider), LvEconomy.currencyName(rt), rt.access.food(provider)));
            out.add(t("page.garrison", "Garrison: %s soldiers (room for %s).", rt.access.recruits(provider), rt.access.garrisonCap(provider)));
        }
        var conflict = LvWarBridge.openFor(state, binding.village()).orElse(null);
        if (conflict != null) out.add(t("page.war", "War: %s against %s — %s.", realmName(state, conflict.attackerRealm()),
                realmName(state, conflict.defenderRealm()), phase(conflict.phase())));
        else if (rt.access.besieged(binding.village())) out.add(t("page.besieged", "Under siege."));
        else out.add(t("page.peace", "At peace."));
        for (var treaty : state.treaties.values())
            if (treaty.inForce(rt.now()) && treaty.covered().contains(binding.village()))
                out.add(t("page.treaty", "Agreement in force: %s.", treaty.term().name().toLowerCase(Locale.ROOT)));
        if (binding.adopted()) out.add(t("page.adopted", "Placed under Living Villages management on purpose; its buildings are kept as they were."));
        return out;
    }

    // ---- realm page ------------------------------------------------------------------------------------------------------

    public static List<Component> realm(LvRuntime rt, ServerPlayer viewer, UUID realmId) {
        var out = new ArrayList<Component>();
        var state = rt.view();
        var realm = state.realms.get(realmId);
        if (realm == null) { out.add(t("realm.unknown", "That realm is not known.")); return out; }
        out.add(t("realm.title", "The realm of %s — %s", realm.name(), t("realm.status." + realm.status().name().toLowerCase(Locale.ROOT), realm.status().name().toLowerCase(Locale.ROOT))));
        out.add(realm.leadership() == Leadership.AI ? t("realm.ai", "Ruled by its own leaders (Living Villages AI).")
                : t("realm.player", "Led by %s (its Recruits faction leader).", Names.player(rt.server, realm.owner())));
        var capital = realm.capital() == null ? null : rt.access.village(rt.server, realm.capital()).orElse(null);
        if (capital != null) {
            var leader = rt.access.leaderRef(capital);
            var unit = leader == null ? null : rt.access.roster(capital).stream().filter(u -> leader.equals(rt.access.unitId(u))).findFirst().orElse(null);
            if (unit != null) out.add(t("realm.commander", "Military commander: a level %s %s at %s. Commanders and civic office holders are different people.",
                    rt.access.unitLevel(unit), rt.access.unitType(unit).replace("recruits:", ""), realm.name()));
        }
        var association = state.associations.get(realmId);
        if (association != null) out.add(t("realm.associated", "Represented by the government of %s (%s).", Names.kingdomName(rt.server, association.kingdom()),
                association.scope().toLowerCase(Locale.ROOT).replace('_', ' ')));
        var known = new ArrayList<String>();
        for (UUID village : realm.members()) {
            var settlement = LvBindings.settlementOf(state, village);
            if (settlement != null && SettlementKnowledge.get(rt.server).visible(viewer, settlement)) known.add(Names.settlement(rt.server, settlement));
        }
        out.add(t("realm.villages", "%s villages; known to you: %s.", realm.members().size(), known.isEmpty() ? "—" : String.join(", ", known)));
        for (var treaty : state.treaties.values()) {
            if (!treaty.inForce(rt.now()) || !(treaty.first().equals("realm:" + realmId) || treaty.second().equals("realm:" + realmId))) continue;
            String other = treaty.first().equals("realm:" + realmId) ? treaty.second() : treaty.first();
            out.add(t("realm.treaty", "%s with %s, until %s.", treaty.term().name().toLowerCase(Locale.ROOT),
                    LvDiplomacy.name(rt, state, LvDiplomacy.party(other)), treaty.expires() <= 0 ? "—" : Names.remaining(rt.server, treaty.expires())));
        }
        state.conflicts.values().stream().filter(c -> c.open() && (realmId.equals(c.attackerRealm()) || realmId.equals(c.defenderRealm()))).limit(4)
                .forEach(c -> out.add(t("realm.war", "War: %s against %s — %s.", realmName(state, c.attackerRealm()), realmName(state, c.defenderRealm()), phase(c.phase()))));
        state.history.stream().filter(e -> realmId.equals(e.realm()) || realmId.equals(e.otherRealm())).skip(Math.max(0,
                        state.history.stream().filter(e -> realmId.equals(e.realm()) || realmId.equals(e.otherRealm())).count() - 6))
                .forEach(e -> out.add(t("realm.history", "Day %s: %s", e.gameTime() / 24000 + 1, LvNotices.line(rt, state, e))));
        return out;
    }

    /** The realm a player belongs to (their team's), or of the village they stand in. */
    public static Optional<UUID> realmFor(LvRuntime rt, ServerPlayer player) {
        var state = rt.view();
        var own = rt.access.playerRealm(player).flatMap(v -> state.realmOfVillage(rt.access.id(v))).map(Realm::id);
        if (own.isPresent()) return own;
        return rt.kingdoms().getSettlementAt(player.serverLevel(), player.blockPosition())
                .flatMap(s -> state.bindingForSettlement(s.id())).map(Binding::realm);
    }

    // ---- War Room ----------------------------------------------------------------------------------------------------------

    public static List<Component> warRoom(MinecraftServer server, ServerPlayer viewer, UUID settlement) {
        var rt = LvRuntime.get(server).orElse(null);
        if (rt == null) return List.of();
        var state = rt.view();
        var binding = state.bindingForSettlement(settlement).orElse(null);
        if (binding == null) return List.of();
        var out = new ArrayList<Component>();
        out.add(t("room.controller", "Living Villages realm in control: %s.", realmName(state, binding.realm())));
        if (binding.occupied()) out.add(t("room.recognized", "Recognized government: %s (occupied).", realmName(state, binding.recognizedRealm())));
        state.conflicts.values().stream().filter(c -> c.open() && (c.defenderVillage().equals(binding.village()) || c.attackers().contains(binding.village())))
                .forEach(c -> out.add(t("room.conflict", "%s attacks %s — %s, fought %s.", realmName(state, c.attackerRealm()), realmName(state, c.defenderRealm()),
                        phase(c.phase()), c.path() == Path.LIVE ? t("room.live", "in the world") : t("room.abstract", "out of view"))));
        state.conflicts.values().stream().filter(c -> !c.open() && c.defenderVillage().equals(binding.village())).reduce((a, b) -> b)
                .ifPresent(c -> out.add(t("room.last", "Last war here: %s.", phase(c.phase()))));
        if (out.size() == 1) out.add(t("room.quiet", "No Living Villages war involves this village."));
        out.add(t("room.actions", "War and peace between realms: Kingdom Tasks › Conflict › War and peace with a realm."));
        return out;
    }

    // ---- operator pages ----------------------------------------------------------------------------------------------------

    public static List<Component> status(MinecraftServer server) {
        var out = new ArrayList<Component>();
        var snapshot = LvCompat.snapshot(server);
        out.add(t("status.tuple", "Installed: %s · audited: Recruits %s with Living Villages %s · overall %s", snapshot.tuple(),
                LvCompat.RECRUITS_VERSION, LvCompat.AUDITED_VERSION, snapshot.overall()));
        out.add(t("status.gate", "Hooks: %s%s", snapshot.gate(), snapshot.shape().isEmpty() ? "" : " · shape: " + snapshot.shape()));
        for (var status : snapshot.capabilities())
            out.add(t("status.capability", "• %s: %s %s", status.label(), status.state(), status.why()));
        var rt = LvRuntime.get(server).orElse(null);
        var data = LvBridgeSavedData.get(server);
        var state = data.view();
        var counts = new EnumMap<BindingStatus, Integer>(BindingStatus.class);
        state.bindings.values().forEach(b -> counts.merge(b.status(), 1, Integer::sum));
        out.add(t("status.bindings", "Villages: %s bound, %s pending, %s ambiguous, %s realm-only, %s unsupported, %s gone from the provider.",
                counts.getOrDefault(BindingStatus.BOUND, 0), counts.getOrDefault(BindingStatus.PENDING, 0), counts.getOrDefault(BindingStatus.AMBIGUOUS, 0),
                counts.getOrDefault(BindingStatus.VIRTUAL, 0), counts.getOrDefault(BindingStatus.UNSUPPORTED_DIMENSION, 0), counts.getOrDefault(BindingStatus.PROVIDER_REMOVED, 0)));
        long realms = state.realms.values().stream().filter(r -> r.status() == RealmStatus.ACTIVE).count();
        long open = state.conflicts.values().stream().filter(Conflict::open).count();
        long active = state.treaties.values().stream().filter(t -> t.status() == TreatyStatus.ACTIVE).count();
        out.add(t("status.records", "Realms %s active · conflicts %s open · agreements %s in force · residents %s · journal %s.", realms, open, active,
                state.residents.size(), state.journal.size()));
        var m = state.migration;
        out.add(m.complete ? t("status.migration_done", "Existing-world import complete: %s imported, %s matched to existing settlements, %s pending, %s ambiguous.",
                m.imported, m.preserved, m.pending, m.ambiguous)
                : t("status.migration_running", "Existing-world import running (batch %s per %s ticks).", LvConfig.batch(), LvConfig.batchInterval()));
        if (rt != null) out.add(t("status.queues", "Queues: %s changed villages, %s operations waiting for a save, %s resident updates.",
                rt.changed.size(), rt.awaitingSave.size(), rt.residentUpdates.size()));
        if (state.provenance.regressedAt > 0) out.add(t("status.regressed", "Living Villages data went back in time on day %s (restored backup?); conquests are not re-applied. Clear with /kingdoms livingvillages recover provider.",
                state.provenance.regressedAt / 24000 + 1));
        if (!data.writable()) out.add(Component.literal(data.diagnostic()));
        out.add(t("status.policy", "Policy: AI wars %s · player targets %s · AI capitals %s · Kingdoms capitals %s · treaties by AI %s · arbitration %s · residents kept %s · MCA residents %s · truce %s ticks.",
                LvConfig.aiWars(), LvConfig.playerTargets(), LvConfig.capitals(), LvConfig.respectCapitals(), LvConfig.aiTreaties(), LvConfig.arbitration(),
                LvConfig.persistentResidents(), LvConfig.mcaResidents(), LvConfig.truceTicks()));
        LvAccess.get().ifPresent(access -> out.add(t("status.native", "Living Villages: AI war %s (needs enemy %s) · relations %s · currency %s · siege trigger %s recruits · siege without owner %s · nobles off %s · adopts undeclared sieges %s · civilians %s.",
                access.flag("AI_WAR_ENABLED"), access.flag("AI_WAR_REQUIRES_ENEMY"), access.flag("REL_ENABLED"), access.setting("CURRENCY_ITEM", "?"),
                access.number("SIEGE_TRIGGER_RECRUITS", 0), access.flag("SIEGE_ALLOW_NO_OWNER"), access.flag("DISABLE_NOBLES"),
                access.flag("ADOPT_UNDECLARED_SIEGES"), access.setting("CIVILIAN_ENTITY_TYPE", "?"))));
        return out;
    }

    public static List<Component> inspect(LvRuntime rt, UUID settlementId) {
        var out = new ArrayList<Component>();
        var state = rt.view();
        var binding = state.bindingForSettlement(settlementId)
                .or(() -> state.bindings.values().stream().filter(b -> settlementId.equals(b.settlement()) || b.candidates().contains(settlementId)).findFirst()).orElse(null);
        if (binding == null) { out.add(t("inspect.none", "No Living Villages village is bound to or waiting on that settlement.")); return out; }
        out.add(t("inspect.binding", "Village %s · %s (%s) · settlement %s", binding.village(), binding.status(), binding.reason(), binding.settlement()));
        out.add(t("inspect.names", "Provider name %s; earlier names %s.", binding.providerName(), binding.aliases().isEmpty() ? "—" : String.join(", ", binding.aliases())));
        out.add(t("inspect.claim", "Claim %s; earlier claims %s.", binding.claim(), binding.retiredClaims().isEmpty() ? "—" : binding.retiredClaims()));
        out.add(t("inspect.realm", "Realm %s (%s); recognized %s.", binding.realm(), realmName(state, binding.realm()), binding.recognizedRealm()));
        if (!binding.candidates().isEmpty()) for (UUID candidate : binding.candidates())
            out.add(t("inspect.candidate", "Candidate settlement: %s (%s) — bind with /kingdoms livingvillages bind %s %s, or keep it separate with /kingdoms livingvillages separate %s.",
                    Names.settlement(rt.server, candidate), candidate, binding.village(), candidate, binding.village()));
        out.add(t("inspect.provenance", "Provider %s %s (sha256 %s…), world %s.", state.provenance.provider, state.provenance.version,
                state.provenance.sha256.length() > 12 ? state.provenance.sha256.substring(0, 12) : state.provenance.sha256, state.provenance.world));
        state.conflicts.values().stream().filter(c -> c.defenderVillage().equals(binding.village()) || c.attackerVillage().equals(binding.village()))
                .reduce((a, b) -> b).ifPresent(c -> out.add(t("inspect.conflict", "Latest conflict %s: %s via %s, %s.", c.id(), c.phase(), c.path(), c.reason())));
        state.refusals.stream().filter(r -> binding.village().equals(r.defender())).reduce((a, b) -> b)
                .ifPresent(r -> out.add(t("inspect.refusal", "Last refused attack on it: %s (day %s).", r.reason(), r.at() / 24000 + 1)));
        state.journal.values().stream().filter(o -> settlementId.equals(o.subject()) || binding.village().equals(o.subject())).reduce((a, b) -> b)
                .ifPresent(o -> out.add(t("inspect.operation", "Latest operation %s: %s %s.", o.id(), o.kind(), o.state())));
        return out;
    }

    public static List<Component> conflicts(LvRuntime rt) {
        var out = new ArrayList<Component>();
        var state = rt.view();
        state.bindings.values().stream().filter(b -> b.status() == BindingStatus.AMBIGUOUS || b.status() == BindingStatus.PENDING).limit(16)
                .forEach(b -> out.add(t("conflicts.binding", "%s %s: %s (%s candidates).", b.status(), b.providerName(), b.reason(), b.candidates().size())));
        state.conflicts.values().stream().filter(c -> c.phase() == Phase.RECOVERY_REQUIRED || c.phase() == Phase.SUSPENDED).limit(16)
                .forEach(c -> out.add(t("conflicts.war", "War %s needs recovery: %s. Resolve with /kingdoms livingvillages recover %s.", c.id(), c.reason(), c.id())));
        state.journal.values().stream().filter(o -> o.state() == OperationState.SUSPENDED || o.state() == OperationState.INTENT && rt.now() - o.created() > 1200).limit(16)
                .forEach(o -> out.add(t("conflicts.operation", "Operation %s (%s) is %s. Resolve with /kingdoms livingvillages recover %s.", o.id(), o.kind(), o.state(), o.id())));
        if (out.isEmpty()) out.add(t("conflicts.none", "Nothing waits for an operator."));
        return out;
    }

    public static List<Component> preview(LvRuntime rt) {
        var out = new ArrayList<Component>();
        var state = rt.view();
        int create = 0, attach = 0, ambiguous = 0, virtual = 0;
        for (var village : LvSnapshots.villages(rt.server, rt.access)) {
            var binding = state.bindings.get(village.id());
            if (binding != null && binding.status() == BindingStatus.BOUND) continue;
            if (village.virtual()) { virtual++; continue; }
            var existing = rt.kingdoms().getSettlementByExternalRef(LvBindings.EXTERNAL_REF, village.id().toString());
            if (existing.isPresent()) { attach++; continue; }
            int candidates = LvBindings.candidates(rt, state, village).size();
            if (candidates > 1) { ambiguous++; out.add(t("preview.ambiguous", "Decide: %s has %s possible settlements.", village.name(), candidates)); }
            else if (candidates == 1) attach++;
            else create++;
        }
        out.add(0, t("preview.summary", "Import plan: %s new settlements, %s matched to existing ones, %s need a decision, %s player factions stay realm-only. Nothing is spawned, built or rewarded.",
                create, attach, ambiguous, virtual));
        long unprotected = state.bindings.values().stream().filter(b -> b.status() == BindingStatus.BOUND).count();
        if (!LvCompat.snapshot(rt.server).ready(LvCompat.Capability.RESIDENT_PRESERVATION) && unprotected > 0)
            out.add(t("preview.unprotected", "Resident protection is not active: %s bound villages' residents could be replaced by Living Villages.", unprotected));
        return out;
    }
}
