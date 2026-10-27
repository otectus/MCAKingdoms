package dev.otectus.mcakingdoms.evolution.drama;

import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Atomically reloaded, bounded authored drama catalog. Active dramas retain frozen terms. */
public final class DramaDefinitions extends SimplePreparableReloadListener<Map<String, DramaState.Template>> {
    public static final DramaDefinitions INSTANCE = new DramaDefinitions();
    private volatile Map<String, DramaState.Template> current = Map.of();
    private volatile Map<String, DramaState.Template> pending;
    @Override protected Map<String, DramaState.Template> prepare(ResourceManager manager, ProfilerFiller profiler) {
        pending = null;
        try {
            Map<String, DramaState.Template> prepared = parse(manager);
            ReloadReport.accepted(ReloadReport.Domain.DRAMA, Map.of("world_event_templates", prepared.size()));
            return prepared;
        } catch (RuntimeException rejected) {
            ReloadReport.rejected(ReloadReport.Domain.DRAMA, List.of(DefinitionProblems.top(rejected)));
            throw rejected;
        }
    }
    private static Map<String, DramaState.Template> parse(ResourceManager manager) {
        var next = new TreeMap<String, DramaState.Template>();
        manager.listResources("evolution/drama", path -> path.getPath().endsWith(".json")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation path = entry.getKey();
            try {
                String id = path.getNamespace() + ":" + path.getPath().substring("evolution/drama/".length(), path.getPath().length() - 5);
                if (ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("the file name does not form a template id");
                if (next.size() >= 32) throw new IllegalArgumentException("more than 32 world event templates are loaded; remove some");
                var json = DefinitionProblems.parse(path, entry.getValue());
                var parsed = Objects.requireNonNull(DramaSavedData.JSON.fromJson(json, DramaState.Template.class));
                // Reconstruct to invoke the record's validation even if a Gson version bypasses constructors.
                parsed = new DramaState.Template(parsed.title(), parsed.kind(), parsed.outcomes(), parsed.goal(), parsed.objective(),
                        parsed.durationTicks(), parsed.maxParticipants(), parsed.operationBudget(), parsed.requiresOccupation(),
                        parsed.organizationTemplate(), parsed.organizationName());
                if (next.put(id, parsed) != null) throw new IllegalArgumentException("duplicate template id " + id);
            } catch (RuntimeException failure) {
                String reason = DefinitionProblems.reason(failure);
                throw new IllegalArgumentException(reason.startsWith(path.toString()) ? reason : path + ": " + reason, failure);
            }
        });
        return Map.copyOf(next);
    }
    @Override protected void apply(Map<String, DramaState.Template> next, ResourceManager manager, ProfilerFiller profiler) { pending = next; }
    public void commitPending() { if (pending != null) { current = pending; pending = null; } }
    public Map<String, DramaState.Template> snapshot() { return current; }
    public void clear() { current = Map.of(); pending = null; }
}
