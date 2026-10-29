package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.PoliticalFact;
import dev.otectus.mcakingdoms.document.DocumentLedger;
import dev.otectus.mcakingdoms.document.DocumentService;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Names;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What a court noticeboard shows (B1): the kingdom's latest sealed proclamations and public political records. */
public final class HeraldryService {
    private HeraldryService() {
    }

    public static List<Component> readNoticeboard(ServerPlayer viewer, String kingdom) {
        var server = viewer.getServer();
        var lines = new ArrayList<Component>();
        lines.add(ActionRegistry.tr("heraldry.mcakingdoms.noticeboard.head", "Court noticeboard of %s", Names.kingdomName(server, kingdom)));
        var proclamations = DocumentService.documents(server, kingdom).stream()
                .filter(d -> d.kind() == DocumentLedger.Kind.PROCLAMATION && d.state() == DocumentLedger.State.ISSUED).limit(3).toList();
        for (var document : proclamations)
            lines.add(ActionRegistry.tr("heraldry.mcakingdoms.noticeboard.proclamation", "Day %s: %s", document.issuedAt() / 24000 + 1, Component.literal(document.title())));
        // Public records only: the viewer's own private records are not pinned to a public board.
        var facts = KingdomsPoliticsApi.get(server).history(viewer, 0, 50).stream()
                .filter(f -> f.kingdom().equals(kingdom) && f.visibility() == PoliticalFact.Visibility.PUBLIC)
                .sorted((a, b) -> Long.compare(b.gameTime(), a.gameTime())).limit(3).toList();
        for (var fact : facts)
            lines.add(ActionRegistry.tr("heraldry.mcakingdoms.noticeboard.fact", "Day %s: %s", fact.gameTime() / 24000 + 1,
                    Component.translatableWithFallback("politics.mcakingdoms.fact." + fact.type().name().toLowerCase(Locale.ROOT), fact.summary())));
        if (proclamations.isEmpty() && facts.isEmpty()) lines.add(ActionRegistry.tr("heraldry.mcakingdoms.noticeboard.empty", "Nothing has been proclaimed or recorded here yet."));
        return lines;
    }
}
