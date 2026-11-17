package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.event.PublicWorkCompletedEvent;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.api.politics.PoliticalCommittedEvent;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillageView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrates a small allowlist of committed MCA: Kingdoms events in Townstead's Chronicles (spec 10.2): an institution
 * recognized, an agreement concluded, a public work completed. Each is projected once at most, keyed by the originating
 * commit's id, and only when a real actor stands at the place it concerns: Townstead scopes a story by its actor, so a
 * remote treaty or an offline approver keeps only MCA: Kingdoms' own record (no stand-in NPC is ever used). Townstead
 * counts the moment before it looks for a template, so an empty answer is recorded and never retried, and no separate
 * counter is added. A crash between the commit and the projection can omit the story; nothing mechanical depends on it,
 * and stories Townstead records are never read back into rewards, standing or decisions.
 */
final class TownsteadChronicleBridge {
    static final String INSTITUTION = "mcakingdoms:institution_recognized";
    static final String AGREEMENT = "mcakingdoms:agreement_concluded";
    static final String PUBLIC_WORK = "mcakingdoms:public_work_completed";
    private static final int LEDGER = 4_096;

    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private final Ledger ledger = new Ledger(LEDGER);
    private final Map<UUID, TownsteadBackend.DigestPage> digests = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, TownsteadBackend.DigestPage> eldest) { return size() > 128; }
    };
    enum Outcome { STORY, NO_STORY, NO_ACTOR, DISABLED, UNAVAILABLE }

    /**
     * At most one attempt per source event: {@link #claim} succeeds once per id and records the attempt before the
     * provider is called, so a throw, an empty answer or a later duplicate post never leads to a second emission.
     * Bounded; the oldest ids are forgotten first (Kingdoms never re-posts an old commit).
     */
    static final class Ledger {
        private final int capacity;
        private final Map<UUID, Outcome> outcomes;
        private final Map<Outcome, Integer> counts = new LinkedHashMap<>();

        Ledger(int capacity) {
            this.capacity = capacity;
            this.outcomes = new LinkedHashMap<>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<UUID, Outcome> eldest) { return size() > Ledger.this.capacity; }
            };
        }

        /** True for the first claim of {@code source}, which is recorded as {@code provisional} at once. */
        boolean claim(UUID source, Outcome provisional) {
            if (outcomes.containsKey(source)) return false;
            outcomes.put(source, provisional);
            counts.merge(provisional, 1, Integer::sum);
            return true;
        }

        /** Replaces a claimed attempt's provisional outcome with the real one. */
        void settle(UUID source, Outcome outcome) {
            Outcome before = outcomes.replace(source, outcome);
            if (before == null) return;
            counts.merge(before, -1, Integer::sum);
            counts.merge(outcome, 1, Integer::sum);
        }

        Optional<Outcome> outcome(UUID source) {
            return Optional.ofNullable(outcomes.get(source));
        }

        Map<Outcome, Integer> counts() {
            Map<Outcome, Integer> out = new LinkedHashMap<>();
            counts.forEach((key, value) -> { if (value > 0) out.put(key, value); });
            return Map.copyOf(out);
        }

        void clear() {
            outcomes.clear();
            counts.clear();
        }
    }

    TownsteadChronicleBridge(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    @SubscribeEvent
    public void political(PoliticalCommittedEvent event) {
        if (event.server() != server) return;
        Politics.Notice notice = event.notice();
        switch (notice.action()) {
            case RECOGNIZE -> {
                UUID record = uuid(notice.recordId());
                if (record == null) return;
                KingdomsPoliticsApi.get(server).institution(record).ifPresent(institution ->
                        project(notice.id(), INSTITUTION, notice.actor(), institution.settlement(), Map.of(
                                "institution", net.minecraft.network.chat.Component.translatable(institution.titleKey()).getString(),
                                "settlement", name(institution.settlement()))));
            }
            case SIGN -> {
                UUID record = uuid(notice.recordId());
                if (record == null) return;
                if (!(KingdomsPoliticsApi.get(server) instanceof dev.otectus.mcakingdoms.politics.GovernmentService government)) return;
                government.activeAgreements(notice.kingdom()).stream().filter(a -> a.id().equals(record)).findFirst().ifPresent(agreement -> {
                    ServerPlayer actor = server.getPlayerList().getPlayer(notice.actor());
                    if (actor == null) { record(notice.id(), Outcome.NO_ACTOR); return; }
                    kingdoms.getSettlementAt(actor.serverLevel(), actor.blockPosition())
                            .filter(s -> agreement.involves(s.kingdomId().toString()))
                            .ifPresentOrElse(here -> project(notice.id(), AGREEMENT, notice.actor(), here.id(), Map.of(
                                    "kingdom", kingdomName(agreement.proposer()), "counterpart", kingdomName(agreement.recipient()),
                                    "settlement", here.displayName())), () -> record(notice.id(), Outcome.NO_ACTOR));
                });
            }
            default -> { }
        }
    }

    @SubscribeEvent
    public void publicWork(PublicWorkCompletedEvent event) {
        if (event.server() != server) return;
        UUID actor = event.approver().filter(id -> server.getPlayerList().getPlayer(id) != null).orElse(event.proposer());
        project(event.project(), PUBLIC_WORK, actor, event.settlement(), Map.of("settlement", name(event.settlement()),
                "work", event.template().getPath().replace('_', ' ')));
    }

    private void project(UUID source, String trigger, UUID actorId, UUID settlementId, Map<String, String> params) {
        if (ledger.outcome(source).isPresent()) return;
        if (!TownsteadIntegrationConfig.CHRONICLE_PROJECTION.get() || !TownsteadMappings.chronicleTrigger(trigger)) { record(source, Outcome.DISABLED); return; }
        if (!townstead.states().usable(TownsteadCapability.EMIT_CHRONICLE)) { record(source, Outcome.UNAVAILABLE); return; }
        ServerPlayer actor = actorId == null ? null : server.getPlayerList().getPlayer(actorId);
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (actor == null || settlement.isEmpty() || !(actor.level() instanceof ServerLevel level) || !at(actor, level, settlement.get())) {
            record(source, Outcome.NO_ACTOR);
            return;
        }
        // Claimed before the call: a throw, an empty answer or a duplicate post afterwards never leads to a second attempt.
        if (!ledger.claim(source, Outcome.NO_STORY)) return;
        TownsteadBackend.Emission emission = townstead.backendImpl().emit(level, actor, trigger, 1.0F, params);
        Outcome outcome = switch (emission) {
            case STORY -> Outcome.STORY;
            case NO_STORY -> Outcome.NO_STORY;
            case UNAVAILABLE -> Outcome.UNAVAILABLE;
        };
        ledger.settle(source, outcome);
        if (outcome == Outcome.STORY) digests.remove(settlement.get().id());
    }

    /** The actor stands in the settlement and inside the border of one of its MCA villages, where Townstead will scope the story. */
    private boolean at(ServerPlayer actor, ServerLevel level, SettlementView settlement) {
        if (!actor.level().dimension().equals(settlement.dimension())) return false;
        if (kingdoms.getSettlementAt(level, actor.blockPosition()).filter(s -> s.id().equals(settlement.id())).isEmpty()) return false;
        BlockPos pos = actor.blockPosition();
        for (int villageId : townstead.villageIds(settlement, level.dimension().location())) {
            Optional<TownsteadVillageView> village = townstead.backendImpl().village(level, villageId);
            if (village.isPresent() && within(village.get(), pos)) return true;
        }
        return false;
    }

    static boolean within(TownsteadVillageView village, BlockPos pos) {
        int margin = village.rangeMargin();
        return pos.getX() >= village.min().getX() - margin && pos.getX() <= village.max().getX() + margin
                && pos.getZ() >= village.min().getZ() - margin && pos.getZ() <= village.max().getZ() + margin;
    }

    private void record(UUID source, Outcome outcome) {
        ledger.claim(source, outcome);
    }

    Optional<Outcome> outcome(UUID source) {
        return ledger.outcome(source);
    }

    Map<Outcome, Integer> counts() {
        return ledger.counts();
    }

    /** One page of a settlement's public Townstead village news; the first page is shared by every viewer. */
    Optional<TownsteadBackend.DigestPage> digest(UUID settlementId, long cursor, int limit) {
        if (!townstead.states().usable(TownsteadCapability.READ_CHRONICLES)) return Optional.empty();
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return Optional.empty();
        if (cursor <= 0) {
            TownsteadBackend.DigestPage cached = digests.get(settlement.get().id());
            if (cached != null) return Optional.of(cached);
        }
        var dimension = settlement.get().dimension().location();
        var villages = townstead.villageIds(settlement.get(), dimension);
        if (villages.isEmpty()) return Optional.empty();
        // A merged settlement's later villages are listed under its first; Townstead keeps one digest per village.
        Optional<TownsteadBackend.DigestPage> page = townstead.backendImpl().digest(server, dimension, villages.get(0), cursor, limit);
        if (cursor <= 0) page.ifPresent(found -> digests.put(settlement.get().id(), found));
        return page;
    }

    void digestChanged(UUID settlement) {
        if (settlement == null) digests.clear();
        else digests.remove(settlement);
    }

    void clear() {
        ledger.clear();
        digests.clear();
    }

    private String name(UUID settlement) {
        return kingdoms.getSettlement(settlement).map(SettlementView::displayName).orElse("");
    }

    private String kingdomName(String kingdom) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(kingdom);
        return id == null ? kingdom : kingdoms.getKingdom(id).map(k -> net.minecraft.network.chat.Component.translatable(k.translationKey()).getString()).orElse(kingdom);
    }

    private static UUID uuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
