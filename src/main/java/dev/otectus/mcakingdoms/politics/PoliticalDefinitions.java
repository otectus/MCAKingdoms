package dev.otectus.mcakingdoms.politics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import dev.otectus.mcakingdoms.api.politics.Politics.Definition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** One atomic snapshot across all six political definition families. */
public final class PoliticalDefinitions extends SimplePreparableReloadListener<Map<String, Definition>> {
    private static final Map<String, String> FAMILIES = Map.of("governments", "government", "offices", "office",
            "agreements", "agreement", "petitions", "petition", "honors", "honor", "institution_charters", "institution");
    private volatile Map<String, Definition> definitions = Map.of();
    private volatile Map<String, Definition> pending;
    @Override protected Map<String, Definition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        pending = null;
        try {
            Map<String, Definition> prepared = parse(manager);
            ReloadReport.accepted(ReloadReport.Domain.POLITICS, Map.of("political_definitions", prepared.size()));
            return prepared;
        } catch (RuntimeException rejected) {
            // Failing here fails the whole reload, so every domain keeps its previous definitions.
            ReloadReport.rejected(ReloadReport.Domain.POLITICS, List.of(DefinitionProblems.top(rejected)));
            throw rejected;
        }
    }
    static Map<String, Definition> parse(ResourceManager manager) {
        Map<String, Definition> next = new LinkedHashMap<>();
        Map<String, ResourceLocation> files = new LinkedHashMap<>();
        manager.listResources("mcakingdoms", path -> path.getPath().endsWith(".json")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation path = entry.getKey();
            String[] parts = path.getPath().substring("mcakingdoms/".length()).split("/", 2);
            if (parts.length != 2 || !FAMILIES.containsKey(parts[0])) return;
            JsonElement parsed = DefinitionProblems.parse(path, entry.getValue());
            if (!parsed.isJsonObject()) throw new IllegalArgumentException(path + ": root must be a JSON object " + DefinitionProblems.found(parsed));
            JsonObject json = parsed.getAsJsonObject();
            // Name the field before Gson turns a missing schema or kind into a generic limit failure.
            JsonElement schema = json.get("schema");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException(path + ": schema must be the number 1 " + DefinitionProblems.found(schema));
            if (schema.getAsDouble() != 1) throw new IllegalArgumentException(path + ": unsupported schema " + schema + "; this version reads schema 1 only");
            JsonElement kind = json.get("kind");
            if (kind == null || !kind.isJsonPrimitive() || !FAMILIES.get(parts[0]).equals(kind.getAsString()))
                throw new IllegalArgumentException(path + ": kind must be \"" + FAMILIES.get(parts[0]) + "\" for the " + parts[0]
                        + " folder " + DefinitionProblems.found(kind));
            JsonElement title = json.get("title");
            if (title == null || !title.isJsonPrimitive() || title.getAsString().isBlank())
                throw new IllegalArgumentException(path + ": title must be a translation key or text " + DefinitionProblems.found(title));
            Definition definition;
            try { definition = PoliticalSavedData.JSON.fromJson(json, Definition.class); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException(path + ": " + DefinitionProblems.reason(invalid)); }
            String key = path.getNamespace() + ":" + parts[1].substring(0, parts[1].length() - 5);
            if (next.putIfAbsent(key, definition) != null) throw new IllegalArgumentException(path + ": duplicate political id " + key);
            files.put(key, path);
        });
        next.forEach((id, value) -> {
            value.offices().forEach(office -> reference(next, files.get(id), "offices", office, "office"));
            value.requires().forEach(required -> reference(next, files.get(id), "requires", required, "agreement"));
        });
        return Map.copyOf(next);
    }
    private static void reference(Map<String, Definition> values, ResourceLocation file, String field, String id, String kind) {
        Definition value = values.get(id);
        if (ResourceLocation.tryParse(id) == null || value == null || !kind.equals(value.kind()))
            throw new IllegalArgumentException(file + ": " + field + " references " + id + ", which is not a loaded " + kind + " definition");
    }
    @Override protected void apply(Map<String, Definition> next, ResourceManager manager, ProfilerFiller profiler) { pending = next; }
    public void commitPending() { if (pending != null) { definitions = pending; pending = null; } }
    public void clear() { definitions = Map.of(); pending = null; }
    public Definition get(String id, String kind) { return require(definitions, id, kind); }
    public boolean available(String id) { return definitions.containsKey(id); }
    public Map<String, Definition> snapshot() { return definitions; }
    private static Definition require(Map<String, Definition> values, String id, String kind) {
        Definition value = values.get(id);
        if (ResourceLocation.tryParse(id) == null || value == null || !kind.equals(value.kind()))
            throw new IllegalArgumentException("Political definition unavailable: " + id);
        return value;
    }
}
