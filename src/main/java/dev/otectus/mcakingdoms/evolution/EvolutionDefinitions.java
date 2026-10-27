package dev.otectus.mcakingdoms.evolution;

import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Atomic authored scenario catalog. Existing scenarios keep their frozen terms. */
public final class EvolutionDefinitions extends SimplePreparableReloadListener<Map<String, EvolutionState.Template>> {
    public static final EvolutionDefinitions INSTANCE = new EvolutionDefinitions();
    private volatile Map<String, EvolutionState.Template> current = Map.of();
    private volatile Map<String, EvolutionState.Template> pending;
    @Override protected Map<String, EvolutionState.Template> prepare(ResourceManager manager, ProfilerFiller profiler) {
        pending = null;
        try {
            Map<String, EvolutionState.Template> prepared = parse(manager);
            ReloadReport.accepted(ReloadReport.Domain.EVOLUTION, Map.of("evolution_scenarios", prepared.size()));
            return prepared;
        } catch (RuntimeException rejected) {
            ReloadReport.rejected(ReloadReport.Domain.EVOLUTION, List.of(DefinitionProblems.top(rejected)));
            throw rejected;
        }
    }
    static Map<String, EvolutionState.Template> parse(ResourceManager manager) {
        var next = new TreeMap<String, EvolutionState.Template>();
        manager.listResources("evolution/scenarios", path -> path.getPath().endsWith(".json")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation path = entry.getKey();
            String id = path.getNamespace() + ":" + path.getPath().substring("evolution/scenarios/".length(), path.getPath().length() - 5);
            if (ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException(path + ": the file name does not form a scenario id");
            if (next.size() >= 64) throw new IllegalArgumentException(path + ": more than 64 evolution scenarios are loaded; remove some");
            var parsed = DefinitionProblems.parse(path, entry.getValue());
            if (!parsed.isJsonObject()) throw new IllegalArgumentException(path + ": root must be a JSON object " + DefinitionProblems.found(parsed));
            var schema = parsed.getAsJsonObject().get("schema");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException(path + ": schema must be the number 1 " + DefinitionProblems.found(schema));
            if (schema.getAsDouble() != 1) throw new IllegalArgumentException(path + ": unsupported schema " + schema + "; this version reads schema 1 only");
            try {
                next.put(id, Objects.requireNonNull(EvolutionSavedData.JSON.fromJson(parsed, EvolutionState.Template.class)));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(path + ": " + DefinitionProblems.reason(invalid)
                        + "; scenarios need a title, a trigger, 2 to 6 distinct outcomes including DECLINE, duration and cooldown"
                        + " from 1200 to 24000000 ticks and 1 to 64 contributions");
            }
        });
        return Map.copyOf(next);
    }
    @Override protected void apply(Map<String, EvolutionState.Template> next, ResourceManager manager, ProfilerFiller profiler) { pending = next; }
    public void commitPending() { if (pending != null) { current = pending; pending = null; } }
    public Map<String, EvolutionState.Template> snapshot() { return current; }
    public void clear() { current = Map.of(); pending = null; }
}
