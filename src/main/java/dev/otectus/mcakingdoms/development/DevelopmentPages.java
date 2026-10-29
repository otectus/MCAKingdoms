package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.politics.Politics.Row;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * The Kingdom screen's Development page (D5): the server's mode, each settlement of the kingdom the viewer knows with its
 * tier and works, what each work still needs and why it waits, and (in {@code FULL_ECONOMY}) the abstract ledger and
 * trade, always labelled abstract. Works are public civic records; who delivered what is shown as counts, and a viewer
 * sees their own deliveries.
 */
public final class DevelopmentPages {
    private static final String P = DevelopmentService.P + "page.";
    static final int COMPLETED_SHOWN = 3, TRADES_SHOWN = 8, SETTLEMENTS_SHOWN = 64;

    private DevelopmentPages() {
    }

    private static Component t(String key, String fallback, Object... args) { return ActionRegistry.tr(P + key, fallback, args); }

    private static GovernmentService.Field f(String key, String fallback, Component value) {
        return new GovernmentService.Field(t("field." + key, fallback), value);
    }

    public static List<Row> rows(DevelopmentService service, ServerPlayer viewer, String kingdom) {
        var rows = new ArrayList<Row>();
        rows.add(new Row("development:mode", "", ""));
        if (DevelopmentMode.current() == DevelopmentMode.OFF) return rows;
        var records = service.data().records();
        var settlements = SettlementKnowledge.get(service.server()).all(viewer, McaKingdomsApi.get(service.server()), Optional.of(new ResourceLocation(kingdom)), SETTLEMENTS_SHOWN);
        var known = new HashSet<UUID>();
        for (var settlement : settlements) {
            known.add(settlement.id());
            rows.add(new Row("development:settlement:" + settlement.id(), "", ""));
            records.projects().stream().filter(p -> p.settlement().equals(settlement.id()) && p.state().open())
                    .sorted(Comparator.comparingLong(Project::createdAt).thenComparing(Project::id)).forEach(p -> rows.add(new Row("development:project:" + p.id(), "", "")));
            records.projects().stream().filter(p -> p.settlement().equals(settlement.id()) && p.state() == State.COMPLETED)
                    .sorted(Comparator.comparingLong(Project::updatedAt).reversed()).limit(COMPLETED_SHOWN).forEach(p -> rows.add(new Row("development:project:" + p.id(), "", "")));
        }
        if (DevelopmentMode.current().atLeast(DevelopmentMode.FULL_ECONOMY)) {
            var trades = records.trades();
            for (int i = trades.size() - 1, shown = 0; i >= 0 && shown < TRADES_SHOWN; i--)
                if (known.contains(trades.get(i).from()) || known.contains(trades.get(i).to())) { rows.add(new Row("development:trade:" + i, "", "")); shown++; }
        }
        return rows;
    }

    public static List<GovernmentService.PresentedRow> present(DevelopmentService service, ServerPlayer viewer, List<Row> rows) {
        var out = new ArrayList<GovernmentService.PresentedRow>();
        var records = service.data().records();
        for (Row row : rows) {
            String id = row.id();
            try {
                if (id.equals("development:mode")) out.add(mode());
                else if (id.startsWith("development:settlement:")) {
                    UUID settlement = UUID.fromString(id.substring("development:settlement:".length()));
                    McaKingdomsApi.get(service.server()).getSettlement(settlement).ifPresent(s -> out.add(settlement(s.displayName(), records.economy(settlement))));
                } else if (id.startsWith("development:project:")) {
                    records.project(UUID.fromString(id.substring("development:project:".length()))).ifPresent(p -> out.add(project(service, viewer, p)));
                } else if (id.startsWith("development:trade:")) {
                    int index = Integer.parseInt(id.substring("development:trade:".length()));
                    if (index >= 0 && index < records.trades().size()) out.add(trade(service, records.trades().get(index)));
                }
            } catch (IllegalArgumentException malformed) {
                // A row id this page did not write.
            }
        }
        return out;
    }

    private static GovernmentService.PresentedRow mode() {
        var mode = DevelopmentMode.current();
        Component detail = switch (mode) {
            case OFF -> t("mode.off", "Settlement development is off on this server: nothing is planned, funded or built.");
            case OBSERVE -> t("mode.observe", "Settlement pages explain development from verified buildings and completed works. Nothing is funded or built.");
            case PROJECTS -> t("mode.projects", "Officials post public works that players fund with real deliveries. A funded work is a recorded milestone; nothing is built.");
            case MANAGED_GROWTH -> t("mode.managed", "Funded works with a small template are built on their surveyed plots in settlements that allow it, a few blocks at a time.");
            case FULL_ECONOMY -> t("mode.full", "As managed growth, plus an abstract goods ledger and capped abstract trade between settlements of kingdoms with an agreement in force. No real item or coin is created or moved by it.");
        };
        var fields = List.of(f("construction", "Construction", Component.translatable(DevelopmentMode.constructionPaused() ? "gui.no" : "gui.yes")),
                f("economy", "Deliveries and trade", Component.translatable(DevelopmentMode.economyPaused() ? "gui.no" : "gui.yes")));
        return new GovernmentService.PresentedRow(t("mode", "Development: %s", ActionRegistry.enumLabel(mode)), detail, fields);
    }

    private static GovernmentService.PresentedRow settlement(String name, Economy e) {
        var fields = new ArrayList<GovernmentService.Field>();
        fields.add(f("tier", "Tier", Component.literal(Integer.toString(e.tier()))));
        fields.add(f("points", "Points from completed works", Component.literal(Integer.toString(e.points()))));
        fields.add(f("construction_here", "Construction here", e.optedIn() ? t("opted_in", "Allowed on surveyed plots") : t("opted_out", "Not allowed: works are milestones")));
        if (!e.abstractGoods().isEmpty()) {
            var goods = String.join(", ", e.abstractGoods().entrySet().stream().map(x -> x.getValue() + " " + x.getKey()).toList());
            fields.add(f("abstract", "Abstract ledger", t("abstract", "%s (abstract; no such items exist)", goods)));
        }
        Component detail = e.specialization().<Component>map(role -> t("specialization", "Known for its %s.", ActionRegistry.enumLabel(DevelopmentTemplate.Role.valueOf(role.toUpperCase(Locale.ROOT)))))
                .orElse(t("no_works", "No completed works yet."));
        return new GovernmentService.PresentedRow(t("settlement", "%s · tier %s", name, e.tier()), detail, fields);
    }

    private static GovernmentService.PresentedRow project(DevelopmentService service, ServerPlayer viewer, Project p) {
        var fields = new ArrayList<GovernmentService.Field>();
        fields.add(f("state", "State", ActionRegistry.enumLabel(p.state())));
        var needs = Component.empty();
        for (int i = 0; i < p.demand().size(); i++) {
            var d = p.demand().get(i);
            if (i > 0) needs.append(", ");
            needs.append(t("need", "%s of %s %s", d.delivered(), d.count(), ingredient(d.ingredient())));
        }
        fields.add(f("needs", "Needs", needs));
        fields.add(f("provenance", "Kind", t("provenance", "%s; deliveries are %s", ActionRegistry.enumLabel(Provenance.AUTHORED_DEMAND), ActionRegistry.enumLabel(Provenance.PHYSICAL_VERIFIED))));
        if (p.origin() != null) {
            long built = p.journal().stream().filter(Placement::placed).count();
            fields.add(f("plot", "Plot", t("plot", "%s in %s; %s of %s blocks built", p.origin().toShortString(), p.dimension(), built, p.journal().size())));
        } else fields.add(f("plot", "Plot", t("no_plot", "None: a funded milestone, never built")));
        if (p.approver() != null) fields.add(f("approved", "Approved by", Component.literal(dev.otectus.mcakingdoms.interaction.Names.person(service.server(), p.approver()))));
        int mine = p.contributions().stream().filter(c -> c.player().equals(viewer.getUUID())).mapToInt(Contribution::count).sum();
        long people = p.contributions().stream().map(Contribution::player).distinct().count();
        fields.add(f("contributors", "Deliveries", mine > 0 ? t("contributors.mine", "%s contributor(s); you delivered %s", people, mine) : t("contributors", "%s contributor(s)", people)));
        return new GovernmentService.PresentedRow(t("project", "%s · %s", DevelopmentService.templateTitle(p.template()), ActionRegistry.enumLabel(p.state())), Keyed.component(p.reasonKey()), fields);
    }

    private static Component ingredient(String ingredient) {
        if (ingredient.startsWith("#")) return t("tag", "any %s", ActionRegistry.words(ingredient.substring(ingredient.indexOf(':') + 1).replace('/', ' ')));
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(ingredient));
        return item.<Component>map(i -> i.getDescription()).orElse(Component.literal(ingredient));
    }

    private static GovernmentService.PresentedRow trade(DevelopmentService service, Trade trade) {
        var api = McaKingdomsApi.get(service.server());
        String from = api.getSettlement(trade.from()).map(s -> s.displayName()).orElse("?"), to = api.getSettlement(trade.to()).map(s -> s.displayName()).orElse("?");
        return new GovernmentService.PresentedRow(t("trade", "Abstract trade: %s %s from %s to %s", trade.amount(), trade.commodity(), from, to),
                t("trade.detail", "Day %s. A ledger entry only: no item moved and no coin was paid.", trade.at() / 24000 + 1));
    }
}
