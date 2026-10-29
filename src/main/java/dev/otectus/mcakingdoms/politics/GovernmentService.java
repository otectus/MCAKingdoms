package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.event.*;
import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.api.politics.PoliticalTransition.*;
import dev.otectus.mcakingdoms.api.townstead.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.ModList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/** All mutations share one authenticated, revision-checked copy-on-write transaction path. */
public final class GovernmentService implements PoliticalService {
    private static final String LEADER = "mcakingdoms:leader";
    /** Replay receipts are kept for one game day; the expectedRevision check still rejects any later resend. */
    static final long RECEIPT_RETENTION = 24_000L;
    static final int RECEIPT_CAPACITY = 8192, RECEIPT_PRUNE_BATCH = 256;
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final PoliticalDefinitions definitions;
    private final PoliticalSavedData data;
    /** Who governs each kingdom's crown (C2): this mod, or MCA Capitals for the kingdoms it governs. */
    private final java.util.function.Function<String, Sovereignty> sovereignty;
    private final java.util.function.Predicate<UUID> knownPlayer;
    private final java.util.function.BooleanSupplier openFounding;
    private final java.util.function.BooleanSupplier institutionalServices;
    /** Most deadline transitions each maintenance pass commits per domain (agreements, petitions, elections, regencies). */
    static final int MAINTENANCE_BUDGET = 8;
    private final java.util.Deque<LivingDeathEvent> pendingDeaths = new java.util.ArrayDeque<>();
    public GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions) {
        this(server, kingdoms, definitions, PoliticalSavedData.get(server), false);
    }
    GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict) {
        this(server, kingdoms, definitions, data, conflict, id -> server.getPlayerList().getPlayer(id) != null
                || server.getProfileCache() != null && server.getProfileCache().get(id).isPresent());
    }
    /** A service over {@code data} with an explicit known-player test; used by GameTests outside this package. */
    public GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict, java.util.function.Predicate<UUID> knownPlayer) {
        this(server, kingdoms, definitions, data, conflict, knownPlayer,
                () -> dev.otectus.mcakingdoms.civic.CivicConfig.INSTITUTIONAL_SERVICES.get());
    }
    GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict, java.util.function.Predicate<UUID> knownPlayer,
                      java.util.function.BooleanSupplier institutionalServices) {
        this(server, kingdoms, definitions, data, conflict, knownPlayer, institutionalServices,
                () -> dev.otectus.mcakingdoms.config.McaKingdomsConfig.OPEN_FOUNDING.get());
    }
    /** {@code openFounding} is the {@code politics.openFounding} setting; tests pass a fixed value instead of rewriting the config file. */
    GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict, java.util.function.Predicate<UUID> knownPlayer,
                      java.util.function.BooleanSupplier institutionalServices, java.util.function.BooleanSupplier openFounding) {
        this.server = server; this.kingdoms = kingdoms; this.definitions = definitions;
        this.data = data; this.knownPlayer = knownPlayer;
        // conflict = true is the legacy all-read-only answer (tests and the ALL_READ_ONLY setting); otherwise per kingdom.
        this.sovereignty = conflict ? kingdom -> new Sovereignty(Sovereignty.Mode.UNSUPPORTED, "politics.mcakingdoms.sovereignty.capitals_all_read_only")
                : kingdom -> dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop.get(server).sovereignty(kingdom);
        this.institutionalServices = institutionalServices; this.openFounding = openFounding;
    }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("Politics requires the server thread"); }
    @Override public long revision() { thread(); return data.records().revision; }
    @Override public Optional<Government> government(String kingdom) {
        thread(); Objects.requireNonNull(kingdom); return Optional.ofNullable(data.records().governments.get(kingdom));
    }
    @Override public Optional<Result> receipt(ServerPlayer actor, UUID requestId) {
        thread(); check(actor.getServer() == server && !actor.hasDisconnected(), Keyed.text("politics.mcakingdoms.refusal.disconnected","You must be connected to this server."));
        Receipt value = data.records().receipts.get(Objects.requireNonNull(requestId));
        return value != null && value.actor().equals(actor.getUUID()) ? Optional.of(value.result()) : Optional.empty();
    }
    @Override public List<ElectionView> elections(ServerPlayer viewer,int offset,int limit) {
        thread();check(offset>=0&&offset<=100000&&limit>0&&limit<=64,Keyed.text("politics.mcakingdoms.refusal.invalid_page","That page is not valid."));
        return data.records().elections.keySet().stream().sorted().map(id->election(viewer,id)).flatMap(Optional::stream).skip(offset).limit(limit).toList();
    }
    @Override public Optional<Rule> transitionRule(String kingdom) { thread(); return Optional.ofNullable(data.records().transitionRules.get(kingdom)); }
    @Override public Optional<Regency> regency(String kingdom) { thread(); return Optional.ofNullable(data.records().regencies.get(kingdom)); }
    @Override public Optional<ElectionView> election(ServerPlayer viewer, UUID id) {
        thread(); check(viewer.getServer() == server && !viewer.hasDisconnected(), Keyed.text("politics.mcakingdoms.refusal.disconnected","You must be connected to this server."));
        Election value = data.records().elections.get(id); if (value == null) return Optional.empty();
        Government government = data.records().governments.get(value.kingdom()); boolean eligible = value.electorate().contains(viewer.getUUID());
        if (!eligible && !viewer.hasPermissions(2) && !permitted(viewer, government, Permission.APPOINT)) return Optional.empty();
        return Optional.of(new ElectionView(value.id(), value.kingdom(), value.candidates(), value.state(), value.electorate().size(), value.ballots().size(),
                eligible, value.ballots().containsKey(viewer.getUUID()), value.deadline(), value.graceUntil(), value.revision(), Optional.ofNullable(value.winner())));
    }

    @Override public Result adoptTransitionRule(ServerPlayer actor,UUID request,long expected,String kingdom,Rule rule) {
        Objects.requireNonNull(rule); return transition(actor, request, expected, kingdom, Action.ADOPT_TRANSITION_RULE, PoliticalSavedData.JSON.toJson(rule), state -> {
            Government government = state.governments.get(kingdom); authorize(actor, government, Permission.APPOINT);
            check(government.offices().get(LEADER) != null && government.offices().get(LEADER).holder().equals(new Person(actor.getUUID(), Kind.PLAYER)),
                    "politics.mcakingdoms.refusal.leader_only_rules");
            check(state.elections.values().stream().noneMatch(e->e.kingdom().equals(kingdom)&&(e.state()==ElectionState.OPEN||e.state()==ElectionState.GRACE)),"politics.mcakingdoms.refusal.close_election_first");
            Regency regency=state.regencies.get(kingdom);check(regency==null||!regency.active(),"politics.mcakingdoms.refusal.end_regency_first");
            state.transitionRules.put(kingdom, rule); return kingdom;
        });
    }
    @Override public Result openElection(ServerPlayer actor,UUID request,long expected,String kingdom,List<UUID> candidates) {
        List<UUID> frozen = List.copyOf(candidates); return transition(actor, request, expected, kingdom, Action.OPEN_ELECTION, frozen.toString(), state -> {
            Government government = state.governments.get(kingdom); authorize(actor, government, Permission.APPOINT);
            Rule rule = state.transitionRules.get(kingdom); check(rule != null && rule.elections(), "politics.mcakingdoms.refusal.no_elections");
            Set<UUID> nominees = new LinkedHashSet<>(frozen); check(nominees.size() >= 2 && nominees.size() <= rule.maxCandidates(), "politics.mcakingdoms.refusal.candidate_count");
            nominees.forEach(id -> person(new Person(id, Kind.PLAYER), kingdom, null));
            check(state.elections.values().stream().noneMatch(e -> e.kingdom().equals(kingdom) && (e.state() == ElectionState.OPEN || e.state() == ElectionState.GRACE)), "politics.mcakingdoms.refusal.election_open");
            Set<UUID> electorate = electorate(government, rule); Regency regent = state.regencies.get(kingdom); if (unexpired(regent,now())) electorate.add(regent.regent().id());
            check(!electorate.isEmpty() && electorate.size() <= 128, "politics.mcakingdoms.refusal.no_electorate");
            state.elections.put(request, new Election(request, kingdom, nominees, electorate, Map.of(), ElectionState.OPEN, now(), now()+rule.electionTicks(),
                    now()+rule.electionTicks()+rule.graceTicks(), state.revision, null)); return request.toString();
        });
    }
    @Override public Result castBallot(ServerPlayer actor,UUID request,long expected,UUID election,UUID candidate) {
        Election existing = data.records().elections.get(election); if (existing == null) return new Result(false,revision(),"politics.mcakingdoms.refusal.election_unavailable","");
        return transition(actor, request, expected, existing.kingdom(), Action.CAST_BALLOT, election+"|"+candidate, state -> {
            Election value = state.elections.get(election); check(value != null && (value.state()==ElectionState.OPEN || value.state()==ElectionState.GRACE) && now()<value.graceUntil(), "politics.mcakingdoms.refusal.election_closed");
            check(value.electorate().contains(actor.getUUID()), "politics.mcakingdoms.refusal.not_in_electorate"); check(value.candidates().contains(candidate), "politics.mcakingdoms.refusal.candidate_not_on_ballot");
            Map<UUID,UUID> ballots = new LinkedHashMap<>(value.ballots()); ballots.put(actor.getUUID(), candidate);
            state.elections.put(election, new Election(value.id(),value.kingdom(),value.candidates(),value.electorate(),ballots,value.state(),value.openedAt(),value.deadline(),value.graceUntil(),state.revision,null));
            return election.toString();
        });
    }
    @Override public Result closeElection(ServerPlayer actor,UUID request,long expected,UUID election) {
        Election existing = data.records().elections.get(election); if (existing == null) return new Result(false,revision(),"politics.mcakingdoms.refusal.election_unavailable","");
        return transition(actor, request, expected, existing.kingdom(), Action.CLOSE_ELECTION, election.toString(), state -> closeElection(actor,election,state));
    }
    @Override public Result appointRegent(ServerPlayer actor,UUID request,long expected,String kingdom,UUID regentId) {
        return transition(actor, request, expected, kingdom, Action.APPOINT_REGENT, regentId.toString(), state -> {
            Government government = state.governments.get(kingdom); Rule rule = state.transitionRules.get(kingdom); check(rule != null && rule.regency(), "politics.mcakingdoms.refusal.no_regency");
            if (government.state()==State.ACTIVE) authorize(actor,government,Permission.APPOINT); else check(actor.hasPermissions(2),"politics.mcakingdoms.refusal.regency_operator");
            Regency prior = state.regencies.get(kingdom); check(prior==null || !prior.active() || now()>=prior.expiresAt(),"politics.mcakingdoms.refusal.regency_active");
            Person regent = person(new Person(regentId,Kind.PLAYER),kingdom,null); UUID id = request;
            state.regencies.put(kingdom,new Regency(id,kingdom,regent,rule.regentPermissions(),government.successor(),now(),now()+rule.regencyTicks(),true,state.revision));
            return id.toString();
        });
    }
    @Override public Result endRegency(ServerPlayer actor,UUID request,long expected,String kingdom) {
        return transition(actor, request, expected, kingdom, Action.END_REGENCY, kingdom, state -> {
            Government government=state.governments.get(kingdom);Regency value=state.regencies.get(kingdom);check(value!=null&&value.active(),"politics.mcakingdoms.refusal.no_active_regency");
            check(actor.hasPermissions(2)||value.regent().id().equals(actor.getUUID())||permitted(actor,government,Permission.APPOINT),"politics.mcakingdoms.refusal.end_regency_authority");
            state.regencies.put(kingdom,new Regency(value.id(),kingdom,value.regent(),value.permissions(),value.preservedSuccessor(),value.appointedAt(),value.expiresAt(),false,state.revision));return value.id().toString();
        });
    }

    private interface TransitionMutation { String apply(PoliticalSavedData.Records state); }
    private Result transition(ServerPlayer actor,UUID request,long expected,String kingdom,Action action,String detail,TransitionMutation mutation) {
        thread();check(actor.getServer()==server&&!actor.hasDisconnected(),Keyed.text("politics.mcakingdoms.refusal.disconnected","You must be connected to this server."));Objects.requireNonNull(request); Politics.bounded(kingdom,128);
        String fingerprint=hash(action+"|"+expected+"|"+kingdom+"|"+detail);Receipt replay=data.records().receipts.get(request);
        if(replay!=null)return replay.actor().equals(actor.getUUID())&&replay.fingerprint().equals(fingerprint)?replay.result():new Result(false,revision(),"politics.mcakingdoms.refusal.request_used","");
        try{check(data.writable(),data.diagnostic());Sovereignty crown=sovereignty.apply(kingdom);check(crown.native_(),crown.refusal());check(expected==revision(),"politics.mcakingdoms.refusal.state_changed");
            Government government=data.records().governments.get(kingdom);check(government!=null,"politics.mcakingdoms.refusal.no_government");
            check(definitions.available(government.profileId()),"politics.mcakingdoms.refusal.dormant");check(data.records().receipts.size()<RECEIPT_CAPACITY,"politics.mcakingdoms.refusal.receipts_full");
            var next=data.transaction();next.revision++;String record=mutation.apply(next);Result result=new Result(true,next.revision,Keyed.text("politics.mcakingdoms.result.transition","Constitutional transition recorded."),record);
            next.receipts.put(request,new Receipt(actor.getUUID(),fingerprint,result,now()));Notice notice=new Notice(request,kingdom,action,actor.getUUID(),record,now());append(next,notice,transitionFact(request,kingdom,action,actor.getUUID(),record,next));
            if(!data.commitDurably(server,next))return new Result(false,revision(),"politics.mcakingdoms.refusal.save_failed","");publish(notice);return result;
        }catch(IllegalArgumentException|IllegalStateException failure){return new Result(false,revision(),failure.getMessage()==null?Keyed.text("politics.mcakingdoms.refusal.transition_rejected","The constitutional transition was refused."):Keyed.text(failure),"");}
    }
    @Override public List<PoliticalFact> history(ServerPlayer viewer, int offset, int limit) {
        thread(); check(viewer.getServer() == server && !viewer.hasDisconnected(), Keyed.text("politics.mcakingdoms.refusal.disconnected","You must be connected to this server."));
        check(offset >= 0 && offset <= 100000 && limit > 0 && limit <= 50, Keyed.text("politics.mcakingdoms.refusal.invalid_history_page","That history page is not valid."));
        return visibleHistory(viewer).skip(offset).limit(limit).toList();
    }
    private long now() { return server.overworld().getGameTime(); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static void check(boolean valid, String message) { if (!valid) throw Keyed.of(message); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public Result execute(ServerPlayer actor, Request request) {
        thread();
        check(actor.getServer() == server && !actor.hasDisconnected(), Keyed.text("politics.mcakingdoms.refusal.disconnected","You must be connected to this server."));
        String fingerprint = hash(PoliticalSavedData.JSON.toJson(request));
        Receipt receipt = data.records().receipts.get(request.requestId());
        if (receipt != null) {
            return receipt.actor().equals(actor.getUUID()) && receipt.fingerprint().equals(fingerprint)
                    ? receipt.result() : new Result(false, revision(), "politics.mcakingdoms.refusal.request_used", "");
        }
        try {
            check(data.writable(), data.diagnostic());
            // Crown actions of a kingdom MCA Capitals governs stay with Capitals (C2); its civic actions continue here.
            Sovereignty crown = sovereignty.apply(request.kingdom());
            check(crown.native_() || !Sovereignty.crown(request.action(), crownOffice(request)), crown.refusal());
            check(request.expectedRevision() == revision(), "politics.mcakingdoms.refusal.state_changed");
            check(kingdoms.getKingdom(new ResourceLocation(request.kingdom())).filter(KingdomView::defined).isPresent(),
                    "politics.mcakingdoms.refusal.kingdom_undefined");
            // Receipts retire after RECEIPT_RETENTION ticks; a later replay fails the expectedRevision check instead of re-executing.
            check(data.records().receipts.size() < RECEIPT_CAPACITY, "politics.mcakingdoms.refusal.receipts_full");
            PoliticalSavedData.Records next = data.transaction();
            next.revision++;
            String id = mutate(actor, request, next);
            Result result = new Result(true, next.revision, Keyed.text("politics.mcakingdoms.result.decision","Political decision recorded."), id);
            next.receipts.put(request.requestId(), new Receipt(actor.getUUID(), fingerprint, result, now()));
            Notice notice = new Notice(request.requestId(), request.kingdom(), request.action(), actor.getUUID(), id, now());
            append(next, notice, fact(request, result, next, actor.getUUID()));
            if (!data.commitDurably(server, next))
                return new Result(false, revision(), "politics.mcakingdoms.refusal.save_failed", "");
            try {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new PoliticalCommittedEvent(server, notice));
            } catch (RuntimeException listenerFailure) {
                com.mojang.logging.LogUtils.getLogger().error("Political state committed but a post-commit listener failed; no provider delivery is assumed", listenerFailure);
            }
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Result(false, revision(), failure.getMessage() == null ? Keyed.text("politics.mcakingdoms.refusal.action_rejected","The political action was refused.") : Keyed.text(failure), "");
        }
    }

    private PoliticalFact fact(Request request, Result result, PoliticalSavedData.Records records, UUID actor) {
        String recordId = result.recordId();
        Agreement agreement = parse(recordId).map(records.agreements::get).orElse(null);
        Petition petition = parse(recordId).map(records.petitions::get).orElse(null);
        PoliticalFact.Type type = switch (request.action()) {
            case BOOTSTRAP -> PoliticalFact.Type.GOVERNMENT_FOUNDED;
            case SEAT -> PoliticalFact.Type.CAPITAL_MOVED;
            case APPOINT -> PoliticalFact.Type.OFFICE_APPOINTED;
            case REMOVE_OFFICE -> PoliticalFact.Type.OFFICE_REMOVED;
            case DELEGATE -> PoliticalFact.Type.MANDATE_GRANTED;
            case REVOKE -> PoliticalFact.Type.MANDATE_REVOKED;
            case PROPOSE -> PoliticalFact.Type.AGREEMENT_PROPOSED;
            case SIGN -> PoliticalFact.Type.AGREEMENT_SIGNED;
            case DECLINE -> PoliticalFact.Type.AGREEMENT_DECLINED;
            case WITHDRAW -> petition == null ? PoliticalFact.Type.AGREEMENT_WITHDRAWN : PoliticalFact.Type.PETITION_WITHDRAWN;
            case TERMINATE -> PoliticalFact.Type.AGREEMENT_TERMINATED;
            case PETITION -> PoliticalFact.Type.PETITION_SUBMITTED;
            case REVIEW -> PoliticalFact.Type.PETITION_REVIEWED;
            case APPROVE -> PoliticalFact.Type.PETITION_APPROVED;
            case REJECT -> PoliticalFact.Type.PETITION_REJECTED;
            case RECOGNIZE -> PoliticalFact.Type.INSTITUTION_RECOGNIZED;
            case REVALIDATE -> PoliticalFact.Type.INSTITUTION_REVALIDATED;
            case SUSPEND_RECOGNITION -> PoliticalFact.Type.INSTITUTION_SUSPENDED;
            case HONOR -> PoliticalFact.Type.HONOR_GRANTED;
            case REVOKE_HONOR -> PoliticalFact.Type.HONOR_REVOKED;
            case ABDICATE -> PoliticalFact.Type.LEADER_ABDICATED;
            case NAME_SUCCESSOR -> PoliticalFact.Type.SUCCESSOR_NAMED;
            case SUCCEED -> PoliticalFact.Type.SUCCESSION_CONFIRMED;
            case HOUSE -> PoliticalFact.Type.HOUSE_UPDATED;
            case ADOPT_TRANSITION_RULE -> PoliticalFact.Type.TRANSITION_RULE_ADOPTED;
            case OPEN_ELECTION -> PoliticalFact.Type.ELECTION_OPENED;
            case CAST_BALLOT -> PoliticalFact.Type.BALLOT_CAST;
            case CLOSE_ELECTION -> PoliticalFact.Type.ELECTION_RESOLVED;
            case APPOINT_REGENT -> PoliticalFact.Type.REGENCY_APPOINTED;
            case END_REGENCY -> PoliticalFact.Type.REGENCY_ENDED;
        };
        Set<String> affectedKingdoms = new LinkedHashSet<>();
        affectedKingdoms.add(request.kingdom());
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        affectedPlayers.add(actor);
        if (request.person() != null) affectedPlayers.add(request.person().id());
        if (agreement != null) {
            affectedKingdoms.add(agreement.proposer()); affectedKingdoms.add(agreement.recipient());
            affectedPlayers.addAll(agreement.signatures().values());
        }
        if (petition != null) {
            affectedKingdoms.add(petition.kingdom()); affectedPlayers.add(petition.requester());
            if (petition.nominee() != null) affectedPlayers.add(petition.nominee().id());
            if (petition.decidedBy() != null) affectedPlayers.add(petition.decidedBy());
        }
        PoliticalFact.Visibility visibility;
        if (petition != null || request.action() == Action.PETITION) visibility = PoliticalFact.Visibility.PRIVATE;
        else if (agreement != null && agreement.state() != AgreementState.ACTIVE && agreement.state() != AgreementState.TERMINATED)
            visibility = PoliticalFact.Visibility.PARTIES;
        else if (Set.of(Action.DELEGATE, Action.REVOKE).contains(request.action())) visibility = PoliticalFact.Visibility.PARTIES;
        else visibility = PoliticalFact.Visibility.PUBLIC;
        return new PoliticalFact(request.requestId(), request.requestId(), result.revision(), now(), request.kingdom(),
                type, actor, recordId, affectedKingdoms, affectedPlayers, visibility,
                PoliticalFact.Correction.CURRENT, null, summary(type));
    }

    private static Optional<UUID> parse(String value) {
        try { return Optional.of(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    private static String summary(PoliticalFact.Type type) {
        String value = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void append(PoliticalSavedData.Records records, Notice notice, PoliticalFact fact) {
        records.journal.add(notice); records.facts.add(fact);
        while (records.journal.stream().filter(n -> n.kingdom().equals(notice.kingdom())).count() > 256)
            removeFirst(records.journal, n -> n.kingdom().equals(notice.kingdom()));
        while (records.facts.stream().filter(value -> value.kingdom().equals(fact.kingdom())).count() > 256)
            removeFirst(records.facts, value -> value.kingdom().equals(fact.kingdom()));
    }

    private static <T> void removeFirst(List<T> values, java.util.function.Predicate<T> predicate) {
        for (int i = 0; i < values.size(); i++) if (predicate.test(values.get(i))) { values.remove(i); return; }
    }

    private java.util.stream.Stream<PoliticalFact> visibleHistory(ServerPlayer viewer) {
        return data.records().facts.stream().filter(fact -> visible(viewer, fact)).map(this::corrected)
                .sorted(Comparator.comparingLong(PoliticalFact::revision).thenComparingLong(PoliticalFact::gameTime)
                        .thenComparing(PoliticalFact::id).reversed());
    }

    private boolean visible(ServerPlayer viewer, PoliticalFact fact) {
        if (fact.visibility() == PoliticalFact.Visibility.PUBLIC || fact.affectedPlayers().contains(viewer.getUUID())) return true;
        boolean petition = petitionFact(fact.type());
        for (String kingdom : fact.affectedKingdoms()) {
            Government government = data.records().governments.get(kingdom);
            if (petition && permitted(viewer, government, Permission.REVIEW)) return true;
            if (fact.visibility() == PoliticalFact.Visibility.PARTIES
                    && (permitted(viewer, government, Permission.PROPOSE) || permitted(viewer, government, Permission.RATIFY))) return true;
        }
        return false;
    }

    private PoliticalFact corrected(PoliticalFact fact) {
        if (!lifecycleFact(fact.type()) || fact.type() == PoliticalFact.Type.AGREEMENT_EXPIRED
                || fact.type() == PoliticalFact.Type.PETITION_EXPIRED) return fact;
        PoliticalFact correction = data.records().facts.stream()
                .filter(other -> !other.id().equals(fact.id()) && other.recordId().equals(fact.recordId())
                        && other.revision() > fact.revision() && lifecycleFact(other.type()))
                .max(Comparator.comparingLong(PoliticalFact::revision).thenComparing(PoliticalFact::id)).orElse(null);
        boolean expired = correction != null && (correction.type() == PoliticalFact.Type.AGREEMENT_EXPIRED
                || correction.type() == PoliticalFact.Type.PETITION_EXPIRED);
        if (!expired) {
            Optional<UUID> id = parse(fact.recordId());
            Agreement agreement = id.map(data.records().agreements::get).orElse(null);
            Petition petition = id.map(data.records().petitions::get).orElse(null);
            expired = agreement != null && (agreement.state() == AgreementState.EXPIRED
                    || now() >= agreement.expiresAt() && (agreement.state() == AgreementState.PROPOSED || agreement.state() == AgreementState.ACTIVE))
                    || petition != null && (petition.state() == PetitionState.EXPIRED || now() >= petition.expiresAt() && open(petition));
        }
        if (correction == null && !expired) return fact;
        return new PoliticalFact(fact.id(), fact.sourceReceiptId(), fact.revision(), fact.gameTime(), fact.kingdom(), fact.type(),
                fact.actor(), fact.recordId(), fact.affectedKingdoms(), fact.affectedPlayers(), fact.visibility(),
                expired ? PoliticalFact.Correction.EXPIRED : PoliticalFact.Correction.SUPERSEDED,
                correction == null ? null : correction.id(), fact.summary());
    }

    private static boolean lifecycleFact(PoliticalFact.Type type) {
        return switch (type) {
            case AGREEMENT_PROPOSED, AGREEMENT_SIGNED, AGREEMENT_DECLINED, AGREEMENT_WITHDRAWN,
                    AGREEMENT_TERMINATED, AGREEMENT_EXPIRED, PETITION_SUBMITTED, PETITION_REVIEWED,
                    PETITION_APPROVED, PETITION_REJECTED, PETITION_WITHDRAWN, PETITION_EXPIRED -> true;
            default -> false;
        };
    }

    private static boolean petitionFact(PoliticalFact.Type type) {
        return switch (type) {
            case PETITION_SUBMITTED, PETITION_REVIEWED, PETITION_APPROVED, PETITION_REJECTED,
                    PETITION_WITHDRAWN, PETITION_EXPIRED -> true;
            default -> false;
        };
    }
    private String mutate(ServerPlayer actor, Request r, PoliticalSavedData.Records s) {
        Government g = s.governments.get(r.kingdom());
        if (r.action() == Action.BOOTSTRAP) {
            check(actor.hasPermissions(2) || residentFounder(actor, r), Keyed.text("politics.mcakingdoms.refusal.founding_capital_resident","Founding needs an operator, or a civic resident of the capital naming themselves leader."));
            check(g == null, Keyed.text("politics.mcakingdoms.refusal.already_constituted","This kingdom already has a government."));
            Definition profile = definitions.get(r.definition(), "government");
            SettlementView seat = settlement(r.target(), r.kingdom());
            Person leader = person(r.person(), r.kingdom(), null);
            check(profile.offices().contains(LEADER), Keyed.text("politics.mcakingdoms.refusal.no_leadership_office","This constitution has no leadership office."));
            Office office = new Office(LEADER, definitions.get(LEADER, "office"), leader, null, actor.getUUID(), now());
            s.governments.put(r.kingdom(), new Government(r.kingdom(), seat.id(), r.definition(), profile, State.ACTIVE,
                    Map.of(LEADER, office), Map.of(), null, s.revision));
            return r.kingdom();
        }
        if (r.action() == Action.PETITION) return petition(actor, r, s).toString();
        check(g != null, "politics.mcakingdoms.refusal.no_government");
        check(definitions.available(g.profileId()), "politics.mcakingdoms.refusal.dormant");
        switch (r.action()) {
            case SEAT -> {
                authorize(actor, g, Permission.SEAT);
                SettlementView seat = settlement(r.target(), r.kingdom());
                s.governments.put(g.kingdom(), government(g, seat.id(), g.offices(), g.mandates(), g.successor(), s.revision));
            }
            case APPOINT -> {
                UUID scope = r.target().isEmpty() ? null : settlement(r.target(), r.kingdom()).id();
                GovernmentActionPolicy.require(facts(actor, g.kingdom(), g, s), Action.APPOINT, GovernmentActionPolicy.Target.office(officeKey(r.definition(), scope)));
                check(g.constitution().offices().contains(r.definition()), Keyed.text("politics.mcakingdoms.refusal.office_not_in_constitution","That office is not part of this constitution."));
                Definition office = definitions.get(r.definition(), "office");
                check(!r.definition().equals(LEADER) || scope == null, Keyed.text("politics.mcakingdoms.refusal.leadership_kingdom_wide","The leadership office is kingdom-wide; it cannot be limited to one settlement."));
                Person candidate = person(r.person(), r.kingdom(), scope);
                check(g.offices().values().stream().filter(o -> o.holder().equals(candidate)).count() < g.constitution().maxOffices(), Keyed.text("politics.mcakingdoms.refusal.office_limit","That person already holds as many offices as this constitution allows."));
                String key = officeKey(r.definition(), scope);
                check(g.offices().values().stream().noneMatch(o -> o.definitionId().equals(r.definition())
                        && Objects.equals(scope, o.settlement() == null ? null : kingdoms.getSettlement(o.settlement()).orElseThrow().id())), "Office occupied; remove its holder explicitly first");
                Map<String, Office> offices = new LinkedHashMap<>(g.offices());
                offices.put(key, new Office(r.definition(), office, candidate, scope, actor.getUUID(), now()));
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, g.mandates(), g.successor(), s.revision));
            }
            case REMOVE_OFFICE, ABDICATE -> {
                String key = r.action() == Action.ABDICATE ? LEADER : officeKey(r.definition(), r.target().isEmpty() ? null : uuid(r.target()));
                GovernmentActionPolicy.require(facts(actor, g.kingdom(), g, s), r.action(), GovernmentActionPolicy.Target.office(key));
                Office office = g.offices().get(key);
                if (r.action() == Action.ABDICATE && elected(s, g.kingdom(), office.holder())) s.electedMandates.remove(g.kingdom());
                Map<String, Office> offices = new LinkedHashMap<>(g.offices()); offices.remove(key);
                Map<UUID, Set<Permission>> mandates = key.equals(LEADER) ? Map.of() : g.mandates();
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, mandates, g.successor(), s.revision));
            }
            case DELEGATE, REVOKE -> {
                GovernmentActionPolicy.require(facts(actor, g.kingdom(), g, s), r.action(), GovernmentActionPolicy.Target.ANY);
                check(r.person() != null && r.person().kind() == Kind.PLAYER, "politics.mcakingdoms.refusal.mandate_player");
                Map<UUID, Set<Permission>> mandates = new LinkedHashMap<>(g.mandates());
                if (r.action() == Action.REVOKE) mandates.remove(r.person().id());
                else {
                    Permission permission = Permission.valueOf(r.definition().toUpperCase(Locale.ROOT));
                    check(permission != Permission.DELEGATE, "politics.mcakingdoms.refusal.redelegate");
                    Set<Permission> grant = new HashSet<>(mandates.getOrDefault(r.person().id(), Set.of())); grant.add(permission);
                    mandates.put(r.person().id(), grant);
                }
                s.governments.put(g.kingdom(), government(g, g.capital(), g.offices(), mandates, g.successor(), s.revision));
            }
            case PROPOSE -> { return propose(actor, r, s, g).toString(); }
            case SIGN, DECLINE, WITHDRAW, TERMINATE -> {
                if (r.action() == Action.WITHDRAW && s.petitions.containsKey(uuid(r.target()))) {
                    Petition p = s.petitions.get(uuid(r.target()));
                    check(p.requester().equals(actor.getUUID()) && p.kingdom().equals(r.kingdom()), Keyed.text("politics.mcakingdoms.refusal.petitioner_only","Only the petitioner can withdraw this petition."));
                    check(open(p), Keyed.text("politics.mcakingdoms.refusal.petition_closed","This petition is closed."));
                    s.petitions.put(p.id(), decision(p, PetitionState.WITHDRAWN, r.text(), actor.getUUID(), s.revision));
                } else agreement(actor, r, s, g);
            }
            case REVIEW, APPROVE, REJECT -> {
                authorize(actor, g, Permission.REVIEW);
                Petition p = s.petitions.get(uuid(r.target()));
                check(p != null && p.kingdom().equals(r.kingdom()), "Petition unavailable");
                check(open(p) && now() < p.expiresAt(), Keyed.text("politics.mcakingdoms.refusal.petition_expired","This petition is closed or has expired."));
                check(definitions.available(p.definitionId()), Keyed.text("politics.mcakingdoms.refusal.petition_definition","This petition's definition is no longer loaded."));
                if (p.terms().independentApproval()) check(!p.requester().equals(actor.getUUID())
                        && (p.nominee() == null || !p.nominee().id().equals(actor.getUUID())), Keyed.text("politics.mcakingdoms.refusal.independent_approver","An independent approver is required: not the petitioner or the nominee."));
                check(!r.text().isBlank(), Keyed.text("politics.mcakingdoms.refusal.decision_reason","Give a reason for the decision."));
                if (r.action() == Action.APPROVE) approvePetition(actor, p, s, g);
                s.petitions.put(p.id(), decision(p, r.action() == Action.REVIEW ? PetitionState.UNDER_REVIEW
                        : r.action() == Action.APPROVE ? PetitionState.APPROVED : PetitionState.DECLINED,
                        r.text(), actor.getUUID(), s.revision));
            }
            case RECOGNIZE -> {
                Building evidence = building(actor.serverLevel(), new BlockPos(r.x(), r.y(), r.z()), g.kingdom());
                authorizeScoped(actor, g, Permission.RECOGNIZE, evidence.settlement());
                return recognize(actor, s, g.kingdom(), r.definition(), evidence).toString();
            }
            case SUSPEND_RECOGNITION -> {
                Recognition recognition = s.recognitions.get(uuid(r.target()));
                check(recognition != null && recognition.kingdom().equals(g.kingdom()), "Recognition unavailable");
                UUID scope = kingdoms.getSettlement(recognition.building().settlement()).orElseThrow().id();
                authorizeScoped(actor, g, Permission.RECOGNIZE, scope);
                s.recognitions.put(recognition.id(), new Recognition(recognition.id(), recognition.kingdom(), recognition.definitionId(), recognition.terms(), recognition.building(), actor.getUUID(), false, s.revision));
            }
            case REVALIDATE -> {
                Recognition recognition = s.recognitions.get(uuid(r.target()));
                check(recognition != null && recognition.kingdom().equals(g.kingdom()), "Recognition unavailable");
                authorizeScoped(actor, g, Permission.RECOGNIZE, settlement(recognition.building().settlement().toString(), g.kingdom()).id());
                check(definitions.available(recognition.definitionId()), Keyed.text("politics.mcakingdoms.refusal.charter_definition","The institution charter's definition is no longer loaded."));
                Building original = recognition.building();
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(original.dimension())));
                check(level != null && level.hasChunkAt(new BlockPos(original.x(), original.y(), original.z())), Keyed.text("politics.mcakingdoms.refusal.building_unloaded","The building's status is unknown because its location is not loaded."));
                Optional<TownsteadBuildingView> observed = townstead().buildingAt(level, new BlockPos(original.x(), original.y(), original.z()));
                check(townstead().capabilities().contains(TownsteadCapability.READ_BUILDING), Keyed.text("politics.mcakingdoms.refusal.building_capability","Building records cannot be read on this server."));
                boolean valid = observed.filter(b -> b.villageId() == original.villageId() && b.buildingId() == original.buildingId()
                        && b.type().equals(original.type())).isPresent();
                s.recognitions.put(recognition.id(), new Recognition(recognition.id(), recognition.kingdom(), recognition.definitionId(), recognition.terms(), original, actor.getUUID(), valid, s.revision));
            }
            case HONOR -> {
                authorize(actor, g, Permission.HONOR);
                return honor(actor, s, g.kingdom(), r.definition(), person(r.person(), g.kingdom(), null), "Discretionary: " + r.text(), true).toString();
            }
            case REVOKE_HONOR -> {
                authorize(actor, g, Permission.HONOR);
                Honor h = s.honors.get(uuid(r.target())); check(h != null && h.kingdom().equals(g.kingdom()), "Honor unavailable");
                s.honors.put(h.id(), new Honor(h.id(), h.kingdom(), h.definitionId(), h.terms(), h.recipient(), actor.getUUID(), h.evidence(), h.discretionary(), false, s.revision));
            }
            case NAME_SUCCESSOR -> {
                GovernmentActionPolicy.require(facts(actor, g.kingdom(), g, s), Action.NAME_SUCCESSOR, GovernmentActionPolicy.Target.ANY);
                Person successor = person(r.person(), g.kingdom(), null);
                s.governments.put(g.kingdom(), government(g, g.capital(), g.offices(), g.mandates(), successor, s.revision));
            }
            case SUCCEED -> {
                GovernmentActionPolicy.require(facts(actor, g.kingdom(), g, s), Action.SUCCEED, GovernmentActionPolicy.Target.ANY);
                Person successor = person(g.successor(), g.kingdom(), null);
                Map<String, Office> offices = new LinkedHashMap<>(g.offices());
                offices.put(LEADER, new Office(LEADER, definitions.get(LEADER, "office"), successor, null, actor.getUUID(), now()));
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, Map.of(), null, s.revision));
                Regency regency=s.regencies.get(g.kingdom());if(regency!=null&&regency.active())s.regencies.put(g.kingdom(),new Regency(regency.id(),regency.kingdom(),regency.regent(),regency.permissions(),regency.preservedSuccessor(),regency.appointedAt(),regency.expiresAt(),false,s.revision));
            }
            case HOUSE -> {
                authorize(actor, g, Permission.APPOINT);
                Person member = person(r.person(), g.kingdom(), null);
                s.houses.put(g.kingdom(), new House(g.kingdom(), r.definition(), r.text(), Set.of(member)));
            }
            default -> throw new IllegalArgumentException("Unsupported action");
        }
        return r.target().isEmpty() ? r.kingdom() : r.target();
    }
    private static String officeKey(String definition, UUID scope) { return scope == null ? definition : definition + "@" + scope; }
    /** The office a request appoints or removes, as the crown rule reads it: the leader's key only for the kingdom-wide leader. */
    private static String crownOffice(Request r) {
        if (r.action() != Action.APPOINT && r.action() != Action.REMOVE_OFFICE) return null;
        return r.definition().equals(LEADER) && r.target().isEmpty() ? LEADER : r.definition();
    }
    /** A person by name: online player, loaded entity, then MCA's family record; never a raw id. */
    private String personName(UUID id) {
        String known = dev.otectus.mcakingdoms.interaction.Names.person(server, id);
        return known.startsWith("an ") ? dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence.name(server, id).orElse(known) : known;
    }
    /** Who governs {@code kingdom}'s crown now (C2). */
    public Sovereignty sovereignty(String kingdom) { thread(); return sovereignty.apply(kingdom); }
    /** The player whose current mandate in {@code kingdom} came from a resolved election, if any. */
    public Optional<UUID> electedHolder(String kingdom) { thread(); return Optional.ofNullable(data.records().electedMandates.get(kingdom)); }
    /** The players who sit on {@code kingdom}'s council: player office holders and mandate holders. */
    public Set<UUID> council(String kingdom) {
        thread(); Government g = data.records().governments.get(kingdom); if (g == null) return Set.of();
        Set<UUID> out = new LinkedHashSet<>(g.mandates().keySet());
        g.offices().values().stream().map(Office::holder).filter(p -> p.kind() == Kind.PLAYER).map(Person::id).forEach(out::add);
        return out;
    }
    /** Why {@code candidate} could not hold office in {@code kingdom} now (an encoded bundled message), or empty when they could. */
    public Optional<String> eligibility(Person candidate, String kingdom) {
        thread();
        try { person(candidate, kingdom, null); return Optional.empty(); }
        catch (IllegalArgumentException | IllegalStateException refused) { return Optional.of(refused.getMessage() == null ? "politics.mcakingdoms.refusal.action_rejected" : Keyed.text(refused)); }
    }
    /** Whether {@code id} is a player this service knows (connected, or in the server's profile cache): the test every player choice passes. */
    public boolean knownPlayer(UUID id) { return id != null && knownPlayer.test(id); }
    /** Whether {@code actor} holds {@code permission} in {@code kingdom} through an office, mandate or regency. */
    public boolean permitted(UUID actor, String kingdom, Permission permission) { thread(); return permitted(actor, data.records().governments.get(kingdom), permission); }

    /**
     * Seats {@code heir} as leader of {@code kingdom} because its law of succession chose them (A2): a system write in
     * interregnum, on the kingdom's own crown only, with the same eligibility check as any succession. An elected
     * successor is never displaced: when one is named, only they can be seated. One commit per succession case; a
     * repeat returns the first result. Ends an active regency, once.
     */
    public Result succeedByLaw(String kingdom, Person heir, UUID caseId, UUID authorizedBy) {
        thread();
        UUID request = stable("succession-by-law", caseId, 0, kingdom);
        Receipt receipt = data.records().receipts.get(request);
        if (receipt != null) return receipt.result();
        try {
            check(data.writable(), data.diagnostic());
            Sovereignty crown = sovereignty.apply(kingdom); check(crown.native_(), crown.refusal());
            check(kingdoms.getKingdom(new ResourceLocation(kingdom)).filter(KingdomView::defined).isPresent(), "politics.mcakingdoms.refusal.kingdom_undefined");
            check(data.records().receipts.size() < RECEIPT_CAPACITY, "politics.mcakingdoms.refusal.receipts_full");
            PoliticalSavedData.Records next = data.transaction();
            next.revision++;
            Government g = next.governments.get(kingdom);
            check(g != null && definitions.available(g.profileId()), "politics.mcakingdoms.refusal.no_government");
            check(g.state() == State.INTERREGNUM, "politics.mcakingdoms.refusal.no_succession");
            check(g.successor() == null || !elected(next, kingdom, g.successor()) || g.successor().equals(heir), "politics.mcakingdoms.refusal.elected_successor");
            Person seated = person(heir, kingdom, null);
            Map<String, Office> offices = new LinkedHashMap<>(g.offices());
            UUID by = authorizedBy == null ? new UUID(0, 0) : authorizedBy;
            offices.put(LEADER, new Office(LEADER, definitions.get(LEADER, "office"), seated, null, by, now()));
            next.governments.put(kingdom, government(g, g.capital(), offices, Map.of(), null, next.revision));
            Regency regency = next.regencies.get(kingdom);
            if (regency != null && regency.active()) next.regencies.put(kingdom, new Regency(regency.id(), regency.kingdom(), regency.regent(), regency.permissions(),
                    regency.preservedSuccessor(), regency.appointedAt(), regency.expiresAt(), false, next.revision));
            Result result = new Result(true, next.revision, Keyed.text("politics.mcakingdoms.result.succession_by_law", "The heir was seated under the kingdom's law of succession."), kingdom);
            next.receipts.put(request, new Receipt(by, "succession-by-law|" + caseId, result, now()));
            Notice notice = new Notice(request, kingdom, Action.SUCCEED, by, kingdom, now());
            append(next, notice, new PoliticalFact(request, request, next.revision, now(), kingdom, PoliticalFact.Type.SUCCESSION_CONFIRMED, authorizedBy, kingdom,
                    Set.of(kingdom), Set.of(seated.id()), PoliticalFact.Visibility.PUBLIC, PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.SUCCESSION_CONFIRMED)));
            if (!data.commitDurably(server, next)) return new Result(false, revision(), "politics.mcakingdoms.refusal.save_failed", "");
            publish(notice);
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Result(false, revision(), failure.getMessage() == null ? Keyed.text("politics.mcakingdoms.refusal.action_rejected", "The political action was refused.") : Keyed.text(failure), "");
        }
    }

    /**
     * Appoints {@code regent}, a minor heir's guardian, under the kingdom's transition rule while the heir grows up (A2):
     * the existing regency with its bounded term and permissions, preserving the heir as the named successor. Refused
     * without a rule that allows regencies, while one is active, or when it would displace an elected successor. One
     * commit per succession case.
     */
    public Result appointRegentByLaw(String kingdom, Person regent, Person heir, UUID caseId) {
        thread();
        UUID request = stable("regency-by-law", caseId, 0, kingdom);
        Receipt receipt = data.records().receipts.get(request);
        if (receipt != null) return receipt.result();
        try {
            check(data.writable(), data.diagnostic());
            Sovereignty crown = sovereignty.apply(kingdom); check(crown.native_(), crown.refusal());
            check(data.records().receipts.size() < RECEIPT_CAPACITY, "politics.mcakingdoms.refusal.receipts_full");
            PoliticalSavedData.Records next = data.transaction();
            next.revision++;
            Government g = next.governments.get(kingdom); Rule rule = next.transitionRules.get(kingdom);
            check(g != null && g.state() == State.INTERREGNUM, "politics.mcakingdoms.refusal.no_succession");
            check(rule != null && rule.regency(), "politics.mcakingdoms.refusal.no_regency");
            Regency prior = next.regencies.get(kingdom); check(prior == null || !prior.active() || now() >= prior.expiresAt(), "politics.mcakingdoms.refusal.regency_active");
            Person guardian = person(regent, kingdom, null);
            check(guardian.kind() == Kind.PLAYER, "politics.mcakingdoms.refusal.regency_operator");
            // The regency preserves the named successor, so the minor heir becomes it; an elected successor is never displaced.
            check(g.successor() == null || !elected(next, kingdom, g.successor()) || g.successor().equals(heir), "politics.mcakingdoms.refusal.elected_successor");
            UUID id = stable("regency", caseId, 0, kingdom);
            next.governments.put(kingdom, government(g, g.capital(), g.offices(), g.mandates(), heir, next.revision));
            next.regencies.put(kingdom, new Regency(id, kingdom, guardian, rule.regentPermissions(), heir, now(), now() + rule.regencyTicks(), true, next.revision));
            Result result = new Result(true, next.revision, Keyed.text("politics.mcakingdoms.result.regency_by_law", "The heir's guardian governs as regent until the heir comes of age."), id.toString());
            next.receipts.put(request, new Receipt(new UUID(0, 0), "regency-by-law|" + caseId, result, now()));
            Notice notice = new Notice(request, kingdom, Action.APPOINT_REGENT, new UUID(0, 0), id.toString(), now());
            append(next, notice, new PoliticalFact(request, request, next.revision, now(), kingdom, PoliticalFact.Type.REGENCY_APPOINTED, null, id.toString(),
                    Set.of(kingdom), Set.of(guardian.id()), PoliticalFact.Visibility.PUBLIC, PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.REGENCY_APPOINTED)));
            if (!data.commitDurably(server, next)) return new Result(false, revision(), "politics.mcakingdoms.refusal.save_failed", "");
            publish(notice);
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Result(false, revision(), failure.getMessage() == null ? Keyed.text("politics.mcakingdoms.refusal.action_rejected", "The political action was refused.") : Keyed.text(failure), "");
        }
    }
    private Government government(Government g, UUID capital, Map<String, Office> offices,
                                  Map<UUID, Set<Permission>> mandates, Person successor, long revision) {
        return new Government(g.kingdom(), capital, g.profileId(), g.constitution(), offices.containsKey(LEADER) ? State.ACTIVE : State.INTERREGNUM,
                offices, mandates, successor, revision);
    }
    private Set<UUID> electorate(Government government, Rule rule) {
        Set<UUID> result=new LinkedHashSet<>(government.mandates().keySet());
        government.offices().values().stream().map(Office::holder).filter(p->p.kind()==Kind.PLAYER).map(Person::id).forEach(result::add);
        if (rule.electorate() == PoliticalTransition.Electorate.RESIDENTS) {
            var api = dev.otectus.mcakingdoms.api.McaKingdomsApi.get(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (result.size() >= 128) break;
                boolean resident = api.getCivicIdentity(player).flatMap(dev.otectus.mcakingdoms.api.CivicIdentityView::residenceSettlement)
                        .flatMap(api::getSettlement).map(s -> s.kingdomId().toString().equals(government.kingdom())).orElse(false);
                if (resident) result.add(player.getUUID());
            }
        }
        return result;
    }
    /** True when this player's current mandate in the kingdom came from a resolved election. */
    private static boolean elected(PoliticalSavedData.Records state, String kingdom, Person person) {
        return person != null && person.kind() == Kind.PLAYER && person.id().equals(state.electedMandates.get(kingdom));
    }
    private String closeElection(ServerPlayer actor,UUID id,PoliticalSavedData.Records state) {
        Election value=state.elections.get(id);check(value!=null&&(value.state()==ElectionState.OPEN||value.state()==ElectionState.GRACE),"politics.mcakingdoms.refusal.election_closed");
        Government government=state.governments.get(value.kingdom());check(value.electorate().contains(actor.getUUID())||actor.hasPermissions(2)||permitted(actor,government,Permission.APPOINT),"politics.mcakingdoms.refusal.close_authority");
        check(value.ballots().size()==value.electorate().size()||now()>=value.deadline(),"politics.mcakingdoms.refusal.ballot_still_open");
        ElectionState outcome=closureState(value,now());
        if(outcome!=ElectionState.RESOLVED){ElectionState phase=outcome;
            state.elections.put(id,new Election(value.id(),value.kingdom(),value.candidates(),value.electorate(),value.ballots(),phase,value.openedAt(),value.deadline(),value.graceUntil(),state.revision,null));return id.toString();}
        UUID winner=uniqueWinner(value).orElseThrow();Person elected=person(new Person(winner,Kind.PLAYER),value.kingdom(),null);Regency activeRegency=state.regencies.get(value.kingdom());
        check(activeRegency==null||!activeRegency.active()||government.state()==State.INTERREGNUM,"politics.mcakingdoms.refusal.regency_preserves_successor");
        if(government.state()==State.INTERREGNUM){Map<String,Office> offices=new LinkedHashMap<>(government.offices());
            offices.put(LEADER,new Office(LEADER,definitions.get(LEADER,"office"),elected,null,actor.getUUID(),now()));
            state.governments.put(government.kingdom(),government(government,government.capital(),offices,Map.of(),null,state.revision));
            Regency regency=state.regencies.get(government.kingdom());if(regency!=null&&regency.active())state.regencies.put(government.kingdom(),new Regency(regency.id(),regency.kingdom(),regency.regent(),regency.permissions(),regency.preservedSuccessor(),regency.appointedAt(),regency.expiresAt(),false,state.revision));
        }else state.governments.put(government.kingdom(),government(government,government.capital(),government.offices(),government.mandates(),elected,state.revision));
        state.electedMandates.put(government.kingdom(), winner);
        state.elections.put(id,new Election(value.id(),value.kingdom(),value.candidates(),value.electorate(),value.ballots(),ElectionState.RESOLVED,value.openedAt(),value.deadline(),value.graceUntil(),state.revision,winner));return id.toString();
    }
    static Optional<UUID> uniqueWinner(Election value){Map<UUID,Long> counts=new TreeMap<>();value.candidates().forEach(candidate->counts.put(candidate,0L));
        value.ballots().values().forEach(candidate->counts.put(candidate,counts.get(candidate)+1));long highest=counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if(highest==0)return Optional.empty();List<UUID>winners=counts.entrySet().stream().filter(e->e.getValue()==highest).map(Map.Entry::getKey).toList();return winners.size()==1?Optional.of(winners.get(0)):Optional.empty();}
    static ElectionState closureState(Election value,long time){if(time>=value.graceUntil())return ElectionState.EXPIRED;return uniqueWinner(value).isPresent()?ElectionState.RESOLVED:ElectionState.GRACE;}
    static boolean unexpired(Regency value,long time){return value!=null&&value.active()&&time<value.expiresAt();}
    private PoliticalFact transitionFact(UUID source,String kingdom,Action action,UUID actor,String record,PoliticalSavedData.Records state) {
        PoliticalFact.Type type=switch(action){case ADOPT_TRANSITION_RULE->PoliticalFact.Type.TRANSITION_RULE_ADOPTED;case OPEN_ELECTION->PoliticalFact.Type.ELECTION_OPENED;
            case CAST_BALLOT->PoliticalFact.Type.BALLOT_CAST;case CLOSE_ELECTION->{Election e=state.elections.get(parse(record).orElse(new UUID(0,0)));yield e!=null&&e.state()==ElectionState.EXPIRED?PoliticalFact.Type.ELECTION_EXPIRED:e!=null&&e.state()==ElectionState.GRACE?PoliticalFact.Type.ELECTION_GRACE:PoliticalFact.Type.ELECTION_RESOLVED;}
            case APPOINT_REGENT->PoliticalFact.Type.REGENCY_APPOINTED;case END_REGENCY->PoliticalFact.Type.REGENCY_ENDED;default->throw new IllegalArgumentException("Not a transition action");};
        PoliticalFact.Visibility visibility=action==Action.CAST_BALLOT?PoliticalFact.Visibility.PRIVATE:PoliticalFact.Visibility.PUBLIC;
        return new PoliticalFact(source,source,state.revision,now(),kingdom,type,actor,record,Set.of(kingdom),Set.of(actor),visibility,PoliticalFact.Correction.CURRENT,null,summary(type));
    }
    private boolean permitted(ServerPlayer actor, Government g, Permission permission) {
        return permitted(actor.getUUID(), g, permission);
    }
    private boolean activeRegency(String kingdom){Regency value=data.records().regencies.get(kingdom);return value!=null&&value.active()&&now()<value.expiresAt();}
    private boolean permitted(UUID actor, Government g, Permission permission) {
        if (g == null || !definitions.available(g.profileId())) return false;
        Regency regency=data.records().regencies.get(g.kingdom());
        if(regency!=null&&regency.active()&&now()<regency.expiresAt()&&regency.regent().id().equals(actor)&&regency.permissions().contains(permission))return true;
        if(g.state()!=State.ACTIVE)return false;
        if (g.mandates().getOrDefault(actor, Set.of()).contains(permission)) return true;
        if (kingdoms.getKingdom(new ResourceLocation(g.kingdom())).filter(KingdomView::defined).isEmpty()) return false;
        return g.offices().values().stream().anyMatch(o -> o.settlement() == null && definitions.available(o.definitionId())
                && o.holder().equals(new Person(actor, Kind.PLAYER)) && o.terms().permissions().contains(permission));
    }
    private void authorize(ServerPlayer actor, Government g, Permission permission) {
        check(permitted(actor, g, permission), GovernmentActionPolicy.permissionKey(permission));
    }
    private void authorizeScoped(ServerPlayer actor, Government g, Permission permission, UUID settlement) {
        if (permitted(actor, g, permission)) return;
        check(g.state() == State.ACTIVE && g.offices().values().stream().anyMatch(o -> o.settlement() != null
                && o.holder().equals(new Person(actor.getUUID(), Kind.PLAYER)) && definitions.available(o.definitionId())
                && o.terms().permissions().contains(permission) && kingdoms.getSettlement(o.settlement()).map(v -> v.id().equals(settlement)).orElse(false)),
                Keyed.text("politics.mcakingdoms.refusal.scoped_permission", "This needs the %s responsibility for this settlement.", permission));
    }
    private SettlementView settlement(String id, String kingdom) {
        SettlementView result = kingdoms.findSettlement(id).orElseThrow(() -> new IllegalArgumentException("Settlement unavailable"));
        check(result.kingdomId().toString().equals(kingdom), Keyed.text("politics.mcakingdoms.refusal.settlement_other_kingdom","That settlement belongs to another kingdom.")); return result;
    }
    private TownsteadService townstead() { return KingdomsTownsteadApi.get(server); }
    private Person person(Person candidate, String kingdom, UUID scope) {
        check(candidate != null, Keyed.text("politics.mcakingdoms.refusal.person_required","Choose a person."));
        if (candidate.kind() == Kind.PLAYER) {
            check(knownPlayer.test(candidate.id()), "Unknown player");
            return candidate;
        }
        Entity entity = null;
        for (ServerLevel level : server.getAllLevels()) { entity = level.getEntity(candidate.id()); if (entity != null) break; }
        check(entity instanceof LivingEntity && entity.isAlive() && !(entity instanceof ServerPlayer), Keyed.text("politics.mcakingdoms.refusal.npc_unloaded","That villager must be alive and loaded to be checked."));
        var identity = kingdoms.getCivicIdentity(entity).orElseThrow(() -> Keyed.refuse("politics.mcakingdoms.refusal.no_residence","That villager has no known civic residence."));
        UUID residence = identity.residenceSettlement().orElseThrow(() -> Keyed.refuse("politics.mcakingdoms.refusal.no_residence","That villager has no known civic residence."));
        SettlementView home = settlement(residence.toString(), kingdom);
        check(scope == null || scope.equals(home.id()), Keyed.text("politics.mcakingdoms.refusal.not_resident_here","That person does not live in this office's settlement."));
        if (ModList.get().isLoaded("townstead")) {
            // Whether Townstead's life stages can be read is a capability probe, not a version match: a
            // release that moved these members fails closed here, one that kept them works.
            check(townstead().capabilities().contains(TownsteadCapability.READ_VILLAGER)
                    && townstead().capabilities().contains(TownsteadCapability.READ_ORIGIN),
                    Keyed.text("politics.mcakingdoms.refusal.townstead_unreadable","Townstead's life stages cannot be read: its villager and origin data are unavailable."));
            TownsteadVillagerView view = townstead().villager(entity).orElseThrow(() -> Keyed.refuse("politics.mcakingdoms.refusal.townstead_unavailable","Townstead's life stage for this villager is unavailable."));
            String presentation = view.rootId().flatMap(townstead()::origin).stream().flatMap(root -> root.lifeStages().stream())
                    .filter(stage -> stage.id().equalsIgnoreCase(view.lifeStage())).map(TownsteadLifeStageView::presentsAs).findFirst().orElse("");
            check(Set.of("adult", "senior").contains(presentation.toLowerCase(Locale.ROOT)), Keyed.text("politics.mcakingdoms.refusal.not_adult", "That villager is not confirmed as an adult (life stage %s).", view.lifeStage()));
        } else {
            check(entity instanceof Villager && !((Villager) entity).isBaby(), Keyed.text("politics.mcakingdoms.refusal.adult_unknown","Adulthood cannot be confirmed for this kind of villager."));
        }
        return candidate;
    }
    private Building building(ServerLevel level, BlockPos position, String kingdom) {
        check(level.hasChunkAt(position), Keyed.text("politics.mcakingdoms.refusal.building_unloaded","The building's status is unknown because its location is not loaded."));
        // Townstead's building query, or MCA's own building list without it; either way a probed capability.
        check(townstead().capabilities().contains(TownsteadCapability.READ_BUILDING), Keyed.text("politics.mcakingdoms.refusal.building_capability","Building records cannot be read on this server."));
        TownsteadBuildingView b = townstead().buildingAt(level, position).orElseThrow(() -> Keyed.refuse("politics.mcakingdoms.refusal.building_evidence","No building record was found at that position."));
        SettlementView settlement = settlement(b.settlementId().toString(), kingdom);
        return new Building("townstead", b.dimension().toString(), b.villageId(), b.buildingId(), settlement.id(), b.type(), b.centerX(), b.centerY(), b.centerZ());
    }
    private UUID propose(ServerPlayer actor, Request r, PoliticalSavedData.Records s, Government g) {
        authorize(actor, g, Permission.PROPOSE);
        Government other = s.governments.get(r.counterpart());
        check(other != null && !other.kingdom().equals(g.kingdom()) && other.state() == State.ACTIVE
                && definitions.available(other.profileId()), Keyed.text("politics.mcakingdoms.refusal.counterpart_unavailable","The other kingdom has no government."));
        Definition terms = definitions.get(r.definition(), "agreement");
        for (String prerequisite : terms.requires()) check(s.agreements.values().stream().anyMatch(a -> a.involves(g.kingdom()) && a.involves(other.kingdom())
                && a.definitionId().equals(prerequisite) && active(a)), Keyed.text("politics.mcakingdoms.refusal.requires_prerequisite", "This needs an active %s first.",
                Optional.ofNullable(definitions.snapshot().get(prerequisite)).map(d -> definitionTitle(d.title())).orElse(net.minecraft.network.chat.Component.literal(prerequisite))));
        check(s.agreements.values().stream().noneMatch(a -> a.involves(g.kingdom()) && a.involves(other.kingdom()) && a.definitionId().equals(r.definition())
                && (active(a) || a.state() == AgreementState.PROPOSED && now() < a.expiresAt())), Keyed.text("politics.mcakingdoms.refusal.agreement_exists","An agreement of this type is already pending or in force."));
        UUID id = UUID.randomUUID();
        String termsHash = hash(g.kingdom() + "|" + other.kingdom() + "|" + r.definition() + "|" + PoliticalSavedData.JSON.toJson(terms) + "|" + r.text());
        s.agreements.put(id, new Agreement(id, g.kingdom(), other.kingdom(), r.definition(), terms, r.text(), termsHash,
                Map.of(), AgreementState.PROPOSED, now(), now() + 720000, s.revision)); return id;
    }
    private boolean active(Agreement a) { return a.state() == AgreementState.ACTIVE && now() < a.expiresAt(); }
    /** The agreements in force that {@code kingdom} signed, by id, for documents that seal them (B2). */
    public List<Agreement> activeAgreements(String kingdom) {
        thread();
        return data.records().agreements.values().stream().filter(a -> a.involves(kingdom) && active(a)).sorted(Comparator.comparing(a -> a.id().toString())).limit(64).toList();
    }
    /** A proposed agreement waiting for a signature this viewer may give, for one of the two governments. */
    public record AwaitingSignature(String kingdom, UUID agreement, net.minecraft.network.chat.Component title) { }
    /**
     * Proposals still open for signature where the viewer holds the ratify responsibility of a party that has not signed:
     * only the viewer's own duty, never other governments' pending business.
     */
    public List<AwaitingSignature> awaitingSignature(ServerPlayer viewer, int limit) {
        thread();
        if (!data.writable()) return List.of();
        PoliticalSavedData.Records s = data.records();
        List<AwaitingSignature> out = new ArrayList<>();
        for (Agreement a : s.agreements.values().stream().sorted(Comparator.comparingLong(Agreement::proposedAt).reversed()).toList()) {
            if (out.size() >= limit) break;
            if (a.state() != AgreementState.PROPOSED || now() >= a.expiresAt()) continue;
            for (String kingdom : List.of(a.proposer(), a.recipient()))
                if (!a.signatures().containsKey(kingdom) && permitted(viewer, s.governments.get(kingdom), Permission.RATIFY)) {
                    out.add(new AwaitingSignature(kingdom, a.id(), definitionTitle(a.terms().title())));
                    break;
                }
        }
        return out;
    }
    @Override public AgreementClauseView clause(String first, String second, Clause clause) {
        thread(); Objects.requireNonNull(clause);
        if (!data.writable() || !institutionalServices.getAsBoolean())
            return new AgreementClauseView(clause, AgreementClauseView.Status.UNAVAILABLE, Optional.empty(), revision(), 0, "civic.agreement_unavailable");
        if (first.equals(second)) return new AgreementClauseView(clause, AgreementClauseView.Status.NOT_AGREED,
                Optional.empty(), revision(), 0, "civic.agreement_required");
        var state = data.records();
        var agreement = state.agreements.values().stream()
                .filter(a -> a.involves(first) && a.involves(second) && active(a) && a.signatures().size() == 2)
                .filter(a -> a.terms().clauses().contains(clause))
                .sorted(Comparator.comparing(Agreement::id)).findFirst();
        if (agreement.isEmpty()) return new AgreementClauseView(clause, AgreementClauseView.Status.NOT_AGREED,
                Optional.empty(), revision(), 0, "civic.agreement_required");
        var a = agreement.get();
        boolean available = clause == Clause.HOSPITALITY && definitions.available(a.definitionId()) && java.util.stream.Stream.of(first, second).allMatch(id -> {
            var government = state.governments.get(id);
            return government != null && government.state() == State.ACTIVE && definitions.available(government.profileId())
                    && kingdoms.getKingdom(new ResourceLocation(id)).filter(KingdomView::defined).isPresent();
        });
        return new AgreementClauseView(clause, available ? AgreementClauseView.Status.ACTIVE : AgreementClauseView.Status.UNAVAILABLE,
                Optional.of(a.id()), revision(), a.expiresAt(), available ? "civic.agreement_active" : "civic.agreement_unavailable");
    }
    private void agreement(ServerPlayer actor, Request r, PoliticalSavedData.Records s, Government g) {
        Agreement a = s.agreements.get(uuid(r.target())); check(a != null && a.involves(g.kingdom()), "Agreement unavailable");
        authorize(actor, g, Permission.RATIFY);
        check(now() < a.expiresAt(), "Agreement expired");
        check(definitions.available(a.definitionId()), Keyed.text("politics.mcakingdoms.refusal.agreement_dormant","This agreement's definition is no longer loaded; its kept terms are dormant."));
        Map<String, UUID> signatures = new LinkedHashMap<>(a.signatures());
        AgreementState state;
        long expires = a.expiresAt();
        if (r.action() == Action.SIGN) {
            check(a.state() == AgreementState.PROPOSED, Keyed.text("politics.mcakingdoms.refusal.agreement_not_pending","That agreement is not waiting for signatures."));
            check(a.termsHash().equals(r.termsHash()), Keyed.text("politics.mcakingdoms.refusal.terms_changed","The terms you reviewed are not the current terms; review the proposal again."));
            check(!signatures.containsKey(g.kingdom()), Keyed.text("politics.mcakingdoms.refusal.already_signed","This government has already signed."));
            for (var signature : signatures.entrySet()) check(permitted(signature.getValue(), s.governments.get(signature.getKey()), Permission.RATIFY),
                    Keyed.text("politics.mcakingdoms.refusal.signatory_lost","An earlier signer lost the ratification responsibility; withdraw it and submit a new proposal."));
            for (String prerequisite : a.terms().requires()) check(s.agreements.values().stream().anyMatch(required -> required.involves(a.proposer())
                    && required.involves(a.recipient()) && required.definitionId().equals(prerequisite) && active(required)), Keyed.text("politics.mcakingdoms.refusal.prerequisite_inactive","The agreement it depends on is no longer in force."));
            signatures.put(g.kingdom(), actor.getUUID());
            state = signatures.size() == 2 ? AgreementState.ACTIVE : AgreementState.PROPOSED;
            if (state == AgreementState.ACTIVE) expires = now() + a.terms().durationTicks();
        } else if (r.action() == Action.TERMINATE) {
            check(active(a), Keyed.text("politics.mcakingdoms.refusal.agreement_inactive","That agreement is not in force.")); state = AgreementState.TERMINATED;
        } else {
            check(a.state() == AgreementState.PROPOSED, Keyed.text("politics.mcakingdoms.refusal.agreement_not_pending","That agreement is not waiting for signatures."));
            check(r.action() == Action.DECLINE ? a.recipient().equals(g.kingdom()) : a.proposer().equals(g.kingdom()), Keyed.text("politics.mcakingdoms.refusal.wrong_party","Your government is not the party that can do this."));
            state = r.action() == Action.DECLINE ? AgreementState.DECLINED : AgreementState.WITHDRAWN;
        }
        s.agreements.put(a.id(), new Agreement(a.id(), a.proposer(), a.recipient(), a.definitionId(), a.terms(), a.explanation(), a.termsHash(), signatures, state, a.proposedAt(), expires, s.revision));
    }
    private static boolean open(Petition p) { return p.state() == PetitionState.SUBMITTED || p.state() == PetitionState.UNDER_REVIEW; }
    private UUID petition(ServerPlayer actor, Request r, PoliticalSavedData.Records s) {
        Government g = s.governments.get(r.kingdom()); check(g != null && g.state() == State.ACTIVE && definitions.available(g.profileId()), "Government unavailable");
        Definition terms = definitions.get(r.definition(), "petition");
        check(Set.of("mcakingdoms:institution_petition", "mcakingdoms:honor_petition", "mcakingdoms:introduction_petition").contains(r.definition()),
                Keyed.text("politics.mcakingdoms.refusal.petition_handler","This petition type has no supported way to be handled."));
        check(s.petitions.values().stream().filter(p -> p.requester().equals(actor.getUUID()) && p.kingdom().equals(g.kingdom()) && open(p) && now() < p.expiresAt()).count() < 3, Keyed.text("politics.mcakingdoms.refusal.petition_limit","You may have at most three open petitions per kingdom."));
        check(!r.text().isBlank(), Keyed.text("politics.mcakingdoms.refusal.explain_request","Explain your request."));
        UUID settlement = r.target().isEmpty() ? g.capital() : settlement(r.target(), r.kingdom()).id();
        Building building = r.definition().equals("mcakingdoms:institution_petition") ? building(actor.serverLevel(), new BlockPos(r.x(), r.y(), r.z()), r.kingdom()) : null;
        if (r.definition().equals("mcakingdoms:honor_petition")) person(r.person(), r.kingdom(), null);
        if (r.definition().equals("mcakingdoms:introduction_petition")) check(s.governments.containsKey(r.counterpart()) && !r.counterpart().equals(r.kingdom()), Keyed.text("politics.mcakingdoms.refusal.counterpart_unavailable","The other kingdom has no government."));
        UUID id = UUID.randomUUID();
        s.petitions.put(id, new Petition(id, actor.getUUID(), r.kingdom(), r.definition(), terms, settlement, r.person(), building,
                r.counterpart(), r.text(), PetitionState.SUBMITTED, "", null, now(), now() + terms.durationTicks(), s.revision)); return id;
    }
    private Petition decision(Petition p, PetitionState state, String reason, UUID actor, long revision) {
        return new Petition(p.id(), p.requester(), p.kingdom(), p.definitionId(), p.terms(), p.settlement(), p.nominee(), p.building(), p.counterpart(), p.explanation(), state, reason, actor, p.submittedAt(), p.expiresAt(), revision);
    }
    private void approvePetition(ServerPlayer actor, Petition p, PoliticalSavedData.Records s, Government g) {
        settlement(p.settlement().toString(), g.kingdom());
        switch (p.definitionId()) {
            case "mcakingdoms:institution_petition" -> {
                authorize(actor, g, Permission.RECOGNIZE);
                Building b = p.building();
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(b.dimension())));
                check(level != null, Keyed.text("politics.mcakingdoms.refusal.building_dimension","The building's dimension is not available."));
                Building observed = building(level, new BlockPos(b.x(), b.y(), b.z()), g.kingdom());
                check(observed.villageId() == b.villageId() && observed.buildingId() == b.buildingId(), Keyed.text("politics.mcakingdoms.refusal.institution_gone","The original institution no longer exists."));
                String definition = definitions.snapshot().entrySet().stream().filter(e -> e.getValue().kind().equals("institution") && e.getValue().buildingTypes().contains(observed.type()))
                        .map(Map.Entry::getKey).sorted().findFirst().orElseThrow(() -> Keyed.refuse("politics.mcakingdoms.refusal.no_charter_type","No institution charter covers this building type."));
                recognize(actor, s, g.kingdom(), definition, observed);
            }
            case "mcakingdoms:honor_petition" -> {
                authorize(actor, g, Permission.HONOR);
                honor(actor, s, g.kingdom(), petitionHonor(g.kingdom()), person(p.nominee(), g.kingdom(), null), "Discretionary petition " + p.id(), true);
            }
            case "mcakingdoms:introduction_petition" -> {
                Request proposal = new Request(UUID.randomUUID(), s.revision, Action.PROPOSE, g.kingdom(), "mcakingdoms:diplomatic_recognition", "", null, p.counterpart(), p.explanation(), "", 0, 0, 0);
                propose(actor, proposal, s, g);
            }
            default -> throw Keyed.refuse("politics.mcakingdoms.refusal.fulfillment_handler","This petition has no supported way to be fulfilled.");
        }
    }
    /** Kingdom definitions name the honor an approved honor petition grants; one that does not grants the open bell. */
    static final String PETITION_HONOR_METADATA = "petition_honor";
    static final String DEFAULT_PETITION_HONOR = "mcakingdoms:open_bell";
    private String petitionHonor(String kingdom) {
        ResourceLocation id = ResourceLocation.tryParse(kingdom);
        return id == null ? DEFAULT_PETITION_HONOR : petitionHonor(kingdoms.getKingdom(id).map(KingdomView::metadata).orElse(Map.of()));
    }
    static String petitionHonor(Map<String, String> metadata) {
        String configured = metadata.get(PETITION_HONOR_METADATA);
        return configured != null && ResourceLocation.tryParse(configured) != null ? configured : DEFAULT_PETITION_HONOR;
    }
    /** What the political pages can draw on right now, as the provider bridges report it. */
    private String providerStatus() {
        String receipts = switch (dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.capability()) {
            case "available" -> "MCA: Quests completion receipts available; paid workshop commissions credit honors";
            case "absent" -> "MCA: Quests absent, so there are no completion receipts or paid commissions";
            case "not_probed" -> "MCA: Quests not probed yet";
            default -> "MCA: Quests installed, but its completion receipts are unsupported";
        };
        return receipts + "; building evidence " + (townstead().capabilities().contains(TownsteadCapability.READ_BUILDING)
                ? "available" : "unavailable") + ".";
    }
    private UUID recognize(ServerPlayer actor, PoliticalSavedData.Records s, String kingdom, String id, Building b) {
        Definition terms = definitions.get(id, "institution");
        check(terms.buildingTypes().contains(b.type()), Keyed.text("politics.mcakingdoms.refusal.charter_type", "This charter does not cover the building type %s.", b.type()));
        check(s.recognitions.values().stream().noneMatch(v -> v.building().dimension().equals(b.dimension()) && v.building().villageId() == b.villageId() && v.building().buildingId() == b.buildingId()), Keyed.text("politics.mcakingdoms.refusal.already_recognized","This institution is already recognized; use Revalidate instead."));
        UUID record = UUID.randomUUID(); s.recognitions.put(record, new Recognition(record, kingdom, id, terms, b, actor.getUUID(), true, s.revision)); return record;
    }
    private UUID honor(ServerPlayer actor, PoliticalSavedData.Records s, String kingdom, String id, Person person, String evidence, boolean discretionary) {
        check(honorCapacity(s), Keyed.text("politics.mcakingdoms.refusal.honor_capacity","The remaining honor capacity is reserved for accepted institutional work."));
        Definition terms = definitions.get(id, "honor");
        check(s.honors.values().stream().noneMatch(h -> h.kingdom().equals(kingdom) && h.definitionId().equals(id) && h.recipient().equals(person)), Keyed.text("politics.mcakingdoms.refusal.honor_awarded","That honor has already been awarded to this person here."));
        UUID record = UUID.randomUUID(); s.honors.put(record, new Honor(record, kingdom, id, terms, person, actor.getUUID(), evidence, discretionary, true, s.revision)); return record;
    }
    private boolean honorCapacity(PoliticalSavedData.Records records) {
        int reserved=dev.otectus.mcakingdoms.civic.CivicRuntime.get(server).reservedHonorSlots(records.honors.keySet());
        return records.honors.size()+reserved<8192;
    }
    public boolean canPromiseCommissionHonor() { thread();return data.writable()&&honorCapacity(data.records()); }
    /** Internal recipient: frozen recognized-workshop authorization plus durable provider evidence. */
    public boolean recordCommissionHonor(ServerPlayer player, UUID epoch, UUID receipt) {
        thread();if(!data.writable()||player.getServer()!=server)return false;
        var proof=dev.otectus.mcakingdoms.civic.CivicRuntime.get(server).commissionHonor(player,epoch,receipt);
        if(proof.isEmpty())return false;
        var grant=proof.get();var prior=data.records().honors.get(grant.contract());
        String evidence="Verified workshop commission "+grant.contract()+"; provider "+epoch+"/"+receipt;
        if(prior!=null)return prior.recipient().equals(new Person(player.getUUID(),Kind.PLAYER))&&prior.evidence().equals(evidence);
        if(data.records().honors.size()>=8192)return false;
        var next=data.transaction();next.revision++;
        next.honors.put(grant.contract(),new Honor(grant.contract(),grant.kingdom(),grant.honor(),grant.terms(),
                new Person(player.getUUID(),Kind.PLAYER),grant.giver(),evidence,false,true,next.revision));
        // Personal receipt, not a public crime/quest announcement. Existing honor panel shows the award.
        if(!data.commitDurably(server,next))return false;
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("civic.workshop_honor_awarded"));
        return true;
    }
    @Override public boolean canRecognize(ServerPlayer actor, UUID settlementId) {
        thread(); if (actor.getServer() != server || !data.writable()) return false;
        var settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return false;
        Government government = data.records().governments.get(settlement.get().kingdomId().toString());
        if (government == null) return false;
        try { authorizeScoped(actor, government, Permission.RECOGNIZE, settlement.get().id()); return true; }
        catch (IllegalArgumentException failure) { return false; }
    }
    @Override public boolean authorized(ServerPlayer actor, String kingdom, Permission permission) {
        thread();
        return actor.getServer() == server && !actor.hasDisconnected() && data.writable()
                && permitted(actor, data.records().governments.get(kingdom), permission);
    }
    @Override public boolean isCapital(UUID settlement) {
        thread();
        UUID place = kingdoms.resolveSettlementId(settlement);
        return data.records().governments.values().stream().anyMatch(g -> kingdoms.resolveSettlementId(g.capital()).equals(place));
    }
    @Override public Optional<dev.otectus.mcakingdoms.api.politics.InstitutionView> institution(UUID id) {
        thread(); Recognition recognition = data.records().recognitions.get(id);
        if (recognition == null) return Optional.empty();
        var original = recognition.building();
        var settlement = kingdoms.getSettlement(original.settlement());
        if (settlement.isEmpty()) return Optional.empty();
        var status = dev.otectus.mcakingdoms.api.politics.InstitutionView.Status.AVAILABLE;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation(original.dimension())));
        BlockPos position = new BlockPos(original.x(),original.y(),original.z());
        if (!recognition.active() || !settlement.get().kingdomId().toString().equals(recognition.kingdom()))
            status = dev.otectus.mcakingdoms.api.politics.InstitutionView.Status.SUSPENDED;
        else if (!data.writable() || !definitions.available(recognition.definitionId()))
            status = dev.otectus.mcakingdoms.api.politics.InstitutionView.Status.DEFINITION_UNAVAILABLE;
        else if (level == null || !level.hasChunkAt(position))
            status = dev.otectus.mcakingdoms.api.politics.InstitutionView.Status.UNLOADED;
        else if (!townstead().capabilities().contains(TownsteadCapability.READ_BUILDING))
            status = dev.otectus.mcakingdoms.api.politics.InstitutionView.Status.PROVIDER_UNAVAILABLE;
        else {
            var current = townstead().buildingAt(level,position);
            if (current.isEmpty() || current.get().buildingId()!=original.buildingId()
                    || current.get().villageId()!=original.villageId()
                    || !recognition.terms().buildingTypes().contains(current.get().type())
                    || !kingdoms.getSettlement(current.get().settlementId()).map(s -> s.id().equals(settlement.get().id())).orElse(false))
                status = dev.otectus.mcakingdoms.api.politics.InstitutionView.Status.BUILDING_CHANGED;
        }
        return Optional.of(new dev.otectus.mcakingdoms.api.politics.InstitutionView(recognition.id(),
                settlement.get().kingdomId(),settlement.get().id(),new ResourceLocation(original.dimension()),position,
                original.type(),recognition.terms().title(),status,data.records().revision));
    }
    @Override public List<dev.otectus.mcakingdoms.api.politics.InstitutionView> knownInstitutions(ServerPlayer viewer,int offset,int limit) {
        thread(); check(viewer.getServer()==server,"Wrong server"); check(offset>=0&&offset<=4096&&limit>0&&limit<=32,Keyed.text("politics.mcakingdoms.refusal.invalid_institution_page","That institution page is not valid."));
        return data.records().recognitions.keySet().stream().sorted().map(this::institution).flatMap(Optional::stream)
                .filter(v -> dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(server).visible(viewer,v.settlement()))
                .skip(offset).limit(limit).toList();
    }
    @Override public Page page(ServerPlayer viewer, UUID requestId, String kingdom, String tab, int offset) {
        thread(); check(viewer.getServer() == server, "Wrong server");
        // Any offset: a reply cut to its byte budget continues from the first row it left out (H08).
        check(offset >= 0 && offset <= 100000, Keyed.text("politics.mcakingdoms.refusal.invalid_offset","That page is not valid."));
        PoliticalSavedData.Records s = data.records(); Government g = s.governments.get(kingdom);
        List<Row> rows = new ArrayList<>();
        // History filters ride in the tab name (H10): history:<group>:<days>:<visibility>, applied to the authorized facts before paging.
        var historyFilter = HistoryFilter.parse(tab);
        switch (historyFilter.isPresent() ? "history" : tab) {
            case "overview" -> {
                if (g == null) rows.add(new Row("", "UNORGANIZED", "An operator may explicitly constitute this kingdom at an existing settlement."));
                else {
                    rows.add(new Row(g.kingdom(), definitions.available(g.profileId()) ? g.state().name() : "DORMANT", g.profileId()));
                    rows.add(new Row(g.capital().toString(), "Capital seat", kingdoms.getSettlement(g.capital()).map(SettlementView::displayName).orElse("Unavailable")));
                    rows.add(new Row("", "Succession preview", g.successor() == null ? "No named successor; vacant leadership remains in interregnum" : successionPreview(g)));
                    House house = s.houses.get(kingdom); if (house != null) rows.add(new Row("", house.name(), house.motto()));
                }
                rows.add(new Row("", "Provider status", providerStatus()));
            }
            case "council" -> {
                if (g != null) g.offices().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rows.add(new Row(e.getKey(), e.getValue().terms().title(),
                        personLabel(e.getValue().holder()) + (e.getValue().settlement() == null ? "" : " at " + dev.otectus.mcakingdoms.interaction.Names.settlement(server, e.getValue().settlement())))));
                if (g != null && permitted(viewer, g, Permission.DELEGATE)) g.mandates().forEach((id, permissions) -> rows.add(new Row(id.toString(), "Mandate", permissions.toString())));
            }
            case "agreements" -> s.agreements.values().stream().filter(a -> a.involves(kingdom))
                    .filter(a -> a.signatures().size() == 2 || permitted(viewer, s.governments.get(a.proposer()), Permission.PROPOSE)
                            || permitted(viewer, s.governments.get(a.proposer()), Permission.RATIFY) || permitted(viewer, s.governments.get(a.recipient()), Permission.PROPOSE)
                            || permitted(viewer, s.governments.get(a.recipient()), Permission.RATIFY))
                    .sorted(Comparator.comparing(a -> a.id().toString())).forEach(a -> rows.add(new Row(a.id().toString(), a.terms().title(),
                            (now() >= a.expiresAt() && (a.state() == AgreementState.PROPOSED || a.state() == AgreementState.ACTIVE) ? "EXPIRED" : a.state().name())
                                    + " " + a.proposer() + " ↔ " + a.recipient() + "; " + a.explanation()
                                    + "; signatures=" + a.signatures().keySet() + "; deadline=" + a.expiresAt() + " game ticks"
                                    + (definitions.available(a.definitionId()) ? "" : "; definition unavailable")
                                    + (a.terms().clauses().isEmpty() ? "; ceremonial terms; no operational clauses" : "; clauses=" + a.terms().clauses().stream().sorted().map(c -> c + ": " + (active(a) ? clause(a.proposer(), a.recipient(), c).status() : AgreementClauseView.Status.NOT_AGREED)).collect(Collectors.joining(", ")))
                                    + (a.terms().commissionPool().isEmpty() ? "" : "; opportunity=" + a.terms().commissionPool()), a.termsHash())));
            case "petitions" -> s.petitions.values().stream().filter(p -> p.kingdom().equals(kingdom))
                    .filter(p -> p.requester().equals(viewer.getUUID()) || permitted(viewer, g, Permission.REVIEW))
                    .sorted(Comparator.comparing(p -> p.id().toString())).forEach(p -> rows.add(new Row(p.id().toString(), p.definitionId(),
                            (open(p) && now() >= p.expiresAt() ? "EXPIRED" : p.state().name()) + "; " + p.explanation() + "; " + p.decision())));
            case "institutions" -> s.recognitions.values().stream().filter(r -> r.kingdom().equals(kingdom))
                    .filter(r -> kingdoms.getSettlement(r.building().settlement()).map(settlement ->
                            dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(server).visible(viewer, settlement.id())).orElse(false))
                    .forEach(r -> rows.add(new Row(r.id().toString(), r.terms().title(),
                    r.building().type() + " at " + r.building().x() + "," + r.building().y() + "," + r.building().z() + "; " + (r.active() ? "Recognized; current building eligibility requires revalidation" : "Suspended"))));
            case "honors" -> s.honors.values().stream().filter(h -> h.kingdom().equals(kingdom)).forEach(h -> rows.add(new Row(h.id().toString(), h.terms().title(),
                    h.recipient().id() + "; " + (h.active() ? "Awarded" : "Revoked") + "; " + h.evidence())));
            case "history" -> visibleHistory(viewer).filter(fact -> fact.affectedKingdoms().contains(kingdom))
                    .filter(fact -> historyFilter.map(filter -> filter.accepts(fact, now())).orElse(true))
                    .forEach(fact -> rows.add(new Row(fact.id().toString(), fact.summary(),
                            "Recorded on day " + (fact.gameTime() / 24000 + 1) + ". "
                                    + (fact.actor() == null ? "Government record." : "By " + dev.otectus.mcakingdoms.interaction.Names.player(server, fact.actor()) + ".")
                                    + " Status: " + fact.correction().name().toLowerCase(java.util.Locale.ROOT) + ".")));
            case "succession" -> rows.addAll(dev.otectus.mcakingdoms.politics.dynasty.DynastyPages.rows(dev.otectus.mcakingdoms.politics.dynasty.DynastyService.of(server, this), viewer, kingdom));
            case "development" -> rows.addAll(dev.otectus.mcakingdoms.development.DevelopmentPages.rows(dev.otectus.mcakingdoms.development.DevelopmentService.get(server), viewer, kingdom));
            default -> throw Keyed.refuse("politics.mcakingdoms.refusal.unknown_tab","That government page does not exist.");
        }
        Sovereignty crown = sovereignty.apply(kingdom);
        String diagnostic = !data.writable() ? data.diagnostic() : crown.native_() ? "" : crown.statusKey();
        int start = Math.min(offset, rows.size());
        return new Page(requestId, revision(), kingdom, tab, offset, rows.size() > start + 20,
                rows.subList(start, Math.min(start + 20, rows.size())), definitions.snapshot().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).limit(128).map(e -> new Choice(e.getKey(), e.getValue().kind(), e.getValue().title())).toList(),
                actionDenials(viewer, kingdom, g), diagnostic);
    }
    /**
     * A page row as players read it: translated title and detail, naming people, places and kingdoms instead of ids, and
     * the same facts as labelled fields ("State", "Parties", "Signatures") for screens that show records as sections.
     */
    /** The committed fact with this id (the id a {@link PoliticalCommittedEvent}'s notice carries), for presentation (A02). */
    public Optional<PoliticalFact> committedFact(UUID id) {
        thread();
        for (int i = data.records().facts.size() - 1; i >= 0; i--) {
            PoliticalFact fact = data.records().facts.get(i);
            if (fact.id().equals(id)) return Optional.of(fact);
        }
        return Optional.empty();
    }

    /** One political record's current state for a task's Check (F07): {@code agreement}, {@code petition} or {@code election}. */
    public record RecordState(String kind, String state, String detail) { }
    /**
     * The state of the agreement, petition or election {@code recordId}, read without changing anything, when the viewer
     * may see it: a party or authorized official of an agreement (or anyone once it is in effect), the requester, nominee,
     * decider or a reviewing official of a petition, and the visibility rules of {@link #election} for an election.
     */
    public Optional<RecordState> recordState(ServerPlayer viewer, String recordId) {
        thread();
        Optional<UUID> id = parse(recordId);
        if (id.isEmpty()) return Optional.empty();
        var records = data.records();
        Agreement agreement = records.agreements.get(id.get());
        if (agreement != null) {
            boolean party = agreement.state() == AgreementState.ACTIVE || agreement.signatures().containsValue(viewer.getUUID())
                    || viewer.hasPermissions(2);
            for (String kingdom : List.of(agreement.proposer(), agreement.recipient())) {
                Government g = records.governments.get(kingdom);
                party |= permitted(viewer, g, Permission.PROPOSE) || permitted(viewer, g, Permission.RATIFY);
            }
            return party ? Optional.of(new RecordState("agreement", agreement.state().name(), Integer.toString(agreement.signatures().size()))) : Optional.empty();
        }
        Petition petition = records.petitions.get(id.get());
        if (petition != null) {
            boolean party = viewer.getUUID().equals(petition.requester()) || viewer.getUUID().equals(petition.decidedBy())
                    || petition.nominee() != null && viewer.getUUID().equals(petition.nominee().id())
                    || permitted(viewer, records.governments.get(petition.kingdom()), Permission.REVIEW) || viewer.hasPermissions(2);
            return party ? Optional.of(new RecordState("petition", petition.state().name(), petition.decision())) : Optional.empty();
        }
        return election(viewer, id.get()).map(e -> new RecordState("election", e.state().name(), Long.toString(e.graceUntil())));
    }

    public record PresentedRow(net.minecraft.network.chat.Component title, net.minecraft.network.chat.Component detail, List<Field> fields) {
        public PresentedRow { fields = List.copyOf(fields); }
        public PresentedRow(net.minecraft.network.chat.Component title, net.minecraft.network.chat.Component detail) { this(title, detail, List.of()); }
    }
    /** One labelled fact of a presented row. */
    public record Field(net.minecraft.network.chat.Component label, net.minecraft.network.chat.Component value) { }
    private static Field f(String key, String fallback, net.minecraft.network.chat.Component value) {
        return new Field(net.minecraft.network.chat.Component.translatableWithFallback("politics.mcakingdoms.field." + key, fallback), value);
    }

    private static net.minecraft.network.chat.Component p(String key, String fallback, Object... args) {
        return net.minecraft.network.chat.Component.translatableWithFallback("politics.mcakingdoms.row." + key, fallback, args);
    }
    private net.minecraft.network.chat.Component definitionTitle(String title) {
        return dev.otectus.mcakingdoms.interaction.PlayerWords.isKey(title)
                ? net.minecraft.network.chat.Component.translatableWithFallback(title, dev.otectus.mcakingdoms.interaction.PlayerWords.text(title))
                : net.minecraft.network.chat.Component.literal(title);
    }
    private net.minecraft.network.chat.Component kingdomName(String kingdom) {
        return kingdoms.getKingdom(ResourceLocation.tryParse(kingdom) == null ? new ResourceLocation("minecraft", "missing") : new ResourceLocation(kingdom))
                .map(k -> (net.minecraft.network.chat.Component) net.minecraft.network.chat.Component.translatableWithFallback(k.translationKey(), dev.otectus.mcakingdoms.interaction.PlayerWords.text(k.translationKey())))
                .orElse(net.minecraft.network.chat.Component.literal(kingdom));
    }
    private static net.minecraft.network.chat.Component enumWords(Enum<?> value) {
        return dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(value);
    }
    /** Each clause of {@code a} with its current state, in the reader's language. */
    private net.minecraft.network.chat.Component clauseWords(Agreement a) {
        var words = net.minecraft.network.chat.Component.empty();
        boolean first = true;
        for (Clause c : a.terms().clauses().stream().sorted().toList()) {
            if (!first) words.append(", ");
            first = false;
            var status = active(a) ? clause(a.proposer(), a.recipient(), c).status() : AgreementClauseView.Status.NOT_AGREED;
            words.append(p("clause", "%s (%s)", enumWords(c), enumWords(status)));
        }
        return words;
    }
    /**
     * Presents the rows of a page this viewer was already allowed to read. Each row's record is looked up by its id, so
     * no extra record can appear; the API's {@link Row} strings are left exactly as they were.
     */
    public List<PresentedRow> present(ServerPlayer viewer, Page page) {
        thread();
        if (page.tab().equals("succession"))
            return dev.otectus.mcakingdoms.politics.dynasty.DynastyPages.present(dev.otectus.mcakingdoms.politics.dynasty.DynastyService.of(server, this), viewer, page.kingdom(), page.rows());
        if (page.tab().equals("development"))
            return dev.otectus.mcakingdoms.development.DevelopmentPages.present(dev.otectus.mcakingdoms.development.DevelopmentService.get(server), viewer, page.rows());
        PoliticalSavedData.Records s = data.records(); Government g = s.governments.get(page.kingdom());
        List<PresentedRow> out = new ArrayList<>();
        for (Row row : page.rows()) {
            net.minecraft.network.chat.Component title = definitionTitle(row.title()), detail = net.minecraft.network.chat.Component.literal(row.detail());
            List<Field> fields = new ArrayList<>();
            Optional<UUID> id = parse(row.id());
            switch (HistoryFilter.parse(page.tab()).isPresent() ? "history" : page.tab()) {
                case "overview" -> {
                    if (row.title().equals("UNORGANIZED")) {
                        title = p("unorganized", "No government yet");
                        detail = openFounding.getAsBoolean()
                                ? p("unorganized.open", "An operator, or a player whose recorded residence is in this kingdom, can establish its first government with Establish a government.")
                                : p("unorganized.closed", "An operator can establish this kingdom's first government with Establish a government.");
                        fields.add(f("government", "Government", p("unorganized", "No government yet")));
                        fields.add(f("founding", "Who can found it", openFounding.getAsBoolean() ? p("founding.open", "An operator, or a resident of this kingdom naming themselves leader")
                                : p("founding.closed", "Only an operator")));
                    } else if (g != null && row.id().equals(g.kingdom())) {
                        title = definitions.available(g.profileId()) ? enumWords(g.state()) : p("dormant", "Dormant");
                        net.minecraft.network.chat.Component constitution = definitions.available(g.profileId()) ? definitionTitle(g.constitution().title()) : net.minecraft.network.chat.Component.literal(g.profileId());
                        detail = p("constitution", "Constitution: %s", constitution);
                        fields.add(f("state", "State", title));
                        fields.add(f("constitution", "Constitution", constitution));
                    } else if (row.title().equals("Capital seat")) {
                        title = p("capital", "Capital seat");
                        fields.add(f("capital", "Capital", net.minecraft.network.chat.Component.literal(row.detail())));
                    } else if (row.title().equals("Succession preview")) {
                        title = p("succession", "Succession preview");
                        if (g != null && g.successor() == null) {
                            detail = p("succession.none", "No named successor; a vacant leadership stays in interregnum.");
                            fields.add(f("successor", "Named successor", p("successor.none", "None")));
                        } else if (g != null) {
                            net.minecraft.network.chat.Component who = net.minecraft.network.chat.Component.literal(personLabel(g.successor()));
                            if (elected(s, g.kingdom(), g.successor())) who = p("succession.elected", "%s (elected)", who);
                            String pending = "";
                            try { person(g.successor(), g.kingdom(), null); } catch (IllegalArgumentException unavailable) { pending = unavailable.getMessage(); }
                            detail = pending.isEmpty() ? p("succession.eligible", "%s; currently eligible; a confirmed vacancy is required.", who)
                                    : p("succession.pending", "%s; pending: %s", who, pending);
                            fields.add(f("successor", "Named successor", who));
                            fields.add(f("succession_status", "Status", pending.isEmpty() ? p("succession.status.eligible", "Eligible; takes office only after a confirmed vacancy")
                                    : p("succession.status.pending", "Pending: %s", pending)));
                        }
                    } else if (row.title().equals("Provider status")) {
                        title = p("providers", "Provider status");
                        detail = providerStatusComponent();
                        fields.add(f("providers", "Companion services", detail));
                    } else {
                        // A recorded house: its name and motto are player-authored and stay literal.
                        title = net.minecraft.network.chat.Component.literal(row.title());
                        fields.add(f("house", "Political house", title));
                        if (!row.detail().isBlank()) fields.add(f("motto", "Motto", net.minecraft.network.chat.Component.literal(row.detail())));
                    }
                }
                case "council" -> {
                    if (row.title().equals("Mandate") && id.isPresent() && g != null) {
                        title = p("mandate", "Mandate: %s", dev.otectus.mcakingdoms.interaction.Names.player(server, id.get()));
                        var permissions = net.minecraft.network.chat.Component.empty(); boolean first = true;
                        for (Permission permission : g.mandates().getOrDefault(id.get(), Set.of()).stream().sorted().toList()) {
                            if (!first) permissions.append(", "); first = false; permissions.append(enumWords(permission));
                        }
                        detail = permissions;
                        fields.add(f("player", "Player", net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.player(server, id.get()))));
                        fields.add(f("responsibilities", "Granted responsibilities", permissions));
                    } else if (g != null && g.offices().containsKey(row.id())) {
                        Office office = g.offices().get(row.id());
                        // A02: the same mark the office badge shows above the holder, so the list and the world agree.
                        title = net.minecraft.network.chat.Component.literal(row.id().equals(LEADER) || row.id().startsWith(LEADER + "@") ? "♛ " : "⚑ ").append(title);
                        detail = office.settlement() == null ? p("holder", "%s", personLabel(office.holder()))
                                : p("holder.at", "%s at %s", personLabel(office.holder()), dev.otectus.mcakingdoms.interaction.Names.settlement(server, office.settlement()));
                        fields.add(f("office", "Office", title));
                        fields.add(f("holder", "Held by", net.minecraft.network.chat.Component.literal(personLabel(office.holder()))));
                        fields.add(f("jurisdiction", "Where", office.settlement() == null ? p("jurisdiction.kingdom", "Kingdom-wide")
                                : net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.settlement(server, office.settlement()))));
                        var responsibilities = net.minecraft.network.chat.Component.empty(); boolean first = true;
                        for (Permission permission : office.terms().permissions().stream().sorted().toList()) {
                            if (!first) responsibilities.append(", "); first = false; responsibilities.append(enumWords(permission));
                        }
                        if (!first) fields.add(f("office_responsibilities", "Responsibilities", responsibilities));
                    }
                }
                case "agreements" -> {
                    Agreement a = id.map(s.agreements::get).orElse(null);
                    if (a != null) {
                        boolean expired = now() >= a.expiresAt() && (a.state() == AgreementState.PROPOSED || a.state() == AgreementState.ACTIVE);
                        var state = expired ? enumWords(AgreementState.EXPIRED) : enumWords(a.state());
                        var parties = p("parties", "%s and %s", kingdomName(a.proposer()), kingdomName(a.recipient()));
                        var signatures = p("signatures", "%s of 2 signatures", a.signatures().size());
                        var timing = expired ? p("expired", "expired") : p("expires", "expires in %s", dev.otectus.mcakingdoms.interaction.Names.remainingText(server, a.expiresAt()));
                        var clauses = a.terms().clauses().isEmpty() ? p("ceremonial", "ceremonial terms; no operational clauses") : p("clauses", "clauses: %s", clauseWords(a));
                        var text = net.minecraft.network.chat.Component.empty().append(state).append(" · ").append(parties).append(" · ").append(signatures).append(" · ").append(timing);
                        if (!definitions.available(a.definitionId())) text.append(" · ").append(p("definition_unavailable", "its definition is no longer loaded"));
                        text.append(" · ").append(clauses);
                        if (!a.explanation().isBlank()) text.append("\n").append(p("terms", "Terms: %s", Keyed.component(a.explanation())));
                        detail = text;
                        fields.add(f("agreement", "Agreement", title));
                        fields.add(f("state", "State", state));
                        fields.add(f("parties", "Parties", parties));
                        var signed = net.minecraft.network.chat.Component.empty(); boolean first = true;
                        for (String kingdom : a.signatures().keySet()) { if (!first) signed.append(", "); first = false; signed.append(kingdomName(kingdom)); }
                        fields.add(f("signatures", "Signatures", first ? signatures : p("signatures.by", "%s: %s", signatures, signed)));
                        if (a.state() == AgreementState.PROPOSED && !expired) {
                            var waiting = net.minecraft.network.chat.Component.empty(); boolean any = false;
                            for (String kingdom : List.of(a.proposer(), a.recipient())) if (!a.signatures().containsKey(kingdom)) { if (any) waiting.append(", "); any = true; waiting.append(kingdomName(kingdom)); }
                            fields.add(f("next", "Next step", p("next.sign", "Waiting for the signature of %s", waiting)));
                        }
                        fields.add(f("timing", "Timing", timing));
                        fields.add(f("clauses", "What it allows", clauses));
                        if (!a.explanation().isBlank()) fields.add(f("terms", "Terms", Keyed.component(a.explanation())));
                    }
                }
                case "petitions" -> {
                    Petition petition = id.map(s.petitions::get).orElse(null);
                    if (petition != null) {
                        title = definitionTitle(petition.terms().title());
                        boolean expired = open(petition) && now() >= petition.expiresAt();
                        var state = expired ? enumWords(PetitionState.EXPIRED) : enumWords(petition.state());
                        var requester = net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.player(server, petition.requester()));
                        var text = net.minecraft.network.chat.Component.empty().append(state)
                                .append(" · ").append(p("requested_by", "requested by %s", requester));
                        if (!petition.explanation().isBlank()) text.append("\n").append(p("reason", "Reason: %s", Keyed.component(petition.explanation())));
                        if (!petition.decision().isBlank()) text.append("\n").append(p("decision", "Decision: %s", Keyed.component(petition.decision())));
                        detail = text;
                        fields.add(f("request", "Request", title));
                        fields.add(f("requester", "Requested by", requester));
                        fields.add(f("state", "State", state));
                        if (!petition.explanation().isBlank()) fields.add(f("reason_given", "Reason given", Keyed.component(petition.explanation())));
                        if (!petition.decision().isBlank()) fields.add(f("decision", "Decision", net.minecraft.network.chat.Component.literal(petition.decision())));
                        if (open(petition) && !expired) fields.add(f("next", "Next step", p("next.review", "Waiting for an official who holds the review responsibility")));
                    }
                }
                case "institutions" -> {
                    Recognition r = id.map(s.recognitions::get).orElse(null);
                    if (r != null) {
                        var status = r.active() ? p("recognized", "recognized; its building is checked again when revalidated") : p("suspended", "suspended");
                        detail = p("institution", "%s at %s; %s", r.building().type(), r.building().x() + ", " + r.building().y() + ", " + r.building().z(), status);
                        fields.add(f("institution", "Recognized for", title));
                        fields.add(f("building", "Building", net.minecraft.network.chat.Component.literal(r.building().type())));
                        fields.add(f("location", "Location", net.minecraft.network.chat.Component.literal(r.building().x() + ", " + r.building().y() + ", " + r.building().z())));
                        fields.add(f("verification", "Status", status));
                        fields.add(f("meaning", "What this means", p("institution.meaning", "Recognition lets civic services use this building. It does not build anything and does not guarantee a provider is available.")));
                    }
                }
                case "honors" -> {
                    Honor h = id.map(s.honors::get).orElse(null);
                    if (h != null) {
                        var status = h.active() ? p("awarded", "in force") : p("revoked", "revoked");
                        detail = p("honor", "Awarded to %s; %s%s", personLabel(h.recipient()), status,
                                h.evidence().isBlank() ? net.minecraft.network.chat.Component.empty() : p("evidence", ". Reason: %s", h.evidence()));
                        fields.add(f("honor", "Honor", title));
                        fields.add(f("recipient", "Awarded to", net.minecraft.network.chat.Component.literal(personLabel(h.recipient()))));
                        fields.add(f("status", "Status", status));
                        if (!h.evidence().isBlank()) fields.add(f("reason", "Reason", net.minecraft.network.chat.Component.literal(h.evidence())));
                        fields.add(f("meaning", "What this means", p("honor.meaning", "An honor records the kingdom's recognition. It is not an office and grants no permissions.")));
                    }
                }
                case "history" -> {
                    PoliticalFact fact = id.flatMap(value -> s.facts.stream().filter(fa -> fa.id().equals(value)).findFirst()).map(this::corrected).orElse(null);
                    if (fact != null) {
                        title = net.minecraft.network.chat.Component.translatableWithFallback("politics.mcakingdoms.fact." + fact.type().name().toLowerCase(Locale.ROOT), fact.summary());
                        var by = fact.actor() == null ? p("fact.government", "Government record.") : p("fact.by", "By %s.", dev.otectus.mcakingdoms.interaction.Names.player(server, fact.actor()));
                        detail = p("fact", "Recorded on day %s. %s Status: %s.", fact.gameTime() / 24000 + 1, by, enumWords(fact.correction()));
                        fields.add(f("event", "Event", title));
                        fields.add(f("when", "When", p("day", "Day %s", fact.gameTime() / 24000 + 1)));
                        fields.add(f("by", "Recorded by", fact.actor() == null ? p("fact.government.short", "The government")
                                : net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.player(server, fact.actor()))));
                        fields.add(f("status", "Status", enumWords(fact.correction())));
                        fields.add(f("history_note", "Note", p("history.note", "History is a record of what happened; it cannot be replayed or undone from here.")));
                    }
                }
                default -> { }
            }
            out.add(new PresentedRow(title, detail, fields));
        }
        return out;
    }

    /**
     * The Kingdom page header for this viewer: government status, leader and capital, open elections and regency, and the
     * responsibilities the viewer holds here (their own only). Public facts and the viewer's own state; no ballots.
     */
    public List<net.minecraft.network.chat.Component> header(ServerPlayer viewer, String kingdom) {
        thread();
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        Sovereignty crown = sovereignty.apply(kingdom);
        if (!crown.native_()) {
            // Read-through (C3): the crown as Capitals records it; only public court roles, no family ties or private records.
            lines.add(dev.otectus.mcakingdoms.interaction.PlayerWords.component(crown.statusKey()));
            if (crown.mode() == Sovereignty.Mode.CAPITALS) dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop.get(server).crownOf(kingdom).ifPresent(capital -> {
                lines.add(p("header.capitals.sovereign", "Sovereign (MCA Capitals): %s", capital.sovereign() == null ? p("header.leader.vacant", "vacant") : net.minecraft.network.chat.Component.literal(personName(capital.sovereign()))));
                if (capital.heir() != null) lines.add(p("header.capitals.heir", "Heir (MCA Capitals): %s", net.minecraft.network.chat.Component.literal(personName(capital.heir()))));
                if (capital.mourning()) lines.add(p("header.capitals.mourning", "The court is in mourning."));
            });
        }
        PoliticalSavedData.Records s = data.records(); Government g = s.governments.get(kingdom);
        if (g == null) {
            lines.add(p("header.none", "No government yet."));
            lines.add(openFounding.getAsBoolean() ? p("header.none.open", "A resident of this kingdom, or an operator, can found its first government; Check whether you can found a government explains your case.")
                    : p("header.none.closed", "Only an operator can found its first government on this server."));
            return lines;
        }
        lines.add(p("header.state", "Government: %s", definitions.available(g.profileId()) ? enumWords(g.state()) : p("dormant", "Dormant")));
        Office leader = g.offices().get(LEADER);
        lines.add(p("header.leader", "Leader: %s", leader == null ? p("header.leader.vacant", "vacant") : net.minecraft.network.chat.Component.literal(personLabel(leader.holder()))));
        lines.add(p("header.capital", "Capital: %s", kingdoms.getSettlement(g.capital()).map(v -> (net.minecraft.network.chat.Component) net.minecraft.network.chat.Component.literal(v.displayName()))
                .orElse(p("header.capital.unavailable", "unavailable"))));
        Regency regency = s.regencies.get(kingdom);
        if (regency != null && regency.active() && now() < regency.expiresAt())
            lines.add(p("header.regency", "Regency: %s, for %s more", personLabel(regency.regent()), dev.otectus.mcakingdoms.interaction.Names.remainingText(server, regency.expiresAt())));
        for (var election : elections(viewer, 0, 64)) {
            if (!election.kingdom().equals(kingdom) || election.state() != ElectionState.OPEN) continue;
            lines.add(p("header.election", "Election open: %s of %s ballots cast; closes in %s; %s", election.ballotsCast(), election.electorateSize(),
                    dev.otectus.mcakingdoms.interaction.Names.remainingText(server, election.deadline()),
                    election.viewerVoted() ? p("header.election.voted", "your vote is recorded") : election.viewerEligible() ? p("header.election.not_voted", "you may vote")
                            : p("header.election.not_eligible", "you are not in its electorate")));
        }
        if (crown.native_()) lines.addAll(dev.otectus.mcakingdoms.politics.dynasty.DynastyPages.header(dev.otectus.mcakingdoms.politics.dynasty.DynastyService.of(server, this), viewer, kingdom));
        var held = net.minecraft.network.chat.Component.empty(); boolean first = true;
        for (Permission permission : Permission.values()) if (permitted(viewer, g, permission)) { if (!first) held.append(", "); first = false; held.append(enumWords(permission)); }
        lines.add(first ? p("header.yours.none", "Your responsibilities here: none. You can read its public records and send petitions.")
                : p("header.yours", "Your responsibilities here: %s", held));
        return lines;
    }
    private net.minecraft.network.chat.Component providerStatusComponent() {
        var receipts = switch (dev.otectus.mcakingdoms.compat.quests.receipts.QuestCompletionBridge.capability()) {
            case "available" -> p("providers.quests", "MCA Quests completion receipts are available, so paid workshop commissions credit honors");
            case "absent" -> p("providers.no_quests", "MCA Quests is not installed, so there are no completion receipts or paid commissions");
            case "not_probed" -> p("providers.not_probed", "MCA Quests has not been checked yet");
            default -> p("providers.unsupported", "MCA Quests is installed, but its completion receipts are unsupported");
        };
        return p("providers.line", "%s; building evidence %s.", receipts, townstead().capabilities().contains(TownsteadCapability.READ_BUILDING)
                ? p("providers.available", "available") : p("providers.unavailable", "unavailable"));
    }
    private String successionPreview(Government government) {
        String label = personLabel(government.successor()) + (elected(data.records(), government.kingdom(), government.successor()) ? " (elected)" : "");
        try { person(government.successor(), government.kingdom(), null); return label + "; currently eligible; confirmed vacancy required"; }
        catch (IllegalArgumentException unavailable) { return label + "; pending: " + unavailable.getMessage(); }
    }
    /**
     * Open founding: the actor names themselves leader and their civic residence is the chosen capital. Both sides are
     * compared as current settlement ids, so a residence recorded before a merge still names the merged settlement.
     */
    private boolean residentFounder(ServerPlayer actor, Request r) {
        if (!openFounding.getAsBoolean() || r.person() == null || r.person().kind() != Kind.PLAYER || !r.person().id().equals(actor.getUUID())) return false;
        Optional<UUID> capital;
        try { capital = kingdoms.findSettlement(r.target()).map(SettlementView::id).map(kingdoms::resolveSettlementId); }
        catch (IllegalArgumentException ambiguous) { return false; }
        return capital.isPresent() && kingdoms.getCivicIdentity(actor).flatMap(dev.otectus.mcakingdoms.api.CivicIdentityView::residenceSettlement)
                .map(kingdoms::resolveSettlementId).map(capital.get()::equals).orElse(false);
    }
    /** Whether the viewer's recorded residence lies in {@code kingdom}, so resident founding could apply there. */
    private boolean residentOf(ServerPlayer viewer, String kingdom) {
        return kingdoms.getCivicIdentity(viewer).flatMap(dev.otectus.mcakingdoms.api.CivicIdentityView::residenceSettlement)
                .map(kingdoms::resolveSettlementId).flatMap(kingdoms::getSettlement).map(home -> home.kingdomId().toString().equals(kingdom)).orElse(false);
    }
    private String personLabel(Person person) {
        if (person.kind() == Kind.PLAYER) {
            var player = server.getPlayerList().getPlayer(person.id());
            if (player != null) return player.getName().getString();
        } else for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(person.id());
            if (entity != null) return entity.getName().getString();
        }
        return person.kind() == Kind.PLAYER ? dev.otectus.mcakingdoms.interaction.Names.player(server, person.id()) : "an absent " + dev.otectus.mcakingdoms.interaction.Names.lower(dev.otectus.mcakingdoms.interaction.Names.words(person.kind()));
    }
    /** The page's generic availability: each action's refusal for some target, from the one shared policy (H02). */
    private Map<Action, String> actionDenials(ServerPlayer viewer, String kingdom, Government g) {
        var facts = facts(viewer, kingdom, g, data.records());
        Map<Action, String> reasons = new EnumMap<>(Action.class);
        for (Action action : Action.values()) reasons.put(action, GovernmentActionPolicy.denial(facts, action, GovernmentActionPolicy.Target.ANY).orElse(""));
        return reasons;
    }
    /** The facts {@link GovernmentActionPolicy} decides from, read from {@code records} on the server thread. */
    private GovernmentActionPolicy.Facts facts(ServerPlayer actor, String kingdom, Government g, PoliticalSavedData.Records records) {
        String key = g == null ? kingdom : g.kingdom();
        boolean scopedRecognition = g != null && g.state() == State.ACTIVE && g.offices().values().stream()
                .anyMatch(o -> o.settlement() != null && o.holder().equals(new Person(actor.getUUID(), Kind.PLAYER))
                        && o.terms().permissions().contains(Permission.RECOGNIZE) && definitions.available(o.definitionId()));
        return new GovernmentActionPolicy.Facts(actor.getUUID(), actor.hasPermissions(2), data.writable(), sovereignty.apply(key), g,
                g != null && definitions.available(g.profileId()), records.electedMandates.get(key), g != null && activeRegency(g.kingdom()),
                permission -> permitted(actor, g, permission), scopedRecognition, openFounding.getAsBoolean(), g == null && residentOf(actor, kingdom));
    }
    /**
     * Why {@code viewer} cannot take {@code action} on {@code target} in {@code kingdom} now: the bundled key the mutation
     * would refuse with, or empty (H02). Previews ask this per target, so an elected leader's office is never offered for
     * removal; the mutation asks it again when it runs.
     */
    public Optional<String> actionDenial(ServerPlayer viewer, String kingdom, Action action, GovernmentActionPolicy.Target target) {
        thread();
        return GovernmentActionPolicy.denial(facts(viewer, kingdom, data.records().governments.get(kingdom), data.records()), action, target);
    }
    /** One occupied office as the removal form lists it: its key, holder and whether this viewer may vacate it (H02). */
    public record OccupiedOffice(String key, String definitionId, String title, Person holder, String holderName, UUID settlement, Optional<String> removal) {
        /** The scope part of the stored key, exactly as a removal request must name it (a pre-merge id stays as stored). */
        public String scope() { int at = key.indexOf('@'); return at < 0 ? "" : key.substring(at + 1); }
    }
    /** The kingdom's occupied offices in key order, each with this viewer's removal decision. Office holders are public. */
    public List<OccupiedOffice> occupiedOffices(ServerPlayer viewer, String kingdom) {
        thread();
        Government g = data.records().governments.get(kingdom);
        if (g == null) return List.of();
        var facts = facts(viewer, kingdom, g, data.records());
        return g.offices().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new OccupiedOffice(e.getKey(), e.getValue().definitionId(),
                e.getValue().terms().title(), e.getValue().holder(), personLabel(e.getValue().holder()),
                e.getValue().settlement() == null ? null : kingdoms.resolveSettlementId(e.getValue().settlement()),
                GovernmentActionPolicy.denial(facts, Action.REMOVE_OFFICE, GovernmentActionPolicy.Target.office(e.getKey())))).toList();
    }
    @SubscribeEvent public void preflight(SettlementMutationPreflightEvent event) {
        if (event.server() != server) return;
        if (!data.writable()) { event.reject(Keyed.text("politics.mcakingdoms.refusal.merge.read_only","Political records are read-only, so settlement references cannot be checked.")); return; }
        UUID source = event.source().id();
        for (Government g : data.records().governments.values()) {
            boolean seat = kingdoms.getSettlement(g.capital()).map(v -> v.id().equals(source)).orElse(false);
            if (seat && !event.destinationKingdom().toString().equals(g.kingdom())) {
                event.reject(Keyed.text("politics.mcakingdoms.refusal.merge.capital","Choose another capital before changing this settlement's kingdom.")); return;
            }
        }
        if (event.mergeTarget().isPresent()) {
            UUID target = event.mergeTarget().get().id();
            for (Government g : data.records().governments.values()) {
                Set<String> sourceOffices = new HashSet<>();
                Set<String> targetOffices = new HashSet<>();
                for (Office office : g.offices().values()) if (office.settlement() != null) {
                    UUID scope = kingdoms.getSettlement(office.settlement()).orElseThrow().id();
                    if (scope.equals(source)) sourceOffices.add(office.definitionId());
                    if (scope.equals(target)) targetOffices.add(office.definitionId());
                }
                if (!Collections.disjoint(sourceOffices, targetOffices)) {
                    event.reject(Keyed.text("politics.mcakingdoms.refusal.merge.office","Both settlements have holders of the same local office; remove one appointment before merging.")); return;
                }
            }
            boolean sourceRecognition = data.records().recognitions.values().stream().anyMatch(r -> r.active() && kingdoms.getSettlement(r.building().settlement()).map(v -> v.id().equals(source)).orElse(false));
            boolean targetRecognition = data.records().recognitions.values().stream().anyMatch(r -> r.active() && kingdoms.getSettlement(r.building().settlement()).map(v -> v.id().equals(target)).orElse(false));
            if (sourceRecognition && targetRecognition) event.reject(Keyed.text("politics.mcakingdoms.refusal.merge.charters","Both settlements have active institution charters; suspend the source charters before merging."));
        }
        if (!event.source().kingdomId().equals(event.destinationKingdom()) && data.records().recognitions.values().stream()
                .anyMatch(r -> r.active() && kingdoms.getSettlement(r.building().settlement()).map(v -> v.id().equals(source)).orElse(false)))
            event.reject(Keyed.text("politics.mcakingdoms.refusal.reassign.charters","This settlement has active institution charters; suspend them before changing its kingdom."));
    }
    @SubscribeEvent public void merged(SettlementMergedEvent event) {
        if (event.server() != server || !data.writable()) return;
        PoliticalSavedData.Records s = data.transaction(); boolean changed = false;
        for (Government g : List.copyOf(s.governments.values())) if (g.capital().equals(event.source().id())) {
            s.governments.put(g.kingdom(), government(g, event.target().id(), g.offices(), g.mandates(), g.successor(), s.revision + 1)); changed = true;
        }
        if (changed) { s.revision++; data.commit(s); }
    }
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void deathObserved(LivingDeathEvent event) {
        if (event.getEntity().getServer() == server && pendingDeaths.size() < 8192
                && data.records().governments.values().stream().flatMap(g -> g.offices().values().stream())
                .anyMatch(o -> o.holder().kind() == Kind.NPC && o.holder().id().equals(event.getEntity().getUUID()))) pendingDeaths.addLast(event);
    }
    @SubscribeEvent public void tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (server.getTickCount() % 20 == 0) maintainDeadlines();
        if (dev.otectus.mcakingdoms.compat.capitals.CapitalsBridge.installed()) dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop.get(server).tick();
        for (int i = 0; i < 32 && !pendingDeaths.isEmpty(); i++) {
            LivingDeathEvent death = pendingDeaths.removeFirst();
            if (!death.isCanceled() && death.getEntity().isDeadOrDying() && !confirmedDeath(death)) {
                pendingDeaths.addFirst(death); break;
            }
        }
    }
    /**
     * Commits deadline transitions (F05). Each domain (agreements, petitions, elections, regencies) independently takes
     * its due records, oldest deadline first with the record id breaking ties, and transitions at most
     * {@link #MAINTENANCE_BUDGET} of them per pass, so one domain's size never delays another's. A record that came due
     * transitions within ceil(d / 8) passes, d being the records of its domain due no later than it: a record due later
     * always queues behind it, so nothing starves. There is no shared cursor to drift or overflow, and growing or
     * shrinking sets need no bookkeeping. Facts and notices commit once, in the same durable transaction, and are
     * published only after it. Receipt pruning keeps its own bounded batch.
     */
    private void maintainDeadlines() {
        if (!data.writable()) return;
        var current = data.records();
        long now = now();
        PoliticalSavedData.Records next = null;
        List<Notice> notices = new ArrayList<>();
        for (Agreement a : due(current.agreements.values(), value -> (value.state() == AgreementState.PROPOSED || value.state() == AgreementState.ACTIVE)
                && now >= value.expiresAt(), Agreement::expiresAt, Agreement::id)) {
            if (next == null) { next = data.transaction(); next.revision++; }
            UUID id = a.id();
            next.agreements.put(id, new Agreement(a.id(), a.proposer(), a.recipient(), a.definitionId(), a.terms(), a.explanation(), a.termsHash(),
                    a.signatures(), AgreementState.EXPIRED, a.proposedAt(), a.expiresAt(), next.revision));
            UUID source = stable("agreement-expired", id, a.expiresAt());
            Notice notice = new Notice(source, a.proposer(), Action.PROPOSE, new UUID(0, 0), id.toString(), now);
            append(next, notice, new PoliticalFact(source, source, next.revision, now, a.proposer(), PoliticalFact.Type.AGREEMENT_EXPIRED,
                    null, id.toString(), Set.of(a.proposer(), a.recipient()), new LinkedHashSet<>(a.signatures().values()),
                    a.state() == AgreementState.ACTIVE ? PoliticalFact.Visibility.PUBLIC : PoliticalFact.Visibility.PARTIES,
                    PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.AGREEMENT_EXPIRED)));
            notices.add(notice);
        }
        for (Petition p : due(current.petitions.values(), value -> open(value) && now >= value.expiresAt(), Petition::expiresAt, Petition::id)) {
            if (next == null) { next = data.transaction(); next.revision++; }
            UUID id = p.id();
            next.petitions.put(id, decision(p, PetitionState.EXPIRED, Keyed.text("politics.mcakingdoms.decision.deadline","The submission deadline passed."), null, next.revision));
            UUID source = stable("petition-expired", id, p.expiresAt());
            Notice notice = new Notice(source, p.kingdom(), Action.PETITION, new UUID(0, 0), id.toString(), now);
            Set<UUID> affected = new LinkedHashSet<>(); affected.add(p.requester());
            if (p.nominee() != null) affected.add(p.nominee().id());
            append(next, notice, new PoliticalFact(source, source, next.revision, now, p.kingdom(), PoliticalFact.Type.PETITION_EXPIRED,
                    null, id.toString(), Set.of(p.kingdom()), affected, PoliticalFact.Visibility.PRIVATE,
                    PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.PETITION_EXPIRED)));
            notices.add(notice);
        }
        for (Election election : due(current.elections.values(), value -> electionPhaseDue(value, now) != null, GovernmentService::electionTransitionAt, Election::id)) {
            ElectionState phase = electionPhaseDue(election, now);
            if (next == null) { next = data.transaction(); next.revision++; }
            UUID id = election.id();
            next.elections.put(id, new Election(election.id(), election.kingdom(), election.candidates(), election.electorate(), election.ballots(), phase,
                    election.openedAt(), election.deadline(), election.graceUntil(), next.revision, null));
            UUID source = stable("election-" + phase.name().toLowerCase(Locale.ROOT), id, phase == ElectionState.GRACE ? election.deadline() : election.graceUntil());
            Notice notice = new Notice(source, election.kingdom(), Action.CLOSE_ELECTION, new UUID(0, 0), id.toString(), now);
            PoliticalFact.Type type = phase == ElectionState.EXPIRED ? PoliticalFact.Type.ELECTION_EXPIRED : PoliticalFact.Type.ELECTION_GRACE;
            append(next, notice, new PoliticalFact(source, source, next.revision, now, election.kingdom(), type, null, id.toString(), Set.of(election.kingdom()),
                    election.candidates(), PoliticalFact.Visibility.PUBLIC, PoliticalFact.Correction.CURRENT, null,
                    phase == ElectionState.EXPIRED ? "Election expired without a unique authorized result" : "Election entered neutral grace"));
            notices.add(notice);
        }
        for (Regency regency : due(current.regencies.values(), value -> value.active() && now >= value.expiresAt(), Regency::expiresAt, Regency::id)) {
            if (next == null) { next = data.transaction(); next.revision++; }
            next.regencies.put(regency.kingdom(), new Regency(regency.id(), regency.kingdom(), regency.regent(), regency.permissions(), regency.preservedSuccessor(),
                    regency.appointedAt(), regency.expiresAt(), false, next.revision));
            UUID source = stable("regency-expired", regency.id(), regency.expiresAt());
            Notice notice = new Notice(source, regency.kingdom(), Action.END_REGENCY, new UUID(0, 0), regency.id().toString(), now);
            append(next, notice, new PoliticalFact(source, source, next.revision, now, regency.kingdom(), PoliticalFact.Type.REGENCY_EXPIRED, null,
                    regency.id().toString(), Set.of(regency.kingdom()), Set.of(regency.regent().id()), PoliticalFact.Visibility.PUBLIC,
                    PoliticalFact.Correction.CURRENT, null, "Regency authority expired; named successor preserved"));
            notices.add(notice);
        }
        var expiredReceipts = expiredReceipts(current, now);
        if (!expiredReceipts.isEmpty()) {
            if (next == null) { next = data.transaction(); next.revision++; }
            expiredReceipts.forEach(next.receipts::remove);
        }
        if (next != null && data.commitDurably(server, next)) notices.forEach(this::publish);
    }

    /** The phase an election moves to now, or null: OPEN past its deadline enters GRACE; GRACE past its grace expires. */
    static ElectionState electionPhaseDue(Election election, long now) {
        if (election.state() == ElectionState.OPEN && now >= election.deadline()) return ElectionState.GRACE;
        if (election.state() == ElectionState.GRACE && now >= election.graceUntil()) return ElectionState.EXPIRED;
        return null;
    }

    /** When an election's next transition falls due: its deadline while open, its grace end while in grace. */
    static long electionTransitionAt(Election election) {
        return election.state() == ElectionState.GRACE ? election.graceUntil() : election.deadline();
    }

    /**
     * One domain's work for this pass: the due records, oldest deadline first (ties by id), at most
     * {@link #MAINTENANCE_BUDGET}. Pure, so the fairness bound is tested without a server.
     */
    static <T> List<T> due(Collection<T> records, java.util.function.Predicate<T> isDue,
                           java.util.function.ToLongFunction<T> deadline, java.util.function.Function<T, UUID> id) {
        return records.stream().filter(isDue)
                .sorted(Comparator.comparingLong(deadline).thenComparing(id))
                .limit(MAINTENANCE_BUDGET).toList();
    }
    /** Oldest receipts past retention, bounded per pass. Their revision is behind, so a resend is refused, never re-run. */
    static List<UUID> expiredReceipts(PoliticalSavedData.Records records, long now) {
        return records.receipts.entrySet().stream().filter(e -> now - e.getValue().gameTime() > RECEIPT_RETENTION)
                .sorted(Comparator.comparingLong((Map.Entry<UUID, Receipt> e) -> e.getValue().gameTime()).thenComparing(e -> e.getKey()))
                .limit(RECEIPT_PRUNE_BATCH).map(Map.Entry::getKey).toList();
    }
    private boolean confirmedDeath(LivingDeathEvent event) {
        // Player respawn is not political death. Unload, absence and restart are not death signals.
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player || event.getEntity().getServer() != server || !data.writable()) return true;
        UUID id = event.getEntity().getUUID(); PoliticalSavedData.Records s = data.transaction(); boolean changed = false;
        List<Notice> notices = new ArrayList<>();
        for (Government g : List.copyOf(s.governments.values())) {
            Map<String, Office> offices = new LinkedHashMap<>(g.offices());
            // A crown MCA Capitals governs is Capitals' to succeed: its kept leader record is history and is not vacated here.
            boolean crownElsewhere = !sovereignty.apply(g.kingdom()).native_();
            boolean removed = offices.entrySet().removeIf(e -> e.getValue().holder().kind() == Kind.NPC && e.getValue().holder().id().equals(id) && !(crownElsewhere && e.getKey().equals(LEADER)));
            if (removed) {
                if (!changed) s.revision++;
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, offices.containsKey(LEADER) ? g.mandates() : Map.of(), g.successor(), s.revision));
                UUID source = stable("npc-office-vacated", id, now(), g.kingdom());
                Notice notice = new Notice(source, g.kingdom(), Action.REMOVE_OFFICE, id, id.toString(), now());
                append(s, notice, new PoliticalFact(source, source, s.revision, now(), g.kingdom(), PoliticalFact.Type.NPC_OFFICE_VACATED,
                        id, id.toString(), Set.of(g.kingdom()), Set.of(), PoliticalFact.Visibility.PUBLIC,
                        PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.NPC_OFFICE_VACATED)));
                notices.add(notice); changed = true;
            }
        }
        if (!changed) return true;
        if (!data.commitDurably(server, s)) return false;
        notices.forEach(this::publish); return true;
    }

    private static UUID stable(String kind, UUID record, long sequence, String... scope) {
        return UUID.nameUUIDFromBytes((kind + "|" + record + "|" + sequence + "|" + String.join("|", scope)).getBytes(StandardCharsets.UTF_8));
    }

    private void publish(Notice notice) {
        try { net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new PoliticalCommittedEvent(server, notice)); }
        catch (RuntimeException listenerFailure) {
            com.mojang.logging.LogUtils.getLogger().error("Political state committed but a post-commit listener failed; no provider delivery is assumed", listenerFailure);
        }
    }
}
