package dev.otectus.mcakingdoms.politics.dynasty;

import com.google.gson.JsonElement;
import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

/**
 * Loads data-pack succession laws ({@code mcakingdoms/succession_laws}) and court ranks ({@code mcakingdoms/noble_ranks})
 * as one domain of the reload report. They validate as a set: one invalid file rejects this domain's reload and the
 * laws and ranks already active stay active (keep-last-good); the five built-in laws can never be replaced. A reload is
 * installed only when every domain prepared, like the other definitions.
 */
public final class DynastyDefinitions extends SimplePreparableReloadListener<DynastyDefinitions.Prepared> {
    public static final DynastyDefinitions INSTANCE = new DynastyDefinitions();
    static final String LAWS = "mcakingdoms/succession_laws", RANKS = "mcakingdoms/noble_ranks";
    static final int LIMIT = 64;

    record Prepared(Map<ResourceLocation, JsonElement> laws, Map<ResourceLocation, JsonElement> ranks, List<String> problems) { }
    private record Pending(Map<ResourceLocation, SuccessionLaw> laws, Map<ResourceLocation, NobleRank> ranks) { }
    private volatile Pending pending;

    private DynastyDefinitions() {
    }

    @Override
    protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
        var problems = new ArrayList<String>();
        return new Prepared(read(manager, LAWS, problems), read(manager, RANKS, problems), problems);
    }

    private static Map<ResourceLocation, JsonElement> read(ResourceManager manager, String directory, List<String> problems) {
        var found = new TreeMap<ResourceLocation, JsonElement>();
        manager.listResources(directory, path -> path.getPath().endsWith(".json")).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation path = entry.getKey();
            String file = path.getPath().substring(directory.length() + 1, path.getPath().length() - 5);
            try { found.put(new ResourceLocation(path.getNamespace(), file), DefinitionProblems.parse(path, entry.getValue())); }
            catch (RuntimeException malformed) { problems.add(DefinitionProblems.top(malformed)); }
        });
        return found;
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
        var problems = new ArrayList<>(prepared.problems());
        var laws = new LinkedHashMap<ResourceLocation, SuccessionLaw>();
        var ranks = new LinkedHashMap<ResourceLocation, NobleRank>();
        if (prepared.laws().size() > LIMIT) problems.add(prepared.laws().size() + " succession laws were found; at most " + LIMIT + " are supported");
        else prepared.laws().forEach((id, json) -> {
            String file = "data/" + id.getNamespace() + "/" + LAWS + "/" + id.getPath() + ".json";
            if (SuccessionLaw.builtIn(id)) { problems.add(file + ": a built-in law cannot be replaced"); return; }
            try {
                if (!json.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object " + DefinitionProblems.found(json));
                laws.put(id, SuccessionLaw.parse(id, json.getAsJsonObject()));
            } catch (RuntimeException invalid) { problems.add(file + ": " + DefinitionProblems.top(invalid)); }
        });
        if (prepared.ranks().size() > LIMIT) problems.add(prepared.ranks().size() + " court ranks were found; at most " + LIMIT + " are supported");
        else prepared.ranks().forEach((id, json) -> {
            String file = "data/" + id.getNamespace() + "/" + RANKS + "/" + id.getPath() + ".json";
            try {
                if (!json.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object " + DefinitionProblems.found(json));
                ranks.put(id, NobleRank.parse(id, json.getAsJsonObject()));
            } catch (RuntimeException invalid) { problems.add(file + ": " + DefinitionProblems.top(invalid)); }
        });
        if (problems.isEmpty()) {
            pending = new Pending(Map.copyOf(laws), Map.copyOf(ranks));
            ReloadReport.accepted(ReloadReport.Domain.DYNASTY, Map.of("succession_laws", laws.size(), "court_ranks", ranks.size()));
        } else {
            pending = null;
            ReloadReport.rejected(ReloadReport.Domain.DYNASTY, problems);
        }
    }

    /** Installs the definitions the last reload accepted; called once every domain of the reload prepared. */
    public void commitPending() {
        var next = pending;
        if (next == null) return;
        SuccessionLaw.install(next.laws());
        NobleRank.install(next.ranks());
        pending = null;
    }

    /** For tests: installs definitions directly. */
    public static void installForTests(Map<ResourceLocation, SuccessionLaw> laws, Map<ResourceLocation, NobleRank> ranks) {
        SuccessionLaw.install(laws);
        NobleRank.install(ranks);
    }
}
