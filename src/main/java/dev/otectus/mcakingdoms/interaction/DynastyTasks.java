package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.politics.dynasty.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.*;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Kingdom Tasks for the dynasty and the court (A2-A3): read the succession, adopt a law, answer an offered crown,
 * confirm an heir, review a blocked succession, renounce, file and decide claims, and grant, answer, resign and revoke
 * court titles. Every choice is checked again by the server when the task runs.
 */
final class DynastyTasks {
    private DynastyTasks() {
    }

    private static Component d(String key, String fallback, Object... args) {
        return tr("dynasty.mcakingdoms." + key, fallback, args);
    }

    private static DynastyService service(Context c) {
        return DynastyService.get(c.server()).orElseThrow(() -> new InteractionNetwork.FormException(d("refusal.politics_unavailable", "Political records are unavailable on this server.")));
    }

    private static String version(Context c) {
        return DynastySavedData.get(c.server()).records().revision() + ":" + KingdomsPoliticsApi.get(c.server()).revision();
    }

    /** The seat of a landed rank: no seat, or one known settlement of the chosen kingdom. */
    private static List<Choice> seats(Context c) {
        var out = new ArrayList<Choice>();
        out.add(Choice.of("", d("seat.none", "No seat: a rank without lands")));
        String kingdom = c.text("kingdom");
        for (var settlement : c.choices("settlement"))
            if (settlement.metadata().getOrDefault("kingdom", "").equals(kingdom)) out.add(settlement);
        return out;
    }

    static void init() {
        targets("succession_law", c -> SuccessionLaw.all().values().stream().map(law -> Choice.of(law.id().toString(), DynastyService.lawTitle(law), switch (law.mode()) {
            case MANUAL_ONLY -> d("law.choice.manual", "Officials name and seat the successor by hand (the default).");
            case ELECTIVE_MONARCHY -> d("law.choice.elective", "The government's election fills a vacancy; lineage is never read.");
            case COUNCIL_CONFIRMATION -> d("law.choice.council", "The named successor, then lineage; the council confirms the heir.");
            case DESIGNATED_HEIR_THEN_LINEAGE -> d("law.choice.designated", "The named successor first, then children, grandchildren and siblings.");
            case EQUAL_PRIMOGENITURE -> d("law.choice.equal", "Children, grandchildren and siblings in a fixed order, whatever their gender.");
            case CUSTOM_DATAPACK -> d("law.choice.custom", "Defined by a data pack on this server.");
        })).toList());
        targets("noble_rank", c -> NobleRank.all().stream().map(rank -> Choice.of(rank.id().toString(), Component.translatableWithFallback(rank.titleKey(), ActionRegistry.words(rank.id().getPath())),
                rank.landed() ? d("rank.landed", "Granted for one settlement of the kingdom") : rank.court() ? d("rank.court", "A court function") : d("rank.landless", "A rank without lands"))).toList());
        targets("claim_kind", c -> Arrays.stream(DynastySavedData.ClaimKind.values()).map(kind -> Choice.of(kind.name().toLowerCase(Locale.ROOT), enumLabel(kind), switch (kind) {
            case CONTEST -> d("claim_kind.contest", "Ask the crown to place you first in the succession.");
            case RECOGNITION -> d("claim_kind.recognition", "Ask to be recognized as a member of the ruling house.");
            case RESTORATION -> d("claim_kind.restoration", "Ask to have your renounced place restored.");
        })).toList());
        targets("review_choice", c -> List.of(Choice.of("retry", d("review.retry", "Ask the law again from current evidence")),
                Choice.of("set_aside", d("review.set_aside", "Leave it to the officials to name and seat a successor"))));
        targets("offered_crown", c -> cases(c, k -> k.status() == DynastySavedData.Status.CLAIMS_PENDING && k.pending() == DynastySavedData.Pending.ACCEPTANCE
                && k.heir() != null && k.heir().id().equals(c.player.getUUID())));
        targets("confirmation_case", c -> cases(c, k -> k.status() == DynastySavedData.Status.CLAIMS_PENDING && k.pending() == DynastySavedData.Pending.CONFIRMATION));
        targets("succession_case", c -> cases(c, k -> true));
        targets("open_claim", c -> claims(c, claim -> claim.state() == DynastySavedData.ClaimState.OPEN && !claim.claimant().equals(c.player.getUUID())));
        targets("own_claim", c -> claims(c, claim -> claim.state() == DynastySavedData.ClaimState.OPEN && claim.claimant().equals(c.player.getUUID())));
        targets("offered_title", c -> titles(c, t -> t.state() == DynastySavedData.TitleState.OFFERED && t.recipient().id().equals(c.player.getUUID()), null));
        targets("own_title", c -> titles(c, t -> t.state().current() && t.recipient().id().equals(c.player.getUUID()), null));
        targets("court_title", c -> titles(c, t -> t.state().current(), c.text("kingdom")));
        targetHint("offered_crown", d("hint.offered_crown", "No crown awaits your answer."));
        targetHint("confirmation_case", d("hint.confirmation_case", "No heir awaits the council's confirmation."));
        targetHint("succession_case", d("hint.succession_case", "No succession is under way."));
        targetHint("open_claim", d("hint.open_claim", "No claim awaits a decision."));
        targetHint("own_claim", d("hint.own_claim", "You have no open claim."));
        targetHint("offered_title", d("hint.offered_title", "No title awaits your answer."));
        targetHint("own_title", d("hint.own_title", "You hold no court title."));
        targetHint("court_title", d("hint.court_title", "This kingdom has no court titles in force."));
        targetHint("noble_rank", d("hint.noble_rank", "No court ranks are defined on this server."));

        add(new Task("dynasty.court", "Government", "Read the court and succession",
                "Shows a kingdom's law of succession, the legitimacy reading, the succession under way and why its heir, candidates and claims you may see, court titles and memorials. Read-only.",
                false, false, List.of(pick("kingdom", "Kingdom", "kingdom")), c -> "", c -> {
                    var service = service(c);
                    String kingdom = c.text("kingdom");
                    var lines = new ArrayList<Object>();
                    for (var row : DynastyPages.present(service, c.player, kingdom, DynastyPages.rows(service, c.player, kingdom)))
                        lines.add(d("court.line", "%s — %s", row.title(), row.detail()));
                    return lines;
                }));
        add(new Task("dynasty.law", "Government", "Adopt a law of succession",
                "The leader or an operator chooses how the next vacancy of the leadership is decided. Manual only, the default, leaves it to the officials as before. Refused while a succession is under way.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom"), pick("law", "Law of succession", "succession_law")), DynastyTasks::version,
                c -> service(c).adoptLaw(c.player, UUID.randomUUID(), c.text("kingdom"), new ResourceLocation(c.text("law")))));
        add(new Task("dynasty.answer", "Government", "Answer an offered crown",
                "When the law names you heir, accept the crown to be seated as leader, or decline it. Declining renounces your place, and the next heir is considered.",
                false, true, List.of(pick("kingdom", "Kingdom", "offered_crown"), toggle("accept", "Accept the crown")), DynastyTasks::version,
                c -> service(c).answerCrown(c.player, UUID.randomUUID(), c.text("kingdom"), Boolean.parseBoolean(c.text("accept")))));
        add(new Task("dynasty.confirm", "Government", "Confirm or object to an heir",
                "For the council (player officials and mandate holders) or an operator, when the law asks the council to confirm its heir. A majority of the council decides either way.",
                false, true, List.of(pick("kingdom", "Kingdom", "confirmation_case"), toggle("approve", "Confirm the heir")), DynastyTasks::version,
                c -> service(c).confirm(c.player, UUID.randomUUID(), c.text("kingdom"), Boolean.parseBoolean(c.text("approve")))));
        add(new Task("dynasty.review", "Government", "Review a succession",
                "For an official with the Appoint officials responsibility, or an operator: ask the law again once missing evidence is available, or leave the succession to the officials.",
                false, true, List.of(pick("kingdom", "Kingdom", "succession_case"), pick("choice", "Decision", "review_choice")), DynastyTasks::version,
                c -> service(c).review(c.player, UUID.randomUUID(), c.text("kingdom"), c.text("choice").equals("retry"))));
        add(new Task("dynasty.renounce", "Government", "Renounce your place in a succession",
                "Leaves a kingdom's line of succession, if you are in it. Only the crown can restore your place, when you ask with a claim.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom")), DynastyTasks::version,
                c -> service(c).renounce(c.player, UUID.randomUUID(), c.text("kingdom"))));
        add(new Task("dynasty.claim", "Government", "File a claim with the crown",
                "Contest the order of succession, ask to be recognized as a member of the ruling house, or ask for a renounced place back. The crown decides; every decision is kept, and a dismissed claim may be filed again after a wait.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom"), pick("kind", "Kind of claim", "claim_kind"), text("text", "Statement", "", true)), DynastyTasks::version,
                c -> service(c).fileClaim(c.player, UUID.randomUUID(), c.text("kingdom"), DynastySavedData.ClaimKind.valueOf(c.text("kind").toUpperCase(Locale.ROOT)), c.text("text"))));
        add(new Task("dynasty.withdraw", "Government", "Withdraw your claim",
                "Withdraws one of your open claims. Its record is kept.",
                false, true, List.of(pick("claim", "Claim", "own_claim")), DynastyTasks::version,
                c -> service(c).withdrawClaim(c.player, UUID.randomUUID(), c.uuid("claim"))));
        add(new Task("dynasty.decide", "Government", "Decide a claim",
                "For an official with the Appoint officials responsibility, or an operator; never the claimant. An upheld claim changes the succession only from this decision on.",
                false, true, List.of(pick("claim", "Claim", "open_claim"), toggle("uphold", "Uphold the claim")), DynastyTasks::version,
                c -> service(c).decideClaim(c.player, UUID.randomUUID(), c.uuid("claim"), Boolean.parseBoolean(c.text("uphold")))));
        add(new Task("dynasty.grant_title", "Government", "Grant a court title",
                "For an official with the Award honors responsibility, or an operator. A court rank is ceremonial: it grants no authority, office or command. A player recipient accepts it first; landed ranks name one settlement of the kingdom.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom"), pick("rank", "Rank", "noble_rank"), pick("person", "Person", "person"),
                        pick("settlement", "Seat, for landed ranks", DynastyTasks::seats).after("kingdom"), number("days", "Days it lasts (0 for no end)", 0, Constraint.range(0, 365, "days", 30))),
                DynastyTasks::version, c -> {
                    var entity = BasicTargets.entity(c, "person");
                    var recipient = new Politics.Person(entity.getUUID(), entity instanceof Player ? Politics.Kind.PLAYER : Politics.Kind.NPC);
                    UUID seat = c.text("settlement").isBlank() ? null : c.uuid("settlement");
                    return service(c).grantTitle(c.player, UUID.randomUUID(), c.text("kingdom"), new ResourceLocation(c.text("rank")), recipient, seat,
                            c.integer("days", Constraint.range(0, 365, "days", 30)));
                }));
        add(new Task("dynasty.answer_title", "Government", "Answer an offered title",
                "Accept a court rank you were offered, or decline it.",
                false, true, List.of(pick("title", "Title", "offered_title"), toggle("accept", "Accept the title")), DynastyTasks::version,
                c -> service(c).answerTitle(c.player, UUID.randomUUID(), c.uuid("title"), Boolean.parseBoolean(c.text("accept")))));
        add(new Task("dynasty.resign_title", "Government", "Resign a court title",
                "Resign one of your court ranks with honor. Its record is kept.",
                false, true, List.of(pick("title", "Title", "own_title")), DynastyTasks::version,
                c -> service(c).resignTitle(c.player, UUID.randomUUID(), c.uuid("title"))));
        add(new Task("dynasty.revoke_title", "Government", "Revoke a court title",
                "For the title's grantor, an official with the Award honors responsibility, or an operator. Its record is kept, and any charter of it reads as historical.",
                false, true, List.of(pick("kingdom", "Kingdom", "kingdom"), pick("title", "Title", "court_title").after("kingdom")), DynastyTasks::version,
                c -> service(c).revokeTitle(c.player, UUID.randomUUID(), c.uuid("title"))));
    }

    private static List<Choice> cases(Context c, java.util.function.Predicate<DynastySavedData.Case> filter) {
        var out = new ArrayList<Choice>();
        var records = DynastySavedData.get(c.server()).records();
        var seen = new HashSet<String>();
        for (var k : records.cases())
            if (!k.status().closed() && filter.test(k) && seen.add(k.kingdom()) && records.current(k.kingdom()).map(current -> current.id().equals(k.id())).orElse(false))
                out.add(Choice.of(k.kingdom(), Names.kingdomName(c.server(), k.kingdom()), d("case.choice", "Succession of %s · %s", k.rulerName(), enumLabel(k.status()))));
        return out;
    }

    private static List<Choice> claims(Context c, java.util.function.Predicate<DynastySavedData.Claim> filter) {
        var out = new ArrayList<Choice>();
        for (var claim : DynastySavedData.get(c.server()).records().claims())
            if (filter.test(claim)) out.add(Choice.of(claim.id().toString(), d("claim.choice", "%s: %s", enumLabel(claim.kind()), claim.claimantName()),
                    Names.kingdomName(c.server(), claim.kingdom())));
        return out;
    }

    private static List<Choice> titles(Context c, java.util.function.Predicate<DynastySavedData.Title> filter, String kingdom) {
        var out = new ArrayList<Choice>();
        for (var title : DynastySavedData.get(c.server()).records().titles())
            if ((kingdom == null || kingdom.isBlank() || title.kingdom().equals(kingdom)) && filter.test(title))
                out.add(Choice.of(title.id().toString(), d("title.choice", "%s · %s", NobleRank.get(title.rank())
                        .map(rank -> (Component) Component.translatableWithFallback(rank.titleKey(title.kingdom()), ActionRegistry.words(rank.id().getPath())))
                        .orElse(Component.literal(title.rank().toString())), title.recipientName()), Names.kingdomName(c.server(), title.kingdom())));
        return out;
    }
}
