package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.core.KingdomsServiceImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import java.util.*;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

public final class CoreTasks {
    /** Manual settlement radius: the form shows it, the form checks it, and the task checks it again. */
    static final Constraint RADIUS=Constraint.range(16,512,"blocks",64);
    static final Constraint DISCOVERY=Constraint.range(1,32,"chunks",8);
    static final Constraint RECONCILE=Constraint.range(0,32,"chunks",8);
    static KingdomsService api(Context c){return McaKingdomsApi.get(c.server());}
    private static KingdomsServiceImpl impl(Context c){if(api(c) instanceof KingdomsServiceImpl impl)return impl;throw new IllegalStateException("Settlement administration is unavailable on this server.");}
    static void task(String id,String title,String help,boolean operator,boolean change,List<Field> fields,Handler handler){add(new Task(id,operator?"Administration":"Settlements",title,help,operator,change,fields,c->Long.toString(api(c).revision()),handler));}
    private static Component t(String key,String fallback,Object... args){return tr("interaction.mcakingdoms.settlement."+key,fallback,args);}
    public static void init(){
        task("settlement.here","Read this settlement","Names the settlement you are standing in and records it as discovered for you. Read-only.",false,false,List.of(),
                c->api(c).getSettlementAt(c.player.serverLevel(),c.player.blockPosition()).<Object>map(s->{dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(c.server()).discover(c.player.getUUID(),s.id());return describe(c,s);})
                        .orElseGet(()->List.of(t("none_here","You are not inside a recognized settlement."),
                                t("none_here.why","Settlements are recognized from village structures and from clusters of village beds, bells and workstations in loaded chunks. Walk into the village itself, then try Read this settlement again."),
                                t("none_here.next","Operators can register a place with Register a settlement here."))));
        task("settlement.inspect","Read a known settlement","Shows a settlement you have discovered or that is public: its kingdom, location and how its kingdom was assigned. Read-only.",false,false,List.of(pick("settlement","Settlement","settlement")),c->describe(c,api(c).getSettlement(c.uuid("settlement")).orElseThrow()));
        task("kingdom.list","Browse kingdoms","Lists the kingdoms defined on this server. Read-only.",false,false,List.of(),c->{var kingdoms=c.choices("kingdom");return kingdoms.isEmpty()?List.of(t("no_kingdoms","No kingdoms are loaded. Kingdom definitions come from data packs.")):kingdoms.stream().map(k->(Object)k.shown()).toList();});
        task("kingdom.inspect","Read a kingdom","Describes a kingdom and counts the settlements of it that you know. Read-only.",false,false,List.of(pick("kingdom","Kingdom","kingdom")),
                c->List.of(c.choice("kingdom").shown(),t("known_count","Known settlements: %s",dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(c.server()).count(c.player,api(c),c.id("kingdom")))));
        task("settlement.create","Register a settlement here","Registers a settlement at your position. Inside a recognized village the place joins that village instead of creating a second record.",true,true,
                List.of(number("radius","Radius (blocks)",64,RADIUS),text("name","Settlement name","",true)),c->{int r=c.integer("radius",RADIUS);var s=api(c).registerCandidate(c.player.serverLevel(),SettlementCandidate.manual(c.player.level().dimension(),c.player.blockPosition(),r,new ResourceLocation("mcakingdoms","manual"),"gui:"+UUID.randomUUID(),c.text("name")));
                    boolean joined=s.createdGameTime()!=c.player.level().getGameTime()||s.detectionSource()!=DetectionSource.MANUAL||!c.text("name").isBlank()&&!s.displayName().equals(c.text("name").strip());
                    return joined?t("joined","This place is part of %s, a recognized village, so no second settlement was registered.",s.displayName()):t("registered","Registered %s.",s.displayName());});
        task("settlement.rename","Rename a settlement","Changes a settlement's civic name. Residents and their MCA names are not renamed; the old name stays searchable.",true,true,List.of(pick("settlement","Settlement","settlement"),text("name","New name")),
                c->t("renamed","Renamed to %s.",api(c).rename(c.uuid("settlement"),c.text("name")).displayName()));
        task("settlement.kingdom","Change civic kingdom","Assigns a settlement to another kingdom. A capital seat or charter can block it; troop ownership and standing history stay as they are.",true,true,List.of(pick("settlement","Settlement","settlement"),pick("kingdom","New kingdom","kingdom")),
                c->{api(c).setKingdom(c.uuid("settlement"),c.id("kingdom"));return List.of(t("kingdom_changed","Civic kingdom changed to %s.",c.choice("kingdom").shown()),t("kingdom_changed.note","Native troop ownership, faction standing and recorded origin kingdoms are separate and did not change."));});
        effects("settlement.kingdom",c->{var s=api(c).getSettlement(c.uuid("settlement")).orElseThrow();var lines=new ArrayList<Object>();
            lines.add(t("reassign.from_to","Kingdom: %s → %s.",kingdomName(c,s.kingdomId()),c.choice("kingdom").shown()));
            lines.add(t("reassign.moves","Residents whose civic residence is this settlement now belong to the new kingdom. Their recorded origin kingdom is kept."));
            lines.add(impl(c).previewKingdomChange(s.id(),c.id("kingdom")).<Object>map(reason->t("blocked","Blocked now: %s. Apply will be refused until that is resolved.",reason))
                    .orElse(t("unblocked","No blocking reference was found now; Apply checks again.")));
            return lines;});
        task("settlement.reclassify","Reevaluate kingdom assignment","Runs the current biome and structure-style rules for this settlement again, unless its kingdom is locked.",true,true,List.of(pick("settlement","Settlement","settlement")),
                c->{var s=api(c).reclassify(c.uuid("settlement"));return t("reclassified","Assignment reevaluated from current definitions: %s.",kingdomName(c,s.kingdomId()));});
        for(boolean locked:new boolean[]{true,false})task("settlement."+(locked?"lock":"unlock"),(locked?"Lock":"Unlock")+" settlement identity",locked?"Stops automatic renaming and kingdom reassignment of a settlement.":"Allows automatic renaming and kingdom reassignment of a settlement again.",true,true,List.of(pick("settlement","Settlement","settlement")),
                c->{api(c).setLocks(c.uuid("settlement"),locked,locked);return locked?t("locked","Name and kingdom locked."):t("unlocked","Name and kingdom unlocked.");});
        task("settlement.discover","Discover surrounding settlements","Scans loaded chunks around you for villages and village point-of-interest clusters. Nothing is generated and no distant chunk is loaded.",true,false,
                List.of(number("radius","Search radius (chunks)",8,DISCOVERY)),c->t("discovered","Found or observed %s settlements.",api(c).discover(c.player.serverLevel(),c.player.blockPosition(),c.integer("radius",DISCOVERY)).size()));
        task("settlement.reconcile","Reconcile nearby village records","Merges settlement records that are pieces of one generated village, for records anchored in loaded chunks around you.",true,true,
                List.of(number("radius","Radius (chunks)",8,RECONCILE)),CoreTasks::reconcile);
        effects("settlement.reconcile",c->List.of(t("reconcile.scope","Records in scope now: %s, anchored in loaded chunks within %s chunks of you. Unloaded chunks are not read; their records reconcile when the chunks load.",impl(c).reconcileScope(c.player.serverLevel(),c.player.blockPosition(),c.integer("radius",RECONCILE)),c.integer("radius",RECONCILE)),
                t("reconcile.what","Each record inside a generated village's footprint is merged into the record holding the village's structure start. Retired ids keep resolving and retired names stay as aliases. A merge that politics or warfare refuses leaves both records unchanged.")));
        task("settlement.merge","Merge duplicate settlement records","Folds one settlement record into another: the retired name becomes an alias and its id redirects to the kept record.",true,true,List.of(pick("source","Record to retire","settlement"),pick("target","Record to keep","settlement")),
                c->{var merged=api(c).merge(c.uuid("source"),c.uuid("target"));return t("merged","Merged %s into %s; the kept settlement retains its identity.",c.choice("source").label(),merged.displayName());});
        effects("settlement.merge",CoreTasks::mergeEffects);
        task("settlement.debug","Inspect settlement assignment evidence","Shows the evidence behind a settlement's kingdom, its generated-village identity and footprint. Read-only.",true,false,List.of(pick("settlement","Settlement","settlement")),CoreTasks::debug);
        task("citizen.inspect","Inspect civic residence","Shows a nearby person's recorded civic origin and residence. Read-only.",true,false,List.of(pick("person","Person","person")),c->api(c).getCivicIdentity(BasicTargets.entity(c,"person")).<Object>map(v->List.of(
                t("citizen.origin","Origin: %s",v.originSettlement().map(id->(Object)BasicTargets.settlement(c,id)).orElse(t("citizen.unknown","Unknown"))),
                t("citizen.residence","Residence: %s",v.residenceSettlement().map(id->(Object)BasicTargets.settlement(c,id)).orElse(t("citizen.unknown","Unknown"))))).orElse(t("citizen.none","No confirmed civic identity.")));
        for(boolean origin:new boolean[]{true,false})task("citizen."+(origin?"origin":"residence"),origin?"Set civic origin":"Set civic residence",origin?"Records where a person comes from. Their MCA home and family are unchanged.":"Records where a person lives; resident founding and civic services read it. Their MCA home and family are unchanged.",true,true,List.of(pick("person","Person","person"),pick("settlement","Settlement","settlement")),
                c->{var entity=BasicTargets.entity(c,"person");if(origin)api(c).setOrigin(entity,c.uuid("settlement"));else api(c).setResidence(entity,c.uuid("settlement"));return t("citizen.updated","Civic record updated; native home and family are unchanged.");});
        // The result is this attempt's report: a rejected domain is never shown as a successful reload.
        task("definitions.reload","Reload server definitions","Reloads data packs and reports, domain by domain, which definitions were accepted and which were rejected and kept their previous state.",true,true,List.of(),
                c->new Pending(dev.otectus.mcakingdoms.data.DefinitionReloads.start(c.server()).thenApply(dev.otectus.mcakingdoms.data.DefinitionReloads.Result::lines),
                        tr("interaction.mcakingdoms.reload.requested","Reload requested. Use Check this action to see whether each definition domain was accepted or rejected.")));
    }
    private static Object reconcile(Context c){
        int radius=c.integer("radius",RECONCILE);var impl=impl(c);var level=c.player.serverLevel();var center=c.player.blockPosition();
        var report=impl.reconcileAround(level,center,radius);var lines=new ArrayList<Object>();
        lines.add(t("reconcile.result","Merged %s split record(s) into %s village(s); %s of %s examined record(s) lie in no known village.",report.merged(),report.villages(),report.unresolved(),report.examined()));
        if(report.examined()==0)lines.add(t("reconcile.nothing","Nothing to reconcile: no unreconciled record is anchored in a loaded chunk within %s chunks.",radius));
        var refused=new LinkedHashMap<String,dev.otectus.mcakingdoms.core.SettlementReconciler.Refusal>();
        report.refused().forEach(r->refused.putIfAbsent(r.source()+">"+r.target(),r));impl.refusalsAround(level,center,radius).forEach(r->refused.putIfAbsent(r.source()+">"+r.target(),r));
        refused.values().forEach(r->lines.add(t("reconcile.refused","Not merged: %s into %s — %s. Resolve that, then run this task again (console: %s).",r.sourceName(),r.targetName(),r.reason(),r.command())));
        return lines;
    }
    private static List<Object> mergeEffects(Context c){
        var preview=impl(c).previewMerge(c.uuid("source"),c.uuid("target"));var source=preview.source();var target=preview.target();var lines=new ArrayList<Object>();
        lines.add(t("merge.keeps","Keeps: %s, with its id, name and kingdom (%s)%s.",target.displayName(),kingdomName(c,target.kingdomId()),locks(target)));
        lines.add(t("merge.retires","Retires: %s%s. Its name stays searchable as an alias, and its id and slug resolve to %s.",source.displayName(),locks(source),target.displayName()));
        lines.add(t("merge.moves","Moved to the kept record: discovery and visits, residents, campaigns, civilian contracts, notices, evolution regions and scenarios, protection pacts, world events, chapter sites, appointments, introductions and route ends."));
        lines.add(t("merge.kept","Not rewritten: faction standing, recorded origin kingdoms, and other mods' own records."));
        if(preview.blockers().isEmpty())lines.add(t("unblocked","No blocking reference was found now; Apply checks again."));
        else preview.blockers().forEach(reason->lines.add(t("blocked","Blocked now: %s. Apply will be refused until that is resolved.",reason)));
        return lines;
    }
    private static Component locks(SettlementView s){return s.nameLocked()||s.kingdomLocked()?t("merge.locked",", locked %s",s.nameLocked()&&s.kingdomLocked()?t("merge.locked.both","name and kingdom"):s.nameLocked()?t("merge.locked.name","name"):t("merge.locked.kingdom","kingdom")):Component.empty();}
    private static Object debug(Context c){
        var s=api(c).getSettlement(c.uuid("settlement")).orElseThrow();var lines=new ArrayList<Object>(describe(c,s));
        lines.add(t("debug.detection","Detected by: %s · revision %s",ActionRegistry.words(s.detectionSource().name()),s.revision()));
        s.assignmentTrace().forEach(line->lines.add(Component.literal(line)));
        var structures=api(c).getSettlementExternalRefs(s.id()).getOrDefault(dev.otectus.mcakingdoms.settlement.VillageFootprints.STRUCTURE_REF,Set.of());
        lines.add(t("debug.structure","Structure identity: %s",structures.isEmpty()?t("debug.none","none"):Component.literal(String.join(", ",structures))));
        var level=c.server().getLevel(s.dimension());
        var footprint=level==null?Optional.<VillageFootprint>empty():impl(c).villageAt(level,s.anchor());
        lines.add(footprint.<Object>map(v->t("debug.footprint","Village footprint: %s, box %s to %s",v.key(),v.box().minX()+" "+v.box().minY()+" "+v.box().minZ(),v.box().maxX()+" "+v.box().maxY()+" "+v.box().maxZ()))
                .orElse(t("debug.no_footprint","Village footprint: none known at the anchor")));
        return lines;
    }
    /** A settlement's details, translated on the reader's client; its name is the player-authored literal. */
    static List<Object> describe(Context c,SettlementView s){return List.of(Component.literal(s.displayName()),
            t("kingdom","Civic kingdom: %s",kingdomName(c,s.kingdomId())),t("location","Location: %s",s.anchor().toShortString()),
            t("dimension","Dimension: %s",dimensionName(s.dimension().location())),
            t("assignment","Assignment: %s",Component.translatableWithFallback("assignment.mcakingdoms."+s.assignmentSource().name().toLowerCase(Locale.ROOT),ActionRegistry.words(s.assignmentSource().name()))));}
    static Component kingdomName(Context c,ResourceLocation id){return api(c).getKingdom(id).map(k->(Component)Component.translatableWithFallback(k.translationKey(),PlayerWords.text(k.translationKey()))).orElse(Component.literal(id.toString()));}
    static Component dimensionName(ResourceLocation dimension){
        if(dimension.equals(Level.OVERWORLD.location()))return Component.translatable("dimension.mcakingdoms.overworld");
        if(dimension.equals(Level.NETHER.location()))return Component.translatable("dimension.mcakingdoms.the_nether");
        if(dimension.equals(Level.END.location()))return Component.translatable("dimension.mcakingdoms.the_end");
        return Component.literal(dimension.toString());
    }
    private CoreTasks(){}
}
