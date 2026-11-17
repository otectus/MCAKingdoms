package dev.otectus.mcakingdoms.compat.townstead;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Kingdoms-owned Townstead mapping layer (spec 12.3), read from {@code data/<namespace>/mcakingdoms_townstead/<kind>/}:
 * institution building rules, career prerequisites, culture and founding-profile hints, village-life suggestions,
 * heraldry fallback designs, Chronicle triggers and political reaction mappings. Each file is validated on its own: an
 * invalid one is skipped with a diagnostic and disables only its own content, the rest still apply, and nothing here
 * needs Townstead to load. Ids that only Townstead or the game registries can confirm (professions, skills, cultures,
 * banner patterns) are checked after each reload, once those registries are in place.
 */
public final class TownsteadMappings extends SimplePreparableReloadListener<Map<String, Map<ResourceLocation, JsonElement>>> {
    public static final TownsteadMappings INSTANCE = new TownsteadMappings();
    static final String ROOT = "mcakingdoms_townstead";
    static final List<String> KINDS = List.of("institutions", "careers", "culture_hints", "suggestions", "livery", "chronicle", "reactions");
    static final Set<String> TRIGGERS = Set.of(TownsteadChronicleBridge.INSTITUTION, TownsteadChronicleBridge.AGREEMENT, TownsteadChronicleBridge.PUBLIC_WORK);
    static final Set<String> REACTION_EVENTS = Set.of("standing_improved", "standing_worsened", "settlement_allegiance_changed");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final int LIMIT = 256;

    /** Extra building families (and a minimum tier) an institution charter accepts; a recognized building must be finished. */
    public record Institution(ResourceLocation id, String charter, Set<String> families, int minimumTier) {
    }

    public enum RequirementKind { PROFESSION_TIER, SKILL, ANY_PROFESSION }

    /** A career prerequisite: one profession at a tier, one skill, or any of an explicit list of professions at a tier. */
    public record Requirement(RequirementKind kind, List<String> professions, Optional<ResourceLocation> skill, int minimumTier) {
        public Requirement {
            professions = List.copyOf(professions);
        }
    }

    public record Career(ResourceLocation id, ResourceLocation organization, Optional<ResourceLocation> service, Requirement requires) {
    }

    public record CultureHint(ResourceLocation id, Optional<ResourceLocation> culture, Optional<ResourceLocation> foundingProfile, ResourceLocation kingdom) {
    }

    public enum Signal { HUNGER, THIRST, THERMAL, EXHAUSTION, LEARNING, SPIRIT_TIER }

    /**
     * A suggestion shown while {@code signal} stays at or above {@code enter} (a share of sampled residents, or a tier) and
     * hidden again below {@code exit}; it needs {@code minimumCoverage} of the roll known, readings no older than
     * {@code maximumAgeDays}, {@code sustain} consecutive readings, and waits {@code cooldownDays} after it is hidden.
     */
    public record Suggestion(ResourceLocation id, Signal signal, double enter, double exit, double minimumCoverage, int maximumAgeDays,
                             int sustain, int cooldownDays, Optional<String> task, String messageKey) {
    }

    /** A banner layer; {@code color} is a vanilla dye name, checked when read and turned into a dye when painted. */
    public record Layer(ResourceLocation pattern, String color) {
    }

    public record Livery(ResourceLocation id, ResourceLocation kingdom, String base, List<Layer> layers) {
        public Livery {
            layers = List.copyOf(layers);
        }
    }

    public record Snapshot(Map<String, Institution> institutions, List<Career> careers, Map<String, CultureHint> cultureHints,
                           List<Suggestion> suggestions, Map<ResourceLocation, Livery> livery, Set<String> triggers,
                           Map<String, ResourceLocation> reactions, List<String> problems, long revision) {
        public Snapshot {
            institutions = Map.copyOf(institutions);
            careers = List.copyOf(careers);
            cultureHints = Map.copyOf(cultureHints);
            suggestions = List.copyOf(suggestions);
            livery = Map.copyOf(livery);
            triggers = Set.copyOf(triggers);
            reactions = Map.copyOf(reactions);
            problems = List.copyOf(problems);
        }
    }

    private static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), Map.of(), List.of(), Map.of(), Set.of(), Map.of(), List.of(), 0);

    /**
     * The mappings in force and the provider checks made on exactly them, published together in one reference (AUD-13):
     * no reader sees new mappings with old checks, or mappings a later reload step rejected.
     */
    private record Active(Snapshot snapshot, List<String> providerProblems) { }

    private static volatile Active active = new Active(EMPTY, List.of());
    /** Mappings the last reload parsed, installed only when the whole reload is committed. */
    private volatile Snapshot pending;
    private static long revisions;

    private TownsteadMappings() {
    }

    public static Snapshot snapshot() {
        return active.snapshot();
    }

    /** Structural and provider-id problems of the mappings in force, for diagnostics; a rejected reload's are in its reload report. */
    public static List<String> problems() {
        Active now = active;
        List<String> out = new ArrayList<>(now.snapshot().problems());
        out.addAll(now.providerProblems());
        return List.copyOf(out);
    }

    /** Whether a reload's mappings wait for the reload to be committed. */
    public static boolean staged() {
        return INSTANCE.pending != null;
    }

    public static Optional<Institution> institution(String charter) {
        return Optional.ofNullable(active.snapshot().institutions().get(charter));
    }

    public static List<Career> careers(ResourceLocation organization, ResourceLocation service) {
        return active.snapshot().careers().stream().filter(c -> c.organization().equals(organization))
                .filter(c -> c.service().map(s -> s.equals(service)).orElse(true)).toList();
    }

    public static Optional<Livery> livery(ResourceLocation kingdom) {
        return Optional.ofNullable(active.snapshot().livery().get(kingdom));
    }

    public static boolean chronicleTrigger(String trigger) {
        return active.snapshot().triggers().contains(trigger);
    }

    public static Optional<ResourceLocation> reaction(String event) {
        return Optional.ofNullable(active.snapshot().reactions().get(event));
    }

    public static Optional<CultureHint> cultureHint(Optional<ResourceLocation> culture, Optional<ResourceLocation> profile) {
        if (profile.isPresent()) {
            var byProfile = active.snapshot().cultureHints().values().stream().filter(h -> h.foundingProfile().equals(profile)).findFirst();
            if (byProfile.isPresent()) return byProfile;
        }
        return culture.flatMap(c -> active.snapshot().cultureHints().values().stream().filter(h -> h.culture().equals(Optional.of(c))).findFirst());
    }

    @Override
    protected Map<String, Map<ResourceLocation, JsonElement>> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Map<ResourceLocation, JsonElement>> out = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (String kind : KINDS) {
            Map<ResourceLocation, JsonElement> files = new TreeMap<>();
            String folder = ROOT + "/" + kind;
            manager.listResources(folder, path -> path.getPath().endsWith(".json")).forEach((path, resource) -> {
                ResourceLocation id = new ResourceLocation(path.getNamespace(), path.getPath().substring(folder.length() + 1, path.getPath().length() - 5));
                try (Reader reader = resource.openAsReader()) {
                    files.put(id, GSON.fromJson(reader, JsonElement.class));
                } catch (Exception malformed) {
                    unreadable.add(file(kind, id) + ": not readable JSON (" + malformed.getMessage() + ")");
                }
            });
            out.put(kind, files);
        }
        out.put("#unreadable", unreadable.isEmpty() ? Map.of() : Map.of(new ResourceLocation("mcakingdoms", "unreadable"), GSON.toJsonTree(unreadable)));
        return out;
    }

    /**
     * Stages the reload's mappings (AUD-13). They become current in {@link #commitPending}, at the same successful-reload
     * boundary as every other definition domain; a reload a later step rejects never reaches it, so the mappings in force
     * stay the previous coherent generation.
     */
    @Override
    protected void apply(Map<String, Map<ResourceLocation, JsonElement>> files, ResourceManager manager, ProfilerFiller profiler) {
        Snapshot next = parse(files);
        pending = next;
        next.problems().forEach(problem -> LOGGER.warn("[MCA: Kingdoms] Townstead mapping skipped: {}", problem));
        ReloadReport.accepted(ReloadReport.Domain.TOWNSTEAD, Map.of("institution_rules", next.institutions().size(), "career_rules", next.careers().size(),
                "culture_hints", next.cultureHints().size(), "suggestions", next.suggestions().size(), "livery", next.livery().size(),
                "chronicle_triggers", next.triggers().size(), "skipped", next.problems().size()));
    }

    /** Parses one reload's files; pure, so the bundled and example packs are tested through it. */
    static Snapshot parse(Map<String, Map<ResourceLocation, JsonElement>> files) {
        List<String> problems = new ArrayList<>();
        Map<ResourceLocation, JsonElement> unreadable = files.getOrDefault("#unreadable", Map.of());
        unreadable.values().forEach(list -> list.getAsJsonArray().forEach(p -> problems.add(p.getAsString())));
        Map<String, Institution> institutions = new TreeMap<>();
        List<Career> careers = new ArrayList<>();
        Map<String, CultureHint> hints = new TreeMap<>();
        List<Suggestion> suggestions = new ArrayList<>();
        Map<ResourceLocation, Livery> livery = new TreeMap<>();
        Set<String> triggers = new TreeSet<>();
        Map<String, ResourceLocation> reactions = new TreeMap<>();
        for (String kind : KINDS) {
            var entries = files.getOrDefault(kind, Map.of());
            if (entries.size() > LIMIT) {
                problems.add(kind + ": " + entries.size() + " files found, more than the " + LIMIT + " allowed, so none of this kind is applied");
                continue;
            }
            for (var entry : entries.entrySet()) {
                try {
                    JsonObject json = object(entry.getValue());
                    schema(json);
                    switch (kind) {
                        case "institutions" -> {
                            fields(json, "schema", "charter", "families", "minimum_tier");
                            String charter = id(json, "charter").toString();
                            Set<String> families = new LinkedHashSet<>();
                            for (JsonElement family : array(json, "families", 1, 16)) {
                                String value = family.getAsString();
                                if (!value.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("families entries must be lower-case words: " + value);
                                families.add(value);
                            }
                            int tier = integer(json, "minimum_tier", 1, 1, 16);
                            if (institutions.putIfAbsent(charter, new Institution(entry.getKey(), charter, families, tier)) != null)
                                throw new IllegalArgumentException("charter " + charter + " already has a rule in another file");
                        }
                        case "careers" -> {
                            fields(json, "schema", "organization", "service", "requires");
                            ResourceLocation organization = id(json, "organization");
                            Optional<ResourceLocation> service = json.has("service") ? Optional.of(id(json, "service")) : Optional.empty();
                            careers.add(new Career(entry.getKey(), organization, service, requirement(object(json.get("requires")))));
                        }
                        case "culture_hints" -> {
                            fields(json, "schema", "culture", "founding_profile", "kingdom");
                            Optional<ResourceLocation> culture = json.has("culture") ? Optional.of(id(json, "culture")) : Optional.empty();
                            Optional<ResourceLocation> profile = json.has("founding_profile") ? Optional.of(id(json, "founding_profile")) : Optional.empty();
                            if (culture.isEmpty() && profile.isEmpty()) throw new IllegalArgumentException("a hint names a culture, a founding_profile or both");
                            hints.put(entry.getKey().toString(), new CultureHint(entry.getKey(), culture, profile, id(json, "kingdom")));
                        }
                        case "suggestions" -> {
                            fields(json, "schema", "signal", "enter", "exit", "minimum_coverage", "maximum_age_days", "sustain", "cooldown_days", "task", "message");
                            Signal signal = enumValue(Signal.class, json, "signal");
                            double enter = decimal(json, "enter", 0.0, 1_000.0);
                            double exit = json.has("exit") ? decimal(json, "exit", 0.0, 1_000.0) : enter;
                            if (exit > enter) throw new IllegalArgumentException("exit must not be above enter (hysteresis)");
                            String message = string(json, "message");
                            if (!message.matches("[a-z0-9_.]{1,128}")) throw new IllegalArgumentException("message must be a translation key");
                            Optional<String> task = json.has("task") ? Optional.of(string(json, "task")) : Optional.empty();
                            if (task.isPresent() && !task.get().matches("[a-z0-9_.]{1,64}")) throw new IllegalArgumentException("task must be a Kingdom Tasks id");
                            suggestions.add(new Suggestion(entry.getKey(), signal, enter, exit, json.has("minimum_coverage") ? decimal(json, "minimum_coverage", 0.0, 1.0) : 0.5,
                                    integer(json, "maximum_age_days", 3, 0, 365), integer(json, "sustain", 2, 1, 64), integer(json, "cooldown_days", 1, 0, 365), task, message));
                        }
                        case "livery" -> {
                            fields(json, "schema", "kingdom", "base", "patterns");
                            ResourceLocation kingdom = id(json, "kingdom");
                            String base = dye(string(json, "base"));
                            List<Layer> layers = new ArrayList<>();
                            for (JsonElement layer : array(json, "patterns", 0, 6)) {
                                JsonObject l = object(layer);
                                fields(l, "pattern", "color");
                                layers.add(new Layer(id(l, "pattern"), dye(string(l, "color"))));
                            }
                            if (livery.putIfAbsent(kingdom, new Livery(entry.getKey(), kingdom, base, layers)) != null)
                                throw new IllegalArgumentException("kingdom " + kingdom + " already has a design in another file");
                        }
                        case "chronicle" -> {
                            fields(json, "schema", "trigger", "enabled");
                            String trigger = id(json, "trigger").toString();
                            if (!TRIGGERS.contains(trigger)) throw new IllegalArgumentException("unknown trigger " + trigger + "; known: " + new TreeSet<>(TRIGGERS));
                            if (!json.has("enabled") || json.get("enabled").getAsBoolean()) triggers.add(trigger); else triggers.remove(trigger);
                        }
                        case "reactions" -> {
                            fields(json, "schema", "event", "reaction");
                            String event = string(json, "event");
                            if (!REACTION_EVENTS.contains(event)) throw new IllegalArgumentException("unknown event " + event + "; known: " + new TreeSet<>(REACTION_EVENTS));
                            reactions.put(event, id(json, "reaction"));
                        }
                        default -> throw new IllegalStateException(kind);
                    }
                } catch (RuntimeException invalid) {
                    problems.add(file(kind, entry.getKey()) + ": " + invalid.getMessage());
                }
            }
        }
        long revision;
        synchronized (TownsteadMappings.class) { revision = ++revisions; }
        return new Snapshot(institutions, careers, hints, suggestions, livery, triggers, reactions, problems, revision);
    }

    private static Requirement requirement(JsonObject json) {
        fields(json, "profession", "professions", "skill", "minimum_tier");
        if (json.has("skill")) {
            if (json.has("profession") || json.has("professions")) throw new IllegalArgumentException("requires names a skill or professions, not both");
            if (json.has("minimum_tier")) throw new IllegalArgumentException("minimum_tier applies to professions; a skill is learned or not");
            return new Requirement(RequirementKind.SKILL, List.of(), Optional.of(id(json, "skill")), 1);
        }
        int tier = integer(json, "minimum_tier", 1, 1, 64);
        if (json.has("profession") == json.has("professions")) throw new IllegalArgumentException("requires names exactly one of profession, professions or skill");
        if (json.has("profession")) return new Requirement(RequirementKind.PROFESSION_TIER, List.of(id(json, "profession").toString()), Optional.empty(), tier);
        List<String> professions = new ArrayList<>();
        for (JsonElement value : array(json, "professions", 1, 16)) professions.add(parse(value.getAsString(), "professions").toString());
        return new Requirement(RequirementKind.ANY_PROFESSION, professions, Optional.empty(), tier);
    }

    // ---- provider-id checks after a reload (Townstead and the game registries are in place then) ----

    /**
     * Installs the mappings the last reload staged, checked against the installed providers first, as one generation
     * (AUD-13). Called once every domain of the reload prepared ({@code serverAboutToStart} and the reload's datapack
     * sync); {@code server} may be null or not yet attached, and then registries are not read until server start.
     */
    public void commitPending(MinecraftServer server) {
        Snapshot next = pending;
        if (next == null) return;
        pending = null;
        active = validated(next, server);
    }

    /** Forgets a reload's staged mappings (a server stopped before it committed). */
    public void clearPending() {
        pending = null;
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        validate(event.getServer());
    }

    /** Checks the mappings in force again against the installed providers (at server start, once Townstead is attached). */
    static void validate(MinecraftServer server) {
        active = validated(active.snapshot(), server);
    }

    /** Test seam: installs mappings as a committed reload would, without a server. */
    static void installForTests(Snapshot snapshot) {
        active = new Active(snapshot, List.of());
    }

    /** {@code staged} without the entries whose provider ids the installed Townstead or game does not know, with a diagnostic each. */
    private static Active validated(Snapshot staged, MinecraftServer server) {
        if (server == null) return new Active(staged, List.of());
        Snapshot current = staged;
        List<String> problems = new ArrayList<>();
        var backend = TownsteadRuntime.session(server).map(s -> s.service().backendImpl());
        // An unreadable registry checks nothing (the entry is kept and evaluated, failing closed, when used); a readable one
        // is authoritative even when empty.
        Optional<Set<String>> professions = backend.flatMap(b -> b.registryIds(TownsteadBackend.Registry.PROFESSIONS)).map(Set::copyOf);
        Optional<Set<String>> skills = backend.flatMap(b -> b.registryIds(TownsteadBackend.Registry.SKILLS)).map(Set::copyOf);
        Optional<Set<String>> cultures = backend.flatMap(b -> b.registryIds(TownsteadBackend.Registry.CULTURES)).map(Set::copyOf);
        Optional<Set<String>> profiles = backend.flatMap(b -> b.registryIds(TownsteadBackend.Registry.FOUNDING_PROFILES)).map(Set::copyOf);
        List<Career> careers = new ArrayList<>();
        for (Career career : current.careers()) {
            String missing = "";
            if (professions.isPresent()) {
                for (String profession : career.requires().professions())
                    if (backend.get().profession(profession).isEmpty()) missing = "profession " + profession;
            }
            if (skills.isPresent() && career.requires().skill().isPresent() && !skills.get().contains(career.requires().skill().get().toString()))
                missing = "skill " + career.requires().skill().get();
            if (!missing.isEmpty()) problems.add(file("careers", career.id()) + ": Townstead does not know " + missing + "; this prerequisite is not applied");
            else careers.add(career);
        }
        Map<String, CultureHint> hints = new TreeMap<>();
        current.cultureHints().forEach((key, hint) -> {
            boolean unknown = cultures.isPresent() && hint.culture().filter(c -> !cultures.get().contains(c.toString())).isPresent()
                    || profiles.isPresent() && hint.foundingProfile().filter(p -> !profiles.get().contains(p.toString())).isPresent();
            if (unknown) problems.add(file("culture_hints", hint.id()) + ": Townstead does not know its culture or founding profile; the hint is not applied");
            else hints.put(key, hint);
        });
        Map<ResourceLocation, Livery> livery = new TreeMap<>();
        current.livery().forEach((kingdom, design) -> {
            var unknown = design.layers().stream().map(Layer::pattern).filter(p -> !BuiltInRegistries.BANNER_PATTERN.containsKey(p)).findFirst();
            if (unknown.isPresent()) problems.add(file("livery", design.id()) + ": unknown banner pattern " + unknown.get() + "; the design is not applied");
            else livery.put(kingdom, design);
        });
        problems.forEach(problem -> LOGGER.warn("[MCA: Kingdoms] Townstead mapping disabled: {}", problem));
        return new Active(new Snapshot(current.institutions(), careers, hints, current.suggestions(), livery, current.triggers(), current.reactions(),
                current.problems(), current.revision()), List.copyOf(problems));
    }

    // ---- JSON helpers ----

    private static String file(String kind, ResourceLocation id) {
        return "data/" + id.getNamespace() + "/" + ROOT + "/" + kind + "/" + id.getPath() + ".json";
    }

    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("expected a JSON object");
        return element.getAsJsonObject();
    }

    private static void schema(JsonObject json) {
        if (!json.has("schema")) return;
        if (!json.get("schema").isJsonPrimitive() || json.get("schema").getAsInt() != 1) throw new IllegalArgumentException("unsupported schema " + json.get("schema") + "; this version reads schema 1");
    }

    private static void fields(JsonObject json, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String key : json.keySet()) if (!names.contains(key)) throw new IllegalArgumentException("unknown field " + key + "; allowed: " + new TreeSet<>(names));
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException(key + " must be a non-empty string");
        return value.getAsString();
    }

    private static ResourceLocation id(JsonObject json, String key) {
        return parse(string(json, key), key);
    }

    private static ResourceLocation parse(String value, String key) {
        ResourceLocation id = ResourceLocation.tryParse(value.contains(":") ? value : "minecraft:" + value);
        if (id == null) throw new IllegalArgumentException(key + " is not a valid id: " + value);
        return id;
    }

    private static List<JsonElement> array(JsonObject json, String key, int min, int max) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException(key + " must be a list");
        List<JsonElement> out = new ArrayList<>();
        value.getAsJsonArray().forEach(out::add);
        if (out.size() < min || out.size() > max) throw new IllegalArgumentException(key + " must have " + min + " to " + max + " entries");
        return out;
    }

    private static int integer(JsonObject json, String key, int fallback, int min, int max) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a number");
        int number = value.getAsInt();
        if (number < min || number > max || value.getAsDouble() != number) throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + max);
        return number;
    }

    private static double decimal(JsonObject json, String key, double min, double max) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a number");
        double number = value.getAsDouble();
        if (number < min || number > max || Double.isNaN(number)) throw new IllegalArgumentException(key + " must be from " + min + " to " + max);
        return number;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonObject json, String key) {
        String value = string(json, key);
        // Unrecognized words are refused, never read as the first constant (spec 3.1).
        for (E constant : type.getEnumConstants()) if (constant.name().equalsIgnoreCase(value)) return constant;
        throw new IllegalArgumentException(key + " " + value + " is not one of " + java.util.Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT));
    }

    /** The sixteen vanilla dye names; read without touching {@code DyeColor}, which needs the game bootstrapped. */
    static final Set<String> DYES = Set.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black");

    private static String dye(String value) {
        if (!DYES.contains(value)) throw new IllegalArgumentException(value + " is not a dye colour");
        return value;
    }
}
