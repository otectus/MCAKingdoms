package dev.otectus.mcakingdoms.politics.dynasty;

import dev.otectus.mcakingdoms.api.politics.Politics.Row;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Names;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * The Kingdom screen's Succession page (A2): the law and its preview, the current case and why its heir, the
 * candidates with their reasons, claims, court titles, mourning and memorials, and the header lines. Filtered for the
 * viewer on the server before paging: the law, the case's state, titles and memorials are public; candidates, the
 * preview, the legitimacy factors and other people's claims are for the court only, and a candidate always sees their
 * own row.
 */
public final class DynastyPages {
    private static final String P = DynastyService.P + "page.";
    static final int MEMORIALS_SHOWN = 16, DECIDED_CLAIMS_SHOWN = 8;

    private DynastyPages() {
    }

    private static Component t(String key, String fallback, Object... args) {
        return ActionRegistry.tr(P + key, fallback, args);
    }

    private static GovernmentService.Field f(String key, String fallback, Component value) {
        return new GovernmentService.Field(t("field." + key, fallback), value);
    }

    /** The rows of the Succession page this viewer may see, in display order. */
    public static List<Row> rows(DynastyService service, ServerPlayer viewer, String kingdom) {
        var rows = new ArrayList<Row>();
        var records = service.data().records();
        boolean court = service.court(viewer, kingdom);
        UUID me = viewer.getUUID();
        rows.add(new Row("dynasty:law", "", ""));
        if (service.legitimacy(kingdom).isPresent()) rows.add(new Row("dynasty:legitimacy", "", ""));
        if (service.mourning(kingdom).isPresent()) rows.add(new Row("dynasty:mourning", "", ""));
        var current = records.current(kingdom);
        if (current.isPresent()) {
            rows.add(new Row("dynasty:case", "", ""));
            for (var candidate : current.get().candidates())
                if (court || candidate.id().equals(me)) rows.add(new Row("dynasty:candidate:" + candidate.id(), "", ""));
        } else if (court && !service.lawId(kingdom).equals(SuccessionLaw.MANUAL)) {
            rows.add(new Row("dynasty:preview", "", ""));
            for (var candidate : service.preview(kingdom).candidates()) rows.add(new Row("dynasty:preview:" + candidate.id(), "", ""));
        }
        var claims = records.claims().stream().filter(c -> c.kingdom().equals(kingdom) && (court || c.claimant().equals(me))).toList();
        claims.stream().filter(c -> c.state() == ClaimState.OPEN).sorted(Comparator.comparingLong(Claim::filedAt).thenComparing(Claim::id))
                .forEach(c -> rows.add(new Row("dynasty:claim:" + c.id(), "", "")));
        claims.stream().filter(c -> c.state() != ClaimState.OPEN).sorted(Comparator.comparingLong(Claim::decidedAt).reversed().thenComparing(Claim::id))
                .limit(DECIDED_CLAIMS_SHOWN).forEach(c -> rows.add(new Row("dynasty:claim:" + c.id(), "", "")));
        records.titles().stream().filter(t -> t.kingdom().equals(kingdom) && (t.state() == TitleState.ACTIVE || t.state() == TitleState.OFFERED && (court || t.recipient().id().equals(me))))
                .sorted(Comparator.comparingInt((Title t) -> NobleRank.get(t.rank()).map(NobleRank::tier).orElse(99)).thenComparing(Title::recipientName).thenComparing(Title::id))
                .forEach(t -> rows.add(new Row("dynasty:title:" + t.id(), "", "")));
        records.memorials().stream().filter(m -> m.kingdom().equals(kingdom)).sorted(Comparator.comparingLong(Memorial::reignTo).reversed()).limit(MEMORIALS_SHOWN)
                .forEach(m -> rows.add(new Row("dynasty:memorial:" + m.id(), "", "")));
        return rows;
    }

    /** The rows as players read them, rebuilt from the records of this same tick. */
    public static List<GovernmentService.PresentedRow> present(DynastyService service, ServerPlayer viewer, String kingdom, List<Row> rows) {
        var out = new ArrayList<GovernmentService.PresentedRow>();
        var records = service.data().records();
        boolean court = service.court(viewer, kingdom);
        var preview = rows.stream().anyMatch(r -> r.id().startsWith("dynasty:preview")) ? service.preview(kingdom) : null;
        for (Row row : rows) {
            String id = row.id();
            try {
                if (id.equals("dynasty:law")) out.add(law(service, kingdom, records));
                else if (id.equals("dynasty:legitimacy")) service.legitimacy(kingdom).ifPresent(l -> out.add(legitimacy(l, court)));
                else if (id.equals("dynasty:mourning")) service.mourning(kingdom).ifPresent(m -> out.add(new GovernmentService.PresentedRow(
                        t("mourning", "In mourning for %s", m.rulerName()), t("mourning.detail", "Banners of the kingdom are darkened until day %s.", m.endsAt() / 24000 + 1))));
                else if (id.equals("dynasty:case")) records.current(kingdom).ifPresent(k -> out.add(caseRow(service, k)));
                else if (id.equals("dynasty:preview") && preview != null) out.add(previewRow(service, preview));
                else if (id.startsWith("dynasty:candidate:")) {
                    UUID person = UUID.fromString(id.substring("dynasty:candidate:".length()));
                    records.current(kingdom).flatMap(k -> k.candidates().stream().filter(c -> c.id().equals(person)).findFirst()).ifPresent(c -> out.add(candidate(service, c)));
                } else if (id.startsWith("dynasty:preview:") && preview != null) {
                    UUID person = UUID.fromString(id.substring("dynasty:preview:".length()));
                    preview.candidates().stream().filter(c -> c.id().equals(person)).findFirst().ifPresent(c -> out.add(candidate(service, c)));
                } else if (id.startsWith("dynasty:claim:")) {
                    var claim = records.claims.get(UUID.fromString(id.substring("dynasty:claim:".length())));
                    if (claim != null) out.add(claim(service, claim));
                } else if (id.startsWith("dynasty:title:")) {
                    var title = records.titles.get(UUID.fromString(id.substring("dynasty:title:".length())));
                    if (title != null) out.add(title(service, viewer, title));
                } else if (id.startsWith("dynasty:memorial:")) {
                    UUID memorial = UUID.fromString(id.substring("dynasty:memorial:".length()));
                    records.memorials().stream().filter(m -> m.id().equals(memorial)).findFirst().ifPresent(m -> out.add(memorial(m)));
                }
            } catch (IllegalArgumentException malformed) {
                // A row id this page did not write; nothing to show for it.
            }
        }
        return out;
    }

    private static GovernmentService.PresentedRow law(DynastyService service, String kingdom, Records records) {
        var law = service.law(kingdom);
        var fields = new ArrayList<GovernmentService.Field>();
        boolean missing = !law.id().equals(service.lawId(kingdom));
        fields.add(f("law", "Law", missing ? t("law.missing", "%s (no longer defined; succession waits for review)", service.lawId(kingdom).toString()) : DynastyService.lawTitle(law)));
        records.law(kingdom).ifPresent(choice -> fields.add(f("adopted", "Adopted", t("law.adopted", "By %s on day %s", choice.adoptedBy() == null ? "" : service.name(choice.adoptedBy()), choice.adoptedAt() / 24000 + 1))));
        Component detail = switch (law.mode()) {
            case MANUAL_ONLY -> t("law.manual", "A vacant leadership waits for the officials to name and seat a successor. Nothing happens on its own.");
            case ELECTIVE_MONARCHY -> t("law.elective", "A vacant leadership is filled by the government's election; lineage is never read.");
            default -> {
                var considers = new ArrayList<Component>();
                for (var relation : Lineage.Relation.values()) if (law.relations().contains(relation)) considers.add(switch (relation) {
                    case CHILD -> t("law.relation.child", "children");
                    case GRANDCHILD -> t("law.relation.grandchild", "grandchildren");
                    case SIBLING -> t("law.relation.sibling", "siblings");
                    case RECOGNIZED -> t("law.relation.recognized", "recognized members of the house");
                    default -> ActionRegistry.enumLabel(relation);
                });
                fields.add(f("named_first", "Named successor first", Component.translatable(law.designatedFirst() ? "gui.yes" : "gui.no")));
                fields.add(f("council", "Council confirms", Component.translatable(law.confirmation() == SuccessionLaw.Confirmation.COUNCIL ? "gui.yes" : "gui.no")));
                fields.add(f("minors", "Minor heirs wait under a regency", Component.translatable(law.minorRegency() ? "gui.yes" : "gui.no")));
                yield t("law.lineage", "An elected successor comes first, then an upheld claimant%s, then %s, each in a fixed order by person. Unverified evidence waits for review.",
                        law.designatedFirst() ? t("law.lineage.named", ", then the named successor") : Component.empty(), join(considers));
            }
        };
        return new GovernmentService.PresentedRow(t("law.title", "Law of succession: %s", DynastyService.lawTitle(law)), detail, fields);
    }

    private static Component join(List<Component> parts) {
        var out = Component.empty();
        for (int i = 0; i < parts.size(); i++) { if (i > 0) out.append(", "); out.append(parts.get(i)); }
        return out;
    }

    private static GovernmentService.PresentedRow legitimacy(Legitimacy legitimacy, boolean court) {
        var fields = new ArrayList<GovernmentService.Field>();
        if (court) for (var factor : legitimacy.factors())
            fields.add(new GovernmentService.Field(Component.literal((factor.weight() >= 0 ? "+" : "") + factor.weight()), ActionRegistry.tr(factor.key(), factor.english(), factor.count())));
        return new GovernmentService.PresentedRow(t("legitimacy", "Legitimacy: %s", ActionRegistry.enumLabel(legitimacy.level())),
                court ? t("legitimacy.detail", "A reading of the ruler's claim from the records below. It changes no authority.")
                        : t("legitimacy.public", "A reading of the ruler's claim; its reasons are for the court."), fields);
    }

    private static GovernmentService.PresentedRow caseRow(DynastyService service, Case k) {
        var fields = new ArrayList<GovernmentService.Field>();
        fields.add(f("ruler", "Ruler", Component.literal(k.rulerName())));
        fields.add(f("cause", "How the reign ended", ActionRegistry.enumLabel(k.cause())));
        if (k.heir() != null) fields.add(f("heir", "Heir", Component.literal(service.name(k.heir().id()))));
        if (k.status() == Status.CLAIMS_PENDING && k.pending() == Pending.ACCEPTANCE)
            fields.add(f("waiting", "Waiting for", t("waiting.acceptance", "The heir's answer, for %s more", Names.remainingText(service.server(), k.deadline()))));
        if (k.status() == Status.CLAIMS_PENDING && k.pending() == Pending.CONFIRMATION) {
            int needed = service.politics().council(k.kingdom()).size() / 2 + 1;
            fields.add(f("waiting", "Waiting for", t("waiting.council", "The council: %s of %s confirmations, %s objections", k.confirmations().size(), needed, k.objections().size())));
        }
        if (k.status() == Status.REGENCY) fields.add(f("waiting", "Waiting for", t("waiting.adulthood", "The heir's verified adulthood")));
        fields.add(f("opened", "Vacancy confirmed", t("day", "Day %s", k.openedAt() / 24000 + 1)));
        return new GovernmentService.PresentedRow(t("case", "Succession: %s", ActionRegistry.enumLabel(k.status())), Keyed.component(k.reasonKey()), fields);
    }

    private static GovernmentService.PresentedRow previewRow(DynastyService service, SuccessionResolver.Resolution preview) {
        var heir = preview.heir().map(c -> (Component) Component.literal(c.name().isBlank() ? service.name(c.id()) : c.name())).orElse(t("preview.nobody", "nobody yet"));
        return new GovernmentService.PresentedRow(t("preview", "If the leadership fell vacant now: %s", heir), Keyed.component(preview.reasonKey()));
    }

    private static GovernmentService.PresentedRow candidate(DynastyService service, SuccessionResolver.Candidate c) {
        String who = c.name().isBlank() ? service.name(c.id()) : c.name();
        var fields = List.of(f("relation", "Why considered", ActionRegistry.enumLabel(c.relation())), f("stage", "Life stage", ActionRegistry.enumLabel(c.stage())),
                f("eligibility", "Eligibility", Keyed.component(c.reasonKey())));
        return new GovernmentService.PresentedRow(Component.literal(who), t("candidate", "%s · %s · %s", ActionRegistry.enumLabel(c.relation()), ActionRegistry.enumLabel(c.stage()),
                Keyed.component(c.reasonKey())), fields);
    }

    private static GovernmentService.PresentedRow claim(DynastyService service, Claim claim) {
        var fields = new ArrayList<GovernmentService.Field>();
        fields.add(f("state", "State", ActionRegistry.enumLabel(claim.state())));
        fields.add(f("filed", "Filed", t("day", "Day %s", claim.filedAt() / 24000 + 1)));
        if (claim.decidedBy() != null && claim.state() != ClaimState.WITHDRAWN) fields.add(f("decided", "Decided", t("decided", "By %s on day %s", service.name(claim.decidedBy()), claim.decidedAt() / 24000 + 1)));
        if (!claim.statement().isEmpty()) fields.add(f("statement", "Statement", Component.literal(claim.statement())));
        return new GovernmentService.PresentedRow(t("claim", "%s: %s", ActionRegistry.enumLabel(claim.kind()), claim.claimantName()),
                claim.statement().isEmpty() ? ActionRegistry.enumLabel(claim.state()) : t("claim.detail", "%s · “%s”", ActionRegistry.enumLabel(claim.state()), claim.statement()), fields);
    }

    private static GovernmentService.PresentedRow title(DynastyService service, ServerPlayer viewer, Title title) {
        var fields = new ArrayList<GovernmentService.Field>();
        fields.add(f("rank", "Rank", DynastyService.rankTitle(title.rank(), title.kingdom())));
        fields.add(f("state", "State", ActionRegistry.enumLabel(title.state())));
        if (title.scope() != null) fields.add(f("seat", "Seat", Component.literal(Names.settlement(viewer.getServer(), viewer, title.scope()))));
        fields.add(f("granted", "Granted", t("granted", "By %s on day %s", service.name(title.grantor()), title.grantedAt() / 24000 + 1)));
        if (title.expiresAt() > 0) fields.add(f("ends", "Ends", t("day", "Day %s", title.expiresAt() / 24000 + 1)));
        var address = NobleRank.get(title.rank()).map(NobleRank::addressKey).filter(k -> !k.isEmpty());
        address.ifPresent(key -> fields.add(f("address", "Form of address", Component.translatableWithFallback(key, ""))));
        return new GovernmentService.PresentedRow(t("title", "%s · %s", DynastyService.rankTitle(title.rank(), title.kingdom()), title.recipientName()),
                t("title.detail", "A court rank: ceremonial, it grants no authority."), fields);
    }

    private static GovernmentService.PresentedRow memorial(Memorial m) {
        return new GovernmentService.PresentedRow(t("memorial", "In memory of %s", m.rulerName()),
                t("memorial.detail", "Reigned from day %s to day %s · %s", m.reignFrom() / 24000 + 1, m.reignTo() / 24000 + 1, ActionRegistry.enumLabel(m.cause())));
    }

    /** Lines for the Kingdom page header: mourning, the law when one is adopted, the succession under way, the legitimacy level. */
    public static List<Component> header(DynastyService service, ServerPlayer viewer, String kingdom) {
        var lines = new ArrayList<Component>();
        service.mourning(kingdom).ifPresent(m -> lines.add(t("header.mourning", "In mourning for %s.", m.rulerName())));
        if (!service.lawId(kingdom).equals(SuccessionLaw.MANUAL)) lines.add(t("header.law", "Law of succession: %s", DynastyService.lawTitle(service.law(kingdom))));
        service.currentCase(kingdom).ifPresent(k -> lines.add(t("header.case", "Succession: %s", ActionRegistry.enumLabel(k.status()))));
        service.legitimacy(kingdom).ifPresent(l -> lines.add(t("header.legitimacy", "Legitimacy: %s", ActionRegistry.enumLabel(l.level()))));
        return lines;
    }
}
