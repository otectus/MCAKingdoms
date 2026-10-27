package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.civic.*;
import dev.otectus.mcakingdoms.api.factions.organization.*;
import dev.otectus.mcakingdoms.api.progression.*;
import java.util.*;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

public final class CivicTasks {
    private static final Constraint MINIMUM=Constraint.range(0,1_000_000,"",1);
    private static CivicService civic(Context c){return CivicRuntime.get(c.server());}
    private static final String SERVICE_HELP="Membership, service qualification, nearby contact and legal access are checked separately. A missing requirement is explained by the service.";
    private static void task(String id,String title,boolean change,List<Field> fields,Handler handler){task(id,title,change?SERVICE_HELP:"Reads guild, service and qualification records available to you. Read-only.",change,fields,handler);}
    private static void task(String id,String title,String help,boolean change,List<Field> fields,Handler handler){add(new Task("civic."+id,"Guilds and service",title,help,false,change,fields,c->civic(c).revision()+":"+OrganizationApi.get(c.server()).revision()+":"+civic(c).commissionFingerprint(),handler));}
    /**
     * Joining or leaving depends on the player's own memberships and on which guilds exist. Versioning it by those alone
     * keeps another player's join from making this player's review stale; the service checks everything again on apply.
     */
    private static void membershipTask(String id,String title,List<Field> fields,Handler handler){add(new Task("civic."+id,"Guilds and service",title,SERVICE_HELP,false,true,fields,CivicTasks::ownMemberships,handler));}
    private static String ownMemberships(Context c){var api=OrganizationApi.get(c.server());
        return api.ownSnapshot(c.player).memberships().stream().map(m->m.organizationId()+"="+m.status()+"@"+m.revision()).collect(java.util.stream.Collectors.joining(","))
                +"|"+api.definitions().stream().map(d->d.id().toString()).sorted().collect(java.util.stream.Collectors.joining(","));}
    public static void init(){
        targets("chapter",c->{var out=new ArrayList<Choice>();for(int offset=0;offset<=4096;offset+=16){var page=civic(c).knownChapters(c.player,offset);for(var v:page)out.add(Choice.of(v.id().toString(),tr("interaction.mcakingdoms.civic.chapter","Chapter at %s",v.settlementName()),enumLabel(v.status()),Map.of("revision",""+civic(c).revision(),"organization",v.organization())));if(page.size()<16)break;}return out;});
        targets("contact",c->{var out=new ArrayList<Choice>();for(int offset=0;offset<=16384;offset+=64){var page=civic(c).dismissibleContacts(c.player,offset,64);for(var v:page)out.add(new Choice(v.npc().toString(),v.label(),v.detail(),Map.of("revision",""+v.revision())));if(page.size()<64)break;}return out;});
        targets("own_membership",c->OrganizationApi.get(c.server()).ownSnapshot(c.player).memberships().stream().filter(m->m.status()==OrganizationMembershipSnapshot.Status.ACTIVE).map(m->new Choice(m.organizationId().toString(),OrganizationApi.get(c.server()).definition(m.organizationId()).map(d->PlayerWords.text(d.nameKey())).orElse(words(m.organizationId().getPath())),"Your active membership",Map.of("revision",""+m.revision()))).toList());
        targets("guild_permission",c->OrganizationApi.get(c.server()).definition(c.id("organization")).stream().flatMap(d->d.services().stream()).map(s->Choice.of(s.permission().toString(),CivicRequirements.serviceName(s.permission()),
                s.neutralAlternative()?tr("interaction.mcakingdoms.civic.permission.routes","Members: %s standing and %s different jobs. Independent: %s standing and %s different jobs.",s.minimumStanding(),s.requiredDeeds(),s.neutralMinimumStanding(),s.neutralRequiredDeeds())
                        :tr("interaction.mcakingdoms.civic.permission.members","Members only: %s standing and %s different jobs.",s.minimumStanding(),s.requiredDeeds()))).toList());
        task("status","Read your guild progress",false,List.of(),c->{var memberships=OrganizationApi.get(c.server()).ownSnapshot(c.player).memberships();
            if(memberships.isEmpty())return tr("interaction.mcakingdoms.civic.status.none","You have no guild memberships or recorded guild service yet. Join a guild to start.");
            return memberships.stream().map(m->(Object)tr("interaction.mcakingdoms.civic.status","%s\n%s · standing %s · verified deeds %s · rank %s",
                    c.choices("organization").stream().filter(o->o.value().equals(m.organizationId().toString())).map(Choice::shown).findFirst().orElse(tr("interaction.mcakingdoms.civic.organization","Organization")),
                    enumLabel(m.status()),m.standing(),m.deedCount(),m.rank().<Object>map(r->words(r.getPath())).orElse(tr("interaction.mcakingdoms.civic.rank_none","None")))).toList();});
        for(boolean joining:new boolean[]{true,false})membershipTask(joining?"join":"leave",joining?"Join a guild":"Leave a guild",List.of(pick("organization","Organization",joining?"organization":"own_membership")),c->{var s=OrganizationApi.get(c.server());var result=joining?s.join(c.player,c.id("organization")):s.leave(c.player,c.id("organization"));if(!result.applied())throw new InteractionNetwork.FormException(PlayerWords.component(result.reason(),c));return PlayerWords.component(result.reason(),c);});
        // The decision, then each requirement on its own row with current and required values, then the contact rule.
        task("explain","Check service requirements",false,List.of(pick("organization","Organization","organization"),pick("permission","Service","guild_permission")),c->{var r=OrganizationApi.get(c.server()).explainOwn(c.player,c.id("organization"),c.id("permission"));var lines=new ArrayList<Object>();
            lines.add(tr("interaction.mcakingdoms.civic.explain.decision","Decision: %s",enumLabel(r.decision())));
            if(r.decision()==OrganizationExplanation.Decision.UNAVAILABLE)lines.addAll(r.reasons());
            else{CivicRequirements.service(c.player,c.id("organization"),c.id("permission")).forEach(row->lines.add(row.line()));lines.add(CivicRequirements.contact(c.player,c.id("organization")).line());}
            return lines;});
        for(String action:List.of("introduction","commissions","hospitality","workshop"))task(action,switch(action){case "introduction"->"Find another chapter";case "commissions"->"Browse commissions";case "hospitality"->"Request hospitality access";default->"Browse paid workshop jobs";},
                action.equals("workshop")?"Ask a nearby Lamplighters contact for a paid job at a recognized workshop. Needs MCA: Crime's workshop-legality check and MCA Quests; a missing requirement is explained by the service.":SERVICE_HELP,true,List.of(pick("organization","Organization","organization")),c->{var s=civic(c);var npc=s.nearbyContact(c.player,c.id("organization")).orElseThrow(()->new IllegalArgumentException("Visit an eligible nearby chapter contact first."));return result(c,switch(action){case "introduction"->s.introduction(c.player,npc);case "commissions"->s.commissions(c.player,npc);case "hospitality"->s.hospitality(c.player,npc);default->s.workshop(c.player,npc);});});
        task("chapters","Browse known chapters",false,List.of(pick("chapter","Chapter","chapter")),c->List.of(c.choice("chapter").shown(),c.choice("chapter").shownDetail()));
        task("institutions","Browse known institutions",false,List.of(pick("institution","Institution","institution")),c->List.of(c.choice("institution").shown(),c.choice("institution").shownDetail()));
        task("charter","Establish a guild chapter",true,List.of(pick("organization","Organization","organization"),pick("institution","Recognized institution","institution")),c->result(c,civic(c).charter(c.player,c.id("organization"),c.uuid("institution"))));
        task("appoint","Appoint a chapter contact",true,List.of(pick("chapter","Chapter","chapter"),pick("npc","Nearby resident","nearby_npc")),c->result(c,civic(c).appoint(c.player,c.uuid("chapter"),BasicTargets.contact(c,"npc"))));
        task("dismiss","Dismiss a chapter contact",true,List.of(pick("contact","Chapter contact","contact")),c->result(c,civic(c).dismiss(c.player,c.uuid("contact"))));
        task("qualify","Check a personal qualification",false,List.of(pick("kind","Qualification",c->enums(ProgressionPredicate.Kind.values())),text("subject","Skill, deity, race or lore entry name"),number("minimum","Minimum level or amount",1,MINIMUM)),c->{int minimum=c.integer("minimum",MINIMUM);var answer=ProgressionApi.evaluate(c.player,new ProgressionPredicate(ProgressionPredicate.Kind.valueOf(c.text("kind").toUpperCase(Locale.ROOT)),c.text("subject"),minimum));return List.of(answer.matches()?"Qualified":"Not qualified",answer.reason());});
    }
    /**
     * The service's reason travels as its translation key (F14): each player reads it in their own language, and a
     * refusal stays a refusal. The destination settlement's name is a literal, as player-facing names always are.
     */
    private static Object result(Context c,CivicService.Result result){
        if(!result.success())throw new InteractionNetwork.FormException(PlayerWords.component(result.reason(),c));
        var lines=new ArrayList<Object>();lines.add(PlayerWords.component(result.reason(),c));
        result.settlement().ifPresent(id->lines.add(tr("interaction.mcakingdoms.civic.destination","Destination: %s",BasicTargets.settlement(c,id))));
        return lines;}
    private CivicTasks(){}
}
