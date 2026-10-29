package dev.otectus.mcakingdoms.development;

import com.google.gson.JsonElement;
import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

/**
 * Loads development templates ({@code mcakingdoms/development_templates}) as one domain of the reload report. They validate
 * as a set: one invalid file rejects this domain's reload and the templates already active stay active. A reload is
 * installed only when every domain prepared.
 */
public final class DevelopmentDefinitions extends SimplePreparableReloadListener<DevelopmentDefinitions.Prepared> {
    public static final DevelopmentDefinitions INSTANCE = new DevelopmentDefinitions();
    static final String DIRECTORY = "mcakingdoms/development_templates";
    static final int LIMIT = 64;

    record Prepared(Map<ResourceLocation, JsonElement> files, List<String> problems) { }
    private volatile Map<ResourceLocation, DevelopmentTemplate> pending;

    private DevelopmentDefinitions() {
    }

    @Override
    protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
        var problems = new ArrayList<String>();
        var found = new TreeMap<ResourceLocation, JsonElement>();
        manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation path = entry.getKey();
            String file = path.getPath().substring(DIRECTORY.length() + 1, path.getPath().length() - 5);
            try { found.put(new ResourceLocation(path.getNamespace(), file), DefinitionProblems.parse(path, entry.getValue())); }
            catch (RuntimeException malformed) { problems.add(DefinitionProblems.top(malformed)); }
        });
        return new Prepared(found, problems);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
        var problems = new ArrayList<>(prepared.problems());
        var templates = new LinkedHashMap<ResourceLocation, DevelopmentTemplate>();
        if (prepared.files().size() > LIMIT) problems.add(prepared.files().size() + " development templates were found; at most " + LIMIT + " are supported");
        else prepared.files().forEach((id, json) -> {
            String file = "data/" + id.getNamespace() + "/" + DIRECTORY + "/" + id.getPath() + ".json";
            try {
                if (!json.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object " + DefinitionProblems.found(json));
                templates.put(id, DevelopmentTemplate.parse(id, json.getAsJsonObject()));
            } catch (RuntimeException invalid) { problems.add(file + ": " + DefinitionProblems.top(invalid)); }
        });
        if (problems.isEmpty()) {
            pending = Map.copyOf(templates);
            ReloadReport.accepted(ReloadReport.Domain.DEVELOPMENT, Map.of("development_templates", templates.size()));
        } else {
            pending = null;
            ReloadReport.rejected(ReloadReport.Domain.DEVELOPMENT, problems);
        }
    }

    /** Installs the templates the last reload accepted; called once every domain of the reload prepared. */
    public void commitPending() {
        var next = pending;
        if (next == null) return;
        DevelopmentTemplate.install(next);
        pending = null;
    }
}
