package dev.otectus.mcakingdoms.compat.capitals;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.*;

/**
 * A read-only, capability-checked view of MCA Capitals (C1). Capitals publishes no API for other mods, so this reads
 * the public accessors of its capital records by name, after checking that every one it needs exists with the expected
 * type; anything else makes the bridge {@link Availability#UNSUPPORTED} with the reason, never an error. It never
 * writes: Capitals has no public operation to delegate to (docs/Capitals-Interoperability.md), so every crown action of
 * a kingdom Capitals governs stays with Capitals. Nothing of Capitals is loaded unless the mod is installed.
 */
public final class CapitalsBridge {
    public static final String MOD_ID = "mcacapitals";
    /** The Capitals builds this bridge was tested against: 1.1.1 is the last for MCA 7.6.x; 1.3.7 needs MCA 7.7.1 alphas. */
    public static final Set<String> TESTED_VERSIONS = Set.of("1.1.1", "1.3.7");
    /** The newest tested build, named in diagnostics. */
    public static final String TESTED_VERSION = "1.3.7";
    private static final String ROOT = "com.majesttyx.mcacapitals.";
    private static final int LIMIT = 256;

    public enum Availability { ABSENT, AVAILABLE, UNSUPPORTED }

    /** What the installed Capitals offers: its version, the readable facets, and why it is unsupported when it is. */
    public record Capabilities(Availability availability, String version, boolean tested, Set<String> facets, String diagnostic) {
        public Capabilities { facets = Set.copyOf(facets); }
        public boolean readable() { return availability == Availability.AVAILABLE; }
    }

    /** One capital as Capitals records it, reduced to the facts Kingdoms may show or map. */
    public record CapitalView(UUID id, ResourceLocation dimension, int villageId, String state, UUID sovereign, boolean playerSovereign,
                              UUID consort, UUID heir, List<UUID> succession, Set<UUID> dukes, Set<UUID> lords, Set<UUID> knights,
                              boolean mourning, boolean monarchyRejected) {
        public CapitalView {
            Objects.requireNonNull(id); Objects.requireNonNull(dimension); state = state == null ? "" : state;
            succession = List.copyOf(succession.subList(0, Math.min(succession.size(), 16)));
            dukes = Set.copyOf(dukes); lords = Set.copyOf(lords); knights = Set.copyOf(knights);
        }
        /** A capital that has a crown to govern: founded or active, and not one whose people refused the monarchy. */
        public boolean governing() { return !monarchyRejected && (state.equals("FOUNDED") || state.equals("ACTIVE")); }
    }

    private static final String DIMENSION = "getVillageDimensionId";
    private static final List<String> ACCESSORS = List.of("getCapitalId", "getVillageId", "getState", "getSovereign",
            "isPlayerSovereign", "getConsort", "getHeir", "getRoyalSuccessionOrder", "getDukes", "getLords", "getKnights", "isMourningActive", "isMonarchyRejected");
    private static volatile Capabilities probed;
    /** Test seam: a fixed list of capitals instead of reading Capitals (GameTests run without it). */
    static volatile java.util.function.Function<MinecraftServer, List<CapitalView>> testSource;
    private static volatile Method snapshot;
    private static volatile Map<String, Method> accessors = Map.of();

    private CapitalsBridge() {
    }

    public static boolean installed() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    /** The probe result, computed once per run: Capitals cannot be added or removed without a restart. */
    public static Capabilities capabilities() {
        Capabilities known = probed;
        if (known != null) return known;
        synchronized (CapitalsBridge.class) {
            if (probed == null) probed = probe();
            return probed;
        }
    }

    private static Capabilities probe() {
        if (!installed()) return new Capabilities(Availability.ABSENT, "", false, Set.of(), "");
        String version = ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        try {
            Class<?> manager = Class.forName(ROOT + "capital.CapitalManager", false, CapitalsBridge.class.getClassLoader());
            Class<?> record = Class.forName(ROOT + "capital.CapitalRecord", false, CapitalsBridge.class.getClassLoader());
            Method all = manager.getMethod("getAllCapitalsSnapshot");
            if (!Map.class.isAssignableFrom(all.getReturnType()) || !java.lang.reflect.Modifier.isStatic(all.getModifiers()))
                return unsupported(version, "CapitalManager.getAllCapitalsSnapshot() is not a static map");
            Map<String, Method> found = new LinkedHashMap<>();
            for (String name : ACCESSORS) {
                Method method = record.getMethod(name);
                Class<?> type = method.getReturnType();
                boolean expected = switch (name) {
                    case "getCapitalId", "getSovereign", "getConsort", "getHeir" -> type == UUID.class;
                    case "getVillageId" -> type == Integer.class || type == int.class;
                    case "getVillageDimensionId" -> type == String.class;
                    case "getState" -> type.isEnum();
                    case "getRoyalSuccessionOrder" -> List.class.isAssignableFrom(type);
                    case "getDukes", "getLords", "getKnights" -> Set.class.isAssignableFrom(type);
                    default -> type == boolean.class;
                };
                if (!expected) return unsupported(version, "CapitalRecord." + name + " returns " + type.getSimpleName());
                found.put(name, method);
            }
            // Builds before 1.2 record no village dimension: their capitals are overworld villages.
            boolean dimensions = false;
            try { Method dimension = record.getMethod(DIMENSION); if (dimension.getReturnType() == String.class) { found.put(DIMENSION, dimension); dimensions = true; } }
            catch (NoSuchMethodException older) { }
            snapshot = all;
            accessors = Map.copyOf(found);
            boolean tested = TESTED_VERSIONS.contains(version);
            String note = (tested ? "" : "read accessors match; this version was not tested (tested: " + String.join(", ", new TreeSet<>(TESTED_VERSIONS)) + ")")
                    + (dimensions ? "" : (tested ? "" : "; ") + "this build records no village dimension, so its capitals are read as overworld villages");
            return new Capabilities(Availability.AVAILABLE, version, tested, Set.of("read.capitals", "read.court", "read.succession"), note);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return unsupported(version, failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
    }

    private static Capabilities unsupported(String version, String reason) {
        com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] MCA Capitals {} cannot be read ({}); every crown action is kept read-only", version, reason);
        return new Capabilities(Availability.UNSUPPORTED, version, false, Set.of(), reason);
    }

    /**
     * Every capital Capitals records, at most {@value #LIMIT}, in id order; empty when Capitals is absent or unreadable.
     * Must run on the server thread, where Capitals changes its records.
     */
    public static List<CapitalView> capitals(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Capitals reads require the server thread");
        if (!capabilities().readable()) return List.of();
        if (testSource != null) return testSource.apply(server);
        try {
            Map<?, ?> all = (Map<?, ?>) snapshot.invoke(null);
            var out = new ArrayList<CapitalView>();
            for (Object record : all.values()) {
                if (out.size() >= LIMIT) break;
                var view = view(record);
                if (view != null) out.add(view);
            }
            out.sort(Comparator.comparing(CapitalView::id));
            return List.copyOf(out);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] Reading MCA Capitals failed; its capitals are treated as unknown this pass", failure);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static CapitalView view(Object record) throws ReflectiveOperationException {
        UUID id = (UUID) get(record, "getCapitalId");
        Object village = get(record, "getVillageId");
        ResourceLocation dimension = accessors.containsKey(DIMENSION) ? ResourceLocation.tryParse(String.valueOf(get(record, DIMENSION))) : new ResourceLocation("minecraft", "overworld");
        if (id == null || village == null || dimension == null) return null;
        return new CapitalView(id, dimension, ((Number) village).intValue(), String.valueOf(get(record, "getState")),
                (UUID) get(record, "getSovereign"), (boolean) get(record, "isPlayerSovereign"), (UUID) get(record, "getConsort"), (UUID) get(record, "getHeir"),
                uuids((Collection<Object>) get(record, "getRoyalSuccessionOrder")).stream().toList(),
                uuids((Collection<Object>) get(record, "getDukes")), uuids((Collection<Object>) get(record, "getLords")), uuids((Collection<Object>) get(record, "getKnights")),
                (boolean) get(record, "isMourningActive"), (boolean) get(record, "isMonarchyRejected"));
    }

    private static Object get(Object record, String name) throws ReflectiveOperationException {
        return accessors.get(name).invoke(record);
    }

    private static LinkedHashSet<UUID> uuids(Collection<Object> values) {
        var out = new LinkedHashSet<UUID>();
        if (values != null) for (Object value : values) if (value instanceof UUID id && out.size() < 64) out.add(id);
        return out;
    }

    /** Test hook: forget the probe so a test can install a fake answer. */
    static void reset(Capabilities value) {
        probed = value;
    }
}
