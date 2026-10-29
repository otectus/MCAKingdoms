package dev.otectus.mcakingdoms.politics.dynasty;

import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Names;
import dev.otectus.mcakingdoms.interaction.Notify;
import dev.otectus.mcakingdoms.politics.GovernmentActionPolicy;
import dev.otectus.mcakingdoms.politics.GovernmentService;
import dev.otectus.mcakingdoms.politics.dynasty.DynastySavedData.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Dynastic succession and the court (A1-A4), layered on the government's existing interregnum, regency, election and
 * succession records rather than beside them.
 *
 * <p>When a ruler's office falls vacant (an observed death, an abdication, a removal) one succession case opens, keyed
 * by the ruler and the seat they held, so a repeated notice or a restart never opens another. The kingdom's law then
 * decides it on the next maintenance pass: {@code mcakingdoms:manual_only} (every government's default) and elective
 * laws leave it to the officials and elections exactly as before; a lineage law reads MCA's family records and seats
 * an adult NPC heir, offers the crown to a player heir to accept, asks the council to confirm, or keeps a minor heir
 * under a regency until a verified adulthood. A ruler seated by election is never replaced by lineage, and an elected
 * successor always comes first. Evidence that cannot be verified blocks the case for review instead of being guessed.
 * Crowns that MCA Capitals governs are never decided here.
 *
 * <p>Every player decision carries a request id and is written before it takes effect; system steps are idempotent by
 * case id, on both stores. Titles are ceremonial and grant no authority; legitimacy is a reading, not a power.
 */
public final class DynastyService {
    static final String P = "dynasty.mcakingdoms.";
    private static final Logger LOG = LogUtils.getLogger();
    /** Ticks between automatic retries of a case blocked only because evidence was not loaded. */
    static final long RETRY_TICKS = 600;
    /** Most cases one maintenance pass advances. */
    static final int CASE_BUDGET = 8;
    static final String UNVERIFIED = P + "outcome.unverified";

    private final MinecraftServer server;
    private final GovernmentService politics;
    private final DynastySavedData data;
    private final Function<MinecraftServer, Lineage.Source> lineage;
    private final BiPredicate<String, UUID> residentNpc;

    /** A service over explicit parts; GameTests pass their own government service, store and lineage. */
    public DynastyService(MinecraftServer server, GovernmentService politics, DynastySavedData data, Function<MinecraftServer, Lineage.Source> lineage,
                          BiPredicate<String, UUID> residentNpc) {
        this.server = server; this.politics = politics; this.data = data; this.lineage = lineage; this.residentNpc = residentNpc;
    }

    /** The live service, when the server's political service is this mod's. */
    public static Optional<DynastyService> get(MinecraftServer server) {
        if (!(KingdomsPoliticsApi.get(server) instanceof GovernmentService government)) return Optional.empty();
        return Optional.of(of(server, government));
    }

    static DynastyService require(MinecraftServer server) {
        return get(server).orElseThrow(() -> Keyed.refuse(P + "refusal.politics_unavailable", "Political records are unavailable on this server."));
    }

    /** The service over {@code politics}, which may be a GameTest's own government service, with MCA lineage. */
    public static DynastyService of(MinecraftServer server, GovernmentService politics) {
        return new DynastyService(server, politics, DynastySavedData.get(server), LineageAdapter::source, (kingdom, id) -> resident(server, kingdom, id));
    }

    public MinecraftServer server() { return server; }
    public DynastySavedData data() { return data; }
    public GovernmentService politics() { return politics; }

    // --- reads -------------------------------------------------------------------------------------------------------

    long now() { return server.overworld().getGameTime(); }

    public SuccessionLaw law(String kingdom) {
        var choice = data.records().law(kingdom).map(LawChoice::law).orElse(SuccessionLaw.MANUAL);
        return SuccessionLaw.get(choice).orElseGet(() -> SuccessionLaw.get(SuccessionLaw.MANUAL).orElseThrow());
    }

    /** The adopted law's id even when a data pack no longer defines it. */
    public ResourceLocation lawId(String kingdom) {
        return data.records().law(kingdom).map(LawChoice::law).orElse(SuccessionLaw.MANUAL);
    }

    public Optional<Case> currentCase(String kingdom) { return data.records().current(kingdom); }

    public Optional<Mourning> mourning(String kingdom) {
        return data.records().mourning(kingdom).filter(m -> now() < m.endsAt());
    }

    /** Whether {@code kingdom} mourns now (for its banners); false when the dynasty is off or its records are unreadable. */
    public static boolean mourning(MinecraftServer server, String kingdom) {
        if (!DynastyConfig.enabled()) return false;
        try {
            return DynastySavedData.get(server).records().mourning(kingdom).filter(m -> server.overworld().getGameTime() < m.endsAt()).isPresent();
        } catch (RuntimeException unavailable) { return false; }
    }

    static Person leader(Government g) {
        var office = g == null ? null : g.offices().get(GovernmentActionPolicy.LEADER);
        return office == null ? null : office.holder();
    }

    private Government government(String kingdom) {
        return politics.government(kingdom).orElseThrow(() -> Keyed.refuse(P + "refusal.no_government", "That kingdom has no government."));
    }

    private void nativeCrown(String kingdom) {
        var crown = politics.sovereignty(kingdom);
        if (!crown.native_()) throw Keyed.of(crown.refusal());
    }

    static boolean operator(ServerPlayer player) { return player.hasPermissions(2); }

    /** Whether {@code viewer} sits at court: an operator, a council member, or the regent. Court members read private succession evidence. */
    public boolean court(ServerPlayer viewer, String kingdom) {
        if (operator(viewer) || politics.council(kingdom).contains(viewer.getUUID())) return true;
        return politics.regency(kingdom).filter(r -> r.active() && now() < r.expiresAt() && r.regent().id().equals(viewer.getUUID())).isPresent();
    }

    /** A person's name as players read it: online player, loaded entity, then MCA's record. */
    String name(UUID id) {
        String known = Names.person(server, id);
        return known.startsWith("an ") || known.equals("Player (offline)") ? dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence.name(server, id).orElse(known) : known;
    }

    Component kingdomName(String kingdom) { return Names.kingdomName(server, kingdom); }

    public static Component lawTitle(SuccessionLaw law) {
        return Component.translatableWithFallback(law.titleKey(), ActionRegistry.words(law.id().getPath()));
    }

    public static Component rankTitle(NobleRank rank, String kingdom) {
        return Component.translatableWithFallback(rank.titleKey(kingdom), ActionRegistry.words(rank.id().getPath()));
    }

    public static Component rankTitle(ResourceLocation rank, String kingdom) {
        return NobleRank.get(rank).map(r -> rankTitle(r, kingdom)).orElse(Component.literal(ActionRegistry.words(rank.getPath())));
    }

    /** Who would succeed now if the leadership fell vacant, and why: the law's answer over current evidence, read-only. */
    public SuccessionResolver.Resolution preview(String kingdom) {
        var g = politics.government(kingdom).orElse(null);
        Person ruler = leader(g);
        var records = data.records();
        UUID rulerId = ruler != null ? ruler.id() : records.current(kingdom).map(c -> c.ruler().id()).orElse(null);
        return resolve(kingdom, law(kingdom), g, rulerId, records);
    }

    private SuccessionResolver.Resolution resolve(String kingdom, SuccessionLaw law, Government g, UUID ruler, Records records) {
        var named = new ArrayList<Map.Entry<UUID, Lineage.Relation>>();
        Person successor = g == null ? null : g.successor();
        boolean electedSuccessor = successor != null && politics.electedHolder(kingdom).map(successor.id()::equals).orElse(false);
        if (electedSuccessor) named.add(Map.entry(successor.id(), Lineage.Relation.ELECTED));
        records.priority(kingdom).ifPresent(claimant -> named.add(Map.entry(claimant, Lineage.Relation.CLAIMANT)));
        if (law.designatedFirst() && successor != null && !electedSuccessor) named.add(Map.entry(successor.id(), Lineage.Relation.DESIGNATED));
        var source = lineage.apply(server);
        return SuccessionResolver.resolve(law, source, ruler, named, records.recognized(kingdom), records.renounced(kingdom), id -> eligibleKind(kingdom, source, id));
    }

    private boolean eligibleKind(String kingdom, Lineage.Source source, UUID id) {
        var node = source.node(id);
        if (node.isEmpty()) return false;
        if (node.get().player()) return politics.knownPlayer(id);
        return residentNpc.test(kingdom, id);
    }

    /** A loaded, living villager whose recorded civic residence lies in {@code kingdom}. */
    static boolean resident(MinecraftServer server, String kingdom, UUID id) {
        Entity entity = null;
        for (ServerLevel level : server.getAllLevels()) { entity = level.getEntity(id); if (entity != null) break; }
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || entity instanceof Player) return false;
        var api = McaKingdomsApi.get(server);
        return api.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement).flatMap(api::getSettlement)
                .map(settlement -> settlement.kingdomId().toString().equals(kingdom)).orElse(false);
    }

    /** Whether {@code person} stands in {@code kingdom}'s line now: the named successor, an upheld claimant, a recognized member, or a ruler's recorded relative. */
    boolean inLine(String kingdom, UUID person, Records records) {
        var g = politics.government(kingdom).orElse(null);
        if (g != null && g.successor() != null && g.successor().id().equals(person)) return true;
        if (records.priority(kingdom).map(person::equals).orElse(false) || records.recognized(kingdom).contains(person)) return true;
        var source = lineage.apply(server);
        for (UUID ruler : new LinkedHashSet<>(java.util.stream.Stream.concat(java.util.stream.Stream.ofNullable(leader(g)).map(Person::id),
                records.current(kingdom).map(c -> c.ruler().id()).stream()).toList()))
            if (Lineage.relatives(source, ruler, SuccessionResolver.LIMIT).stream().anyMatch(e -> e.getKey().equals(person))) return true;
        return false;
    }

    // --- legitimacy, titles ------------------------------------------------------------------------------------------

    /** The seated ruler's legitimacy reading, or empty when the leadership is vacant. */
    public Optional<Legitimacy> legitimacy(String kingdom) {
        var g = politics.government(kingdom).orElse(null);
        Person ruler = leader(g);
        if (ruler == null) return Optional.empty();
        var records = data.records();
        var seated = records.cases().stream().filter(c -> c.kingdom().equals(kingdom) && c.status() == Status.RATIFIED_SUCCESSION && ruler.equals(c.heir()))
                .max(Comparator.comparingLong(Case::updatedAt)).orElse(null);
        boolean byLaw = seated != null;
        boolean lineage = byLaw && seated.candidates().stream().anyMatch(c -> c.id().equals(ruler.id()) && c.relation().lineage());
        boolean council = byLaw && !seated.confirmations().isEmpty();
        boolean elected = politics.electedHolder(kingdom).map(ruler.id()::equals).orElse(false);
        int agreements = politics.activeAgreements(kingdom).size();
        int officials = Math.max(0, g.offices().size() - 1);
        int contests = (int) records.claims().stream().filter(c -> c.kingdom().equals(kingdom) && c.kind() == ClaimKind.CONTEST && c.state() == ClaimState.OPEN).count();
        return Optional.of(Legitimacy.assess(new Legitimacy.Facts(byLaw, lineage, council, elected, agreements, officials, contests), DynastyConfig.weights()));
    }

    /** Whether the title a charter names is in force now (B2). */
    public static boolean titleActive(MinecraftServer server, String record) {
        UUID id;
        try { id = UUID.fromString(record); } catch (IllegalArgumentException invalid) { return false; }
        var title = DynastySavedData.get(server).records().titles.get(id);
        return title != null && title.state() == TitleState.ACTIVE && (title.expiresAt() == 0 || server.overworld().getGameTime() < title.expiresAt());
    }

    /** The kingdom's titles in force, as choices for a title-grant charter. */
    public static List<ActionRegistry.Choice> titleChoices(ActionRegistry.Context c, String kingdom) {
        var out = new ArrayList<ActionRegistry.Choice>();
        for (var title : DynastySavedData.get(c.server()).records().titles())
            if (title.kingdom().equals(kingdom) && titleActive(c.server(), title.id().toString()))
                out.add(ActionRegistry.Choice.of(title.id().toString(), ActionRegistry.tr(P + "title.choice", "%s · %s", rankTitle(title.rank(), kingdom), title.recipientName())));
        return out;
    }

    /** What a title-grant charter records: whom, under which rank title. */
    public record TitleCharter(UUID subject, String subjectName, String titleKey) { }

    /** The title a charter will prove; refused unless it is in force and the actor may award honors in the kingdom. */
    public static TitleCharter titleForCharter(ServerPlayer actor, String kingdom, String record) {
        var service = require(actor.getServer());
        service.nativeCrown(kingdom);
        Keyed.require(operator(actor) || service.politics.permitted(actor.getUUID(), kingdom, Permission.HONOR), "document.mcakingdoms.need_honor_title",
                "Title documents need the Award honors responsibility in this kingdom.");
        Keyed.require(titleActive(actor.getServer(), record), "document.mcakingdoms.no_title", "That title is not in force in this kingdom.");
        var title = service.data.records().titles.get(UUID.fromString(record));
        Keyed.require(title.kingdom().equals(kingdom), "document.mcakingdoms.no_title", "That title is not in force in this kingdom.");
        var rank = NobleRank.get(title.rank());
        return new TitleCharter(title.recipient().id(), title.recipientName(), rank.map(r -> r.titleKey(kingdom)).orElse(title.rank().toString()));
    }

    // --- player decisions --------------------------------------------------------------------------------------------

    private record Change(Records next, List<Runnable> after) { }

    /** One decision: replayed from its receipt, or validated, written and only then followed by its notices and political steps. */
    private String change(ServerPlayer actor, UUID request, String fingerprint, Function<Change, String> body) {
        Keyed.require(actor.getServer() == server && server.isSameThread() && !actor.hasDisconnected(), P + "refusal.connected", "You must be connected to this server.");
        Keyed.require(DynastyConfig.enabled(), P + "refusal.disabled", "Dynastic succession is turned off on this server.");
        var replay = data.receipt(request);
        if (replay.isPresent()) {
            Keyed.require(replay.get().actor().equals(actor.getUUID()) && replay.get().fingerprint().equals(fingerprint), P + "refusal.request_used",
                    "That request was already used for a different decision.");
            return replay.get().result();
        }
        Keyed.require(data.writable(), P + "refusal.read_only", "The dynasty records are kept read-only on this server, so nothing can change them now.");
        var change = new Change(data.transaction(), new ArrayList<>());
        String result = body.apply(change);
        change.next().receipts.put(request, new DynastySavedData.Receipt(actor.getUUID(), fingerprint, result));
        Keyed.require(data.commit(server, change.next()), P + "refusal.save_failed", "The dynasty records could not be saved, so nothing changed.");
        runAll(change.after());
        return result;
    }

    private static void runAll(List<Runnable> steps) {
        for (Runnable step : steps) {
            try { step.run(); }
            catch (RuntimeException failure) { LOG.error("[MCA: Kingdoms] A dynasty notice or follow-up step failed after its record was saved", failure); }
        }
    }

    /** Adopts {@code lawId} for the kingdom's future vacancies; the leader or an operator, never during a succession. */
    public String adoptLaw(ServerPlayer actor, UUID request, String kingdom, ResourceLocation lawId) {
        return change(actor, request, "law|" + kingdom + "|" + lawId, c -> {
            nativeCrown(kingdom);
            var g = government(kingdom);
            Person ruler = leader(g);
            Keyed.require(operator(actor) || ruler != null && ruler.equals(new Person(actor.getUUID(), Kind.PLAYER)), P + "refusal.law_authority",
                    "Only the kingdom's leader or an operator adopts its law of succession.");
            var law = SuccessionLaw.get(lawId).orElseThrow(() -> Keyed.refuse(P + "refusal.unknown_law", "That law of succession is not defined on this server."));
            Keyed.require(c.next().current(kingdom).filter(k -> k.status().active()).isEmpty(), P + "refusal.case_open", "A succession is under way; the law cannot change until it ends.");
            Keyed.require(!c.next().law(kingdom).map(LawChoice::law).orElse(SuccessionLaw.MANUAL).equals(lawId), P + "refusal.same_law", "The kingdom already follows that law.");
            if (lawId.equals(SuccessionLaw.MANUAL)) c.next().laws.remove(kingdom);
            else c.next().laws.put(kingdom, new LawChoice(lawId, actor.getUUID(), now()));
            c.after().add(() -> announce(kingdom, actor.getUUID(), ActionRegistry.tr(P + "notice.law", "%s now follows the law of succession %s.", kingdomName(kingdom), lawTitle(law)), "dynasty.court"));
            return Keyed.text(P + "result.law", "%s now follows %s. It decides the next vacancy of the leadership.", kingdomName(kingdom), lawTitle(law));
        });
    }

    /** Leaves {@code kingdom}'s line of succession; only the crown can restore the place, when asked. */
    public String renounce(ServerPlayer actor, UUID request, String kingdom) {
        return change(actor, request, "renounce|" + kingdom, c -> {
            nativeCrown(kingdom);
            var g = government(kingdom);
            UUID me = actor.getUUID();
            Keyed.require(!me.equals(Optional.ofNullable(leader(g)).map(Person::id).orElse(null)), P + "refusal.ruler_renounce", "A ruler leaves the throne by abdicating, not by renouncing.");
            var renounced = c.next().renounced.computeIfAbsent(kingdom, k -> new TreeSet<>());
            Keyed.require(!renounced.contains(me), P + "refusal.already_renounced", "You have already renounced your place in this kingdom's succession.");
            Keyed.require(inLine(kingdom, me, c.next()), P + "refusal.not_in_line", "You have no place in this kingdom's line of succession to renounce.");
            Keyed.require(renounced.size() < DynastySavedData.PER_KINGDOM, P + "refusal.full", "This kingdom's succession records are full.");
            renounced.add(me);
            c.next().priority.remove(kingdom, me);
            reconsider(c.next(), kingdom);
            c.after().add(() -> announce(kingdom, me, ActionRegistry.tr(P + "notice.renounced", "%s renounced their place in the succession of %s.", actor.getGameProfile().getName(), kingdomName(kingdom)), "dynasty.court"));
            return Keyed.text(P + "result.renounced", "You renounced your place in the succession of %s. Only the crown can restore it, if you ask.", kingdomName(kingdom));
        });
    }

    /** Files a claim with the crown: a contest of the order, recognition as a member of the house, or restoration after renouncing. */
    public String fileClaim(ServerPlayer actor, UUID request, String kingdom, ClaimKind kind, String statement) {
        String words = statement == null ? "" : statement.strip();
        return change(actor, request, "claim|" + kingdom + "|" + kind + "|" + words, c -> {
            nativeCrown(kingdom);
            var g = government(kingdom);
            UUID me = actor.getUUID();
            Keyed.require(words.length() <= 256 && words.chars().noneMatch(Character::isISOControl), P + "refusal.statement", "Write the statement in at most 256 plain characters.");
            Keyed.require(!me.equals(Optional.ofNullable(leader(g)).map(Person::id).orElse(null)), P + "refusal.ruler_claim", "The ruler already holds the crown.");
            var claims = c.next().claims;
            Keyed.require(claims.values().stream().noneMatch(x -> x.kingdom().equals(kingdom) && x.claimant().equals(me) && x.state() == ClaimState.OPEN),
                    P + "refusal.claim_open", "You already have an open claim in this kingdom; wait for its decision or withdraw it.");
            long now = now(), cooldown = DynastyConfig.claimCooldownTicks();
            var last = claims.values().stream().filter(x -> x.kingdom().equals(kingdom) && x.claimant().equals(me) && x.kind() == kind
                    && (x.state() == ClaimState.UPHELD || x.state() == ClaimState.DISMISSED)).mapToLong(Claim::decidedAt).max();
            if (last.isPresent() && now - last.getAsLong() < cooldown)
                throw Keyed.refuse(P + "refusal.claim_cooldown", "You may file this kind of claim again in %s.", Names.durationText(cooldown - (now - last.getAsLong())));
            switch (kind) {
                case CONTEST -> {
                    Keyed.require(!c.next().renounced(kingdom).contains(me), P + "refusal.renounced", "You renounced your place; ask the crown for restoration first.");
                    Keyed.require(!c.next().priority(kingdom).map(me::equals).orElse(false), P + "refusal.already_first", "The crown already upheld your claim; you stand first in the succession.");
                }
                case RECOGNITION -> Keyed.require(!c.next().recognized(kingdom).contains(me), P + "refusal.already_recognized", "The crown already recognizes you as a member of the ruling house.");
                case RESTORATION -> Keyed.require(c.next().renounced(kingdom).contains(me), P + "refusal.not_renounced", "You have not renounced your place, so there is nothing to restore.");
            }
            Keyed.require(claims.size() < DynastySavedData.CLAIMS, P + "refusal.claims_full", "The claim register is full on this server.");
            UUID id = UUID.nameUUIDFromBytes(("claim|" + request).getBytes(StandardCharsets.UTF_8));
            claims.put(id, new Claim(id, kingdom, me, actor.getGameProfile().getName(), kind, ClaimState.OPEN, words, now, null, 0, c.next().revision));
            c.after().add(() -> Notify.government(server, kingdom, me, ActionRegistry.tr(P + "notice.claim", "%s filed a claim with the crown of %s: %s.",
                    actor.getGameProfile().getName(), kingdomName(kingdom), ActionRegistry.enumLabel(kind)), "dynasty.decide"));
            return Keyed.text(P + "result.claim_filed", "Your claim was filed with the crown of %s. Its decision will be recorded and kept.", kingdomName(kingdom));
        });
    }

    /** Withdraws one's own open claim. */
    public String withdrawClaim(ServerPlayer actor, UUID request, UUID claimId) {
        return change(actor, request, "withdraw|" + claimId, c -> {
            var claim = Optional.ofNullable(c.next().claims.get(claimId)).filter(x -> x.state() == ClaimState.OPEN && x.claimant().equals(actor.getUUID()))
                    .orElseThrow(() -> Keyed.refuse(P + "refusal.no_own_claim", "You have no such open claim."));
            c.next().claims.put(claimId, claim.decided(ClaimState.WITHDRAWN, actor.getUUID(), now(), c.next().revision));
            return Keyed.text(P + "result.claim_withdrawn", "Your claim was withdrawn; its record is kept.");
        });
    }

    private boolean decider(ServerPlayer actor, String kingdom) {
        return operator(actor) || politics.permitted(actor.getUUID(), kingdom, Permission.APPOINT);
    }

    /** Decides an open claim; an official who appoints, or an operator, never the claimant. An upheld claim changes the order only now, by this record. */
    public String decideClaim(ServerPlayer actor, UUID request, UUID claimId, boolean uphold) {
        return change(actor, request, "decide|" + claimId + "|" + uphold, c -> {
            var claim = Optional.ofNullable(c.next().claims.get(claimId)).filter(x -> x.state() == ClaimState.OPEN)
                    .orElseThrow(() -> Keyed.refuse(P + "refusal.no_claim", "That claim is not open."));
            String kingdom = claim.kingdom();
            nativeCrown(kingdom);
            Keyed.require(decider(actor, kingdom), P + "refusal.decide_authority", "Claims are decided by an official with the Appoint officials responsibility, or by an operator.");
            Keyed.require(!claim.claimant().equals(actor.getUUID()), P + "refusal.own_claim", "No one decides their own claim.");
            c.next().claims.put(claimId, claim.decided(uphold ? ClaimState.UPHELD : ClaimState.DISMISSED, actor.getUUID(), now(), c.next().revision));
            if (uphold) {
                switch (claim.kind()) {
                    case CONTEST -> c.next().priority.put(kingdom, claim.claimant());
                    case RECOGNITION -> {
                        var recognized = c.next().recognized.computeIfAbsent(kingdom, k -> new TreeSet<>());
                        Keyed.require(recognized.size() < DynastySavedData.PER_KINGDOM, P + "refusal.full", "This kingdom's succession records are full.");
                        recognized.add(claim.claimant());
                    }
                    case RESTORATION -> c.next().renounced.computeIfAbsent(kingdom, k -> new TreeSet<>()).remove(claim.claimant());
                }
                reconsider(c.next(), kingdom);
            }
            c.after().add(() -> Notify.player(server, claim.claimant(), actor.getUUID(), uphold
                    ? ActionRegistry.tr(P + "notice.claim_upheld", "The crown of %s upheld your claim.", kingdomName(kingdom))
                    : ActionRegistry.tr(P + "notice.claim_dismissed", "The crown of %s dismissed your claim. You may appeal after the waiting time.", kingdomName(kingdom)), "dynasty.court"));
            return uphold ? Keyed.text(P + "result.claim_upheld", "The claim of %s is upheld; the succession now reads it.", claim.claimantName())
                    : Keyed.text(P + "result.claim_dismissed", "The claim of %s is dismissed; they may appeal after the waiting time.", claim.claimantName());
        });
    }

    /** The heir's answer to an offered crown: accepting seats them; declining renounces their place and the next heir is considered. */
    public String answerCrown(ServerPlayer actor, UUID request, String kingdom, boolean accept) {
        Keyed.require(actor.getServer() == server && server.isSameThread(), P + "refusal.connected", "You must be connected to this server.");
        Keyed.require(DynastyConfig.enabled(), P + "refusal.disabled", "Dynastic succession is turned off on this server.");
        var offered = data.records().current(kingdom).filter(k -> k.status() == Status.CLAIMS_PENDING && k.pending() == Pending.ACCEPTANCE
                && k.heir() != null && k.heir().id().equals(actor.getUUID()));
        if (accept) {
            var k = offered.orElseThrow(() -> Keyed.refuse(P + "refusal.not_offered", "No crown awaits your answer in that kingdom."));
            nativeCrown(kingdom);
            // The political write is the decision; its receipt is keyed by the case, so it happens once. The committed notice then ratifies the case.
            var result = politics.succeedByLaw(kingdom, k.heir(), k.id(), actor.getUUID());
            if (!result.success()) throw Keyed.of(result.message());
            sync(kingdom, null);
            return Keyed.text(P + "result.accepted", "You accepted the crown of %s.", kingdomName(kingdom));
        }
        return change(actor, request, "decline|" + kingdom, c -> {
            var k = c.next().current(kingdom).filter(x -> x.status() == Status.CLAIMS_PENDING && x.pending() == Pending.ACCEPTANCE
                    && x.heir() != null && x.heir().id().equals(actor.getUUID())).orElseThrow(() -> Keyed.refuse(P + "refusal.not_offered", "No crown awaits your answer in that kingdom."));
            var renounced = c.next().renounced.computeIfAbsent(kingdom, x -> new TreeSet<>());
            Keyed.require(renounced.size() < DynastySavedData.PER_KINGDOM, P + "refusal.full", "This kingdom's succession records are full.");
            renounced.add(actor.getUUID());
            c.next().priority.remove(kingdom, actor.getUUID());
            c.next().cases.put(k.id(), k.decided(Status.VACANCY_CONFIRMED, Pending.NONE, null, k.candidates(), P + "outcome.declined", now(), 0, c.next().revision));
            c.after().add(() -> announce(kingdom, actor.getUUID(), ActionRegistry.tr(P + "notice.declined", "%s declined the crown of %s; the next heir is considered.",
                    actor.getGameProfile().getName(), kingdomName(kingdom)), "dynasty.court"));
            return Keyed.text(P + "result.declined", "You declined the crown of %s. Your place in its succession is renounced, and the next heir is considered.", kingdomName(kingdom));
        });
    }

    /** A council member's (or an operator's) confirmation of, or objection to, the heir the law chose. A majority of the council decides. */
    public String confirm(ServerPlayer actor, UUID request, String kingdom, boolean approve) {
        var seat = new ArrayList<Case>();
        String result = change(actor, request, "confirm|" + kingdom + "|" + approve, c -> {
            var k = c.next().current(kingdom).filter(x -> x.status() == Status.CLAIMS_PENDING && x.pending() == Pending.CONFIRMATION)
                    .orElseThrow(() -> Keyed.refuse(P + "refusal.no_confirmation", "No heir in that kingdom awaits the council's confirmation."));
            nativeCrown(kingdom);
            var council = politics.council(kingdom);
            UUID me = actor.getUUID();
            boolean op = operator(actor);
            Keyed.require(op || council.contains(me), P + "refusal.not_council", "Only the council (its player officials and mandate holders) or an operator confirms an heir.");
            Keyed.require(!k.confirmations().contains(me) && !k.objections().contains(me), P + "refusal.already_answered", "You have already answered for this succession.");
            var yes = new TreeSet<>(k.confirmations()); var no = new TreeSet<>(k.objections());
            (approve ? yes : no).add(me);
            int needed = council.size() / 2 + 1;
            long now = now();
            if (approve && (op || yes.size() >= needed)) {
                var confirmed = k.answers(yes, no, now, c.next().revision);
                if (k.heir().kind() == Kind.PLAYER) {
                    c.next().cases.put(k.id(), confirmed.pending(Pending.ACCEPTANCE, now + DynastyConfig.acceptanceTicks(), P + "outcome.offered", now, c.next().revision));
                    c.after().add(() -> offer(kingdom, k.heir().id()));
                } else {
                    c.next().cases.put(k.id(), confirmed);
                    seat.add(confirmed);
                }
                return Keyed.text(P + "result.confirmed", "The council confirmed %s as heir of %s.", name(k.heir().id()), kingdomName(kingdom));
            }
            if (!approve && (op || no.size() >= needed)) {
                c.next().cases.put(k.id(), k.answers(yes, no, now, c.next().revision).status(Status.BLOCKED_NEEDS_REVIEW, P + "outcome.council_rejected", now, c.next().revision));
                c.after().add(() -> announce(kingdom, me, ActionRegistry.tr(P + "notice.rejected", "The council of %s did not confirm the heir; the succession waits for review.", kingdomName(kingdom)), "dynasty.review"));
                return Keyed.text(P + "result.rejected", "The council did not confirm the heir; the succession waits for review.");
            }
            c.next().cases.put(k.id(), k.answers(yes, no, now, c.next().revision));
            return Keyed.text(P + "result.answer_recorded", "Your answer is recorded: %s of %s needed confirmations.", yes.size(), needed);
        });
        for (var k : seat) seatOrBlock(k, actor.getUUID());
        return result;
    }

    /** Asks the law again, or leaves the succession to the officials; an official who appoints, or an operator. */
    public String review(ServerPlayer actor, UUID request, String kingdom, boolean retry) {
        return change(actor, request, "review|" + kingdom + "|" + retry, c -> {
            var k = c.next().current(kingdom).orElseThrow(() -> Keyed.refuse(P + "refusal.no_case", "No succession is under way in that kingdom."));
            nativeCrown(kingdom);
            Keyed.require(decider(actor, kingdom), P + "refusal.review_authority", "A succession is reviewed by an official with the Appoint officials responsibility, or by an operator.");
            long now = now();
            if (retry) {
                Keyed.require(!lawId(kingdom).equals(SuccessionLaw.MANUAL), P + "refusal.manual_law", "This kingdom's law leaves succession to its officials, so there is nothing to ask again.");
                c.next().cases.put(k.id(), k.decided(Status.VACANCY_CONFIRMED, Pending.NONE, null, k.candidates(), P + "outcome.reconsidering", now, 0, c.next().revision));
                return Keyed.text(P + "result.retry", "The law will decide the succession of %s again from current evidence.", kingdomName(kingdom));
            }
            c.next().cases.put(k.id(), k.status(Status.LEFT_TO_OFFICIALS, P + "outcome.set_aside", now, c.next().revision));
            return Keyed.text(P + "result.set_aside", "The succession of %s is left to its officials: they name and seat a successor by hand.", kingdomName(kingdom));
        });
    }

    /** Makes the kingdom's open case decide again from current evidence (after a renunciation or an upheld claim). */
    private void reconsider(Records next, String kingdom) {
        next.current(kingdom).filter(k -> k.status().active()).ifPresent(k ->
                next.cases.put(k.id(), k.decided(Status.VACANCY_CONFIRMED, Pending.NONE, null, k.candidates(), P + "outcome.reconsidering", now(), 0, next.revision)));
    }

    // --- titles ------------------------------------------------------------------------------------------------------

    /** Grants a court rank; an official who awards honors, or an operator. A player recipient acknowledges it first. Ranks grant nothing. */
    public String grantTitle(ServerPlayer actor, UUID request, String kingdom, ResourceLocation rankId, Person recipient, UUID scope, int days) {
        return change(actor, request, "title|" + kingdom + "|" + rankId + "|" + recipient.id() + "|" + scope + "|" + days, c -> {
            nativeCrown(kingdom);
            var g = government(kingdom);
            UUID me = actor.getUUID();
            boolean official = politics.permitted(me, kingdom, Permission.HONOR);
            Keyed.require(official || operator(actor), P + "refusal.title_authority", "Court titles are granted by an official with the Award honors responsibility, or by an operator.");
            var rank = NobleRank.get(rankId).orElseThrow(() -> Keyed.refuse(P + "refusal.unknown_rank", "That court rank is not defined on this server."));
            UUID seat = null;
            if (rank.landed()) {
                Keyed.require(scope != null, P + "refusal.rank_landed", "That rank is granted for one settlement of the kingdom; choose it.");
                var settlement = McaKingdomsApi.get(server).getSettlement(scope).filter(s -> s.kingdomId().toString().equals(kingdom))
                        .orElseThrow(() -> Keyed.refuse(P + "refusal.rank_settlement", "That settlement does not belong to this kingdom."));
                seat = settlement.id();
            }
            Keyed.require(days >= 0 && days <= 365, P + "refusal.title_days", "A title lasts 0 (no end) to 365 days.");
            Keyed.require(!recipient.equals(leader(g)), P + "refusal.title_ruler", "The ruler holds the crown; court titles are for others.");
            politics.eligibility(recipient, kingdom).ifPresent(reason -> { throw Keyed.of(reason); });
            Keyed.require(c.next().titles.values().stream().noneMatch(t -> t.kingdom().equals(kingdom) && t.recipient().id().equals(recipient.id()) && t.state().current()),
                    P + "refusal.title_held", "That person already holds or has been offered a title here; it must end first.");
            Keyed.require(c.next().titles.size() < DynastySavedData.TITLES, P + "refusal.titles_full", "The title register is full on this server.");
            long now = now();
            UUID id = UUID.nameUUIDFromBytes(("title|" + request).getBytes(StandardCharsets.UTF_8));
            String who = name(recipient.id());
            var state = recipient.kind() == Kind.PLAYER ? TitleState.OFFERED : TitleState.ACTIVE;
            c.next().titles.put(id, new Title(id, kingdom, recipient, who.length() > 64 ? who.substring(0, 64) : who, rankId, me, official ? "official" : "operator", seat,
                    now, days == 0 ? 0 : now + days * 24_000L, state, null, 0, c.next().revision));
            if (state == TitleState.OFFERED) c.after().add(() -> Notify.player(server, recipient.id(), me, ActionRegistry.tr(P + "notice.title_offered",
                    "The court of %s offers you the rank of %s.", kingdomName(kingdom), rankTitle(rank, kingdom)), "dynasty.answer_title"));
            return state == TitleState.OFFERED
                    ? Keyed.text(P + "result.title_offered", "%s is offered the rank of %s; it takes effect when they accept.", who, rankTitle(rank, kingdom))
                    : Keyed.text(P + "result.title_granted", "%s now holds the rank of %s at the court of %s.", who, rankTitle(rank, kingdom), kingdomName(kingdom));
        });
    }

    private Title ownTitle(Records next, ServerPlayer actor, UUID titleId, TitleState... states) {
        var title = next.titles.get(titleId);
        if (title == null || !title.recipient().id().equals(actor.getUUID()) || !Set.of(states).contains(title.state()))
            throw Keyed.refuse(P + "refusal.no_own_title", "You hold no such title.");
        return title;
    }

    /** The recipient's acknowledgment of an offered title, or their refusal. */
    public String answerTitle(ServerPlayer actor, UUID request, UUID titleId, boolean accept) {
        return change(actor, request, "answer_title|" + titleId + "|" + accept, c -> {
            var title = ownTitle(c.next(), actor, titleId, TitleState.OFFERED);
            nativeCrown(title.kingdom());
            c.next().titles.put(titleId, accept ? new Title(title.id(), title.kingdom(), title.recipient(), title.recipientName(), title.rank(), title.grantor(), title.authority(),
                    title.scope(), title.grantedAt(), title.expiresAt(), TitleState.ACTIVE, null, 0, c.next().revision) : title.ended(TitleState.DECLINED, actor.getUUID(), now(), c.next().revision));
            return accept ? Keyed.text(P + "result.title_accepted", "You now hold the rank of %s at the court of %s.", rankTitle(title.rank(), title.kingdom()), kingdomName(title.kingdom()))
                    : Keyed.text(P + "result.title_declined", "You declined the rank of %s.", rankTitle(title.rank(), title.kingdom()));
        });
    }

    /** An honorable resignation of one's own title. */
    public String resignTitle(ServerPlayer actor, UUID request, UUID titleId) {
        return change(actor, request, "resign_title|" + titleId, c -> {
            var title = ownTitle(c.next(), actor, titleId, TitleState.ACTIVE, TitleState.OFFERED);
            c.next().titles.put(titleId, title.ended(TitleState.RESIGNED, actor.getUUID(), now(), c.next().revision));
            return Keyed.text(P + "result.title_resigned", "You resigned the rank of %s with honor; the record is kept.", rankTitle(title.rank(), title.kingdom()));
        });
    }

    /** Revokes a title; its grantor, an official who awards honors, or an operator. */
    public String revokeTitle(ServerPlayer actor, UUID request, UUID titleId) {
        return change(actor, request, "revoke_title|" + titleId, c -> {
            var title = Optional.ofNullable(c.next().titles.get(titleId)).filter(t -> t.state().current())
                    .orElseThrow(() -> Keyed.refuse(P + "refusal.no_title", "That title is not in force."));
            nativeCrown(title.kingdom());
            Keyed.require(operator(actor) || title.grantor().equals(actor.getUUID()) || politics.permitted(actor.getUUID(), title.kingdom(), Permission.HONOR),
                    P + "refusal.revoke_authority", "A title is revoked by its grantor, an official with the Award honors responsibility, or an operator.");
            c.next().titles.put(titleId, title.ended(TitleState.REVOKED, actor.getUUID(), now(), c.next().revision));
            c.after().add(() -> Notify.player(server, title.recipient().id(), actor.getUUID(), ActionRegistry.tr(P + "notice.title_revoked",
                    "The court of %s revoked your rank of %s.", kingdomName(title.kingdom()), rankTitle(title.rank(), title.kingdom())), "dynasty.court"));
            return Keyed.text(P + "result.title_revoked", "The rank of %s held by %s is revoked; the record is kept.", rankTitle(title.rank(), title.kingdom()), title.recipientName());
        });
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------------

    static UUID caseId(String kingdom, Ruler ruler) {
        return UUID.nameUUIDFromBytes(("succession|" + kingdom + "|" + ruler.person().id() + "|" + ruler.since()).getBytes(StandardCharsets.UTF_8));
    }

    /** Follows one committed political change of {@code kingdom}: the ruler seen, a vacancy opened, a case closed. */
    public void observe(Notice notice) {
        if (!DynastyConfig.enabled() || !data.writable()) return;
        sync(notice.kingdom(), notice);
    }

    /** Brings {@code kingdom}'s dynasty records in line with its government; {@code notice} names the change, when known. */
    void sync(String kingdom, Notice notice) {
        var next = data.transaction();
        var after = new ArrayList<Runnable>();
        if (follow(next, kingdom, politics.government(kingdom).orElse(null), notice, after) && data.commit(server, next)) runAll(after);
    }

    /**
     * The shared step of notices and maintenance: record the seated ruler and close the cases a seat ended; on a vacancy of
     * a known ruler's seat, open its one case (with its memorial and mourning). True when {@code next} changed.
     */
    private boolean follow(Records next, String kingdom, Government g, Notice notice, List<Runnable> after) {
        long now = now();
        boolean changed = false;
        if (g == null) {
            for (var k : List.copyOf(next.cases.values()))
                if (k.kingdom().equals(kingdom) && !k.status().closed()) { next.cases.put(k.id(), k.status(Status.SUPERSEDED, P + "outcome.government_gone", now, next.revision)); changed = true; }
            return changed;
        }
        var leaderOffice = g.offices().get(GovernmentActionPolicy.LEADER);
        if (leaderOffice != null) {
            Person holder = leaderOffice.holder();
            boolean elected = politics.electedHolder(kingdom).map(holder.id()::equals).orElse(false);
            var seen = next.rulers.get(kingdom);
            if (seen == null || !seen.person().equals(holder) || seen.since() != leaderOffice.appointedAt() || seen.elected() != elected) {
                next.rulers.put(kingdom, new Ruler(holder, leaderOffice.appointedAt(), elected)); changed = true;
            }
            for (var k : List.copyOf(next.cases.values())) {
                if (!k.kingdom().equals(kingdom) || k.status().closed()) continue;
                boolean ours = holder.equals(k.heir());
                next.cases.put(k.id(), k.status(ours ? Status.RATIFIED_SUCCESSION : Status.SUPERSEDED, ours ? P + "outcome.ratified" : P + "outcome.seated_otherwise", now, next.revision));
                changed = true;
            }
            return changed;
        }
        var ruler = next.rulers.get(kingdom);
        if (ruler == null || !politics.sovereignty(kingdom).native_()) return changed;
        UUID id = caseId(kingdom, ruler);
        if (next.cases.containsKey(id)) return changed;
        Cause cause = notice == null ? Cause.VACANCY : notice.action() == Action.ABDICATE ? Cause.ABDICATION
                : notice.action() == Action.REMOVE_OFFICE ? ruler.person().kind() == Kind.NPC && ruler.person().id().equals(notice.actor()) ? Cause.DEATH : Cause.REMOVAL : Cause.VACANCY;
        var law = next.law(kingdom).map(LawChoice::law).orElse(SuccessionLaw.MANUAL);
        Status status; String reason;
        if (ruler.elected()) { status = Status.LEFT_TO_ELECTION; reason = P + "outcome.elected_office"; }
        else if (law.equals(SuccessionLaw.MANUAL)) { status = Status.LEFT_TO_OFFICIALS; reason = P + "outcome.manual"; }
        else { status = Status.VACANCY_CONFIRMED; reason = P + "outcome.vacancy"; }
        String rulerName = bounded(name(ruler.person().id()));
        next.cases.put(id, new Case(id, kingdom, ruler.person(), rulerName, cause, status, Pending.NONE, law, null, List.of(), reason, now, now, 0, Set.of(), Set.of(), next.revision));
        pruneCases(next);
        if (cause == Cause.DEATH || cause == Cause.ABDICATION) {
            UUID memorial = UUID.nameUUIDFromBytes(("memorial|" + id).getBytes(StandardCharsets.UTF_8));
            if (next.memorials.stream().noneMatch(m -> m.id().equals(memorial)))
                next.memorials.add(new Memorial(memorial, kingdom, ruler.person(), rulerName, cause, ruler.since(), now));
        }
        long mourning = DynastyConfig.mourningTicks();
        if (cause == Cause.DEATH && mourning > 0) next.mourning.put(kingdom, new Mourning(id, ruler.person(), rulerName, now, now + mourning));
        // The death, abdication or vacancy itself is already a political notice; the herald adds only what the law now does.
        if (status == Status.VACANCY_CONFIRMED) after.add(() -> announce(kingdom, null, ActionRegistry.tr(P + "notice.opened",
                "The succession of %s has begun under the law %s.", kingdomName(kingdom), SuccessionLaw.get(law).map(DynastyService::lawTitle).orElse(Component.literal(law.toString()))), "dynasty.court"));
        return true;
    }

    private static String bounded(String name) { return name.length() > 64 ? name.substring(0, 64) : name; }

    /** Closed cases beyond the bound go first, oldest first; open cases are never dropped. */
    private static void pruneCases(Records next) {
        if (next.cases.size() <= DynastySavedData.CASES) return;
        var closed = next.cases.values().stream().filter(k -> k.status().closed()).sorted(Comparator.comparingLong(Case::updatedAt).thenComparing(Case::id)).toList();
        for (var k : closed) { if (next.cases.size() <= DynastySavedData.CASES) break; next.cases.remove(k.id()); }
    }

    /**
     * One maintenance pass: follow every kingdom's government (also catching a vacancy whose notice a crash lost), end
     * mourning and titles whose time is over, then advance at most {@link #CASE_BUDGET} open cases, oldest first.
     */
    public void maintain() {
        if (!DynastyConfig.enabled() || !data.writable()) return;
        long now = now();
        var next = data.transaction();
        var after = new ArrayList<Runnable>();
        boolean changed = false;
        var kingdoms = new TreeSet<String>();
        McaKingdomsApi.get(server).getKingdoms().forEach(k -> kingdoms.add(k.id().toString()));
        next.cases.values().forEach(k -> kingdoms.add(k.kingdom()));
        for (String kingdom : kingdoms) changed |= follow(next, kingdom, politics.government(kingdom).orElse(null), null, after);
        changed |= next.mourning.entrySet().removeIf(e -> now >= e.getValue().endsAt());
        for (var title : List.copyOf(next.titles.values()))
            if (title.state() == TitleState.ACTIVE && title.expiresAt() > 0 && now >= title.expiresAt()) { next.titles.put(title.id(), title.ended(TitleState.EXPIRED, null, now, next.revision)); changed = true; }
        if (changed) {
            if (!data.commit(server, next)) return;
            runAll(after);
        }
        var due = data.records().cases().stream().filter(k -> k.status().active()).sorted(Comparator.comparingLong(Case::updatedAt).thenComparing(Case::id)).limit(CASE_BUDGET).toList();
        for (var k : due) {
            try { advance(k, now); }
            catch (RuntimeException failure) { LOG.error("[MCA: Kingdoms] Succession case {} of {} could not advance; it is retried", k.id(), k.kingdom(), failure); }
        }
    }

    private boolean update(Case k, Function<Case, Case> change) {
        var next = data.transaction();
        var current = next.cases.get(k.id());
        if (current == null) return false;
        next.cases.put(k.id(), change.apply(current));
        return data.commit(server, next);
    }

    private void advance(Case k, long now) {
        var g = politics.government(k.kingdom()).orElse(null);
        if (g == null || leader(g) != null) return; // follow() closes it
        if (!politics.sovereignty(k.kingdom()).native_()) {
            if (k.status() != Status.BLOCKED_NEEDS_REVIEW) update(k, x -> x.status(Status.BLOCKED_NEEDS_REVIEW, P + "outcome.capitals", now, x.revision()));
            return;
        }
        switch (k.status()) {
            case VACANCY_CONFIRMED -> decide(k, g, now);
            case CLAIMS_PENDING -> {
                if (k.pending() == Pending.ACCEPTANCE && k.deadline() > 0 && now >= k.deadline()) {
                    if (update(k, x -> x.status(Status.BLOCKED_NEEDS_REVIEW, P + "outcome.acceptance_lapsed", now, x.revision())))
                        announce(k.kingdom(), null, ActionRegistry.tr(P + "notice.lapsed", "The heir of %s did not answer in time; the succession waits for review.", kingdomName(k.kingdom())), "dynasty.review");
                }
            }
            case REGENCY -> regency(k, now);
            case BLOCKED_NEEDS_REVIEW -> { if (k.reasonKey().equals(UNVERIFIED) && now - k.updatedAt() >= RETRY_TICKS) decide(k, g, now); }
            default -> { }
        }
    }

    private void decide(Case k, Government g, long now) {
        var law = SuccessionLaw.get(k.law()).orElse(null);
        if (law == null) { update(k, x -> x.status(Status.BLOCKED_NEEDS_REVIEW, P + "outcome.law_unavailable", now, x.revision())); return; }
        var records = data.records();
        if (records.ruler(k.kingdom()).map(Ruler::elected).orElse(false) && records.ruler(k.kingdom()).get().person().equals(k.ruler())) {
            update(k, x -> x.status(Status.LEFT_TO_ELECTION, P + "outcome.elected_office", now, x.revision())); return;
        }
        var resolution = resolve(k.kingdom(), law, g, k.ruler().id(), records);
        var source = lineage.apply(server);
        Person heir = resolution.heir().map(c -> new Person(c.id(), source.node(c.id()).map(Lineage.Node::player).orElse(false) ? Kind.PLAYER : Kind.NPC)).orElse(null);
        var candidates = resolution.candidates();
        switch (resolution.outcome()) {
            case MANUAL, NO_HEIR -> update(k, x -> x.decided(Status.LEFT_TO_OFFICIALS, Pending.NONE, null, candidates, resolution.reasonKey(), now, 0, x.revision()));
            case ELECTION -> update(k, x -> x.decided(Status.LEFT_TO_ELECTION, Pending.NONE, null, candidates, resolution.reasonKey(), now, 0, x.revision()));
            case BLOCKED -> update(k, x -> x.decided(Status.BLOCKED_NEEDS_REVIEW, Pending.NONE, heir, candidates, resolution.reasonKey(), now, 0, x.revision()));
            case CONFIRMATION -> {
                if (update(k, x -> x.decided(Status.CLAIMS_PENDING, Pending.CONFIRMATION, heir, candidates, resolution.reasonKey(), now, 0, x.revision())))
                    Notify.players(server, politics.council(k.kingdom()), null, ActionRegistry.tr(P + "notice.confirm", "The law of %s names %s as heir; the council is asked to confirm.",
                            kingdomName(k.kingdom()), name(heir.id())), "dynasty.confirm");
            }
            case MINOR_HEIR -> {
                if (update(k, x -> x.decided(Status.REGENCY, Pending.NONE, heir, candidates, resolution.reasonKey(), now, 0, x.revision())))
                    data.records().current(k.kingdom()).ifPresent(updated -> regency(updated, now));
            }
            case HEIR -> {
                if (heir.kind() == Kind.PLAYER) {
                    if (update(k, x -> x.decided(Status.CLAIMS_PENDING, Pending.ACCEPTANCE, heir, candidates, P + "outcome.offered", now, now + DynastyConfig.acceptanceTicks(), x.revision())))
                        offer(k.kingdom(), heir.id());
                } else if (update(k, x -> x.decided(Status.VACANCY_CONFIRMED, Pending.NONE, heir, candidates, resolution.reasonKey(), now, 0, x.revision()))) {
                    data.records().current(k.kingdom()).ifPresent(updated -> seatOrBlock(updated, null));
                }
            }
        }
    }

    /** Seats the case's heir by law; when the government refuses (not adult, not resident, unloaded), the case waits for review with that reason. */
    private void seatOrBlock(Case k, UUID authorizedBy) {
        var result = politics.succeedByLaw(k.kingdom(), k.heir(), k.id(), authorizedBy);
        if (result.success()) { sync(k.kingdom(), null); return; }
        update(k, x -> x.status(Status.BLOCKED_NEEDS_REVIEW, result.message(), now(), x.revision()));
    }

    /** A minor heir: seated once a verified adult; meanwhile a living player parent may govern as regent under the transition rule. */
    private void regency(Case k, long now) {
        if (k.heir() == null) { update(k, x -> x.decided(Status.VACANCY_CONFIRMED, Pending.NONE, null, x.candidates(), P + "outcome.reconsidering", now, 0, x.revision())); return; }
        var source = lineage.apply(server);
        var node = source.node(k.heir().id());
        if (node.isPresent() && node.get().deceased()) {
            update(k, x -> x.decided(Status.VACANCY_CONFIRMED, Pending.NONE, null, x.candidates(), P + "outcome.heir_died", now, 0, x.revision()));
            return;
        }
        if (node.isPresent() && node.get().stage() == Lineage.Stage.ADULT) {
            // Of age: an NPC heir is seated (ending the regency once); a player heir is asked first, like any player heir.
            if (k.heir().kind() == Kind.PLAYER) {
                if (update(k, x -> x.pending(Pending.ACCEPTANCE, now + DynastyConfig.acceptanceTicks(), P + "outcome.offered", now, x.revision()))) offer(k.kingdom(), k.heir().id());
            } else seatOrBlock(k, null);
            return;
        }
        boolean regent = politics.regency(k.kingdom()).filter(r -> r.active() && now < r.expiresAt()).isPresent();
        if (regent || node.isEmpty()) return;
        // A guardian regent: the heir's living parent who is a known player, lowest id first; otherwise officials appoint one.
        var guardian = node.get().parents().stream().sorted().filter(parent -> !parent.equals(k.ruler().id()))
                .filter(parent -> source.node(parent).map(p -> p.player() && !p.deceased()).orElse(false)).findFirst();
        if (guardian.isEmpty()) {
            if (!k.reasonKey().equals(P + "outcome.awaiting_regent")) update(k, x -> x.status(Status.REGENCY, P + "outcome.awaiting_regent", now, x.revision()));
            return;
        }
        var result = politics.appointRegentByLaw(k.kingdom(), new Person(guardian.get(), Kind.PLAYER), k.heir(), k.id());
        // Refused (no transition rule allowing regencies, an unknown guardian): the reason is shown, and officials may appoint a regent.
        if (!result.success() && !k.reasonKey().equals(result.message())) update(k, x -> x.status(Status.REGENCY, result.message(), now, x.revision()));
    }

    private void offer(String kingdom, UUID heir) {
        Notify.player(server, heir, null, ActionRegistry.tr(P + "notice.offered", "The law of %s names you heir. Accept or decline the crown within %s.",
                kingdomName(kingdom), Names.durationText(DynastyConfig.acceptanceTicks())), "dynasty.answer");
    }

    /** A herald announcement to the kingdom's connected officials, when announcements are on. */
    private void announce(String kingdom, UUID except, Component text, String task) {
        if (DynastyConfig.announcements()) Notify.government(server, kingdom, except, text, task);
    }
}
