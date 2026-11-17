package com.aetherianartificer.townstead.api.impl.v1;

import com.aetherianartificer.townstead.api.v1.CalendarApi;
import com.aetherianartificer.townstead.api.v1.ChroniclesApi;
import com.aetherianartificer.townstead.api.v1.EventsApi;
import com.aetherianartificer.townstead.api.v1.HangoutsApi;
import com.aetherianartificer.townstead.api.v1.PoliticsApi;
import com.aetherianartificer.townstead.api.v1.ProfessionsApi;
import com.aetherianartificer.townstead.api.v1.RegistriesApi;
import com.aetherianartificer.townstead.api.v1.SchedulesApi;
import com.aetherianartificer.townstead.api.v1.SettingsApi;
import com.aetherianartificer.townstead.api.v1.SocialApi;
import com.aetherianartificer.townstead.api.v1.TownsteadApiV1;
import com.aetherianartificer.townstead.api.v1.VillagersApi;
import com.aetherianartificer.townstead.api.v1.VillagesApi;
import com.aetherianartificer.townstead.api.v1.WorkApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A test stand-in for Townstead's implementation, under the exact name {@code TownsteadApiV1.get()} loads, so the real
 * {@code TownsteadV1Backend} binds against it on the unit-test classpath. Every facade is a generated fake whose answers a test
 * sets in {@link #ANSWERS} ({@code "Facade#method"} to a function of the arguments); unset methods return empty values.
 * Every call is recorded in {@link #CALLS}. Never on any run classpath.
 */
public final class TownsteadApiV1Impl implements TownsteadApiV1 {
    public static volatile int generation = 1;
    public static volatile int revision = 2;
    public static volatile boolean emptyRevisionTwoFacades;
    public static final Map<String, Function<Object[], Object>> ANSWERS = new ConcurrentHashMap<>();
    public static final List<String> CALLS = java.util.Collections.synchronizedList(new ArrayList<>());
    public static final List<Object[]> ARGUMENTS = java.util.Collections.synchronizedList(new ArrayList<>());

    public static void reset() {
        generation = 1;
        revision = 2;
        emptyRevisionTwoFacades = false;
        ANSWERS.clear();
        CALLS.clear();
        ARGUMENTS.clear();
    }

    /** The generated fakes call this for every facade method. */
    static Object answer(String key, Object[] args, Class<?> type) {
        CALLS.add(key);
        ARGUMENTS.add(args.clone());
        Function<Object[], Object> answer = ANSWERS.get(key);
        if (answer != null) return answer.apply(args);
        return empty(type);
    }

    private static Object empty(Class<?> type) {
        if (type == Optional.class) return Optional.empty();
        if (type == OptionalInt.class) return OptionalInt.empty();
        if (type == OptionalLong.class) return OptionalLong.empty();
        if (type == List.class) return List.of();
        if (type == Set.class) return Set.of();
        if (type == Map.class) return Map.of();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private final VillagersApi villagers = new Fakes.VillagersApiFake();
    private final VillagesApi villages = new Fakes.VillagesApiFake();
    private final ProfessionsApi professions = new Fakes.ProfessionsApiFake();
    private final CalendarApi calendar = new Fakes.CalendarApiFake();
    private final SocialApi social = new Fakes.SocialApiFake();
    private final ChroniclesApi chronicles = new Fakes.ChroniclesApiFake();
    private final EventsApi events = new Fakes.EventsApiFake();
    private final WorkApi work = new Fakes.WorkApiFake();
    private final HangoutsApi hangouts = new Fakes.HangoutsApiFake();
    private final SchedulesApi schedules = new Fakes.SchedulesApiFake();
    private final RegistriesApi registries = new Fakes.RegistriesApiFake();
    private final PoliticsApi politics = new Fakes.PoliticsApiFake();
    private final SettingsApi settings = new Fakes.SettingsApiFake();

    @Override public int getApiVersion() { return generation; }
    @Override public int getApiRevision() { return revision; }
    @Override public String getModVersion() { return "0.8.0+1.20.1"; }
    @Override public VillagersApi villagers() { return villagers; }
    @Override public VillagesApi villages() { return villages; }
    @Override public ProfessionsApi professions() { return professions; }
    @Override public CalendarApi calendar() { return calendar; }
    @Override public SocialApi social() { return social; }
    @Override public ChroniclesApi chronicles() { return chronicles; }
    @Override public EventsApi events() { return events; }
    @Override public WorkApi work() { return work; }
    @Override public HangoutsApi hangouts() { return hangouts; }
    @Override public SchedulesApi schedules() { return schedules; }
    @Override public RegistriesApi registries() { return registries; }
    @Override public PoliticsApi politics() { return emptyRevisionTwoFacades ? PoliticsApi.EMPTY : politics; }
    @Override public SettingsApi settings() { return emptyRevisionTwoFacades ? SettingsApi.EMPTY : settings; }
}
