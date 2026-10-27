package dev.otectus.mcakingdoms.worldcontext;

import com.google.gson.*;
import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

/**
 * Data-pack selectors. Invalid reloads retain the last complete snapshot; the rejection, naming each file and field,
 * is recorded in the reload report so a reload is never reported as complete while these selectors stayed behind.
 */
public final class WorldContextDefinitions extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "warfare/world";
    private static volatile Snapshot current = new Snapshot(Map.of(), Map.of());
    /** Files that could not be read as JSON during the current reload; vanilla would silently skip them. */
    private volatile List<String> unreadable = List.of();
    public WorldContextDefinitions() { super(GSON, DIRECTORY); }

    public record EncounterRule(String role, int repeatBudget, int contributionWindow) { }
    public record Snapshot(Map<ResourceLocation, String> structures,
                           Map<ResourceLocation, EncounterRule> encounters) {
        public Snapshot { structures = Map.copyOf(structures); encounters = Map.copyOf(encounters); }
    }
    public static Snapshot get() { return current; }

    @Override protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> found = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    ResourceLocation path = entry.getKey();
                    String file = path.getPath().substring(DIRECTORY.length() + 1, path.getPath().length() - 5);
                    try { found.put(new ResourceLocation(path.getNamespace(), file), DefinitionProblems.parse(path, entry.getValue())); }
                    catch (RuntimeException malformed) { problems.add(DefinitionProblems.top(malformed)); }
                });
        unreadable = List.copyOf(problems);
        return found;
    }

    @Override protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                                   ProfilerFiller profiler) {
        List<String> problems = new ArrayList<>(unreadable);
        unreadable = List.of();
        Map<ResourceLocation, String> structures = new HashMap<>();
        Map<ResourceLocation, EncounterRule> encounters = new HashMap<>();
        for (var source : resources.entrySet()) {
            String file = "data/" + source.getKey().getNamespace() + "/" + DIRECTORY + "/" + source.getKey().getPath() + ".json";
            try {
                read(source.getValue(), structures, encounters);
            } catch (RuntimeException invalid) {
                problems.add(file + ": " + DefinitionProblems.top(invalid));
            }
        }
        if (problems.isEmpty()) {
            current = new Snapshot(structures, encounters);
            ReloadReport.accepted(ReloadReport.Domain.WORLD_CONTEXT, Map.of("world_structure_selectors", structures.size(),
                    "encounter_rules", encounters.size()));
        } else {
            ReloadReport.rejected(ReloadReport.Domain.WORLD_CONTEXT, problems);
        }
    }

    static void read(JsonElement element, Map<ResourceLocation, String> structures, Map<ResourceLocation, EncounterRule> encounters) {
        if (!element.isJsonObject()) throw new JsonParseException("root must be a JSON object " + DefinitionProblems.found(element));
        JsonObject root = element.getAsJsonObject();
        if (root.has("structures")) for (JsonElement value : array(root, "structures")) {
            JsonObject item = object(value, "structures");
            ResourceLocation id = requiredId(item, "structures[].id");
            String role = token(item, "role", "structures[].role", 48);
            if (structures.putIfAbsent(id, role) != null) throw new JsonParseException("structure " + id + " is listed twice");
        }
        if (root.has("encounters")) for (JsonElement value : array(root, "encounters")) {
            JsonObject item = object(value, "encounters");
            ResourceLocation id = requiredId(item, "encounters[].id");
            EncounterRule rule = new EncounterRule(token(item, "role", "encounters[].role", 48),
                    bounded(item, "repeat_budget", 1, 32), bounded(item, "contribution_window", 20, 12000));
            if (encounters.putIfAbsent(id, rule) != null) throw new JsonParseException("encounter " + id + " is listed twice");
        }
    }
    private static JsonArray array(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (!value.isJsonArray()) throw new JsonParseException(field + " must be an array " + DefinitionProblems.found(value));
        return value.getAsJsonArray();
    }
    private static JsonObject object(JsonElement value, String field) {
        if (!value.isJsonObject()) throw new JsonParseException(field + " entries must be objects " + DefinitionProblems.found(value));
        return value.getAsJsonObject();
    }
    private static ResourceLocation requiredId(JsonObject object, String field) {
        JsonElement value = object.get("id");
        ResourceLocation id = value != null && value.isJsonPrimitive() ? ResourceLocation.tryParse(value.getAsString()) : null;
        if (id == null) throw new JsonParseException(field + " must be a namespaced id such as minecraft:pillager_outpost "
                + DefinitionProblems.found(value));
        return id;
    }
    private static String token(JsonObject object, String key, String field, int max) {
        JsonElement value = object.get(key);
        String text = value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        if (!text.matches("[a-z0-9_.:-]{1," + max + "}")) throw new JsonParseException(field + " must be 1 to " + max
                + " lower-case letters, digits or _ . : - " + DefinitionProblems.found(value));
        return text;
    }
    private static int bounded(JsonObject object, String key, int min, int max) {
        JsonElement value = object.get(key);
        int number;
        try { number = value == null ? min : value.getAsInt(); }
        catch (RuntimeException invalid) { throw new JsonParseException("encounters[]." + key + " must be a whole number " + DefinitionProblems.found(value)); }
        if (number < min || number > max) throw new JsonParseException("encounters[]." + key + " must be from " + min + " to " + max + " (found " + number + ")");
        return number;
    }
}
