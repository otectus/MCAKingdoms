package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.politics.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import java.util.*;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

public final class PoliticalTasks {
    private static final Constraint PAGE=Constraint.range(1,5001,"page",1);
    /** Election and regency lengths in the units chosen before them; the same limits PoliticalTransition.Rule enforces. */
    private static Constraint term(Context c){return switch(c.text("time_unit")){case "minutes"->Constraint.range(1,2016,"minutes",60);case "seconds"->Constraint.range(60,120_960,"seconds",3600);default->Constraint.range(1200,2_419_200,"ticks",72_000);};}
    private static Constraint grace(Context c){return switch(c.text("time_unit")){case "minutes"->Constraint.range(1,144,"minutes",10);case "seconds"->Constraint.range(10,8640,"seconds",600);default->Constraint.range(200,172_800,"ticks",12_000);};}
    private static PoliticalService service(Context c){return KingdomsPoliticsApi.get(c.server());}
    private static Field kingdom(){return pick("kingdom","Kingdom","kingdom");}
    private static Field person(){return pick("person","Person","person");}
    // Definition and record lists are read for the chosen kingdom, so editing the kingdom asks for them again.
    private static Field definition(String kind){return pick("definition",words(kind)+" type",kind+"_definition").after("kingdom");}
    private static Field record(String kind){return pick("target",kind.equals("withdrawable")?"Agreement or petition":words(kind),kind).after("kingdom");}
    private static final String ACTION_HELP="Political authority is checked for the selected kingdom. Review the named parties and terms before applying.";
    private static final String READ_HELP="Shows the selected kingdom's records that you are allowed to see. Read-only.";
    private static void task(String id,String title,boolean operator,boolean change,List<Field> fields,Handler handler){task(id,title,change?ACTION_HELP:READ_HELP,operator,change,fields,handler);}
    private static void task(String id,String title,String help,boolean operator,boolean change,List<Field> fields,Handler handler){add(new Task("politics."+id,"Government",title,help,operator,change,fields,c->Long.toString(service(c).revision()),handler));}
    public static void init(){
        for(String kind:List.of("government","office","agreement","petition","institution","honor"))targets(kind+"_definition",c->{String kingdom=c.text("kingdom");if(kingdom.isEmpty())kingdom=c.choices("kingdom").stream().findFirst().map(ActionRegistry.Choice::value).orElse("");return service(c).page(c.player,UUID.randomUUID(),kingdom,"overview",0).definitions().stream().filter(d->d.kind().equals(kind)&&!d.id().endsWith(":commission_petition")).map(d->ActionRegistry.Choice.of(d.id(),definitionTitle(d.title()))).toList();});
        for(String kind:List.of("agreement","petition","institution","honor"))targets(kind,c->records(c,kind+"s"));
        targets("political_permission",c->Arrays.stream(Permission.values()).map(p->ActionRegistry.Choice.of(p.name(),enumLabel(p))).toList());
        targets("political_record",c->{var result=new ArrayList<ActionRegistry.Choice>();for(String kind:List.of("agreement","petition","institution","honor"))result.addAll(c.choices(kind));return result;});
        targets("withdrawable",c->{var list=new ArrayList<>(c.choices("agreement"));list.addAll(c.choices("petition"));return list;});
        targets("election",c->{var out=new ArrayList<ActionRegistry.Choice>();for(int offset=0;offset<=100000;offset+=64){var page=service(c).elections(c.player,offset,64);for(var e:page)out.add(ActionRegistry.Choice.of(e.id().toString(),tr("politics.mcakingdoms.election.choice","Election · %s",kingdomLabel(c,e.kingdom())),
                tr("politics.mcakingdoms.election.choice.detail","%s · %s ballot(s) cast; %s",enumLabel(e.state()),e.ballotsCast(),e.viewerVoted()?tr("politics.mcakingdoms.election.voted","your vote is recorded"):tr("politics.mcakingdoms.election.not_voted","your vote is not recorded yet")),
                Map.of("revision",Long.toString(e.revision()),"kingdom",e.kingdom())));if(page.size()<64)break;}return out;});
        targets("building",c->{BlockPos feet=c.player.blockPosition();var hit=c.player.pick(6,0,false);BlockPos aimed=BlockPos.containing(hit.getLocation());var out=new ArrayList<ActionRegistry.Choice>();for(var pos:new LinkedHashSet<>(List.of(feet,aimed)))out.add(ActionRegistry.Choice.of(pos.asLong()+"",tr("politics.mcakingdoms.building.choice","Building at %s",pos.toShortString()),pos.equals(feet)?tr("politics.mcakingdoms.building.standing","Where you are standing"):tr("politics.mcakingdoms.building.looking","Where you are looking"),Map.of("x",""+pos.getX(),"y",""+pos.getY(),"z",""+pos.getZ())));return out;});
        for(String tab:List.of("overview","council","agreements","petitions","institutions","honors","history"))task("read_"+tab,"Read "+words(tab).toLowerCase(Locale.ROOT),false,false,List.of(kingdom(),number("page","Page",1,PAGE)),c->{int page=c.integer("page",PAGE);var result=service(c).page(c.player,UUID.randomUUID(),c.text("kingdom"),tab,(page-1)*20);var lines=new ArrayList<Object>();if(!result.diagnostic().isBlank())lines.add(result.diagnostic());
            for(var row:present(c,result))lines.add(row.title().copy().append("\n").append(row.detail()));
            if(result.rows().isEmpty())lines.add(tr("politics.mcakingdoms.page.empty","No records on this page."));else if(result.hasMore())lines.add(tr("politics.mcakingdoms.page.more","More records follow on page %s.",page+1));return lines;});
        task("diagnose","Diagnose government availability","Shows whether a kingdom's government is available, dormant or missing, and why. Read-only.",true,false,List.of(kingdom()),c->service(c).page(c.player,UUID.randomUUID(),c.text("kingdom"),"overview",0).diagnostic());
        // Founding reaches GovernmentService's own rule: operators found any ungoverned kingdom; with politics.openFounding a
        // player whose recorded civic residence lies in a kingdom founds it, with that residence as capital and themselves as
        // leader. Ordinary players are offered only that scope; the service checks everything again when Apply runs.
        targets("founding_kingdom",PoliticalTasks::foundingKingdoms);
        targets("founding_capital",PoliticalTasks::foundingCapitals);
        targets("founding_leader",PoliticalTasks::foundingLeaders);
        targetHint("founding_kingdom",c->foundingRefusal(c).orElse(null));
        targetHint("founding_capital",c->tr("politics.mcakingdoms.founding.no_capital","No known settlement of this kingdom can be its capital. Discover or register one first."));
        action(Action.BOOTSTRAP,"Establish a government",false,List.of(pick("kingdom","Kingdom","founding_kingdom"),pick("target","Capital settlement","founding_capital").after("kingdom"),
                definition("government"),pick("person","First leader","founding_leader").after("kingdom")));
        task("founding_status","Check whether you can found a government","Explains whether a government can be founded for the kingdom of your recorded residence, and what is missing if not. Read-only.",false,false,List.of(),PoliticalTasks::foundingStatus);
        action(Action.SEAT,"Move the capital",false,List.of(kingdom(),pick("target","New capital","settlement")));
        var scope=pick("target","Office jurisdiction",c->{var list=new ArrayList<ActionRegistry.Choice>();list.add(ActionRegistry.Choice.of("",tr("politics.mcakingdoms.scope.kingdom_wide","Kingdom-wide office")));list.addAll(c.choices("settlement"));return list;});
        action(Action.APPOINT,"Appoint an official",false,List.of(kingdom(),definition("office"),person(),scope));
        action(Action.REMOVE_OFFICE,"Remove an official",false,List.of(kingdom(),definition("office"),scope));
        action(Action.DELEGATE,"Authorize a political responsibility",false,List.of(kingdom(),pick("person","Player","player"),pick("definition","Responsibility",c->enums(Arrays.stream(Permission.values()).filter(p->p!=Permission.DELEGATE).toArray(Permission[]::new)))));
        action(Action.REVOKE,"Revoke a player's mandates",false,List.of(kingdom(),pick("person","Player","player")));
        action(Action.PROPOSE,"Propose an agreement",false,List.of(kingdom(),pick("counterpart","Other kingdom","kingdom"),definition("agreement"),text("text","Agreement terms")));
        action(Action.SIGN,"Sign reviewed agreement terms",false,List.of(kingdom(),record("agreement")));
        for(Action action:List.of(Action.DECLINE,Action.TERMINATE))action(action,action==Action.DECLINE?"Decline an agreement":"End an agreement",false,List.of(kingdom(),record("agreement")));
        action(Action.WITHDRAW,"Withdraw an agreement or petition",false,List.of(kingdom(),record("withdrawable")));
        for(Action action:List.of(Action.REVIEW,Action.APPROVE,Action.REJECT))action(action,words(action.name())+" a petition",false,List.of(kingdom(),record("petition"),text("text","Reason for this decision")));
        for(String type:List.of("institution","honor","introduction")){
            var fields=new ArrayList<Field>();fields.add(kingdom());fields.add(pick("target","Settlement","settlement"));
            if(type.equals("institution"))fields.add(pick("building","Building to nominate","building"));
            if(type.equals("honor"))fields.add(person());if(type.equals("introduction"))fields.add(pick("counterpart","Other kingdom","kingdom"));fields.add(text("text","Explain your request"));
            task("petition_"+type,"Request "+(type.equals("institution")?"institution recognition":type.equals("honor")?"an honor":"a diplomatic introduction"),false,true,fields,c->execute(c,Action.PETITION,"mcakingdoms:"+type+"_petition"));
        }
        action(Action.RECOGNIZE,"Recognize a civic building",false,List.of(kingdom(),definition("institution"),pick("building","Building to recognize","building")));
        action(Action.REVALIDATE,"Revalidate an institution",false,List.of(kingdom(),record("institution")));
        action(Action.SUSPEND_RECOGNITION,"Suspend institution recognition",false,List.of(kingdom(),record("institution")));
        action(Action.HONOR,"Award an honor",false,List.of(kingdom(),definition("honor"),person(),text("text","Reason for the award")));
        action(Action.REVOKE_HONOR,"Revoke an honor",false,List.of(kingdom(),record("honor")));
        action(Action.NAME_SUCCESSOR,"Name a successor",false,List.of(kingdom(),person()));
        action(Action.ABDICATE,"Abdicate leadership",false,List.of(kingdom()));action(Action.SUCCEED,"Confirm succession",false,List.of(kingdom()));
        action(Action.HOUSE,"Record a political house",false,List.of(kingdom(),text("definition","House name"),person(),text("text","House motto","",true)));
        task("transition_rule","Set election and regency rules",false,true,List.of(kingdom(),toggle("elections","Allow elections?"),pick("electorate","Who votes",c->List.of(ActionRegistry.Choice.of("council",tr("politics.mcakingdoms.electorate.council","Council only"),tr("politics.mcakingdoms.electorate.council.detail","Office holders and mandate holders")),
                ActionRegistry.Choice.of("residents",tr("politics.mcakingdoms.electorate.residents","Council and residents"),tr("politics.mcakingdoms.electorate.residents.detail","Also every connected player whose civic residence is in the kingdom")))),toggle("regency","Allow regency?"),pick("time_unit","Duration units",c->List.of(ActionRegistry.Choice.of("minutes",tr("politics.mcakingdoms.unit.minutes","Game minutes")),ActionRegistry.Choice.of("seconds",tr("politics.mcakingdoms.unit.seconds","Game seconds")),ActionRegistry.Choice.of("ticks",tr("politics.mcakingdoms.unit.ticks","Game ticks (advanced)")))),number("election_duration","Election duration",60).within(PoliticalTasks::term).after("time_unit"),number("grace_duration","Tie grace duration",10).within(PoliticalTasks::grace).after("time_unit"),number("regency_duration","Regency duration",60).within(PoliticalTasks::term).after("time_unit"),optionalMulti("permissions","Regent responsibilities",c->enums(Arrays.stream(Permission.values()).filter(p->p!=Permission.DELEGATE).toArray(Permission[]::new)))),c->{Set<Permission> permissions=new HashSet<>();for(String v:c.text("permissions").split(","))if(!v.isBlank())permissions.add(Permission.valueOf(v.toUpperCase(Locale.ROOT)));return result(service(c).adoptTransitionRule(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"),new PoliticalTransition.Rule(c.bool("elections"),c.bool("regency"),duration(c,"election_duration"),duration(c,"grace_duration"),duration(c,"regency_duration"),8,permissions,PoliticalTransition.Electorate.valueOf(c.text("electorate").toUpperCase(Locale.ROOT)))));});
        task("election_open","Open a leadership election",false,true,List.of(kingdom(),multi("candidates","Nominate candidates",c->c.choices("player"))),c->result(service(c).openElection(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"),Arrays.stream(c.text("candidates").split(",")).map(UUID::fromString).toList())));
        task("election_vote","Vote in an election",false,true,List.of(pick("election","Election","election"),pick("candidate","Candidate",c->service(c).election(c.player,c.uuid("election")).orElseThrow().candidates().stream().map(id->new ActionRegistry.Choice(id.toString(),BasicTargets.player(c,id))).toList()).after("election")),c->result(service(c).castBallot(c.player,UUID.randomUUID(),service(c).revision(),c.uuid("election"),c.uuid("candidate"))));
        task("election_close","Close and count an election",false,true,List.of(pick("election","Election","election")),c->result(service(c).closeElection(c.player,UUID.randomUUID(),service(c).revision(),c.uuid("election"))));
        task("election_inspect","Read an election",false,false,List.of(pick("election","Election","election")),c->{var e=service(c).election(c.player,c.uuid("election")).orElseThrow();return List.of(c.choice("election").shown(),tr("politics.mcakingdoms.election.status","Status: %s",enumLabel(e.state())),
                tr("politics.mcakingdoms.election.candidates","Candidates: %s",String.join(", ",e.candidates().stream().map(id->BasicTargets.player(c,id)).toList())),tr("politics.mcakingdoms.election.ballots","Ballots: %s of %s",e.ballotsCast(),e.electorateSize()),
                tr("politics.mcakingdoms.election.you","You may vote: %s; your vote is recorded: %s",Component.translatable(e.viewerEligible()?"gui.yes":"gui.no"),Component.translatable(e.viewerVoted()?"gui.yes":"gui.no")),
                tr("politics.mcakingdoms.election.times","Voting closes in: %s; tie grace ends in: %s",Names.remainingText(c.server(),e.deadline()),Names.remainingText(c.server(),e.graceUntil())),
                tr("politics.mcakingdoms.election.winner","Winner: %s",e.winner().<Object>map(id->BasicTargets.player(c,id)).orElse(tr("politics.mcakingdoms.election.no_winner","no winner recorded"))));});
        task("appoint_regent","Appoint a temporary regent",false,true,List.of(kingdom(),pick("person","Regent","player")),c->result(service(c).appointRegent(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"),c.uuid("person"))));
        task("end_regency","End a regency",false,true,List.of(kingdom()),c->result(service(c).endRegency(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"))));
    }
    private static Optional<dev.otectus.mcakingdoms.api.SettlementView> residence(Context c){
        var api=dev.otectus.mcakingdoms.api.McaKingdomsApi.get(c.server());
        return api.getCivicIdentity(c.player).flatMap(dev.otectus.mcakingdoms.api.CivicIdentityView::residenceSettlement).map(api::resolveSettlementId).flatMap(api::getSettlement);
    }
    private static boolean governed(Context c,String kingdom){return service(c).government(kingdom).isPresent();}
    private static boolean capitalsConflict(){return net.minecraftforge.fml.ModList.get().isLoaded("mcacapitals");}
    /** Why this player cannot found a government now, or empty when the founding task can offer them a kingdom. */
    private static Optional<Object> foundingRefusal(Context c){
        if(capitalsConflict())return Optional.of(tr("politics.mcakingdoms.founding.capitals","MCA: Capitals governs kingdoms on this server, so MCA: Kingdoms governments cannot be founded. Use the Capitals government system."));
        if(c.player.hasPermissions(2))return foundingKingdoms(c).isEmpty()?Optional.of(tr("politics.mcakingdoms.founding.all_governed","Every kingdom already has a government. Read a kingdom's Overview for its leader and capital.")):Optional.empty();
        if(!dev.otectus.mcakingdoms.config.McaKingdomsConfig.OPEN_FOUNDING.get())return Optional.of(tr("politics.mcakingdoms.founding.closed","Resident founding is turned off on this server (politics.openFounding = false). Only an operator can establish a government."));
        var home=residence(c);
        if(home.isEmpty())return Optional.of(tr("politics.mcakingdoms.founding.no_residence","You have no recorded civic residence. Resident founding needs one, recorded for you by an operator or an addon; walking through, sleeping in or trading with a settlement does not record it."));
        String kingdom=home.get().kingdomId().toString();
        if(governed(c,kingdom))return Optional.of(tr("politics.mcakingdoms.founding.governed","%s, the kingdom of your residence %s, already has a government. Read its Overview, or ask its leader about offices.",kingdomLabel(c,kingdom),home.get().displayName()));
        return Optional.empty();
    }
    private static List<ActionRegistry.Choice> foundingKingdoms(Context c){
        if(capitalsConflict())return List.of();
        var ungoverned=c.choices("kingdom").stream().filter(k->!governed(c,k.value())).toList();
        if(c.player.hasPermissions(2))return ungoverned.stream().map(k->ActionRegistry.Choice.of(k.value(),k.shown(),tr("politics.mcakingdoms.founding.ungoverned","No government yet"))).toList();
        if(!dev.otectus.mcakingdoms.config.McaKingdomsConfig.OPEN_FOUNDING.get())return List.of();
        var home=residence(c);if(home.isEmpty())return List.of();String kingdom=home.get().kingdomId().toString();
        return ungoverned.stream().filter(k->k.value().equals(kingdom)).map(k->ActionRegistry.Choice.of(k.value(),k.shown(),
                tr("politics.mcakingdoms.founding.your_kingdom","The kingdom of your recorded residence, %s",home.get().displayName()))).toList();
    }
    private static List<ActionRegistry.Choice> foundingCapitals(Context c){
        String kingdom=c.text("kingdom");
        if(c.player.hasPermissions(2))return c.choices("settlement").stream().filter(s->kingdom.equals(s.metadata().get("kingdom"))).toList();
        return residence(c).filter(home->home.kingdomId().toString().equals(kingdom)).map(home->List.of(ActionRegistry.Choice.of(home.id().toString(),Component.literal(home.displayName()),
                tr("politics.mcakingdoms.founding.your_residence","Your recorded residence"),Map.of("revision",Long.toString(home.revision()),"kingdom",kingdom)))).orElse(List.of());
    }
    private static List<ActionRegistry.Choice> foundingLeaders(Context c){
        if(c.player.hasPermissions(2)){var out=new ArrayList<ActionRegistry.Choice>();out.add(self(c));c.choices("person").stream().filter(p->!p.value().equals(c.player.getUUID().toString())).forEach(out::add);return out;}
        return List.of(self(c));
    }
    private static ActionRegistry.Choice self(Context c){
        return ActionRegistry.Choice.of(c.player.getUUID().toString(),Component.literal(c.player.getGameProfile().getName()),tr("politics.mcakingdoms.founding.self","You, as the first leader"));
    }
    /** A plain account of the player's own founding eligibility; reveals only their residence and public government facts. */
    private static List<Object> foundingStatus(Context c){
        var lines=new ArrayList<Object>();var home=residence(c);
        lines.add(home.map(h->tr("politics.mcakingdoms.founding.status.residence","Your recorded civic residence: %s, in %s.",h.displayName(),kingdomLabel(c,h.kingdomId().toString())))
                .orElse(tr("politics.mcakingdoms.founding.status.no_residence","You have no recorded civic residence.")));
        lines.add(dev.otectus.mcakingdoms.config.McaKingdomsConfig.OPEN_FOUNDING.get()?tr("politics.mcakingdoms.founding.status.open","Resident founding is on: a resident may found their kingdom's first government, with their residence as capital and themselves as leader.")
                :tr("politics.mcakingdoms.founding.status.closed","Resident founding is off on this server; only operators establish governments."));
        if(c.player.hasPermissions(2))lines.add(tr("politics.mcakingdoms.founding.status.operator","As an operator you may establish any kingdom's first government, choosing its capital and first leader."));
        var refusal=foundingRefusal(c);
        lines.add(refusal.isEmpty()?tr("politics.mcakingdoms.founding.status.eligible","You can found a government now: open Establish a government and review the terms before applying."):refusal.get());
        lines.add(tr("politics.mcakingdoms.founding.status.note","Residence is a civic record, not something gained by visiting, sleeping or holding a book. Founding records a government only; it claims no territory and grants no offices beyond its constitution."));
        return lines;
    }
    private static Component kingdomLabel(Context c,String kingdom){return c.choices("kingdom").stream().filter(k->k.value().equals(kingdom)).findFirst().map(ActionRegistry.Choice::shown).orElse(Component.literal(kingdom));}
    private static void action(Action action,String title,boolean operator,List<Field> fields){task(action.name().toLowerCase(Locale.ROOT),title,operator,true,fields,c->execute(c,action,c.text("definition")));}
    private static Object execute(Context c,Action action,String definition){
        Person person=null;if(!c.text("person").isEmpty()){var entity=BasicTargets.entity(c,"person");person=new Person(entity.getUUID(),entity instanceof Player?Politics.Kind.PLAYER:Politics.Kind.NPC);}
        String hash=action==Action.SIGN?c.meta("target","hash"):"";int x=0,y=0,z=0;if(c.choice("building")!=null){x=Integer.parseInt(c.meta("building","x"));y=Integer.parseInt(c.meta("building","y"));z=Integer.parseInt(c.meta("building","z"));}
        if(action==Action.DELEGATE)definition=definition.toUpperCase(Locale.ROOT);
        return result(service(c).execute(c.player,new Request(UUID.randomUUID(),service(c).revision(),action,c.text("kingdom"),definition,c.text("target"),person,c.text("counterpart"),c.text("text"),hash,x,y,z)));
    }
    /** A committed decision says so in the reader's language; a refusal carries the service's own reason. */
    private static Object result(Result value){if(!value.success())throw new IllegalArgumentException(value.message());
        return tr("politics.mcakingdoms.result.recorded","Recorded: the political decision is saved and in effect.");}
    static List<ActionRegistry.Choice> records(Context c,String tab){
        var out=new ArrayList<ActionRegistry.Choice>();List<String> kingdoms=c.text("kingdom").isEmpty()?c.choices("kingdom").stream().map(ActionRegistry.Choice::value).toList():List.of(c.text("kingdom"));
        for(String kingdom:kingdoms)for(int offset=0;offset<=100000;offset+=20){var page=service(c).page(c.player,UUID.randomUUID(),kingdom,tab,offset);var shown=present(c,page);for(int i=0;i<page.rows().size();i++){var r=page.rows().get(i);if(!r.id().isEmpty()&&out.stream().noneMatch(v->v.value().equals(r.id())))out.add(ActionRegistry.Choice.of(r.id(),
                tr("politics.mcakingdoms.record.choice","%s · %s",shown.get(i).title(),kingdomLabel(c,kingdom)),shown.get(i).detail(),Map.of("revision",Long.toString(page.revision()),"hash",r.termsHash(),"kingdom",kingdom)));}if(!page.hasMore())break;}return out;
    }
    private static long duration(Context c,String key){return Math.multiplyExact(c.number(key),switch(c.text("time_unit")){case "minutes"->1200;case "seconds"->20;default->1;});}
    public static String gameTime(long ticks){return "game day "+(ticks/24000)+", minute "+((ticks%24000)/1200);}
    /** Rows as players read them, from the political service; falls back to the page's own text for other providers. */
    private static List<dev.otectus.mcakingdoms.politics.GovernmentService.PresentedRow> present(Context c,Page page){
        if(service(c) instanceof dev.otectus.mcakingdoms.politics.GovernmentService government)return government.present(c.player,page);
        return page.rows().stream().map(r->new dev.otectus.mcakingdoms.politics.GovernmentService.PresentedRow(definitionTitle(r.title()),Component.literal(r.detail()))).toList();
    }
    private static Component definitionTitle(String title){return PlayerWords.isKey(title)?tr(title,PlayerWords.text(title)):Component.literal(title);}
    private static String kingdomName(Context c,String value){return c.choices("kingdom").stream().filter(k->k.value().equals(value)).map(ActionRegistry.Choice::label).findFirst().orElse("Kingdom");}
    private PoliticalTasks(){}
}
