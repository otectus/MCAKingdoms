package dev.otectus.mcakingdoms.civic;

import com.google.gson.*;
import dev.otectus.mcakingdoms.data.DefinitionProblems;
import dev.otectus.mcakingdoms.data.ReloadReport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/**
 * Explicit authored sponsor roles. Ordinary villagers are never enrolled by profession or chunk load.
 *
 * <p>The rules validate as a set: one invalid or unreadable file rejects the reload of this domain, the previously
 * committed rules stay active, and the rejection is recorded in the reload report rather than only in the log.
 */
public final class CivicDefinitions extends SimpleJsonResourceReloadListener {
    public static final CivicDefinitions INSTANCE=new CivicDefinitions();
    private static final String DIRECTORY="mcakingdoms_factions/civic_network";
    private static final Set<String> FIELDS=Set.of("schema","organization","sponsor_quests","building_families","commissions");
    public record Rules(ResourceLocation organization,Set<ResourceLocation> sponsorQuests,Set<String> buildingFamilies,Set<ResourceLocation> commissions) {
        public Rules { sponsorQuests=Set.copyOf(sponsorQuests);buildingFamilies=Set.copyOf(buildingFamilies);commissions=Set.copyOf(commissions); }
    }
    private volatile Map<ResourceLocation,Rules> committed=Map.of(),pending;
    /** Files that could not be read as JSON during the current reload; vanilla would silently skip them. */
    private volatile List<String> unreadable=List.of();
    private long revision;
    private CivicDefinitions() { super(new Gson(),DIRECTORY); }
    @Override protected Map<ResourceLocation,JsonElement> prepare(ResourceManager manager,ProfilerFiller profiler) {
        Map<ResourceLocation,JsonElement> found=new LinkedHashMap<>();List<String> problems=new ArrayList<>();
        manager.listResources(DIRECTORY,path->path.getPath().endsWith(".json")).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            ResourceLocation path=entry.getKey();String file=path.getPath().substring(DIRECTORY.length()+1,path.getPath().length()-5);
            try{found.put(new ResourceLocation(path.getNamespace(),file),DefinitionProblems.parse(path,entry.getValue()));}
            catch(RuntimeException malformed){problems.add(DefinitionProblems.top(malformed));}
        });
        unreadable=List.copyOf(problems);return found;
    }
    @Override protected void apply(Map<ResourceLocation,JsonElement> resources,ResourceManager manager,ProfilerFiller profiler) {
        List<String> problems=new ArrayList<>(unreadable);unreadable=List.of();
        Map<ResourceLocation,Rules> next=new LinkedHashMap<>();
        if(resources.size()>128)problems.add(resources.size()+" civic definitions were found; at most 128 are supported");
        else for(var entry:resources.entrySet()) {
            String file="data/"+entry.getKey().getNamespace()+"/"+DIRECTORY+"/"+entry.getKey().getPath()+".json";
            try {
                Rules rules=parse(entry.getValue());
                if(next.putIfAbsent(rules.organization(),rules)!=null)throw new IllegalArgumentException("organization "+rules.organization()+" already has civic rules in another file");
            } catch(RuntimeException invalid) { problems.add(file+": "+DefinitionProblems.top(invalid)); }
        }
        if(problems.isEmpty()) {
            pending=Map.copyOf(next);
            ReloadReport.accepted(ReloadReport.Domain.CIVIC,Map.of("civic_rule_sets",next.size()));
        } else {
            pending=null;
            ReloadReport.rejected(ReloadReport.Domain.CIVIC,problems);
        }
    }
    static Rules parse(JsonElement element) {
        if(!element.isJsonObject())throw new IllegalArgumentException("root must be a JSON object "+DefinitionProblems.found(element));
        var json=element.getAsJsonObject();
        for(String key:json.keySet())if(!FIELDS.contains(key))throw new IllegalArgumentException("unknown field "+key+"; allowed fields: "+new TreeSet<>(FIELDS));
        var schema=json.get("schema");
        if(schema==null||!schema.isJsonPrimitive()||!schema.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("schema must be the number 1 "+DefinitionProblems.found(schema));
        if(!schema.getAsString().equals("1"))throw new IllegalArgumentException("unsupported schema "+schema+"; this version reads schema 1 only");
        ResourceLocation organization=id("organization",string(json,"organization"));
        Set<ResourceLocation> sponsor=resources(json,"sponsor_quests",128);
        Set<ResourceLocation> commissions=resources(json,"commissions",32);
        Set<String> families=new LinkedHashSet<>();
        for(JsonElement value:array(json,"building_families")) {
            String family=value.isJsonPrimitive()?value.getAsString():"";
            if(!family.matches("[a-z0-9_]{1,64}"))throw new IllegalArgumentException("building_families entries must be lower-case words of 1 to 64 letters, digits or _ "+DefinitionProblems.found(value));
            if(!families.add(family))throw new IllegalArgumentException("building_families contains "+family+" twice");
        }
        if(families.isEmpty()||families.size()>16)throw new IllegalArgumentException("building_families must name 1 to 16 families (found "+families.size()+")");
        return new Rules(organization,sponsor,families,commissions);
    }
    private static String string(JsonObject json,String field) {
        var value=json.get(field);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException((value==null?"missing required field ":"")+field+" must be a string "+DefinitionProblems.found(value));
        return value.getAsString();
    }
    private static JsonArray array(JsonObject json,String field) {
        var value=json.get(field);
        if(value==null||!value.isJsonArray())throw new IllegalArgumentException((value==null?"missing required field ":"")+field+" must be an array "+DefinitionProblems.found(value));
        return value.getAsJsonArray();
    }
    private static Set<ResourceLocation> resources(JsonObject json,String field,int max) {
        JsonArray array=array(json,field);
        if(array.size()>max)throw new IllegalArgumentException(field+" lists "+array.size()+" ids; at most "+max+" are allowed");
        Set<ResourceLocation> ids=new LinkedHashSet<>();
        for(JsonElement value:array) {
            ResourceLocation id=id(field,value.isJsonPrimitive()?value.getAsString():value.toString());
            if(!ids.add(id))throw new IllegalArgumentException(field+" lists "+id+" twice");
        }
        return ids;
    }
    private static ResourceLocation id(String field,String text) {
        var id=ResourceLocation.tryParse(text);
        if(text.length()>128||id==null||!id.toString().equals(text))throw new IllegalArgumentException(field+" must contain namespaced ids such as mcakingdoms:lamplighters (found \""+(text.length()>48?text.substring(0,47)+"…":text)+"\")");
        return id;
    }
    public void commitPending() { if(pending!=null){committed=pending;pending=null;revision++;} }
    public Optional<Rules> get(ResourceLocation organization) { return Optional.ofNullable(committed.get(organization)); }
    /** Committed rule sets, for server health. */
    public int size() { return committed.size(); }
    public long revision() { return revision; }
    public void clear() { committed=Map.of();pending=null;unreadable=List.of();revision=0; }
}
