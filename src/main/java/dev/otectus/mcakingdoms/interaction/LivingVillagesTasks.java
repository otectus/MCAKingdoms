package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.compat.livingvillages.*;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.network.chat.Component;

import java.util.*;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for Recruits: Living Villages villages: reading a village and its realm, opening the native village screen,
 * joining an army, supplying a village, asking why growth is paused, war and peace between realms, and the operator's
 * diagnostics and repairs. Each task calls the bridge service, which decides; the forms only offer what the service allows.
 */
final class LivingVillagesTasks {
    private static final Constraint AMOUNT = Constraint.range(1, 2304, "", 64);
    private static final Constraint DAYS = Constraint.range(1, 100, "", 3);

    private LivingVillagesTasks() {
    }

    private static LvRuntime rt(Context c) {
        return LvRuntime.get(c.server()).orElseThrow(() -> LvText.refuse("inactive_short", "The Recruits: Living Villages integration is not active on this server."));
    }

    private static String version(Context c) {
        return LvRuntime.get(c.server()).map(rt -> "" + LvBridgeSavedData.get(c.server()).revision()).orElse("inactive");
    }

    private static void task(String id, String title, String help, boolean operator, boolean change, List<Field> fields, Handler handler) {
        add(new Task(id, "Living Villages", title, help, operator, change, fields, LivingVillagesTasks::version, handler));
    }

    private static Optional<UUID> here(Context c) {
        return McaKingdomsApi.get(c.server()).getSettlementAt(c.player.serverLevel(), c.player.blockPosition()).map(s -> s.id());
    }

    static void init() {
        targets("lv_settlement", c -> {
            var rt = LvRuntime.get(c.server()).orElse(null);
            if (rt == null) return List.of();
            var out = new ArrayList<Choice>();
            var knowledge = SettlementKnowledge.get(c.server());
            for (var binding : LvBridgeSavedData.get(c.server()).snapshot().bindings.values()) {
                if (binding.status() != LvState.BindingStatus.BOUND || binding.settlement() == null || !knowledge.visible(c.player, binding.settlement())) continue;
                out.add(Choice.of(binding.settlement().toString(), Component.literal(BasicTargets.settlement(c, binding.settlement())),
                        LvText.tr("choice.village", "Living Villages village of %s", Optional.ofNullable(LvBridgeSavedData.get(c.server()).snapshot().realms.get(binding.realm()))
                                .map(LvState.Realm::name).orElse("—"))));
            }
            out.sort(Comparator.comparing(Choice::label));
            return out;
        });
        targetHint("lv_settlement", LvText.tr("hint.settlement", "No Living Villages village you know of yet. Visit one, or check that the integration is active."));
        targets("lv_realm", c -> {
            var out = new ArrayList<Choice>();
            for (var realm : LvBridgeSavedData.get(c.server()).snapshot().realms.values()) {
                if (realm.status() != LvState.RealmStatus.ACTIVE) continue;
                out.add(Choice.of(realm.id().toString(), Component.literal(realm.name()), realm.leadership() == LvState.Leadership.AI
                        ? LvText.tr("choice.realm_ai", "AI realm · %s villages", realm.members().size())
                        : LvText.tr("choice.realm_player", "Led by %s · %s villages", Names.player(c.server(), realm.owner()), realm.members().size())));
            }
            out.sort(Comparator.comparing(Choice::label));
            return out;
        });
        targetHint("lv_realm", LvText.tr("hint.realm", "No Living Villages realm is known yet."));
        targets("lv_party", c -> {
            var rt = LvRuntime.get(c.server()).orElse(null);
            if (rt == null) return List.of();
            var out = new ArrayList<Choice>();
            var state = LvBridgeSavedData.get(c.server()).snapshot();
            for (var realm : state.realms.values())
                if (realm.status() == LvState.RealmStatus.ACTIVE && (c.player.getUUID().equals(realm.owner()) || state.associations.containsKey(realm.id())))
                    out.add(Choice.of("realm:" + realm.id(), Component.literal(realm.name()), LvText.tr("choice.party_realm", "Your realm")));
            for (var kingdom : McaKingdomsApi.get(c.server()).getKingdoms())
                if (kingdom.defined() && dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(c.server()).authorized(c.player, kingdom.id().toString(),
                        dev.otectus.mcakingdoms.api.politics.Politics.Permission.RATIFY))
                    out.add(Choice.of("kingdom:" + kingdom.id(), Names.kingdomName(c.server(), kingdom.id().toString()), LvText.tr("choice.party_kingdom", "Your kingdom's government")));
            return out;
        });
        targetHint("lv_party", LvText.tr("hint.party", "You speak for no realm and hold no ratifying office, so you cannot make agreements."));
        targets("lv_counterpart", c -> {
            var out = new ArrayList<Choice>();
            var state = LvBridgeSavedData.get(c.server()).snapshot();
            for (var realm : state.realms.values())
                if (realm.status() == LvState.RealmStatus.ACTIVE)
                    out.add(Choice.of("realm:" + realm.id(), Component.literal(realm.name()), realm.leadership() == LvState.Leadership.AI
                            ? LvText.tr("choice.realm_ai", "AI realm · %s villages", realm.members().size())
                            : LvText.tr("choice.realm_player", "Led by %s · %s villages", Names.player(c.server(), realm.owner()), realm.members().size())));
            for (var kingdom : McaKingdomsApi.get(c.server()).getKingdoms())
                if (kingdom.defined()) out.add(Choice.of("kingdom:" + kingdom.id(), Names.kingdomName(c.server(), kingdom.id().toString()),
                        LvText.tr("choice.kingdom", "Kingdom government")));
            return out;
        });
        targets("lv_treaty", c -> {
            var out = new ArrayList<Choice>();
            var state = LvBridgeSavedData.get(c.server()).snapshot();
            long now = c.server().overworld().getGameTime();
            for (var treaty : state.treaties.values()) {
                if (treaty.status() != LvState.TreatyStatus.PROPOSED && !treaty.inForce(now)) continue;
                var rt = LvRuntime.get(c.server()).orElse(null);
                String first = rt == null ? treaty.first() : partyName(c, treaty.first());
                String second = rt == null ? treaty.second() : partyName(c, treaty.second());
                out.add(Choice.of(treaty.id().toString(), LvText.tr("choice.treaty", "%s: %s and %s", enumLabel(treaty.term()), first, second),
                        LvText.tr("choice.treaty_detail", "%s · %s of 2 signatures · %s", enumLabel(treaty.status()), treaty.signatures().size(),
                                treaty.reason().isBlank() ? "—" : treaty.reason())));
            }
            return out;
        });
        targetHint("lv_treaty", LvText.tr("hint.treaty", "No agreement is proposed or in force."));
        targets("lv_supply", c -> {
            var rt = LvRuntime.get(c.server()).orElse(null);
            var settlement = here(c).orElse(null);
            if (rt == null || settlement == null) return List.of();
            var out = new ArrayList<Choice>();
            for (String kind : LvSupplies.deliverables(rt, settlement)) {
                var item = LvSupplies.item(rt, kind);
                if (item != null) out.add(Choice.of(kind, item.getDescription(), kind.equals("currency") ? LvText.tr("choice.treasury", "For the village treasury")
                        : LvText.tr("choice.store", "For its smiths' stores")));
            }
            return out;
        });
        targetHint("lv_supply", LvText.tr("hint.supply", "Stand in a Living Villages village whose treasury or stores have room."));
        targets("lv_pending", c -> {
            var out = new ArrayList<Choice>();
            for (var binding : LvBridgeSavedData.get(c.server()).snapshot().bindings.values())
                if (binding.status() == LvState.BindingStatus.AMBIGUOUS || binding.status() == LvState.BindingStatus.PENDING)
                    out.add(Choice.of(binding.village().toString(), Component.literal(binding.providerName()),
                            LvText.tr("choice.pending", "%s · %s", enumLabel(binding.status()), binding.reason())));
            return out;
        });
        targetHint("lv_pending", LvText.tr("hint.pending", "Every Living Villages village is bound; nothing waits for a decision."));
        targets("lv_candidate", c -> {
            var out = new ArrayList<Choice>();
            if (c.text("village").isBlank()) return out;
            var binding = LvBridgeSavedData.get(c.server()).snapshot().bindings.get(UUID.fromString(c.text("village")));
            if (binding == null) return out;
            for (UUID candidate : binding.candidates())
                out.add(Choice.of(candidate.toString(), Component.literal(Names.settlement(c.server(), candidate)), LvText.tr("choice.candidate", "Possible settlement")));
            return out;
        });
        targets("lv_record", c -> {
            var out = new ArrayList<Choice>();
            var state = LvBridgeSavedData.get(c.server()).snapshot();
            state.conflicts.values().stream().filter(x -> x.phase() == LvState.Phase.RECOVERY_REQUIRED || x.phase() == LvState.Phase.SUSPENDED)
                    .forEach(x -> out.add(Choice.of(x.id().toString(), LvText.tr("choice.record_war", "War record"), Component.literal(x.reason()))));
            state.journal.values().stream().filter(o -> o.state() == LvState.OperationState.SUSPENDED || o.state() == LvState.OperationState.INTENT)
                    .forEach(o -> out.add(Choice.of(o.id().toString(), LvText.tr("choice.record_operation", "%s operation", enumLabel(o.kind())), Component.literal(o.detail()))));
            if (state.provenance.regressedAt > 0) out.add(Choice.of("provider", LvText.tr("choice.record_provider", "Living Villages data went back in time"),
                    LvText.tr("choice.record_provider_detail", "Accept the current provider data as the baseline")));
            return out;
        });
        targetHint("lv_record", LvText.tr("hint.record", "Nothing waits for recovery."));

        // Player tasks.
        task("villages.here", "Living Villages village here", "Who holds the village you stand in, its people, war and peace, in plain words. Read-only.", false, false, List.of(),
                c -> LvPresentation.settlement(rt(c), c.player, here(c).orElseThrow(() -> LvText.refuse("none_here", "No settlement here."))));
        task("villages.settlement", "Read a Living Villages village", "Its civic kingdom, the realm that holds it, the recognized government, its people and any war. Read-only.",
                false, false, List.of(pick("settlement", "Village", "lv_settlement")), c -> LvPresentation.settlement(rt(c), c.player, c.uuid("settlement")));
        task("villages.realm", "View its realm", "A realm's capital, villages you know, its military commander, its agreements, wars and recent history. Read-only.",
                false, false, List.of(pick("realm", "Realm", "lv_realm")), c -> LvPresentation.realm(rt(c), c.player, c.uuid("realm")));
        task("villages.open", "Open village", "Opens Living Villages' own village screen for the village you stand in or your faction.", false, false, List.of(),
                c -> LvActions.open(rt(c), c.player));
        task("villages.join", "Join the army", "Joins the Living Villages village you stand near as a member of its army. You do not become its commander or ruler.",
                false, true, List.of(), c -> LvActions.join(rt(c), c.player));
        task("villages.growth", "Why is growth paused?", "What the village is waiting for (beds, money, ground, a siege) and what helps. Read-only.", false, false,
                List.of(pick("settlement", "Village", "lv_settlement")), c -> {
                    if (!SettlementKnowledge.get(c.server()).visible(c.player, c.uuid("settlement"))) throw LvText.refuse("unknown_settlement", "That settlement is not available.");
                    return LvEconomy.growth(rt(c), c.uuid("settlement"));
                });
        task("villages.supply", "Provide supplies", "Hands over currency or smithing materials to the village you stand in. Only what its stores have room for is taken.",
                false, true, List.of(pick("kind", "What to give", "lv_supply"), number("amount", "How many", 64, AMOUNT)),
                c -> LvEconomy.supply(rt(c), c.player, here(c).orElseThrow(() -> LvText.refuse("none_here", "No settlement here.")), c.text("kind"),
                        c.integer("amount", AMOUNT), UUID.randomUUID()));
        task("villages.quiet", "Living Villages announcements", "Choose whether you hear about agreements and defeats of realms other than your own.", false, true,
                List.of(toggle("quiet", "Only my realm and places I know")), c -> LvNotices.quiet(rt(c), c.player, c.bool("quiet")));
        task("warfare.realm_peace", "War and peace with a realm", "Proposes peace, a ceasefire, an alliance or a cession between a party you speak for and a realm or kingdom. An AI realm answers at once by its own interests; a player-led party must sign.",
                false, true, List.of(pick("proposer", "Speaking for", "lv_party"), pick("counterpart", "With", "lv_counterpart"),
                        pick("term", "Agreement", c -> enums(LvState.Term.values())), number("days", "For how many Minecraft days", 3, DAYS), text("reason", "Terms in your words", "", true)),
                c -> LvDiplomacy.propose(rt(c), c.player, UUID.randomUUID(), c.text("proposer"), c.text("counterpart"),
                        LvState.Term.valueOf(c.text("term").toUpperCase(Locale.ROOT)), c.number("days", DAYS) * 24000L, c.text("reason")));
        task("warfare.realm_sign", "Sign an agreement with a realm", "Signs a proposed agreement for the party you speak for; it takes effect at once.", false, true,
                List.of(pick("treaty", "Agreement", "lv_treaty")), c -> LvDiplomacy.sign(rt(c), c.player, c.uuid("treaty")));
        task("warfare.realm_reject", "Reject an agreement with a realm", "Refuses a proposed agreement; native relations are not changed.", false, true,
                List.of(pick("treaty", "Agreement", "lv_treaty")), c -> LvDiplomacy.reject(rt(c), c.player, c.uuid("treaty")));
        task("warfare.realm_end", "End an agreement with a realm", "Ends an agreement in force for the party you speak for. A truce it set still runs its course.", false, true,
                List.of(pick("treaty", "Agreement", "lv_treaty")), c -> LvDiplomacy.end(rt(c), c.player, c.uuid("treaty")));

        // Operator tasks.
        task("villages.status", "Living Villages integration status", "Installed versions, each capability's state with its reason, the effective policies and the queues. Read-only.",
                true, false, List.of(), c -> LvPresentation.status(c.server()));
        task("villages.inspect", "Inspect a Living Villages binding", "The village, realm, claim and provenance behind a settlement, and what blocks it. Read-only.",
                true, false, List.of(pick("settlement", "Settlement", "settlement")), c -> LvPresentation.inspect(rt(c), c.uuid("settlement")));
        task("villages.conflicts", "Living Villages records waiting for an operator", "Ambiguous or pending villages, wars that need recovery and interrupted operations. Read-only.",
                true, false, List.of(), c -> LvPresentation.conflicts(rt(c)));
        task("villages.preview", "Preview the Living Villages import", "What an import would bind, match, leave for a decision or keep realm-only. Changes nothing.",
                true, false, List.of(), c -> LvPresentation.preview(rt(c)));
        task("villages.bind", "Bind an ambiguous village", "Binds a waiting Living Villages village to the settlement you choose. The settlement keeps its name, kingdom and history.",
                true, true, List.of(pick("village", "Village", "lv_pending"), pick("candidate", "Settlement", "lv_candidate").after("village")),
                c -> LvBindings.bindTo(rt(c), c.uuid("village"), c.uuid("candidate"), c.player.getUUID()));
        task("villages.separate", "Keep a village separate", "Makes a waiting Living Villages village its own new settlement, leaving the nearby records as they are.",
                true, true, List.of(pick("village", "Village", "lv_pending")), c -> LvBindings.createSeparate(rt(c), c.uuid("village"), c.player.getUUID()));
        task("villages.associate", "Associate a realm with a kingdom government", "Records that a kingdom's government speaks for a realm, for diplomacy or diplomacy and war. Never inferred from names or culture.",
                true, true, List.of(pick("realm", "Realm", "lv_realm"), pick("kingdom", "Kingdom", "kingdom"),
                        pick("scope", "Scope", c -> List.of(Choice.of("DIPLOMACY", LvText.tr("scope.diplomacy", "Diplomacy")), Choice.of("DIPLOMACY_AND_WAR", LvText.tr("scope.war", "Diplomacy and war"))))),
                c -> LvDiplomacy.associate(rt(c), c.uuid("realm"), c.text("kingdom"), c.text("scope"), c.player.getUUID()));
        task("villages.dissociate", "Remove a realm's association", "Ends a kingdom government's association with a realm; agreements already signed stay.", true, true,
                List.of(pick("realm", "Realm", "lv_realm")), c -> LvDiplomacy.dissociate(rt(c), c.uuid("realm")));
        task("villages.adopt", "Enable Living Villages management here", "Places an existing settlement under Living Villages' simulation, counted from the people and beds already there. Nothing is built, flattened or replaced while construction in adopted villages is off.",
                true, true, List.of(pick("settlement", "Settlement", "settlement")), c -> LvAdoption.apply(rt(c), c.uuid("settlement"), c.player.getUUID(), UUID.randomUUID()));
        effects("villages.adopt", c -> {
            if (c.text("settlement").isBlank() || LvRuntime.get(c.server()).isEmpty()) return List.of();
            var preview = LvAdoption.preview(rt(c), c.uuid("settlement"));
            var out = new ArrayList<Object>();
            preview.blockers().forEach(b -> out.add(LvText.tr("adopt.blocker", "✗ %s", b)));
            preview.effects().forEach(e -> out.add(LvText.tr("adopt.effect", "• %s", e)));
            return out;
        });
        task("villages.rename", "Rename a Living Villages village", "Renames it in MCA: Kingdoms and Living Villages together; its team, banner and records are unchanged.",
                true, true, List.of(pick("settlement", "Village", "lv_settlement"), text("name", "New name")),
                c -> LvBindings.rename(rt(c), c.uuid("settlement"), c.text("name"), LvState.ActorKind.ADMINISTRATOR, c.player.getUUID()));
        task("villages.recover", "Recover a Living Villages record", "Resolves one interrupted operation or war record after checking Living Villages' current state. Nothing is replayed or paid twice.",
                true, true, List.of(pick("record", "Record", "lv_record")), c -> LvRecovery.recover(rt(c), c.text("record"), c.player.getUUID()));
    }

    private static String partyName(Context c, String party) {
        var rt = LvRuntime.get(c.server()).orElse(null);
        return rt == null ? party : LvSupplies.partyName(rt, party);
    }
}
