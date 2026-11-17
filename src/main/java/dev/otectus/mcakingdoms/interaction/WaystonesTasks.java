package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.compat.waystones.WaystoneNames;
import dev.otectus.mcakingdoms.compat.waystones.WaystonesIntegration;
import dev.otectus.mcakingdoms.compat.waystones.WaystonesNamingData;
import net.minecraft.network.chat.Component;

import java.util.*;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for settlement waystone names (WAY-01): why the waystone beside you has its name, and, for someone
 * Waystones lets edit it, giving it the settlement's name on request. The integration's own state comes from the shared
 * capability check ({@link TaskMeta.Feature#WAYSTONES}).
 */
final class WaystonesTasks {
    private WaystonesTasks() {
    }

    static Component w(String key, String fallback, Object... args) {
        return tr("waystones.mcakingdoms." + key, fallback, args);
    }

    /** A reason as players read it. */
    static Component reason(WaystoneNames.Reason reason) {
        return w("reason." + reason.name().toLowerCase(Locale.ROOT), WaystonesIntegration.reasonText(reason));
    }

    static void init() {
        add(new Task("waystones.status", "Settlements", "Why this waystone has its name",
                "Explains the waystone within 8 blocks of you: whether it was named after its settlement, why it keeps another name, and how to change it. Waystones' own settings screen renames it as always. Read-only.",
                false, false, List.of(), c -> "", c -> {
                    var lines = new ArrayList<Object>();
                    var status = WaystonesIntegration.status();
                    if (!status.ready()) {
                        lines.add(w("status.off", "Settlement names for waystones are not active on this server: %s.", status.reason()));
                        return lines;
                    }
                    var near = WaystonesIntegration.explainNearest(c.player);
                    if (near.isEmpty()) { lines.add(w("status.none", "No waystone is within 8 blocks of you.")); return lines; }
                    lines.add(w("status.name", "Waystone: %s", near.get().name().isEmpty() ? w("status.unnamed", "(not named yet)") : Component.literal(near.get().name())));
                    lines.add(reason(near.get().reason()));
                    lines.add(w("status.how", "A new waystone placed or first activated inside a recognized settlement takes the settlement's name; a clash gets a number, such as Oakfield (2). Rename it in Waystones' own settings screen at any time: your name is never changed back."));
                    if (!WaystonesIntegration.editsObserved())
                        lines.add(w("status.unobserved", "This server cannot see renames made in Waystones' settings screen, so it never renames a waystone that already has a name."));
                    return lines;
                }));
        add(new Task("waystones.use_settlement_name", "Settlements", "Name this waystone after the settlement",
                "For a waystone within 8 blocks that Waystones lets you edit: gives it the name of the settlement it stands in (numbered if another waystone has that name), replacing its current name because you asked. Its owner, activations and global setting do not change.",
                false, true, List.of(), c -> Review.of("stone", WaystonesIntegration.explainNearest(c.player).map(r -> r.stone() + "@" + r.name()).orElse("none")),
                c -> {
                    var result = WaystonesIntegration.useSettlementName(c.player);
                    return Outcome.applied(w("named", "The waystone is now called %s. You can rename it in its settings screen at any time.", result.name()), result.stone().toString());
                }));
        effects("waystones.use_settlement_name", c -> {
            var near = WaystonesIntegration.explainNearest(c.player);
            return near.<List<Object>>map(r -> List.of(w("effect.rename", "The waystone now called %s gets its settlement's name instead.",
                    r.name().isEmpty() ? w("status.unnamed", "(not named yet)") : Component.literal(r.name())))).orElse(List.of(w("status.none", "No waystone is within 8 blocks of you.")));
        });
    }

    /** Operator lines for Server health: state, versions, counts by stage. */
    static List<Component> health(net.minecraft.server.MinecraftServer server) {
        var lines = new ArrayList<Component>();
        var status = WaystonesIntegration.status();
        lines.add(switch (status.readiness()) {
            case ABSENT -> w("health.absent", "Waystones: not installed.");
            case READY -> w("health.ready", "Waystones %s with Balm %s: settlement names on; player edits %s.", status.waystones(), status.balm(),
                    WaystonesIntegration.editsObserved() ? w("health.observed", "observed") : w("health.unobserved", "not observed (never renames a named stone)"));
            case DISABLED -> w("health.disabled", "Waystones %s: settlement names turned off (waystones.settlementNames).", status.waystones());
            case UNSUPPORTED -> w("health.unsupported", "Waystones %s with Balm %s: settlement names unavailable: %s.", status.waystones(), status.balm(), status.reason());
        });
        if (status.readiness() != WaystonesIntegration.Readiness.ABSENT) {
            var data = WaystonesNamingData.get(server);
            lines.add(w("health.counts", "Named %s, confirmed %s, waiting %s, kept by players %s, unresolved %s.", data.count(WaystonesNamingData.Stage.ASSIGNED),
                    data.count(WaystonesNamingData.Stage.MANAGED), data.count(WaystonesNamingData.Stage.PENDING), data.count(WaystonesNamingData.Stage.DETACHED),
                    data.count(WaystonesNamingData.Stage.UNRESOLVED)));
        }
        return lines;
    }
}
