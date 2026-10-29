package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Player actions that hand over to Living Villages itself: its village screen and its join. Each runs exactly as the
 * provider's own command does for this player, after the same presence check; membership never grants command or rule.
 */
public final class LvActions {
    private LvActions() {
    }

    /** Opens the provider's village screen for the village the player stands in, or the player's own faction. */
    public static String open(LvRuntime rt, ServerPlayer player) {
        var village = rt.access.playerRealm(player).or(() -> rt.access.nearestVillage(player))
                .orElseThrow(() -> LvText.refuse("open_none", "Stand in a Living Villages village (near its town hall), or join one, to open its screen."));
        rt.access.openScreen(player, village);
        return LvText.text("opened", "Opened %s in Living Villages.", rt.access.name(village));
    }

    /**
     * Joins the village the player stands in, through the provider's own join (its Recruits team). The player becomes a member:
     * not its commander, not its ruler, and no civic office in any kingdom changes.
     */
    public static String join(LvRuntime rt, ServerPlayer player) {
        var village = rt.access.nearestVillage(player)
                .orElseThrow(() -> LvText.refuse("join_none", "Stand near a Living Villages town hall to join that village."));
        if (rt.access.playerFaction(rt.access.capitalOf(rt.server, village)))
            throw LvText.refuse("join_player_realm", "That village belongs to a player's faction; its leader invites members through Recruits.");
        try {
            Method join = rt.access.playerService.getMethod("join", ServerPlayer.class, net.minecraft.server.level.ServerLevel.class, rt.access.faction);
            boolean joined = (boolean) join.invoke(null, player, player.serverLevel(), village);
            if (!joined) throw LvText.refuse("join_refused", "%s cannot accept you right now.", rt.access.name(village));
        } catch (ReflectiveOperationException failure) {
            throw LvText.refuse("join_unavailable", "Living Villages' join is unavailable.");
        }
        UUID village_ = rt.access.id(village);
        rt.touch(village_);
        return LvText.text("joined", "You joined %s's army as a member. Its commander and its rulers are unchanged.", rt.access.name(village));
    }
}
