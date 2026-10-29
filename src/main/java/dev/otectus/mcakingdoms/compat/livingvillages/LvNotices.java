package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.compat.livingvillages.LvState.*;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Announcements, once per player, each after the world save that made it durable. Living Villages already broadcasts its
 * own war, siege and conquest lines to everyone, so with {@code deduplicateNotifications} on Kingdoms adds only what the
 * provider does not say (agreements, cessions, capital changes, defeats, adoptions). Each player has a delivery cursor; a
 * player may mute announcements that do not concern their own realm or a settlement they know.
 */
public final class LvNotices {
    /** Kinds Living Villages announces natively in chat. */
    private static final Set<EventKind> NATIVE = EnumSet.of(EventKind.WAR_DECLARED, EventKind.SIEGE_LAID, EventKind.BATTLE_BEGAN,
            EventKind.CONQUERED, EventKind.REPELLED);

    private LvNotices() {
    }

    static void publishAcknowledged(LvRuntime rt) {
        rt.lastSaved = rt.now();
        var next = rt.data.snapshot();
        boolean changed = false;
        for (var op : List.copyOf(next.journal.values())) {
            if (op.state() != OperationState.ACKNOWLEDGED) continue;
            next.journal.put(op.id(), op.state(OperationState.PUBLISHED, null, rt.now()));
            changed = true;
        }
        if (changed) rt.commit(next);
    }

    static void deliver(LvRuntime rt) {
        var state = rt.view();
        if (state.history.isEmpty() || rt.server.getPlayerList().getPlayers().isEmpty()) return;
        long latest = state.historySequence;
        var next = rt.data.snapshot();
        boolean changed = false;
        for (ServerPlayer player : rt.server.getPlayerList().getPlayers()) {
            long cursor = next.delivered.getOrDefault(player.getUUID(), -1L);
            if (cursor < 0) {
                // A player new to the bridge starts at the present: earlier history is in the books, not replayed into chat.
                next.delivered.put(player.getUUID(), latest);
                changed = true;
                continue;
            }
            if (cursor >= latest) continue;
            long through = cursor;
            int sent = 0;
            for (var event : state.history) {
                if (event.sequence() <= cursor) continue;
                // Durable first: an event is announced after the save that followed it.
                if (rt.lastSaved < event.gameTime()) break;
                through = event.sequence();
                if (!event.announce() || (LvConfig.deduplicate() && NATIVE.contains(event.kind()))) continue;
                if (!relevant(rt, state, player, event)) continue;
                if (sent++ < 4) player.sendSystemMessage(line(rt, state, event).copy().withStyle(ChatFormatting.GOLD));
            }
            if (through != cursor) {
                next.delivered.put(player.getUUID(), through);
                changed = true;
            }
        }
        if (changed) rt.data.markChanged(next);
    }

    private static boolean relevant(LvRuntime rt, LvState state, ServerPlayer player, Event event) {
        boolean own = rt.access.playerRealm(player).map(v -> state.realmOfVillage(rt.access.id(v))).flatMap(r -> r)
                .map(r -> r.id().equals(event.realm()) || r.id().equals(event.otherRealm())).orElse(false);
        if (own) return true;
        if (state.quiet.contains(player.getUUID())) return false;
        // Only settlements this player knows; an unknown distant village's war is not revealed through Kingdoms.
        return event.settlement() == null ? event.kind() == EventKind.TREATY_ACTIVE || event.kind() == EventKind.TREATY_BREACHED
                || event.kind() == EventKind.REALM_DEFEATED : SettlementKnowledge.get(rt.server).visible(player, event.settlement());
    }

    static Component line(LvRuntime rt, LvState state, Event event) {
        String realm = event.realm() == null ? "?" : Optional.ofNullable(state.realms.get(event.realm())).map(Realm::name).orElse("?");
        String other = event.otherRealm() == null ? "?" : Optional.ofNullable(state.realms.get(event.otherRealm())).map(Realm::name).orElse("?");
        Component place = Component.literal(event.settlement() == null ? "?" : dev.otectus.mcakingdoms.interaction.Names.settlement(rt.server, event.settlement()));
        return switch (event.kind()) {
            case TREATY_ACTIVE -> LvText.tr("announce.treaty", "%s and %s have an agreement in force (%s).", realm, other, words(event.detail()));
            case TREATY_BREACHED -> LvText.tr("announce.breach", "The agreement between %s and %s was broken: %s.", realm, other, event.detail());
            case TREATY_EXPIRED -> LvText.tr("announce.expired", "The agreement between %s and %s has ended.", realm, other);
            case TREATY_REJECTED -> LvText.tr("announce.rejected", "%s refused a proposed agreement.", realm);
            case SOVEREIGNTY_CEDED -> LvText.tr("announce.ceded", "%s is now recognized as part of %s.", place, realm);
            case CAPITAL_CHANGED -> LvText.tr("announce.capital", "%s has a new capital: %s.", realm, event.detail());
            case REALM_DEFEATED -> LvText.tr("announce.defeated", "%s has lost its last village.", realm);
            case REALM_DISBANDED -> LvText.tr("announce.disbanded", "%s was disbanded; its villages are independent.", realm);
            case ADOPTED -> LvText.tr("announce.adopted", "%s is now a Living Villages village.", place);
            case CONQUERED -> LvText.tr("announce.conquered", "%s now controls %s; its people and name are unchanged.", realm, place);
            case WAR_DECLARED, SIEGE_LAID -> LvText.tr("announce.war", "War: %s.", event.detail());
            case BATTLE_BEGAN -> LvText.tr("announce.battle", "Battle at %s.", place);
            case REPELLED -> LvText.tr("announce.repelled", "%s held off its attackers.", place);
            case WITHDRAWN -> LvText.tr("announce.withdrawn", "A war over %s was called off.", place);
            default -> LvText.tr("announce.other", "%s", event.detail());
        };
    }

    private static String words(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Mutes or unmutes announcements about other realms for one player. */
    public static String quiet(LvRuntime rt, ServerPlayer player, boolean quiet) {
        var next = rt.data.snapshot();
        if (quiet) next.quiet.add(player.getUUID()); else next.quiet.remove(player.getUUID());
        rt.data.markChanged(next);
        return quiet ? LvText.text("quiet_on", "You will hear only about your own realm and settlements you know.")
                : LvText.text("quiet_off", "You will hear about Living Villages agreements and defeats again.");
    }
}
