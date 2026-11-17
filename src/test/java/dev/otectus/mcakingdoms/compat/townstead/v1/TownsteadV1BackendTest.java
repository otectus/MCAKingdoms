package dev.otectus.mcakingdoms.compat.townstead.v1;

import com.aetherianartificer.townstead.api.impl.v1.TownsteadApiV1Impl;
import com.aetherianartificer.townstead.api.v1.event.Subscription;
import com.aetherianartificer.townstead.api.v1.event.TownsteadEvent;
import com.aetherianartificer.townstead.api.v1.event.VillagerVillageChangedEvent;
import com.aetherianartificer.townstead.api.v1.model.NeedLevel;
import com.aetherianartificer.townstead.api.v1.model.NeedsSnapshot;
import com.aetherianartificer.townstead.api.v1.model.ReactionCause;
import com.aetherianartificer.townstead.api.v1.model.VillageId;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadNeedsView;
import dev.otectus.mcakingdoms.api.townstead.TownsteadVillagerNeedsView;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadBackend;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadSignal;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The api.v1 backend against a fake Townstead implementation: binding, revisions, conversions and the event lifecycle. */
class TownsteadV1BackendTest {
    @BeforeEach
    void reset() {
        TownsteadApiV1Impl.reset();
        TownsteadApiV1Impl.ANSWERS.put("SettingsApi#systems", args -> List.of("careers", "politics", "reactions", "chronicles", "work"));
    }

    @AfterEach
    void clear() {
        TownsteadApiV1Impl.reset();
    }

    private static TownsteadV1Backend bound() {
        Object bound = TownsteadV1Backend.bind();
        assertInstanceOf(TownsteadV1Backend.class, bound, String.valueOf(bound));
        return (TownsteadV1Backend) bound;
    }

    @Test
    void generationOneRevisionTwoBindsEveryOfferedCapability() {
        TownsteadV1Backend backend = bound();
        assertEquals("v1", backend.id());
        assertEquals(1, backend.info().generation());
        assertEquals(2, backend.info().revision());
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            if (capability == TownsteadCapability.POLITICAL_DELEGATION) {
                assertFalse(backend.capabilities().contains(capability), "Townstead 0.8.0 has no external-government contract");
                assertTrue(backend.diagnostics().get(capability).contains("no contract"));
            } else {
                assertTrue(backend.capabilities().contains(capability), capability + ": " + backend.diagnostics().get(capability));
            }
        }
    }

    @Test
    void anUnknownGenerationIsRefusedSoTheLegacyFacadeIsUsed() {
        TownsteadApiV1Impl.generation = 2;
        Object bound = TownsteadV1Backend.bind();
        assertInstanceOf(String.class, bound);
        assertTrue(((String) bound).contains("generation 2"));
    }

    @Test
    void revisionTwoFacadesThatAreEmptyLeaveSettingsAndPoliticsUnbound() {
        TownsteadApiV1Impl.revision = 1;
        TownsteadApiV1Impl.emptyRevisionTwoFacades = true;
        TownsteadV1Backend backend = bound();
        assertFalse(backend.capabilities().contains(TownsteadCapability.READ_SETTINGS));
        assertFalse(backend.capabilities().contains(TownsteadCapability.READ_POLITICS));
        assertTrue(backend.diagnostics().get(TownsteadCapability.READ_POLITICS).contains("revision 1"));
        assertTrue(backend.capabilities().contains(TownsteadCapability.READ_BUILDINGS), "revision 1 facades still bind");
    }

    @Test
    void anUnknownSystemIsNeverAssumedOn() {
        TownsteadApiV1Impl.ANSWERS.put("SettingsApi#systemEnabled", args -> true);
        TownsteadV1Backend backend = bound();
        assertEquals(Optional.of(true), backend.systemEnabled("careers"));
        // Townstead answers true for an id it does not know; the backend does not pass that on.
        assertEquals(Optional.empty(), backend.systemEnabled("needs"));
    }

    @Test
    void reactionsAreFiredAsContextCausesWithTheirTagsNeverAsForcedCommands() {
        ReactionCause cause = TownsteadV1Backend.cause(Optional.empty(), net.minecraft.core.BlockPos.ZERO, Set.of("context:faction", "kingdom:mcakingdoms:serenum"));
        assertEquals("context", cause.source(), "a command cause would bypass Townstead's cooldown, lock and chance gates");
        assertEquals(Optional.of(net.minecraft.core.BlockPos.ZERO), cause.location());
        assertEquals(Set.of("context:faction", "kingdom:mcakingdoms:serenum"), cause.contextTags());
        assertNotEquals(ReactionCause.forced().source(), cause.source());
    }

    @Test
    void anEmptyEmissionIsRecordedAsNoStoryAndAPresentOneAsAStory() {
        TownsteadV1Backend backend = bound();
        TownsteadApiV1Impl.ANSWERS.put("ChroniclesApi#emit", args -> OptionalLong.empty());
        assertEquals(TownsteadBackend.Emission.NO_STORY, backend.emit(null, null, "mcakingdoms:institution_recognized", 1F, Map.of()));
        TownsteadApiV1Impl.ANSWERS.put("ChroniclesApi#emit", args -> OptionalLong.of(7L));
        assertEquals(TownsteadBackend.Emission.STORY, backend.emit(null, null, "mcakingdoms:institution_recognized", 1F, Map.of()));
        assertEquals(2, TownsteadApiV1Impl.CALLS.stream().filter("ChroniclesApi#emit"::equals).count(), "one call per emission, never a retry");
        assertFalse(TownsteadApiV1Impl.CALLS.contains("ChroniclesApi#addCounter"), "the moment passed to emit is never counted again");
    }

    @Test
    void aFailingCallDisablesOnlyItsCapability() {
        TownsteadV1Backend backend = bound();
        TownsteadApiV1Impl.ANSWERS.put("ProfessionsApi#profession", args -> { throw new IllegalStateException("boom"); });
        assertEquals(Optional.empty(), backend.profession("minecraft:librarian"));
        assertFalse(backend.capabilities().contains(TownsteadCapability.READ_CAREERS));
        assertTrue(backend.capabilities().contains(TownsteadCapability.READ_BUILDINGS));
    }

    @Test
    void subscriptionsAreAllClosedAndAClosedOneDeliversNothing() {
        List<Consumer<TownsteadEvent>> listeners = new ArrayList<>();
        List<Boolean> closed = new ArrayList<>();
        TownsteadApiV1Impl.ANSWERS.put("EventsApi#subscribe", args -> {
            @SuppressWarnings("unchecked") Consumer<TownsteadEvent> listener = (Consumer<TownsteadEvent>) args[1];
            Class<?> type = (Class<?>) args[0];
            listeners.add(event -> { if (type.isInstance(event)) listener.accept(event); });
            int index = closed.size();
            closed.add(false);
            return new Subscription() {
                @Override public boolean isActive() { return !closed.get(index); }
                @Override public void close() { closed.set(index, true); }
            };
        });
        TownsteadV1Backend backend = bound();
        List<TownsteadSignal> signals = new ArrayList<>();
        var registration = backend.subscribe(null, signals::add, entity -> null);
        assertTrue(listeners.size() > 20, "every relevant event type is subscribed through events().subscribe");
        VillageId village = new VillageId(new ResourceLocation("minecraft:overworld"), 3);
        listeners.forEach(l -> l.accept(new VillagerVillageChangedEvent(UUID.randomUUID(), "Ada", Optional.empty(), village)));
        assertEquals(1, signals.size());
        assertEquals(TownsteadSignal.Kind.RESIDENT_HOME, signals.get(0).kind());
        assertEquals(3, signals.get(0).villageId());
        registration.close();
        assertTrue(closed.stream().allMatch(c -> c), "detach closes every subscription");
        listeners.forEach(l -> l.accept(new VillagerVillageChangedEvent(UUID.randomUUID(), "Bo", Optional.empty(), village)));
        assertEquals(1, signals.size(), "nothing is delivered after detach");
        registration.close();
    }

    @Test
    void energyBecomesFatigueOnTheLegacyViewAndDisabledNeedsAreNotCrises() {
        Map<String, NeedLevel> levels = new LinkedHashMap<>();
        levels.put(NeedsSnapshot.ENERGY, new NeedLevel(NeedsSnapshot.ENERGY, 17, 0, 20, 17, "rested", false, true));
        levels.put(NeedsSnapshot.THIRST, new NeedLevel(NeedsSnapshot.THIRST, 0, 0, 20, 14, "dehydrated", true, false));
        levels.put(NeedsSnapshot.TEMPERATURE, new NeedLevel(NeedsSnapshot.TEMPERATURE, 420, 0, 600, 370, "sweltering", true, true));
        NeedsSnapshot snapshot = new NeedsSnapshot(14, 2F, 0F, 0, 0, 0F, 15, false, false, 420, 330, levels);
        TownsteadNeedsView legacy = TownsteadV1Backend.legacyNeeds(snapshot);
        assertEquals(5, legacy.fatigue(), "fatigue is the energy scale read the other way up");
        TownsteadVillagerNeedsView view = TownsteadV1Backend.needsView(snapshot);
        assertTrue(view.inCrisis(), "a hot villager in Townstead's thermal crisis band is in crisis");
        assertEquals(Optional.of(NeedsSnapshot.TEMPERATURE), view.primaryNeed(), "heat is distress, never prosperity");
        assertFalse(view.enabled(NeedsSnapshot.THIRST));
        assertEquals(420, view.bodyTemperatureTenths().getAsInt());
        levels.put(NeedsSnapshot.TEMPERATURE, new NeedLevel(NeedsSnapshot.TEMPERATURE, 370, 0, 600, 370, "comfortable", false, true));
        TownsteadVillagerNeedsView calm = TownsteadV1Backend.needsView(new NeedsSnapshot(14, 2F, 0F, 0, 0, 0F, 15, false, false, 370, 330, levels));
        assertFalse(calm.inCrisis(), "a disabled thirst need in its crisis band is not a crisis");
    }
}
