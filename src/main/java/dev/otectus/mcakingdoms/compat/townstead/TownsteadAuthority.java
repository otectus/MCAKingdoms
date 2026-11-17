package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState;
import dev.otectus.mcakingdoms.politics.Sovereignty;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one server-owned answer to "who governs this, here?" (spec 4.2), per settlement and domain: owner, scope, state,
 * evidence, revision, reason and the MCA: Kingdoms actions allowed. MCA Capitals' existing crown policy is applied first
 * and unchanged; MCA: Kingdoms governs local government only for an exactly mapped settlement of a kingdom it governs;
 * Townstead governs its own unmapped councils and associations. Populated records on both sides are a reported conflict,
 * never resolved automatically: nothing is merged, chosen or erased, and existing worlds keep acting as before. An
 * operator may adopt one side for a settlement (MCA: Kingdoms keeps acting; the conflict is acknowledged) or release it to
 * Townstead (MCA: Kingdoms then refuses its own settlement-scoped appointments and recognitions there, so there is never
 * a second equivalent officeholder). A release survives Townstead's removal: the local government is then reported
 * unavailable, and MCA: Kingdoms does not take it back on its own. Townstead 0.8.0 accepts no external government, so its
 * own council always keeps running inside Townstead; that is reported as "political delegation unavailable".
 */
public final class TownsteadAuthority {
    public enum Domain { CROWN, LOCAL_GOVERNMENT, ORGANIZATION, NAMING, HERALDRY }

    public enum Owner { MCA_KINGDOMS, MCA_CAPITALS, TOWNSTEAD, MCA, NONE }

    /** Separate from Townstead's own faction status words. */
    public enum State { NATIVE, EXTERNAL_ACTIVE, EXTERNAL_UNAVAILABLE, CONFLICT, UNMAPPED, DISABLED, RECOVERY_REQUIRED }

    /** MCA: Kingdoms actions an answer can allow in its scope. */
    public static final Set<String> LOCAL_ACTIONS = Set.of("appoint_local", "remove_local", "recognize", "revalidate", "suspend_recognition", "petition");
    public static final Set<String> CROWN_ACTIONS = Set.of("found", "seat", "appoint_leader", "succeed", "abdicate", "elect", "regency");

    public record Resolution(Domain domain, UUID scope, Owner owner, State state, List<Component> evidence, Component reason, long revision,
                             Set<String> allowedActions) {
        public Resolution {
            evidence = List.copyOf(evidence);
            allowedActions = Set.copyOf(allowedActions);
        }
    }

    /** Townstead's political context for one settlement, read only. */
    record Native(Optional<TownsteadBackend.Faction> faction, Optional<TownsteadBackend.Governance> governance, TownsteadCapabilityState politics) {
        boolean readable() {
            return politics.status().usable();
        }

        boolean populated() {
            return governance.map(g -> g.populatedOffices() > 0).orElse(false);
        }

        String factionId() {
            return faction.map(f -> f.id().toString()).orElse("");
        }

        int fingerprint() {
            return governance.map(TownsteadBackend.Governance::fingerprint).orElse(0);
        }
    }

    private static final long TTL = 100;
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private final Map<UUID, Map<Domain, Resolution>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Map<Domain, Resolution>> eldest) { return size() > 256; }
    };
    private final Map<UUID, Long> cachedAt = new LinkedHashMap<>();
    private final Map<State, Integer> seen = new EnumMap<>(State.class);

    TownsteadAuthority(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    public static Optional<TownsteadAuthority> get(MinecraftServer server) {
        return TownsteadRuntime.session(server).map(TownsteadRuntime.Session::authority);
    }

    /**
     * The refusal for an MCA: Kingdoms settlement-scoped appointment or recognition in a settlement whose local government
     * an operator released to Townstead; empty everywhere else (a conflict alone refuses nothing).
     */
    public static Optional<IllegalArgumentException> localRefusal(MinecraftServer server, UUID settlement) {
        return get(server).flatMap(authority -> authority.refusal(settlement));
    }

    Optional<IllegalArgumentException> refusal(UUID settlement) {
        if (TownsteadSavedData.get(server).decision(kingdoms.resolveSettlementId(settlement)).isEmpty()) return Optional.empty();
        Resolution local = resolve(settlement, Domain.LOCAL_GOVERNMENT);
        if (local.owner() != Owner.TOWNSTEAD || local.allowedActions().contains("appoint_local")) return Optional.empty();
        return Optional.of(TownsteadText.refuse("authority.refused_local",
                "This settlement's local government was released to Townstead (%s); use its Charter for local offices and institutions.",
                local.evidence().isEmpty() ? Component.literal("—") : local.evidence().get(0)));
    }

    public List<Resolution> resolve(UUID settlement) {
        List<Resolution> out = new ArrayList<>();
        for (Domain domain : Domain.values()) out.add(resolve(settlement, domain));
        return out;
    }

    public Resolution resolve(UUID settlementId, Domain domain) {
        Optional<SettlementView> found = kingdoms.getSettlement(settlementId);
        if (found.isEmpty()) return new Resolution(domain, settlementId, Owner.NONE, State.UNMAPPED, List.of(),
                TownsteadText.tr("authority.no_settlement", "MCA: Kingdoms has no such settlement."), 0, Set.of());
        SettlementView settlement = found.get();
        long now = server.getTickCount();
        Long at = cachedAt.get(settlement.id());
        Map<Domain, Resolution> cached = cache.get(settlement.id());
        if (at != null && cached != null && now - at >= 0 && now - at < TTL && cached.containsKey(domain)) return cached.get(domain);
        Resolution resolution = switch (domain) {
            case CROWN -> crown(settlement);
            case LOCAL_GOVERNMENT -> local(settlement, readNative(settlement));
            case ORGANIZATION -> organization(settlement);
            case NAMING -> naming(settlement);
            case HERALDRY -> heraldry(settlement, readNative(settlement));
        };
        cache.computeIfAbsent(settlement.id(), ignored -> new EnumMap<>(Domain.class)).put(domain, resolution);
        cachedAt.put(settlement.id(), at != null && cached != null && now - at < TTL ? at : now);
        if (domain == Domain.LOCAL_GOVERNMENT) seen.merge(resolution.state(), 1, Integer::sum);
        return resolution;
    }

    Native readNative(SettlementView settlement) {
        TownsteadCapabilityState politics = townstead.states().state(TownsteadCapability.READ_POLITICS);
        if (!politics.status().usable()) return new Native(Optional.empty(), Optional.empty(), politics);
        var dimension = settlement.dimension().location();
        for (int villageId : townstead.villageIds(settlement, dimension)) {
            var faction = townstead.backendImpl().faction(server, dimension, villageId);
            if (faction.isPresent()) return new Native(faction, townstead.backendImpl().governance(server, faction.get().id()), politics);
        }
        return new Native(Optional.empty(), Optional.empty(), politics);
    }

    private Optional<Politics.Government> government(SettlementView settlement) {
        try {
            return KingdomsPoliticsApi.get(server).government(settlement.kingdomId().toString()).filter(g -> g.state() == Politics.State.ACTIVE);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private long revision() {
        return TownsteadSavedData.get(server).revision() * 31 + townstead.revision();
    }

    private Resolution crown(SettlementView settlement) {
        String kingdom = settlement.kingdomId().toString();
        Sovereignty sovereignty;
        try {
            sovereignty = dev.otectus.mcakingdoms.compat.capitals.CapitalsInterop.get(server).sovereignty(kingdom);
        } catch (RuntimeException unavailable) {
            sovereignty = Sovereignty.NATIVE;
        }
        List<Component> evidence = List.of(Component.translatable(sovereignty.statusKey()));
        boolean governed = government(settlement).isPresent();
        return switch (sovereignty.mode()) {
            case NATIVE -> governed
                    ? new Resolution(Domain.CROWN, settlement.id(), Owner.MCA_KINGDOMS, State.NATIVE, evidence,
                    TownsteadText.tr("authority.crown_native", "MCA: Kingdoms governs this kingdom's crown."), revision(), CROWN_ACTIONS)
                    : new Resolution(Domain.CROWN, settlement.id(), Owner.NONE, State.UNMAPPED, evidence,
                    TownsteadText.tr("authority.crown_none", "This kingdom has no government yet; an operator or a resident founder can constitute one."), revision(), Set.of("found"));
            case CAPITALS -> {
                boolean live = "politics.mcakingdoms.sovereignty.capitals_managed".equals(sovereignty.statusKey());
                yield new Resolution(Domain.CROWN, settlement.id(), Owner.MCA_CAPITALS, live ? State.EXTERNAL_ACTIVE : State.EXTERNAL_UNAVAILABLE, evidence,
                        TownsteadText.tr("authority.crown_capitals", "MCA Capitals governs this kingdom's crown; MCA: Kingdoms keeps its civic offices, agreements and guilds."), revision(), Set.of());
            }
            case UNMAPPED -> new Resolution(Domain.CROWN, settlement.id(), Owner.MCA_CAPITALS, State.CONFLICT, evidence,
                    TownsteadText.tr("authority.crown_unmapped", "An MCA Capitals capital in this kingdom waits for an operator (/kingdoms capitals report)."), revision(), Set.of());
            case UNSUPPORTED -> new Resolution(Domain.CROWN, settlement.id(), Owner.MCA_CAPITALS, State.EXTERNAL_UNAVAILABLE, evidence,
                    TownsteadText.tr("authority.crown_unsupported", "The installed MCA Capitals cannot be read, so crown actions are read-only."), revision(), Set.of());
        };
    }

    Resolution local(SettlementView settlement, Native nat) {
        var decision = TownsteadSavedData.get(server).decision(settlement.id());
        Optional<Politics.Government> government = government(settlement);
        List<Component> evidence = new ArrayList<>();
        nat.faction().ifPresent(f -> evidence.add(TownsteadText.tr("authority.evidence_faction", "Townstead faction %s (%s), %s", f.name().isBlank() ? f.id().toString() : f.name(),
                f.kind().toString(), f.status())));
        nat.governance().ifPresent(g -> evidence.add(TownsteadText.tr("authority.evidence_governance", "Townstead native offices: %s of %s filled, %s holder(s)", g.populatedOffices(), g.offices(), g.holders())));
        nat.governance().flatMap(TownsteadBackend.Governance::provider).ifPresent(p -> evidence.add(TownsteadText.tr("authority.evidence_provider",
                "Townstead lists %s as governing this council in its place.", p)));
        government.ifPresent(g -> evidence.add(TownsteadText.tr("authority.evidence_kingdoms", "MCA: Kingdoms government of %s (%s offices)", settlement.kingdomId().toString(), g.offices().size())));
        Optional<Component> restored = restored(settlement);
        restored.ifPresent(evidence::add);
        var verdict = decideLocal(new LocalInputs(TownsteadIntegrationConfig.POLITICAL_OWNERSHIP.get(), decision.map(TownsteadSavedData.Decision::owner),
                decision.map(TownsteadSavedData.Decision::faction).orElse(""), government.isPresent(), nat.readable(), nat.populated(),
                nat.faction().isPresent(), nat.factionId(), restored.isPresent()));
        String was = decision.map(TownsteadSavedData.Decision::faction).orElse("");
        Component politics = Component.literal(nat.politics().reason());
        Component reason = switch (verdict.reason()) {
            case "disabled" -> TownsteadText.tr("authority.disabled", "Ownership resolution is switched off (politicalOwnership); MCA: Kingdoms acts on its own records as before.");
            case "released_unavailable" -> TownsteadText.tr("authority.released_unavailable", "An operator released this local government to Townstead, which cannot be read now (%s). MCA: Kingdoms does not take it back on its own; an operator can clear the decision.", politics);
            case "released_changed" -> TownsteadText.tr("authority.released_changed", "Released to Townstead, but Townstead's faction here changed since (was %s). An operator should review the decision.", was);
            case "released" -> TownsteadText.tr("authority.released", "An operator released this local government to Townstead; its Charter governs local offices here.");
            case "adopted_changed" -> TownsteadText.tr("authority.adopted_changed", "Adopted for MCA: Kingdoms, but Townstead's faction here changed since (was %s). MCA: Kingdoms keeps acting; an operator should review the decision.", was);
            case "adopted" -> TownsteadText.tr("authority.adopted", "An operator adopted MCA: Kingdoms as this local government. Townstead's own council keeps running inside Townstead: political delegation is unavailable.");
            case "conflict" -> TownsteadText.tr("authority.conflict", "Both MCA: Kingdoms and a Townstead council keep a local government here. Nothing was merged or chosen; an operator can adopt one (/kingdoms compat townstead authority).");
            case "native" -> TownsteadText.tr("authority.native", "MCA: Kingdoms governs this settlement's civic offices.");
            case "native_unread" -> TownsteadText.tr("authority.native_unread", "MCA: Kingdoms governs this settlement's civic offices; Townstead's politics is not read (%s).", politics);
            case "townstead" -> TownsteadText.tr("authority.townstead", "Townstead's council governs this village; this settlement's kingdom has no MCA: Kingdoms government.");
            default -> TownsteadText.tr("authority.unmapped", "No local government is recorded for this settlement.");
        };
        return new Resolution(Domain.LOCAL_GOVERNMENT, settlement.id(), verdict.owner(), verdict.state(), evidence, reason, revision(), verdict.allowed());
    }

    /** What the local-government answer depends on, all of it already read. */
    record LocalInputs(boolean resolutionOn, Optional<TownsteadSavedData.Owner> decision, String decidedFaction, boolean governed,
                       boolean readable, boolean populated, boolean faction, String factionId, boolean restored) {
    }

    /** The answer: owner, state, a reason id and the MCA: Kingdoms actions allowed in the scope. */
    record LocalVerdict(Owner owner, State state, String reason, Set<String> allowed) {
    }

    /**
     * The local-government rules (spec 4.2) as a pure function: a decision outranks the evidence; a release survives the
     * provider's absence as unavailable; a changed faction or a restored backup asks an operator; populated records on
     * both sides are a reported conflict in which MCA: Kingdoms keeps acting; Townstead governs a council no MCA:
     * Kingdoms government covers.
     */
    static LocalVerdict decideLocal(LocalInputs in) {
        if (!in.resolutionOn()) return new LocalVerdict(in.governed() ? Owner.MCA_KINGDOMS : Owner.NONE, State.DISABLED, "disabled", LOCAL_ACTIONS);
        if (in.decision().isPresent()) {
            boolean changed = in.readable() && !in.decidedFaction().isEmpty() && !in.decidedFaction().equals(in.factionId());
            if (in.decision().get() == TownsteadSavedData.Owner.TOWNSTEAD) {
                if (!in.readable()) return new LocalVerdict(Owner.TOWNSTEAD, State.EXTERNAL_UNAVAILABLE, "released_unavailable", Set.of());
                if (changed || in.restored()) return new LocalVerdict(Owner.TOWNSTEAD, State.RECOVERY_REQUIRED, "released_changed", Set.of());
                return new LocalVerdict(Owner.TOWNSTEAD, State.EXTERNAL_ACTIVE, "released", Set.of());
            }
            if (changed || in.restored()) return new LocalVerdict(Owner.MCA_KINGDOMS, State.RECOVERY_REQUIRED, "adopted_changed", LOCAL_ACTIONS);
            return new LocalVerdict(Owner.MCA_KINGDOMS, State.NATIVE, "adopted", LOCAL_ACTIONS);
        }
        if (in.governed() && in.readable() && in.populated()) return new LocalVerdict(Owner.MCA_KINGDOMS, State.CONFLICT, "conflict", LOCAL_ACTIONS);
        if (in.governed()) return new LocalVerdict(Owner.MCA_KINGDOMS, State.NATIVE, in.readable() ? "native" : "native_unread", LOCAL_ACTIONS);
        if (in.readable() && in.faction()) return new LocalVerdict(Owner.TOWNSTEAD, State.EXTERNAL_ACTIVE, "townstead", Set.of("petition"));
        return new LocalVerdict(Owner.NONE, State.UNMAPPED, "unmapped", Set.of("petition"));
    }

    private Resolution organization(SettlementView settlement) {
        return new Resolution(Domain.ORGANIZATION, settlement.id(), Owner.MCA_KINGDOMS, State.NATIVE, List.of(),
                TownsteadText.tr("authority.organization", "MCA: Kingdoms governs its guild chapters; Townstead's own associations are Townstead's, and neither becomes the other."), revision(), Set.of("guild_services"));
    }

    private Resolution naming(SettlementView settlement) {
        List<Component> evidence = new ArrayList<>();
        boolean differs = false;
        for (var village : townstead.villages(settlement.id())) {
            if (!village.name().isBlank()) {
                evidence.add(TownsteadText.tr("authority.evidence_village_name", "MCA's village name: %s", village.name()));
                differs |= !village.name().equals(settlement.displayName());
            }
        }
        village(settlement).flatMap(v -> v.culture()).ifPresent(culture -> evidence.add(TownsteadText.tr("authority.evidence_culture", "Townstead culture: %s", culture.toString())));
        return new Resolution(Domain.NAMING, settlement.id(), Owner.MCA_KINGDOMS, State.NATIVE, evidence, differs
                ? TownsteadText.tr("authority.naming_differs", "MCA: Kingdoms keeps the civic name; MCA's village name differs and is not synchronized (no supported rename transaction exists).")
                : TownsteadText.tr("authority.naming", "MCA: Kingdoms keeps the civic name; MCA keeps the village name."), revision(), Set.of("rename"));
    }

    private Optional<dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView> village(SettlementView settlement) {
        return townstead.villages(settlement.id()).stream().findFirst();
    }

    private Resolution heraldry(SettlementView settlement, Native nat) {
        List<Component> evidence = new ArrayList<>();
        nat.faction().flatMap(TownsteadBackend.Faction::emblem).ifPresent(emblem -> evidence.add(TownsteadText.tr("authority.evidence_emblem", "Townstead arms proclaimed: %s", emblem.toString())));
        boolean fallback = TownsteadIntegrationConfig.HERALDRY_FALLBACK.get() && TownsteadMappings.livery(settlement.kingdomId()).isPresent();
        return new Resolution(Domain.HERALDRY, settlement.id(), Owner.MCA_KINGDOMS, State.NATIVE, evidence, fallback
                ? TownsteadText.tr("authority.heraldry_fallback", "MCA: Kingdoms shows the kingdom's arms; plain shields of its guards take the kingdom's design where no local or faction arms are proclaimed.")
                : TownsteadText.tr("authority.heraldry", "MCA: Kingdoms shows the kingdom's arms in its own screens; Townstead keeps local and faction arms on its guards."), revision(), Set.of());
    }

    // ---- baselines and restored backups (spec 6.3) ----

    /** Records the first sight of a settlement with Townstead once, as a baseline; never an award. */
    void observe(UUID settlement) {
        var data = TownsteadSavedData.get(server);
        if (!data.writable() || data.baseline(settlement).isPresent()) return;
        townstead.calendar().ifPresent(calendar -> data.baseline(new TownsteadSavedData.Baseline(settlement, townstead.backendImpl().info().version(),
                townstead.backendImpl().info().revision(), calendar.worldDay(), server.overworld().getGameTime())));
    }

    /** Townstead's calendar day went backwards past the baseline: its data looks restored from an older backup. */
    Optional<Component> restored(SettlementView settlement) {
        var baseline = TownsteadSavedData.get(server).baseline(settlement.id());
        if (baseline.isEmpty()) return Optional.empty();
        return townstead.calendar().filter(calendar -> calendar.worldDay() + 1 < baseline.get().townsteadDay())
                .map(calendar -> TownsteadText.tr("authority.evidence_restored", "Townstead's calendar reads day %s, before day %s when MCA: Kingdoms first saw this settlement: its data may be restored from an older backup.",
                        calendar.worldDay(), baseline.get().townsteadDay()));
    }

    // ---- operator workflow ----

    /** What adopting or releasing would affect, without changing anything. */
    public List<Component> preview(UUID settlementId, TownsteadSavedData.Owner owner) {
        List<Component> out = new ArrayList<>();
        Optional<SettlementView> found = kingdoms.getSettlement(settlementId);
        if (found.isEmpty()) return List.of(TownsteadText.tr("authority.no_settlement", "MCA: Kingdoms has no such settlement."));
        SettlementView settlement = found.get();
        Native nat = readNative(settlement);
        Resolution now = resolve(settlement.id(), Domain.LOCAL_GOVERNMENT);
        out.add(TownsteadText.tr("authority.preview_now", "Now: %s, owner %s. %s", now.state().name(), now.owner().name(), now.reason()));
        government(settlement).ifPresent(g -> {
            long scoped = g.offices().values().stream().filter(o -> settlement.id().equals(o.settlement() == null ? null : kingdoms.resolveSettlementId(o.settlement()))).count();
            out.add(TownsteadText.tr("authority.preview_kingdoms", "MCA: Kingdoms: %s settlement office(s) here; kingdom-wide offices are not affected.", scoped));
        });
        nat.faction().ifPresent(f -> out.add(TownsteadText.tr("authority.preview_townstead", "Townstead: faction %s with %s member(s); its records are never changed from MCA: Kingdoms.",
                f.name().isBlank() ? f.id().toString() : f.name(), f.members())));
        out.add(owner == TownsteadSavedData.Owner.KINGDOMS
                ? TownsteadText.tr("authority.preview_adopt", "Adopting: MCA: Kingdoms keeps acting here and the conflict is recorded as decided. Townstead's council keeps running inside Townstead (political delegation is unavailable).")
                : TownsteadText.tr("authority.preview_release", "Releasing: MCA: Kingdoms refuses new settlement offices and institution recognitions here; existing records stay readable and nothing is deleted. It stays released if Townstead is removed."));
        return out;
    }

    public Component decide(UUID settlementId, TownsteadSavedData.Owner owner, UUID operator, String note) {
        Optional<SettlementView> found = kingdoms.getSettlement(settlementId);
        if (found.isEmpty()) throw TownsteadText.refuse("authority.no_settlement", "MCA: Kingdoms has no such settlement.");
        SettlementView settlement = found.get();
        Native nat = readNative(settlement);
        if (owner == TownsteadSavedData.Owner.TOWNSTEAD && (!nat.readable() || nat.faction().isEmpty()))
            throw TownsteadText.refuse("authority.release_needs_faction", "Townstead has no readable council for this settlement to release it to.");
        var decision = new TownsteadSavedData.Decision(settlement.id(), owner, nat.factionId(), nat.fingerprint(), townstead.backendImpl().info().version(),
                operator, server.overworld().getGameTime(), note == null ? "" : note.length() > 160 ? note.substring(0, 160) : note);
        if (!TownsteadSavedData.get(server).decide(server, settlement.id(), decision))
            throw TownsteadText.refuse("authority.save_failed", "The decision could not be saved; nothing changed.");
        invalidate(settlement.id());
        return owner == TownsteadSavedData.Owner.KINGDOMS
                ? TownsteadText.tr("authority.adopted_done", "Recorded: MCA: Kingdoms governs %s's local government.", settlement.displayName())
                : TownsteadText.tr("authority.released_done", "Recorded: %s's local government is released to Townstead.", settlement.displayName());
    }

    public Component clearDecision(UUID settlementId) {
        Optional<SettlementView> found = kingdoms.getSettlement(settlementId);
        if (found.isEmpty()) throw TownsteadText.refuse("authority.no_settlement", "MCA: Kingdoms has no such settlement.");
        var data = TownsteadSavedData.get(server);
        if (data.decision(found.get().id()).isEmpty()) return TownsteadText.tr("authority.no_decision", "No decision is recorded for %s.", found.get().displayName());
        if (!data.decide(server, found.get().id(), null)) throw TownsteadText.refuse("authority.save_failed", "The decision could not be saved; nothing changed.");
        invalidate(found.get().id());
        return TownsteadText.tr("authority.cleared", "Cleared the decision for %s; the evidence decides again.", found.get().displayName());
    }

    /** Accepts Townstead's current data as the baseline after an operator checked a restored backup. */
    public Component acknowledge(UUID settlementId) {
        Optional<SettlementView> found = kingdoms.getSettlement(settlementId);
        if (found.isEmpty()) throw TownsteadText.refuse("authority.no_settlement", "MCA: Kingdoms has no such settlement.");
        var data = TownsteadSavedData.get(server);
        var calendar = townstead.calendar();
        if (calendar.isEmpty() || !data.writable()) throw TownsteadText.refuse("authority.acknowledge_unavailable", "Townstead's calendar cannot be read, so there is no baseline to record.");
        data.baseline(new TownsteadSavedData.Baseline(found.get().id(), townstead.backendImpl().info().version(), townstead.backendImpl().info().revision(),
                calendar.get().worldDay(), server.overworld().getGameTime()));
        invalidate(found.get().id());
        return TownsteadText.tr("authority.acknowledged", "Recorded Townstead's current data (day %s) as the baseline for %s.", calendar.get().worldDay(), found.get().displayName());
    }

    void invalidate(UUID settlement) {
        if (settlement == null) clear();
        else {
            cache.remove(settlement);
            cachedAt.remove(settlement);
        }
    }

    void clear() {
        cache.clear();
        cachedAt.clear();
    }

    Map<State, Integer> seen() {
        return Map.copyOf(seen);
    }

    /** Settlements in the level whose local-government answer is a conflict, among those resolved recently. */
    int conflicts() {
        int n = 0;
        for (var answers : cache.values()) {
            Resolution local = answers.get(Domain.LOCAL_GOVERNMENT);
            if (local != null && local.state() == State.CONFLICT) n++;
        }
        return n;
    }

    static boolean levelLoaded(MinecraftServer server, SettlementView settlement) {
        ServerLevel level = server.getLevel(settlement.dimension());
        return level != null && level.hasChunkAt(settlement.anchor());
    }
}
