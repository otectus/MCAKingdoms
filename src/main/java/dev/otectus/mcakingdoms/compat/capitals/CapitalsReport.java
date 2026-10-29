package dev.otectus.mcakingdoms.compat.capitals;

import dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData.Status;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Names;
import dev.otectus.mcakingdoms.interaction.PlayerWords;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The dry-run compatibility report (C3) for operators: each governing capital, the settlement and kingdom its village
 * belongs to, the mapping now and the one the evidence supports, conflicts with an existing MCA: Kingdoms government, and
 * which actions become read-only. Writes nothing. Commands and Kingdom Tasks show the same lines.
 */
public final class CapitalsReport {
    private CapitalsReport() {
    }

    private static Component t(String key, String fallback, Object... args) {
        return ActionRegistry.tr("politics.mcakingdoms.capitals." + key, fallback, args);
    }

    public static List<Component> lines(MinecraftServer server) {
        var lines = new ArrayList<Component>();
        var bridge = CapitalsBridge.capabilities();
        if (!bridge.readable()) {
            lines.add(bridge.availability() == CapitalsBridge.Availability.ABSENT ? t("report.absent", "MCA Capitals is not installed; nothing to map.")
                    : t("report.unsupported", "MCA Capitals %s cannot be read (%s); every MCA: Kingdoms crown action stays read-only.", bridge.version(), bridge.diagnostic()));
            return lines;
        }
        var interop = CapitalsInterop.get(server);
        var proposals = interop.proposals();
        lines.add(t("report.head", "MCA Capitals %s: %s governing capital(s). Nothing below has been changed; mappings follow this evidence at the next reconciliation.",
                bridge.version(), proposals.size()));
        var politics = dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(server);
        for (var proposal : proposals) {
            var capital = proposal.capital();
            Component where = proposal.settlement().<Component>map(s -> t("report.where", "%s in %s", s.displayName(), Names.kingdomName(server, s.kingdomId().toString())))
                    .orElse(t("report.unrecognized", "a village MCA: Kingdoms has not recognized yet"));
            Component now = proposal.current().<Component>map(b -> ActionRegistry.enumLabel(b.status())).orElse(t("report.unmapped", "not mapped"));
            Component next = proposal.proposed() == null ? t("report.waiting", "waiting for the village to be recognized") : ActionRegistry.enumLabel(proposal.proposed());
            lines.add(t("report.capital", "Capital #%s (MCA village %s, %s): %s. Mapping now: %s; evidence supports: %s — %s.",
                    capital.id().toString().substring(0, 4), capital.villageId(), capital.dimension(), where, now, next, PlayerWords.component(proposal.reasonKey())));
            proposal.settlement().ifPresent(s -> politics.government(s.kingdomId().toString()).ifPresent(g ->
                    lines.add(t("report.conflict", "  %s already has an MCA: Kingdoms government; it is kept as history and its civic offices keep working, while its crown actions (founding, seat, leader, succession, house, elections, regency) become read-only.",
                            Names.kingdomName(server, g.kingdom())))));
            if (proposal.proposed() == Status.QUARANTINED)
                lines.add(t("report.quarantine", "  Its kingdom's crown actions stay read-only until an operator accepts one capital (Accept an MCA Capitals capital)."));
        }
        if (proposals.isEmpty()) lines.add(t("report.none", "No capital is governing now; every kingdom without an earlier mapping is governed by MCA: Kingdoms."));
        return lines;
    }
}
