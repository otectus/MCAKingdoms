package dev.otectus.mcakingdoms.compat.townstead.v1;

import com.aetherianartificer.townstead.api.v1.CalendarApi;
import com.aetherianartificer.townstead.api.v1.ChroniclesApi;
import com.aetherianartificer.townstead.api.v1.EventsApi;
import com.aetherianartificer.townstead.api.v1.HangoutsApi;
import com.aetherianartificer.townstead.api.v1.PoliticsApi;
import com.aetherianartificer.townstead.api.v1.ProfessionsApi;
import com.aetherianartificer.townstead.api.v1.RegistriesApi;
import com.aetherianartificer.townstead.api.v1.SettingsApi;
import com.aetherianartificer.townstead.api.v1.SocialApi;
import com.aetherianartificer.townstead.api.v1.TownsteadApiV1;
import com.aetherianartificer.townstead.api.v1.VillagersApi;
import com.aetherianartificer.townstead.api.v1.VillagesApi;
import com.aetherianartificer.townstead.api.v1.WorkApi;
import com.aetherianartificer.townstead.api.v1.event.Subscription;
import com.aetherianartificer.townstead.api.v1.model.BuildingSnapshot;
import com.aetherianartificer.townstead.api.v1.model.CalendarSnapshot;
import com.aetherianartificer.townstead.api.v1.model.CareerSnapshot;
import com.aetherianartificer.townstead.api.v1.model.Cursor;
import com.aetherianartificer.townstead.api.v1.model.FactionSnapshot;
import com.aetherianartificer.townstead.api.v1.model.GeneSnapshot;
import com.aetherianartificer.townstead.api.v1.model.GovernanceSnapshot;
import com.aetherianartificer.townstead.api.v1.model.HangoutVisitSnapshot;
import com.aetherianartificer.townstead.api.v1.model.NeedBand;
import com.aetherianartificer.townstead.api.v1.model.NeedLevel;
import com.aetherianartificer.townstead.api.v1.model.NeedsSnapshot;
import com.aetherianartificer.townstead.api.v1.model.OrderSnapshot;
import com.aetherianartificer.townstead.api.v1.model.Page;
import com.aetherianartificer.townstead.api.v1.model.ProfessionProgressSnapshot;
import com.aetherianartificer.townstead.api.v1.model.ProfessionSnapshot;
import com.aetherianartificer.townstead.api.v1.model.ProgressionTrackSnapshot;
import com.aetherianartificer.townstead.api.v1.model.ReactionCause;
import com.aetherianartificer.townstead.api.v1.model.RootSnapshot;
import com.aetherianartificer.townstead.api.v1.model.ScheduleSnapshot;
import com.aetherianartificer.townstead.api.v1.model.SpiritSnapshot;
import com.aetherianartificer.townstead.api.v1.model.VillageDigestView;
import com.aetherianartificer.townstead.api.v1.model.VillageId;
import com.aetherianartificer.townstead.api.v1.model.VillageNeedsSummary;
import com.aetherianartificer.townstead.api.v1.model.VillageSnapshot;
import com.aetherianartificer.townstead.api.v1.model.VillagerRecord;
import com.aetherianartificer.townstead.api.v1.model.VillagerSnapshot;
import com.aetherianartificer.townstead.api.v1.model.WorksiteSnapshot;
import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCalendarView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCareerView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadGeneVariantView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadGeneView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadLifeStageView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedLevelView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedsView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadOriginView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadScheduleView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadSpiritView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerNeedsView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerView;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadBackend;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadSignal;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Townstead's public api.v1 (spec 5.1, 5.2): generation 1, read through the facades of {@link TownsteadApiV1#get()} and
 * never through {@code api.impl.v1}. Each capability is bound by checking that every member it uses exists on the
 * installed Townstead's own interfaces and records ({@link #MANIFEST}), so a release that drops or re-signatures a
 * member fails closed for exactly that capability. Spirit and reactions use the public facades whatever
 * {@code allowInternalFallback} says; that switch governs only the 0.7.x backend. {@code villages().homeOf} and
 * {@code nearest} are never used: both fall back to the nearest village, which is not residence evidence (spec 6.2).
 *
 * <p>Loaded only by reflection from {@code TownsteadBackends}, after the API class was found, so no common class links
 * to a Townstead type.
 */
public final class TownsteadV1Backend implements TownsteadBackend {
    static final int GENERATION = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NONE_PROFESSION = "minecraft:none";

    private final TownsteadApiV1 api;
    private final Info info;
    private final Set<TownsteadCapability> capabilities = ConcurrentHashMap.newKeySet();
    private final Map<TownsteadCapability, String> diagnostics = new ConcurrentHashMap<>();
    private final Set<TownsteadCapability> reported = ConcurrentHashMap.newKeySet();
    private volatile Set<ResourceLocation> reactionIds;
    private volatile long reactionIdsAt = Long.MIN_VALUE;

    /** Every Townstead member a capability calls, checked on the installed classes at bind time. */
    static final Map<TownsteadCapability, List<Member>> MANIFEST = manifest();
    /** The registry enumerations behind {@link #registryIds}, which belong to no capability; probed the same way. */
    static final List<Member> REGISTRIES = List.of(m(TownsteadApiV1.class, "registries"), m(RegistriesApi.class, "cultureIds"),
            m(RegistriesApi.class, "foundingProfileIds"), m(RegistriesApi.class, "professionIds"), m(RegistriesApi.class, "skillIds"),
            m(RegistriesApi.class, "reactionIds"), m(RegistriesApi.class, "needIds"), m(RegistriesApi.class, "rootIds"));
    private final boolean registries;

    private TownsteadV1Backend(TownsteadApiV1 api, int generation, int revision, String version) {
        this.api = api;
        this.info = new Info(version, generation, revision, "Townstead api.v1 generation " + generation + " revision " + revision);
        for (TownsteadCapability capability : TownsteadCapability.values()) bind(capability, revision);
        boolean all = true;
        for (Member member : REGISTRIES) {
            try {
                member.owner().getMethod(member.name(), member.parameters());
            } catch (Throwable missing) {
                all = false;
            }
        }
        this.registries = all;
    }

    /**
     * Binds api.v1, or returns a {@code String} saying why it cannot be used (generation, entry point or identity
     * constructor), in which case the caller falls back to the 0.7.x facade.
     */
    public static Object bind() {
        TownsteadApiV1 api;
        try {
            api = TownsteadApiV1.get();
        } catch (Throwable failure) {
            return "Townstead api.v1 implementation unavailable: " + failure;
        }
        int generation;
        int revision;
        String version;
        try {
            generation = api.getApiVersion();
            revision = api.getApiRevision();
            version = api.getModVersion();
        } catch (Throwable failure) {
            return "Townstead api.v1 version probe failed: " + failure;
        }
        if (generation != GENERATION) {
            return "Townstead api.v1 reports generation " + generation + "; this release reads generation " + GENERATION;
        }
        try {
            VillageId.class.getConstructor(ResourceLocation.class, int.class);
        } catch (Throwable failure) {
            return "Townstead api.v1 VillageId has no (dimension, villageId) constructor";
        }
        return new TownsteadV1Backend(api, generation, revision, version);
    }

    @Override public String id() { return "v1"; }
    @Override public Info info() { return info; }
    @Override public Set<TownsteadCapability> capabilities() { return Set.copyOf(capabilities); }

    @Override
    public Map<TownsteadCapability, String> diagnostics() {
        EnumMap<TownsteadCapability, String> copy = new EnumMap<>(TownsteadCapability.class);
        for (TownsteadCapability capability : TownsteadCapability.values()) copy.put(capability, diagnostics.getOrDefault(capability, "unavailable"));
        return Map.copyOf(copy);
    }

    // ---- binding ----

    record Member(Class<?> owner, String name, Class<?>... parameters) {
    }

    private static Member m(Class<?> owner, String name, Class<?>... parameters) {
        return new Member(owner, name, parameters);
    }

    private static Map<TownsteadCapability, List<Member>> manifest() {
        Map<TownsteadCapability, List<Member>> out = new EnumMap<>(TownsteadCapability.class);
        Class<?> V = VillagerSnapshot.class;
        out.put(TownsteadCapability.READ_VILLAGER, List.of(m(TownsteadApiV1.class, "villagers"), m(VillagersApi.class, "snapshot", Entity.class),
                m(V, "player"), m(V, "uuid"), m(V, "name"), m(V, "entityType"), m(V, "rootId"), m(V, "lifeStage"), m(V, "biologicalAgeDays"),
                m(V, "apparentAgeYears"), m(V, "immortal"), m(V, "ageless"), m(V, "senior"), m(V, "personalityId"), m(V, "professionId"),
                m(V, "professionTier"), m(V, "professionXp"), m(V, "fertility"), m(V, "carriedVariants"), m(V, "expressedAlleles"),
                m(V, "heritage")));
        Class<?> N = NeedsSnapshot.class;
        Class<?> L = NeedLevel.class;
        out.put(TownsteadCapability.READ_NEEDS, List.of(m(TownsteadApiV1.class, "villagers"), m(VillagersApi.class, "needs", Entity.class),
                m(V, "needs"), m(N, "hunger"), m(N, "saturation"), m(N, "hungerExhaustion"), m(N, "thirst"), m(N, "quenched"),
                m(N, "thirstExhaustion"), m(N, "energy"), m(N, "collapsed"), m(N, "thirstSimulated"), m(N, "bodyTemperatureTenths"),
                m(N, "ambientTemperatureTenths"), m(N, "levels"), m(L, "needId"), m(L, "value"), m(L, "min"), m(L, "max"),
                m(L, "neutral"), m(L, "band"), m(L, "crisis"), m(L, "enabled")));
        Class<?> S = ScheduleSnapshot.class;
        out.put(TownsteadCapability.READ_SCHEDULE, List.of(m(TownsteadApiV1.class, "villagers"), m(V, "schedule"), m(S, "mode"),
                m(S, "templateId"), m(S, "customShifts"), m(S, "nonDefaultCustomShifts"), m(S, "currentTickHour"), m(S, "currentDisplayHour"),
                m(S, "currentShiftOrdinal"), m(S, "currentActivity"), m(S, "plannedActivity"), m(S, "currentTemplateId"), m(S, "shifts"),
                m(S, "weekDayTemplates")));
        Class<?> C = CalendarSnapshot.class;
        out.put(TownsteadCapability.READ_CALENDAR, List.of(m(TownsteadApiV1.class, "calendar"), m(CalendarApi.class, "today", MinecraftServer.class),
                m(C, "profileId"), m(C, "worldDay"), m(C, "epochYearOffset"), m(C, "timeMode"), m(C, "year"), m(C, "month"), m(C, "day"),
                m(C, "dayOfYear"), m(C, "dayOfWeek"), m(C, "season")));
        Class<?> B = BuildingSnapshot.class;
        List<Member> building = List.of(m(B, "village"), m(B, "id"), m(B, "type"), m(B, "family"), m(B, "tier"), m(B, "size"),
                m(B, "center"), m(B, "min"), m(B, "max"), m(VillageId.class, "dimension"), m(VillageId.class, "villageId"));
        out.put(TownsteadCapability.READ_BUILDING, concat(building, m(TownsteadApiV1.class, "villages"),
                m(VillagesApi.class, "buildingAt", ServerLevel.class, BlockPos.class)));
        out.put(TownsteadCapability.READ_BUILDINGS, concat(building, m(TownsteadApiV1.class, "villages"),
                m(VillagesApi.class, "buildings", MinecraftServer.class, VillageId.class),
                m(VillagesApi.class, "village", MinecraftServer.class, VillageId.class), m(VillageSnapshot.class, "buildingCount"),
                m(VillageId.class, "asString")));
        out.put(TownsteadCapability.READ_BUILDING_COMPLETION, List.of(m(B, "complete")));
        Class<?> R = RootSnapshot.class;
        Class<?> LS = RootSnapshot.LifeStageInfo.class;
        out.put(TownsteadCapability.READ_ORIGIN, List.of(m(TownsteadApiV1.class, "social"), m(SocialApi.class, "root", ResourceLocation.class),
                m(R, "id"), m(R, "displayName"), m(R, "species"), m(R, "ancestry"), m(R, "lineage"), m(R, "effectiveSpecies"),
                m(R, "defaultGenes"), m(R, "lifeStages"), m(LS, "id"), m(LS, "label"), m(LS, "days"), m(LS, "scale"), m(LS, "presentsAs"),
                m(LS, "narrativeStart"), m(LS, "narrativeEnd")));
        Class<?> G = GeneSnapshot.class;
        Class<?> GV = GeneSnapshot.VariantInfo.class;
        out.put(TownsteadCapability.READ_GENE, List.of(m(TownsteadApiV1.class, "social"), m(SocialApi.class, "gene", ResourceLocation.class),
                m(G, "id"), m(G, "displayName"), m(G, "description"), m(G, "category"), m(G, "dominance"), m(G, "locus"), m(G, "weight"),
                m(G, "displayMode"), m(G, "variants"), m(GV, "id"), m(GV, "displayName"), m(GV, "weight"), m(GV, "type")));
        Class<?> SP = SpiritSnapshot.class;
        out.put(TownsteadCapability.READ_SPIRIT, List.of(m(TownsteadApiV1.class, "villages"), m(VillagesApi.class, "spirit", MinecraftServer.class, VillageId.class),
                m(SP, "perSpirit"), m(SP, "total"), m(SP, "contributingBuildings"), m(SP, "classification"), m(SP, "tierIndex"),
                m(SP, "primarySpiritId"), m(SP, "secondarySpiritId")));
        out.put(TownsteadCapability.DISPATCH_REACTION, List.of(m(TownsteadApiV1.class, "social"),
                m(SocialApi.class, "fireReaction", ServerLevel.class, LivingEntity.class, ResourceLocation.class, ReactionCause.class),
                m(SocialApi.class, "reactionsPlayable"), m(SocialApi.class, "reactionIds")));
        Class<?> VS = VillageSnapshot.class;
        out.put(TownsteadCapability.READ_VILLAGE, List.of(m(TownsteadApiV1.class, "villages"), m(VillagesApi.class, "village", MinecraftServer.class, VillageId.class),
                m(VS, "id"), m(VS, "name"), m(VS, "center"), m(VS, "min"), m(VS, "max"), m(VS, "residentCount"), m(VS, "loadedResidentCount"),
                m(VS, "establishedWorldDay"), m(VS, "playerFounded"), m(VS, "buildingCount"), m(VS, "culture"), m(VS, "rangeMargin")));
        Class<?> VR = VillagerRecord.class;
        out.put(TownsteadCapability.READ_RESIDENT_RECORD, List.of(m(TownsteadApiV1.class, "villagers"), m(VillagersApi.class, "record", MinecraftServer.class, UUID.class),
                m(VR, "uuid"), m(VR, "name"), m(VR, "village"), m(VR, "professionId"), m(VR, "professionTier"), m(VR, "levels"), m(VR, "loaded"),
                m(VR, "lastSeenWorldDay"), m(VR, "alive")));
        Class<?> NS = VillageNeedsSummary.class;
        Class<?> ST = VillageNeedsSummary.NeedStat.class;
        out.put(TownsteadCapability.READ_NEEDS_SUMMARY, List.of(m(TownsteadApiV1.class, "villages"), m(VillagesApi.class, "needs", MinecraftServer.class, VillageId.class),
                m(NS, "residentCount"), m(NS, "knownCount"), m(NS, "loadedCount"), m(NS, "byNeed"), m(NS, "collapsedCount"),
                m(NS, "oldestSampleWorldDay"), m(NS, "worldDay"), m(ST, "needId"), m(ST, "sampled"), m(ST, "mean"), m(ST, "min"), m(ST, "max"),
                m(ST, "inCrisis"), m(ST, "band"), m(NeedBand.class, "id")));
        Class<?> CS = CareerSnapshot.class;
        Class<?> PP = ProfessionProgressSnapshot.class;
        Class<?> PS = ProfessionSnapshot.class;
        out.put(TownsteadCapability.READ_CAREERS, List.of(m(TownsteadApiV1.class, "professions"), m(ProfessionsApi.class, "career", Entity.class),
                m(ProfessionsApi.class, "profession", String.class), m(ProfessionsApi.class, "isKnownSkill", ResourceLocation.class),
                m(ProfessionsApi.class, "hasSkill", Entity.class, ResourceLocation.class), m(CS, "primaryVocation"), m(CS, "acquiredCareers"),
                m(CS, "learnedSkills"), m(CS, "progress"), m(PP, "professionId"), m(PP, "xp"), m(PP, "tier"), m(PP, "maxTier"), m(PS, "id"),
                m(PS, "track"), m(PS, "aliases"), m(PS, "hidden"), m(ProgressionTrackSnapshot.class, "maxTier")));
        Class<?> W = WorksiteSnapshot.class;
        Class<?> O = OrderSnapshot.class;
        out.put(TownsteadCapability.READ_WORK_ORDERS, List.of(m(TownsteadApiV1.class, "work"), m(WorkApi.class, "worksites", MinecraftServer.class, VillageId.class),
                m(WorkApi.class, "orders", MinecraftServer.class, long.class), m(W, "id"), m(W, "binding"), m(W, "anchor"), m(W, "name"),
                m(W, "driver"), m(W, "orderCount"), m(O, "worksiteId"), m(O, "index"), m(O, "output"), m(O, "product"), m(O, "productName"),
                m(O, "kind"), m(O, "mode"), m(O, "target"), m(O, "scope"), m(O, "paused"), m(O, "profession"), m(O, "minRank"), m(O, "villager")));
        out.put(TownsteadCapability.READ_STORAGE, List.of(m(TownsteadApiV1.class, "work"),
                m(WorkApi.class, "countItem", ServerLevel.class, VillageId.class, ResourceLocation.class),
                m(WorkApi.class, "countTag", ServerLevel.class, VillageId.class, ResourceLocation.class)));
        out.put(TownsteadCapability.READ_SETTINGS, List.of(m(TownsteadApiV1.class, "settings"), m(SettingsApi.class, "systems"),
                m(SettingsApi.class, "systemEnabled", String.class)));
        out.put(TownsteadCapability.SUBSCRIBE_EVENTS, List.of(m(TownsteadApiV1.class, "events"),
                m(EventsApi.class, "subscribe", Class.class, Consumer.class), m(Subscription.class, "close")));
        Class<?> D = VillageDigestView.class;
        Class<?> DE = VillageDigestView.Entry.class;
        out.put(TownsteadCapability.READ_CHRONICLES, List.of(m(TownsteadApiV1.class, "chronicles"),
                m(ChroniclesApi.class, "digest", MinecraftServer.class, VillageId.class, Cursor.class, int.class), m(D, "entries"),
                m(D, "categoryCounts"), m(Page.class, "items"), m(Page.class, "hasMore"), m(Page.class, "next"), m(Cursor.class, "value"),
                m(Cursor.class, "isEnd"),
                m(DE, "worldDay"), m(DE, "eventId"), m(DE, "templateId"), m(DE, "headline"), m(DE, "headlineLangKey"), m(DE, "params")));
        out.put(TownsteadCapability.EMIT_CHRONICLE, List.of(m(TownsteadApiV1.class, "chronicles"), m(ChroniclesApi.class, "archiveAvailable"),
                m(ChroniclesApi.class, "emit", ServerLevel.class, LivingEntity.class, LivingEntity.class, String.class, float.class, Map.class)));
        Class<?> F = FactionSnapshot.class;
        Class<?> GS = GovernanceSnapshot.class;
        out.put(TownsteadCapability.READ_POLITICS, List.of(m(TownsteadApiV1.class, "politics"),
                m(PoliticsApi.class, "faction", MinecraftServer.class, VillageId.class), m(PoliticsApi.class, "governance", MinecraftServer.class, ResourceLocation.class),
                m(F, "id"), m(F, "kind"), m(F, "name"), m(F, "color"), m(F, "emblem"), m(F, "status"), m(F, "settlements"), m(F, "parent"),
                m(F, "memberCount"), m(GS, "faction"), m(GS, "form"), m(GS, "head"), m(GS, "offices"), m(GS, "legitimacy"), m(GS, "band"),
                m(GS, "provider"), m(GovernanceSnapshot.Office.class, "office"), m(GovernanceSnapshot.Office.class, "holders"),
                m(PoliticsApi.class, "founding", MinecraftServer.class, VillageId.class),
                m(com.aetherianartificer.townstead.api.v1.model.SettlementFoundingSnapshot.class, "profile")));
        out.put(TownsteadCapability.READ_HANGOUTS, List.of(m(TownsteadApiV1.class, "hangouts"), m(HangoutsApi.class, "visit", Entity.class),
                m(HangoutVisitSnapshot.class, "phase")));
        return out;
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T> first, T... more) {
        List<T> out = new ArrayList<>(first);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    private void bind(TownsteadCapability capability, int revision) {
        if (capability == TownsteadCapability.POLITICAL_DELEGATION) {
            diagnostics.put(capability, "Townstead 0.8.0 publishes no contract for an external government (docs/Townstead-Upstream-API-Proposal.md)");
            return;
        }
        List<Member> members = MANIFEST.get(capability);
        if (members == null) {
            diagnostics.put(capability, "not offered by api.v1");
            return;
        }
        for (Member member : members) {
            try {
                member.owner().getMethod(member.name(), member.parameters());
            } catch (Throwable missing) {
                diagnostics.put(capability, "api.v1 lacks " + member.owner().getSimpleName() + "#" + member.name());
                return;
            }
        }
        // Revision-2 facades have default bodies returning EMPTY in an older implementation: probe the instance too.
        try {
            switch (capability) {
                case READ_SETTINGS -> {
                    if (revision < 2 || api.settings() == SettingsApi.EMPTY || api.settings().systems().isEmpty()) {
                        diagnostics.put(capability, "api.v1 revision " + revision + " has no settings facade");
                        return;
                    }
                }
                case READ_POLITICS -> {
                    if (revision < 2 || api.politics() == PoliticsApi.EMPTY) {
                        diagnostics.put(capability, "api.v1 revision " + revision + " has no politics facade");
                        return;
                    }
                }
                case DISPATCH_REACTION -> {
                    Constructor<ReactionCause> ignored = ReactionCause.class.getConstructor(String.class, Optional.class, Optional.class, Set.class);
                }
                case READ_CHRONICLES -> Cursor.class.getConstructor(long.class);
                default -> { }
            }
        } catch (Throwable unusable) {
            diagnostics.put(capability, "api.v1 " + capability.serializedName() + " probe failed: " + unusable);
            return;
        }
        capabilities.add(capability);
        diagnostics.put(capability, "Townstead api.v1 r" + revision);
    }

    private boolean can(TownsteadCapability capability) {
        return capabilities.contains(capability);
    }

    /** A runtime failure removes only the capability that failed; it is logged once. */
    private void fail(TownsteadCapability capability, Throwable failure) {
        capabilities.remove(capability);
        diagnostics.put(capability, "runtime api.v1 call failed: " + failure);
        if (reported.add(capability)) LOGGER.error("[MCA: Kingdoms] Townstead api.v1 {} disabled after a failed call", capability, failure);
    }

    private <T> Optional<T> read(TownsteadCapability capability, Supplier<Optional<T>> call) {
        if (!can(capability)) return Optional.empty();
        try {
            Optional<T> value = call.get();
            return value == null ? Optional.empty() : value;
        } catch (LinkageError | RuntimeException failure) {
            fail(capability, failure);
            return Optional.empty();
        }
    }

    private static VillageId id(ResourceLocation dimension, int villageId) {
        return new VillageId(dimension, villageId);
    }

    // ---- reads ----

    @Override
    public Optional<TownsteadVillagerView> villager(Entity entity) {
        return read(TownsteadCapability.READ_VILLAGER, () -> api.villagers().snapshot(entity)).flatMap(snapshot -> {
            if (snapshot.player()) return Optional.empty();
            ResourceLocation type = ResourceLocation.tryParse(String.valueOf(snapshot.entityType()));
            if (snapshot.uuid() == null || type == null) return Optional.empty();
            TownsteadScheduleView schedule = can(TownsteadCapability.READ_SCHEDULE) && snapshot.schedule() != null
                    ? schedule(snapshot.schedule()) : new TownsteadScheduleView("", "", false, false, 0, 0, 0, "", "", "", List.of(), List.of());
            TownsteadNeedsView needs = can(TownsteadCapability.READ_NEEDS) && snapshot.needs() != null
                    ? legacyNeeds(snapshot.needs()) : new TownsteadNeedsView(0, 0, 0, 0, 0, 0, 0, false, false);
            Map<ResourceLocation, Float> heritage = new LinkedHashMap<>();
            snapshot.heritage().forEach((key, value) -> {
                ResourceLocation root = ResourceLocation.tryParse(key);
                if (root != null && value != null) heritage.put(root, value);
            });
            return Optional.of(new TownsteadVillagerView(snapshot.uuid(), Objects.toString(snapshot.name(), ""), type,
                    optionalId(snapshot.rootId()), Objects.toString(snapshot.lifeStage(), ""), snapshot.biologicalAgeDays(),
                    snapshot.apparentAgeYears(), snapshot.immortal(), snapshot.ageless(), snapshot.senior(),
                    Objects.toString(snapshot.personalityId(), ""), professionId(snapshot.professionId()), snapshot.professionTier(),
                    snapshot.professionXp(), snapshot.fertility(), schedule, needs, Map.copyOf(snapshot.carriedVariants()),
                    List.copyOf(snapshot.expressedAlleles()), heritage));
        });
    }

    /** Jobless villagers report {@code minecraft:none}; that is no profession. */
    private static Optional<ResourceLocation> professionId(String id) {
        if (id == null || id.isBlank() || NONE_PROFESSION.equals(id)) return Optional.empty();
        return Optional.ofNullable(ResourceLocation.tryParse(id));
    }

    private static Optional<ResourceLocation> optionalId(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(id));
    }

    private static TownsteadScheduleView schedule(ScheduleSnapshot s) {
        return new TownsteadScheduleView(Objects.toString(s.mode(), ""), Objects.toString(s.templateId(), ""), s.customShifts(),
                s.nonDefaultCustomShifts(), s.currentTickHour(), s.currentDisplayHour(), s.currentShiftOrdinal(),
                Objects.toString(s.currentActivity(), ""), Objects.toString(s.plannedActivity(), ""), Objects.toString(s.currentTemplateId(), ""),
                List.copyOf(s.shifts()), List.copyOf(s.weekDayTemplates()));
    }

    /**
     * The 0.7.x shape: api.v1 publishes energy (higher is better), so fatigue is the energy scale read the other way up.
     * {@code gated} is not published by api.v1 and reads false.
     */
    static TownsteadNeedsView legacyNeeds(NeedsSnapshot n) {
        NeedLevel energy = n.levels().get(NeedsSnapshot.ENERGY);
        int fatigue = energy == null ? 0 : Math.max(0, energy.max() - n.energy());
        return new TownsteadNeedsView(n.hunger(), n.saturation(), n.hungerExhaustion(), n.thirst(), n.quenched(), n.thirstExhaustion(),
                fatigue, n.collapsed(), false);
    }

    static TownsteadNeedLevelView level(NeedLevel l) {
        return new TownsteadNeedLevelView(l.needId(), l.value(), l.min(), l.max(), l.neutral(), l.band(), l.crisis(), l.enabled());
    }

    static TownsteadVillagerNeedsView needsView(NeedsSnapshot n) {
        Map<String, TownsteadNeedLevelView> levels = new LinkedHashMap<>();
        n.levels().forEach((key, value) -> { if (key != null && value != null) levels.put(key, level(value)); });
        NeedLevel temperature = n.levels().get(NeedsSnapshot.TEMPERATURE);
        boolean thermal = temperature != null && temperature.enabled();
        return new TownsteadVillagerNeedsView(levels, n.collapsed(), n.thirstSimulated(),
                thermal ? OptionalInt.of(n.bodyTemperatureTenths()) : OptionalInt.empty(),
                thermal ? OptionalInt.of(n.ambientTemperatureTenths()) : OptionalInt.empty());
    }

    @Override
    public Optional<TownsteadVillagerNeedsView> needs(Entity entity) {
        return read(TownsteadCapability.READ_NEEDS, () -> api.villagers().needs(entity)).map(TownsteadV1Backend::needsView);
    }

    @Override
    public Optional<TownsteadCalendarView> calendar(MinecraftServer server) {
        return read(TownsteadCapability.READ_CALENDAR, () -> Optional.ofNullable(api.calendar().today(server))).map(c ->
                new TownsteadCalendarView(Objects.toString(c.profileId(), ""), c.worldDay(), c.epochYearOffset(), Objects.toString(c.timeMode(), ""),
                        c.year(), c.month(), c.day(), c.dayOfYear(), c.dayOfWeek(), Objects.toString(c.season(), "")));
    }

    private RawBuilding raw(BuildingSnapshot b) {
        TownsteadBuildingEvidence.Completion completion = can(TownsteadCapability.READ_BUILDING_COMPLETION)
                ? (b.complete() ? TownsteadBuildingEvidence.Completion.COMPLETE : TownsteadBuildingEvidence.Completion.INCOMPLETE)
                : TownsteadBuildingEvidence.Completion.UNKNOWN;
        String type = Objects.toString(b.type(), "");
        String family = b.family() == null || b.family().isBlank() ? type : b.family();
        BlockPos min = b.min();
        BlockPos max = b.max();
        return new RawBuilding(b.village().villageId(), b.id(), type, family, Math.max(1, b.tier()), Math.max(0, b.size()), b.center(),
                new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ())),
                new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ())),
                completion, TownsteadBuildingEvidence.Source.TOWNSTEAD_V1);
    }

    @Override
    public Optional<RawBuilding> buildingAt(ServerLevel level, BlockPos pos) {
        return read(TownsteadCapability.READ_BUILDING, () -> api.villages().buildingAt(level, pos))
                .filter(b -> b.village() != null && b.village().dimension().equals(level.dimension().location()) && b.type() != null)
                .map(this::raw);
    }

    @Override
    public BuildingRead buildings(ServerLevel level, int villageId) {
        if (!can(TownsteadCapability.READ_BUILDINGS)) return BuildingRead.of(BuildingRead.Status.UNSUPPORTED, diagnostics.get(TownsteadCapability.READ_BUILDINGS));
        try {
            VillageId key = id(level.dimension().location(), villageId);
            Optional<VillageSnapshot> village = api.villages().village(level.getServer(), key);
            if (village == null || village.isEmpty()) return BuildingRead.of(BuildingRead.Status.VILLAGE_ABSENT, "no MCA village " + key.asString());
            List<BuildingSnapshot> found = api.villages().buildings(level.getServer(), key);
            List<RawBuilding> out = new ArrayList<>();
            for (BuildingSnapshot b : found) if (b != null && b.village() != null && b.type() != null) out.add(raw(b));
            // Both numbers come from the same MCA list, so a difference means the read failed part way (spec 3.1).
            if (out.size() != village.get().buildingCount()) {
                return new BuildingRead(BuildingRead.Status.INCONSISTENT, out,
                        "read " + out.size() + " of " + village.get().buildingCount() + " buildings of " + key.asString());
            }
            return new BuildingRead(BuildingRead.Status.OK, out, "");
        } catch (LinkageError | RuntimeException failure) {
            fail(TownsteadCapability.READ_BUILDINGS, failure);
            return BuildingRead.of(BuildingRead.Status.FAILED, String.valueOf(failure));
        }
    }

    @Override
    public Optional<TownsteadOriginView> origin(ResourceLocation id) {
        return read(TownsteadCapability.READ_ORIGIN, () -> api.social().root(id)).flatMap(root -> {
            ResourceLocation rootId = ResourceLocation.tryParse(Objects.toString(root.id(), ""));
            if (rootId == null) return Optional.empty();
            List<TownsteadLifeStageView> stages = root.lifeStages().stream().map(s -> new TownsteadLifeStageView(
                    Objects.toString(s.id(), ""), Objects.toString(s.label(), ""), s.days(), s.scale(), Objects.toString(s.presentsAs(), ""),
                    s.narrativeStart(), s.narrativeEnd())).toList();
            return Optional.of(new TownsteadOriginView(rootId, Objects.toString(root.displayName(), ""), optionalId(root.species()),
                    optionalId(root.ancestry()), optionalId(root.lineage()), optionalId(root.effectiveSpecies()),
                    root.defaultGenes().stream().map(ResourceLocation::tryParse).filter(Objects::nonNull).toList(), stages));
        });
    }

    @Override
    public Optional<TownsteadGeneView> gene(ResourceLocation id) {
        return read(TownsteadCapability.READ_GENE, () -> api.social().gene(id)).flatMap(g -> {
            ResourceLocation geneId = ResourceLocation.tryParse(Objects.toString(g.id(), ""));
            if (geneId == null) return Optional.empty();
            return Optional.of(new TownsteadGeneView(geneId, Objects.toString(g.displayName(), ""), Objects.toString(g.description(), ""),
                    Objects.toString(g.category(), ""), Objects.toString(g.dominance(), ""), optionalId(g.locus()), g.weight(),
                    Objects.toString(g.displayMode(), ""), g.variants().stream().map(v -> new TownsteadGeneVariantView(Objects.toString(v.id(), ""),
                    Objects.toString(v.displayName(), ""), v.weight(), Objects.toString(v.type(), ""))).toList()));
        });
    }

    @Override
    public Optional<TownsteadSpiritView> spirit(ServerLevel level, int villageId) {
        return read(TownsteadCapability.READ_SPIRIT, () -> api.villages().spirit(level.getServer(), id(level.dimension().location(), villageId)))
                .map(s -> new TownsteadSpiritView(villageId, s.perSpirit(), Math.max(0, s.total()), Math.max(0, s.contributingBuildings()),
                        Math.max(0, s.tierIndex()), Objects.toString(s.classification(), "").toLowerCase(Locale.ROOT),
                        s.primarySpiritId(), s.secondarySpiritId()));
    }

    @Override
    public boolean reactionsPlayable() {
        if (!can(TownsteadCapability.DISPATCH_REACTION)) return false;
        try {
            return api.social().reactionsPlayable();
        } catch (LinkageError | RuntimeException failure) {
            fail(TownsteadCapability.DISPATCH_REACTION, failure);
            return false;
        }
    }

    @Override
    public Set<ResourceLocation> reactionIds() {
        if (!can(TownsteadCapability.DISPATCH_REACTION)) return Set.of();
        long now = System.nanoTime();
        Set<ResourceLocation> cached = reactionIds;
        // Reaction definitions change only on a data reload; a second is plenty fresh for a dispatch batch.
        if (cached != null && now - reactionIdsAt < 1_000_000_000L) return cached;
        try {
            cached = Set.copyOf(api.social().reactionIds());
            reactionIds = cached;
            reactionIdsAt = now;
            return cached;
        } catch (LinkageError | RuntimeException failure) {
            fail(TownsteadCapability.DISPATCH_REACTION, failure);
            return Set.of();
        }
    }

    /** A {@code context} cause: Townstead's own cooldown, lock and chance gates apply (never {@code command}). */
    @Override
    public boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> player, ResourceLocation reactionId,
                                Set<String> tags) {
        if (!can(TownsteadCapability.DISPATCH_REACTION)) return false;
        try {
            return api.social().fireReaction(level, villager, reactionId, cause(player, villager.blockPosition(), tags));
        } catch (LinkageError | RuntimeException failure) {
            fail(TownsteadCapability.DISPATCH_REACTION, failure);
            return false;
        }
    }

    /** Townstead's {@code context} trigger source: its normal cooldown, lock and chance gates apply. */
    static ReactionCause cause(Optional<Player> player, BlockPos at, Set<String> tags) {
        Optional<ServerPlayer> cause = player.filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast);
        return new ReactionCause("context", cause, Optional.of(at), Set.copyOf(tags));
    }

    @Override
    public Optional<TownsteadVillageView> village(ServerLevel level, int villageId) {
        return read(TownsteadCapability.READ_VILLAGE, () -> api.villages().village(level.getServer(), id(level.dimension().location(), villageId)))
                .map(v -> new TownsteadVillageView(v.id().dimension(), v.id().villageId(), Objects.toString(v.name(), ""), v.center(), v.min(),
                        v.max(), Math.max(0, v.rangeMargin()), Math.max(0, v.residentCount()), Math.max(0, v.loadedResidentCount()),
                        Math.max(0, v.buildingCount()), v.culture(), v.establishedWorldDay(), v.playerFounded()));
    }

    @Override
    public Optional<NeedsSummary> needsSummary(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return read(TownsteadCapability.READ_NEEDS_SUMMARY, () -> api.villages().needs(server, id(dimension, villageId))).map(s -> {
            Map<String, NeedStat> stats = new LinkedHashMap<>();
            s.byNeed().forEach((need, stat) -> {
                if (need != null && stat != null) stats.put(need, new NeedStat(need, stat.sampled(), stat.mean(), stat.min(), stat.max(),
                        stat.inCrisis(), stat.band() == null ? "" : stat.band().id()));
            });
            return new NeedsSummary(s.residentCount(), s.knownCount(), s.loadedCount(), s.collapsedCount(), s.oldestSampleWorldDay(),
                    s.worldDay(), stats);
        });
    }

    @Override
    public Optional<ResidentRecord> record(MinecraftServer server, UUID villager) {
        return read(TownsteadCapability.READ_RESIDENT_RECORD, () -> api.villagers().record(server, villager)).map(r -> {
            Map<String, TownsteadNeedLevelView> levels = new LinkedHashMap<>();
            r.levels().forEach((key, value) -> { if (key != null && value != null) levels.put(key, level(value)); });
            return new ResidentRecord(r.uuid(), Objects.toString(r.name(), ""), r.village().map(VillageId::villageId),
                    r.village().map(VillageId::dimension), Objects.toString(r.professionId(), ""), r.professionTier(), levels, r.loaded(),
                    r.lastSeenWorldDay(), r.alive());
        });
    }

    @Override
    public Optional<TownsteadCareerView> career(Entity entity) {
        return read(TownsteadCapability.READ_CAREERS, () -> api.professions().career(entity)).map(c -> new TownsteadCareerView(
                c.primaryVocation(), c.acquiredCareers(), c.learnedSkills(), c.progress().stream()
                .map(p -> new TownsteadCareerView.Progress(Objects.toString(p.professionId(), ""), p.xp(), p.tier(), p.maxTier())).toList()));
    }

    @Override
    public Optional<Profession> profession(String id) {
        return read(TownsteadCapability.READ_CAREERS, () -> api.professions().profession(id)).map(p -> new Profession(
                Objects.toString(p.id(), ""), p.track().isPresent(), p.track().map(ProgressionTrackSnapshot::maxTier).orElse(0),
                p.aliases(), p.hidden()));
    }

    @Override
    public Optional<Boolean> knownSkill(ResourceLocation skill) {
        return read(TownsteadCapability.READ_CAREERS, () -> Optional.of(api.professions().isKnownSkill(skill)));
    }

    @Override
    public Optional<Boolean> hasSkill(Entity entity, ResourceLocation skill) {
        return read(TownsteadCapability.READ_CAREERS, () -> Optional.of(api.professions().hasSkill(entity, skill)));
    }

    @Override
    public List<Worksite> worksites(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return read(TownsteadCapability.READ_WORK_ORDERS, () -> Optional.of(api.work().worksites(server, id(dimension, villageId))))
                .orElse(List.of()).stream().filter(Objects::nonNull).map(w -> new Worksite(w.id(), w.binding(), w.anchor(),
                        Objects.toString(w.name(), ""), w.driver(), Math.max(0, w.orderCount()))).toList();
    }

    @Override
    public List<Order> orders(MinecraftServer server, long worksite) {
        return read(TownsteadCapability.READ_WORK_ORDERS, () -> Optional.of(api.work().orders(server, worksite))).orElse(List.of())
                .stream().filter(Objects::nonNull).map(o -> new Order(o.worksiteId(), o.index(), o.output(), o.product(), o.productName(),
                        Objects.toString(o.kind(), ""), Objects.toString(o.mode(), ""), o.target(), Objects.toString(o.scope(), ""), o.paused(),
                        o.profession(), o.minRank(), o.villager())).toList();
    }

    /** Townstead's count trusts the level it is given with the village's integer id; the dimension is checked here (spec 9.3). */
    @Override
    public OptionalInt countItem(ServerLevel level, int villageId, ResourceLocation item) {
        return read(TownsteadCapability.READ_STORAGE, () -> Optional.of(api.work().countItem(level, id(level.dimension().location(), villageId), item)))
                .map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public OptionalInt countTag(ServerLevel level, int villageId, ResourceLocation tag) {
        return read(TownsteadCapability.READ_STORAGE, () -> Optional.of(api.work().countTag(level, id(level.dimension().location(), villageId), tag)))
                .map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public List<String> systems() {
        return read(TownsteadCapability.READ_SETTINGS, () -> Optional.of(List.copyOf(api.settings().systems()))).orElse(List.of());
    }

    @Override
    public Optional<Boolean> systemEnabled(String system) {
        // Townstead reads an unknown system id as on; only a listed id is an answer.
        if (system == null || !systems().contains(system)) return Optional.empty();
        return read(TownsteadCapability.READ_SETTINGS, () -> Optional.of(api.settings().systemEnabled(system)));
    }

    @Override
    public Optional<DigestPage> digest(MinecraftServer server, ResourceLocation dimension, int villageId, long cursor, int limit) {
        return read(TownsteadCapability.READ_CHRONICLES, () -> api.chronicles().digest(server, id(dimension, villageId),
                cursor <= 0 ? Cursor.START : new Cursor(cursor), Math.max(1, Math.min(ChroniclesApi.MAX_PAGE, limit)))).map(d -> {
            Page<VillageDigestView.Entry> page = d.entries();
            List<DigestEntry> entries = page == null ? List.of() : page.items().stream().filter(Objects::nonNull).map(e -> new DigestEntry(
                    e.worldDay(), e.eventId(), Objects.toString(e.templateId(), ""), Objects.toString(e.headline(), ""),
                    Objects.toString(e.headlineLangKey(), ""), e.params())).toList();
            boolean more = page != null && page.hasMore() && page.next() != null && !page.next().isEnd();
            return new DigestPage(entries, more, more ? page.next().value() : -1L, d.categoryCounts());
        });
    }

    /**
     * Townstead adds to the actor's counter before it looks for a template, so an empty answer still happened: the
     * caller records it and never retries (spec 10.2).
     */
    @Override
    public Emission emit(ServerLevel level, LivingEntity actor, String triggerKey, float magnitude, Map<String, String> params) {
        if (!can(TownsteadCapability.EMIT_CHRONICLE)) return Emission.UNAVAILABLE;
        try {
            return api.chronicles().emit(level, actor, null, triggerKey, magnitude, Map.copyOf(params)).isPresent() ? Emission.STORY : Emission.NO_STORY;
        } catch (LinkageError | RuntimeException failure) {
            fail(TownsteadCapability.EMIT_CHRONICLE, failure);
            return Emission.UNAVAILABLE;
        }
    }

    @Override
    public Optional<Faction> faction(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return read(TownsteadCapability.READ_POLITICS, () -> api.politics().faction(server, id(dimension, villageId))).map(f -> new Faction(
                f.id(), f.kind(), Objects.toString(f.name(), ""), f.color(), f.emblem(), Objects.toString(f.status(), ""), f.settlements().size(),
                f.parent(), Math.max(0, f.memberCount())));
    }

    /**
     * Townstead's native offices. Its {@code provider} field is appended after the native head and offices are built, so
     * they are kept apart here: a native head is never presented as another mod's ruler (spec 4.3).
     */
    @Override
    public Optional<Governance> governance(MinecraftServer server, ResourceLocation faction) {
        return read(TownsteadCapability.READ_POLITICS, () -> api.politics().governance(server, faction)).map(g -> {
            int populated = 0;
            int holders = 0;
            int fingerprint = Objects.hash(g.form(), g.head().orElse(null));
            for (GovernanceSnapshot.Office office : g.offices()) {
                if (!office.holders().isEmpty()) populated++;
                holders += office.holders().size();
                fingerprint = 31 * fingerprint + Objects.hash(office.office(), List.copyOf(office.holders()));
            }
            return new Governance(g.faction(), g.form(), g.head(), g.offices().size(), populated, holders, g.legitimacy(), g.band(),
                    g.provider(), fingerprint);
        });
    }

    @Override
    public Optional<ResourceLocation> foundingProfile(MinecraftServer server, ResourceLocation dimension, int villageId) {
        return read(TownsteadCapability.READ_POLITICS, () -> api.politics().founding(server, id(dimension, villageId)))
                .map(com.aetherianartificer.townstead.api.v1.model.SettlementFoundingSnapshot::profile);
    }

    @Override
    public Optional<List<String>> registryIds(Registry registry) {
        if (!registries) return Optional.empty();
        Supplier<List<?>> call = switch (registry) {
            case CULTURES -> () -> api.registries().cultureIds();
            case FOUNDING_PROFILES -> () -> api.registries().foundingProfileIds();
            case PROFESSIONS -> () -> api.registries().professionIds();
            case SKILLS -> () -> api.registries().skillIds();
            case REACTIONS -> () -> api.registries().reactionIds();
            case NEEDS -> () -> api.registries().needIds();
            case ROOTS -> () -> api.registries().rootIds();
        };
        try {
            RegistriesApi registries = api.registries();
            if (registries == null) return Optional.empty();
            List<?> ids = call.get();
            return ids == null ? Optional.empty() : Optional.of(ids.stream().filter(Objects::nonNull).map(String::valueOf).sorted().toList());
        } catch (LinkageError | RuntimeException failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> hangoutPhase(Entity villager) {
        return read(TownsteadCapability.READ_HANGOUTS, () -> api.hangouts().visit(villager)).map(v -> Objects.toString(v.phase(), ""));
    }

    /** Subscribes through {@code events().subscribe} only, never the loader-bus mirror, so nothing is handled twice. */
    @Override
    public Registration subscribe(MinecraftServer server, Consumer<TownsteadSignal> sink, java.util.function.Function<Entity, UUID> residence) {
        if (!can(TownsteadCapability.SUBSCRIBE_EVENTS)) return () -> { };
        try {
            return TownsteadV1Events.subscribe(api.events(), server, sink, residence);
        } catch (LinkageError | RuntimeException failure) {
            fail(TownsteadCapability.SUBSCRIBE_EVENTS, failure);
            return () -> { };
        }
    }
}
