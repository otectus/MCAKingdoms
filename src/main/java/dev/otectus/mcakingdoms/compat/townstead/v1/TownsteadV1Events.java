package dev.otectus.mcakingdoms.compat.townstead.v1;

import com.aetherianartificer.townstead.api.v1.EventsApi;
import com.aetherianartificer.townstead.api.v1.event.BondChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.BuildingEstablishedEvent;
import com.aetherianartificer.townstead.api.v1.event.BuildingRemovedEvent;
import com.aetherianartificer.townstead.api.v1.event.BuildingUpgradedEvent;
import com.aetherianartificer.townstead.api.v1.event.ChronicleEventRecordedEvent;
import com.aetherianartificer.townstead.api.v1.event.FactionFoundedEvent;
import com.aetherianartificer.townstead.api.v1.event.FactionIdentityChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.FactionKindChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.FactionStatusChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.LegitimacyChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.SeatDamagedEvent;
import com.aetherianartificer.townstead.api.v1.event.SeatDesignatedEvent;
import com.aetherianartificer.townstead.api.v1.event.SeatLostEvent;
import com.aetherianartificer.townstead.api.v1.event.SeatRepairedEvent;
import com.aetherianartificer.townstead.api.v1.event.SettlementFactionChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.SettlementFoundedEvent;
import com.aetherianartificer.townstead.api.v1.event.Subscription;
import com.aetherianartificer.townstead.api.v1.event.TownsteadEvent;
import com.aetherianartificer.townstead.api.v1.event.VillageNeedsBandChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillageSpiritChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerBornEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerDiedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerLifeStageChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerMarriedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerProfessionChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerRootChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerTierChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerVillageChangedEvent;
import com.aetherianartificer.townstead.api.v1.event.WorkCompletedEvent;
import com.aetherianartificer.townstead.api.v1.event.WorksiteRegisteredEvent;
import com.aetherianartificer.townstead.api.v1.event.WorksiteRemovedEvent;
import com.aetherianartificer.townstead.api.v1.event.WorldSettingsChangedEvent;
import com.aetherianartificer.townstead.api.v1.model.VillageId;
import com.aetherianartificer.townstead.api.v1.model.WorksiteSnapshot;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadSignal;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadSignal.Kind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Townstead's event feed copied into scalar {@link TownsteadSignal}s (spec 5.4). Townstead's registry is static, so every
 * subscription is kept and closed with the returned registration; an event for another server (an earlier integrated
 * world, a second server in a test JVM) is dropped. {@code VillagerVillageChangedEvent} carries no server: it is accepted
 * only while this session's subscription is open and is verified against current records before anything uses it.
 */
final class TownsteadV1Events {
    private TownsteadV1Events() {
    }

    static Registration subscribe(EventsApi events, MinecraftServer server, Consumer<TownsteadSignal> sink, Function<Entity, UUID> residence) {
        AtomicBoolean open = new AtomicBoolean(true);
        List<Subscription> subscriptions = new ArrayList<>();
        Registration registration = () -> {
            if (!open.compareAndSet(true, false)) return;
            for (Subscription subscription : subscriptions) {
                try {
                    subscription.close();
                } catch (RuntimeException ignored) {
                    // Closing is idempotent on Townstead's side; a failure here must not stop the others closing.
                }
            }
            subscriptions.clear();
        };
        try {
            on(events, subscriptions, open, BuildingEstablishedEvent.class, e -> e.level().getServer() == server
                    ? village(Kind.BUILDINGS, e.village(), null, "established " + e.type()) : null, sink);
            on(events, subscriptions, open, BuildingUpgradedEvent.class, e -> e.level().getServer() == server
                    ? village(Kind.BUILDINGS, e.village(), null, "upgraded " + e.typeBefore() + " to " + e.typeAfter()) : null, sink);
            on(events, subscriptions, open, BuildingRemovedEvent.class, e -> e.level().getServer() == server
                    ? village(Kind.BUILDINGS, e.village(), null, "removed " + e.type()) : null, sink);
            on(events, subscriptions, open, VillageSpiritChangedEvent.class, e -> e.level().getServer() == server
                    ? village(Kind.SPIRIT, e.village(), null, "tier " + e.after().tierIndex()) : null, sink);
            on(events, subscriptions, open, VillageNeedsBandChangedEvent.class, e -> e.server() == server
                    ? village(Kind.NEEDS, e.village(), null, e.needId() + " " + e.after().id()) : null, sink);
            on(events, subscriptions, open, VillagerVillageChangedEvent.class, e -> village(Kind.RESIDENT_HOME, e.after(), e.uuid(), "home"), sink);
            on(events, subscriptions, open, WorldSettingsChangedEvent.class, e -> e.server() == server
                    ? new TownsteadSignal(Kind.SETTINGS, null, -1, null, String.join(",", e.systemsOff())) : null, sink);
            on(events, subscriptions, open, WorksiteRegisteredEvent.class, e -> e.server() == server ? worksite(e.worksite()) : null, sink);
            on(events, subscriptions, open, WorksiteRemovedEvent.class, e -> e.server() == server ? worksite(e.worksite()) : null, sink);
            on(events, subscriptions, open, WorkCompletedEvent.class, e -> local(e.worker(), server)
                    ? new TownsteadSignal(Kind.WORK_DONE, e.worker().level().dimension().location(), -1, e.uuid(), e.verb(), residence.apply(e.worker())) : null, sink);
            on(events, subscriptions, open, SettlementFoundedEvent.class, e -> e.server() == server
                    ? village(Kind.FOUNDED, e.founding().settlement(), null, String.valueOf(e.founding().profile())) : null, sink);
            politics(events, subscriptions, open, server, sink);
            on(events, subscriptions, open, VillagerBornEvent.class, e -> life(e.baby(), e.uuid(), server, "born", residence), sink);
            on(events, subscriptions, open, VillagerDiedEvent.class, e -> life(e.villager(), e.uuid(), server, "died", residence), sink);
            on(events, subscriptions, open, VillagerMarriedEvent.class, e -> life(e.partner(), e.uuid(), server, "married", residence), sink);
            on(events, subscriptions, open, VillagerLifeStageChangedEvent.class, e -> life(e.villager(), e.uuid(), server, "stage", residence), sink);
            on(events, subscriptions, open, VillagerRootChangedEvent.class, e -> life(e.entity(), e.uuid(), server, "root", residence), sink);
            on(events, subscriptions, open, VillagerProfessionChangedEvent.class, e -> life(e.villager(), e.uuid(), server, "profession", residence), sink);
            on(events, subscriptions, open, VillagerTierChangedEvent.class, e -> life(e.worker(), e.uuid(), server, "tier", residence), sink);
            on(events, subscriptions, open, ChronicleEventRecordedEvent.class, e -> e.server() == server && e.event().village().isPresent()
                    ? village(Kind.CHRONICLE, e.event().village().get(), null, e.event().templateId()) : null, sink);
        } catch (RuntimeException | LinkageError failure) {
            registration.close();
            throw failure;
        }
        return registration;
    }

    private static void politics(EventsApi events, List<Subscription> subscriptions, AtomicBoolean open, MinecraftServer server,
                                 Consumer<TownsteadSignal> sink) {
        on(events, subscriptions, open, FactionFoundedEvent.class, e -> e.server() == server ? faction(e.faction().id().toString()) : null, sink);
        on(events, subscriptions, open, FactionStatusChangedEvent.class, e -> e.server() == server ? faction(e.after().id().toString()) : null, sink);
        on(events, subscriptions, open, FactionKindChangedEvent.class, e -> e.server() == server ? faction(e.after().id().toString()) : null, sink);
        on(events, subscriptions, open, FactionIdentityChangedEvent.class, e -> e.server() == server ? faction(e.after().id().toString()) : null, sink);
        on(events, subscriptions, open, SettlementFactionChangedEvent.class, e -> e.server() == server
                ? village(Kind.POLITICS, e.settlement(), null, "settlement faction") : null, sink);
        on(events, subscriptions, open, BondChangedEvent.class, e -> e.server() == server ? faction("bond " + e.after().kind()) : null, sink);
        on(events, subscriptions, open, LegitimacyChangedEvent.class, e -> e.server() == server ? faction(e.faction().toString()) : null, sink);
        on(events, subscriptions, open, SeatDesignatedEvent.class, e -> e.server() == server ? village(Kind.POLITICS, e.seat().settlement(), null, "seat") : null, sink);
        on(events, subscriptions, open, SeatDamagedEvent.class, e -> e.server() == server ? village(Kind.POLITICS, e.seat().settlement(), null, "seat") : null, sink);
        on(events, subscriptions, open, SeatRepairedEvent.class, e -> e.server() == server ? village(Kind.POLITICS, e.seat().settlement(), null, "seat") : null, sink);
        on(events, subscriptions, open, SeatLostEvent.class, e -> e.server() == server ? village(Kind.POLITICS, e.seat().settlement(), null, "seat") : null, sink);
    }

    private static <E extends TownsteadEvent> void on(EventsApi events, List<Subscription> subscriptions, AtomicBoolean open, Class<E> type,
                                                     Function<E, TownsteadSignal> copy, Consumer<TownsteadSignal> sink) {
        subscriptions.add(events.subscribe(type, event -> {
            if (!open.get()) return;
            TownsteadSignal signal = copy.apply(event);
            if (signal != null) sink.accept(signal);
        }));
    }

    private static TownsteadSignal village(Kind kind, VillageId village, UUID subject, String detail) {
        return village == null ? null : new TownsteadSignal(kind, village.dimension(), village.villageId(), subject, detail);
    }

    private static TownsteadSignal worksite(WorksiteSnapshot worksite) {
        return worksite.village().map(v -> village(Kind.WORKSITES, v, null, worksite.name()))
                .orElse(new TownsteadSignal(Kind.WORKSITES, worksite.dimension(), -1, null, worksite.name()));
    }

    private static TownsteadSignal faction(String detail) {
        return new TownsteadSignal(Kind.POLITICS, null, -1, null, detail);
    }

    private static boolean local(Entity entity, MinecraftServer server) {
        return entity != null && entity.getServer() == server;
    }

    private static TownsteadSignal life(Entity entity, UUID uuid, MinecraftServer server, String detail, Function<Entity, UUID> residence) {
        return local(entity, server) ? new TownsteadSignal(Kind.LIFE, entity.level().dimension().location(), -1, uuid, detail, residence.apply(entity)) : null;
    }
}
