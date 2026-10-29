package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.Politics.Page;
import java.util.List;

/** Packs a political page reply into its byte budget (H08); kept apart from the channel so it is testable on its own. */
final class PoliticalPacking {
    private PoliticalPacking() { }
    private static final int HEADER_LINE = 2048;
    /**
     * The reply within the packet bound, measured in UTF-8 bytes (H08). Fields repeat what details say, so details go
     * first, then fields; if the rows still do not fit, trailing rows are left for the next page and the page says more
     * follows, so the client asks for them from the first row not sent. Header lines are cut on a code-point boundary as a
     * last resort. Every level is computed from the viewer's own page, so sizes reveal nothing the page does not show.
     */
    static String bounded(PoliticalNetwork.Reply r) {
        String json = PoliticalSavedData.JSON.toJson(r);
        if (dev.otectus.mcakingdoms.network.PayloadBudget.fits(json)) return json;
        json = PoliticalSavedData.JSON.toJson(new PoliticalNetwork.Reply(r.page(), r.result(), r.titles(), List.of(), r.kingdomName(), r.fields(), r.header(), r.heraldry(), r.color(), r.governed()));
        if (dev.otectus.mcakingdoms.network.PayloadBudget.fits(json)) return json;
        List<String> header = r.header().stream().map(line -> dev.otectus.mcakingdoms.network.PayloadBudget.utf8(line) <= HEADER_LINE ? line
                : net.minecraft.network.chat.Component.Serializer.toJson(dev.otectus.mcakingdoms.network.PayloadBudget.clip(InteractionText.read(line), 512))).toList();
        Page page = r.page();
        for (int keep = Math.min(page.rows().size(), r.titles().isEmpty() ? page.rows().size() : r.titles().size()); keep >= 0; keep--) {
            Page packed = new Page(page.requestId(), page.revision(), page.kingdom(), page.tab(), page.offset(), page.hasMore() || keep < page.rows().size(),
                    page.rows().subList(0, keep), page.definitions(), page.actionDenials(), page.diagnostic());
            json = PoliticalSavedData.JSON.toJson(new PoliticalNetwork.Reply(packed, r.result(), r.titles().subList(0, Math.min(keep, r.titles().size())), List.of(), r.kingdomName(), List.of(),
                    header, r.heraldry(), r.color(), r.governed()));
            if (dev.otectus.mcakingdoms.network.PayloadBudget.fits(json)) return json;
        }
        // Nothing but the frame fits: the page arrives empty, with more to follow, rather than not at all.
        Page empty = new Page(page.requestId(), page.revision(), page.kingdom(), page.tab(), page.offset(), !page.rows().isEmpty() || page.hasMore(),
                List.of(), List.of(), page.actionDenials(), dev.otectus.mcakingdoms.network.PayloadBudget.clip(page.diagnostic(), 512));
        return PoliticalSavedData.JSON.toJson(new PoliticalNetwork.Reply(empty, r.result(), List.of(), List.of(), "", List.of(), List.of(), r.heraldry(), r.color(), r.governed()));
    }
    /** Reads a serialized component back for clipping; malformed text is kept literal. */
    private static final class InteractionText {
        static net.minecraft.network.chat.Component read(String json) {
            try { var parsed = net.minecraft.network.chat.Component.Serializer.fromJson(json); return parsed == null ? net.minecraft.network.chat.Component.empty() : parsed; }
            catch (RuntimeException malformed) { return net.minecraft.network.chat.Component.literal(json); }
        }
    }
}
