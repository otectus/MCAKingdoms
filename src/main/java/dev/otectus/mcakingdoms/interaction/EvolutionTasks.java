package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.api.factions.organization.*;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsMobilization;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsTransfer;
import dev.otectus.mcakingdoms.evolution.*;
import dev.otectus.mcakingdoms.evolution.drama.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;


import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/** Server-owned GUI adapters for the evolving-world services. */
public final class EvolutionTasks {
    /** ProtectionService accepts 24,000 to 24,000,000 game ticks (one to a thousand game days). */
    private static final Constraint PACT_DURATION=Constraint.range(24_000,24_000_000,"ticks",72_000);
    private static EvolutionService evolution(Context c){return EvolutionRuntime.get(c.server());}
    private static ProtectionService protection(Context c){return new ProtectionService(c.server());}
    private static RecruitTransferService transfers(Context c){return new RecruitTransferService(c.server());}
    private static DramaService drama(Context c){return DramaRuntime.get(c.server());}
    private static OrganizationService organizations(Context c){return OrganizationApi.get(c.server());}

    public static void init(){
        targets("scenario",EvolutionTasks::scenarioChoices);
        targets("pact",EvolutionTasks::pactChoices);
        targets("obligation",EvolutionTasks::obligationChoices);
        targets("transfer",EvolutionTasks::transferChoices);
        targets("reconciliation_transfer",EvolutionTasks::reconciliationChoices);
        targets("drama",EvolutionTasks::dramaChoices);
        targets("merge",EvolutionTasks::mergeChoices);
        targets("merge_obligation",EvolutionTasks::mergeObligationChoices);
        targets("native_recruit",EvolutionTasks::recruitChoices);
        targets("native_group",EvolutionTasks::groupChoices);
        targets("own_native_group",EvolutionTasks::ownGroupChoices);
        targets("shared_transfer_destination",EvolutionTasks::ownedShareChoices);
        targets("organization_template",EvolutionTasks::templateChoices);
        targets("organization_lifecycle",EvolutionTasks::lifecycleChoices);

        evolutionTasks(); protectionTasks(); transferTasks(); dramaTasks(); organizationTasks();
    }

    private static void evolutionTasks(){
        task("evolution.overview","Evolution","World evolution status","Read opt-in policy, regions, digest state and visible opportunities.",false,false,List.of(),
                c->Long.toString(evolution(c).revision()),EvolutionTasks::evolutionOverview);
        task("evolution.inspect","Evolution","Inspect opportunity","Read frozen evidence, outcomes and your contribution.",false,false,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->scenarioLines(c,selectedScenario(c)));
        task("evolution.family","Evolution","Request family introduction","Open a private opportunity from a verified reciprocal MCA marriage and civic residence.",false,true,List.of(),
                c->Long.toString(evolution(c).revision()),c->evolution(c).familyIntroduction(c.player));
        task("evolution.digest","Evolution","Change political digest","Subscribe to or leave the bounded personal digest.",false,true,List.of(toggle("enabled","Receive digest")),
                c->Long.toString(evolution(c).revision()),c->evolution(c).subscribe(c.player,c.bool("enabled")));
        task("evolution.contribute","Evolution","Contribute to opportunity","Record one receipt-backed or authority-backed choice.",false,true,
                List.of(pick("scenario","Opportunity","scenario"),pick("outcome","Contribution",EvolutionTasks::contributionChoices)),EvolutionTasks::scenarioVersion,
                c->Outcomes.scenarioAction(c,"contribute",c.uuid("scenario"),()->evolution(c).contribute(c.player,c.uuid("scenario"),c.revision("scenario"),EvolutionState.Outcome.valueOf(c.text("outcome")))));
        probe("evolution.contribute",(c,operation)->Outcomes.scenario(c,"contribute",UUID.fromString(operation)));
        task("evolution.withdraw","Evolution","Withdraw contribution","Withdraw your current choice; its evidence receipt remains consumed.",false,true,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->Outcomes.scenarioAction(c,"withdraw",c.uuid("scenario"),()->evolution(c).withdraw(c.player,c.uuid("scenario"),c.revision("scenario"))));
        probe("evolution.withdraw",(c,operation)->Outcomes.scenario(c,"withdraw",UUID.fromString(operation)));
        task("evolution.resolve","Evolution","Resolve opportunity","Apply one supported outcome after enough verified contributions.",false,true,
                List.of(pick("scenario","Opportunity","scenario"),pick("outcome","Outcome",EvolutionTasks::outcomeChoices),pick("kingdom","Counterpart government",EvolutionTasks::counterpartChoices)),EvolutionTasks::scenarioVersion,
                c->Outcomes.scenarioAction(c,"resolve",c.uuid("scenario"),()->evolution(c).resolve(c.player,c.uuid("scenario"),c.revision("scenario"),EvolutionState.Outcome.valueOf(c.text("outcome")),c.text("kingdom"))));
        probe("evolution.resolve",(c,operation)->Outcomes.scenario(c,"resolve",UUID.fromString(operation)));
        task("evolution.retry","Evolution","Retry pending resolution","Resume the same durable owner request after a provider interruption.",false,true,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->Outcomes.scenarioAction(c,"retry",c.uuid("scenario"),()->evolution(c).retry(c.player,c.uuid("scenario"))));
        probe("evolution.retry",(c,operation)->Outcomes.scenario(c,"retry",UUID.fromString(operation)));
        task("evolution.release","Evolution","Release rejected resolution","Reopen an uncommitted rejected owner request for a fresh choice.",false,true,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->Outcomes.scenarioAction(c,"release",c.uuid("scenario"),()->evolution(c).releaseRejected(c.player,c.uuid("scenario"))));
        probe("evolution.release",(c,operation)->Outcomes.scenario(c,"release",UUID.fromString(operation)));
        task("evolution.contract","Evolution","Open opportunity service","Offer the matching scoped MCA civilian contract from a nearby contact.",false,true,
                List.of(pick("scenario","Opportunity","scenario"),pick("outcome","Service",EvolutionTasks::contractChoices),pick("npc","Institutional contact","nearby_npc")),EvolutionTasks::scenarioVersion,
                c->EvolutionContracts.offer(c.player,c.uuid("scenario"),c.uuid("npc"),EvolutionContracts.kind(EvolutionState.Outcome.valueOf(c.text("outcome")))));
        task("evolution.configure","Evolution","Configure world evolution","Enable or pause generation and separately allow dramatic scenarios.",true,true,
                List.of(toggle("enabled","Enable evolution"),toggle("drama","Enable dramatic scenarios")),c->Long.toString(evolution(c).revision()),
                c->evolution(c).configure(c.player,c.bool("enabled"),c.bool("drama")));
        task("evolution.region","Evolution","Configure evolution region","Make a known settlement eligible or pause it.",true,true,
                List.of(pick("settlement","Settlement","settlement"),toggle("enabled","Eligible region")),c->Long.toString(evolution(c).revision()),
                c->evolution(c).region(c.player,c.uuid("settlement"),c.bool("enabled")));
    }

    private static void protectionTasks(){
        task("protection.overview","Protection","Browse protection pacts","Read only pacts your public status or current authority permits.",false,false,List.of(),
                c->Long.toString(protection(c).revision()),EvolutionTasks::protectionOverview);
        task("protection.inspect","Protection","Inspect protection pact","Read frozen duties and visible beneficiary obligations.",false,false,List.of(pick("pact","Pact","pact")),EvolutionTasks::pactVersion,
                c->pactLines(c,selectedPact(c)));
        task("protection.propose","Protection","Propose protection pact","Propose bounded voluntary duties between two active governments.",false,true,
                List.of(pick("protector","Protector government","kingdom"),pick("subordinate","Subordinate government","kingdom"),pick("settlement","Beneficiary settlement","settlement"),
                        multi("duties","Duties",c->enums(ProtectionState.Duty.values())),number("duration","Duration in game ticks",72000,PACT_DURATION),
                        number("notice","Exit notice in game ticks",12000).within(c->Constraint.range(1200,Math.max(1200,c.text("duration").isBlank()?1200:c.number("duration")),"ticks",12000)).after("duration"),text("terms","Plain-language terms")),
                c->Long.toString(protection(c).revision()),c->protection(c).propose(c.player,c.text("protector"),c.text("subordinate"),c.uuid("settlement"),duties(c),c.number("duration"),c.number("notice"),c.text("terms")));
        task("protection.sign","Protection","Sign protection pact","Ratify frozen terms for one of the two governments.",false,true,
                List.of(pick("pact","Pact","pact"),pick("kingdom","Signing government",EvolutionTasks::pactKingdomChoices)),EvolutionTasks::pactVersion,
                c->protection(c).sign(c.player,c.uuid("pact"),c.text("kingdom"),c.revision("pact")));
        task("protection.exit","Protection","Give peaceful exit notice","Start the pact's frozen notice period without confiscating assets or troops.",false,true,
                List.of(pick("pact","Pact","pact"),pick("kingdom","Exiting government",EvolutionTasks::pactKingdomChoices)),EvolutionTasks::pactVersion,
                c->protection(c).exit(c.player,c.uuid("pact"),c.text("kingdom"),c.revision("pact")));
        task("protection.request","Protection","Request pact duty","Open one enumerated voluntary obligation.",false,true,
                List.of(pick("pact","Pact","pact"),pick("duty","Duty",EvolutionTasks::pactDutyChoices)),EvolutionTasks::pactVersion,
                c->protection(c).request(c.player,c.uuid("pact"),ProtectionState.Duty.valueOf(c.text("duty")),c.revision("pact")));
        task("protection.fulfill","Protection","Fulfill pact obligation","Use your matching completed scoped native contract receipt.",false,true,List.of(pick("obligation","Obligation","obligation")),EvolutionTasks::obligationVersion,
                c->protection(c).fulfill(c.player,c.uuid("obligation"),c.revision("obligation")));
        task("protection.refuse","Protection","Refuse pact obligation","Record an explicit reason without declaring war or transferring property.",false,true,
                List.of(pick("obligation","Obligation","obligation"),text("reason","Reason")),EvolutionTasks::obligationVersion,
                c->protection(c).refuse(c.player,c.uuid("obligation"),c.revision("obligation"),c.text("reason")));
        task("protection.contract","Protection","Open obligation service","Offer the obligation's matching scoped MCA civilian contract.",false,true,
                List.of(pick("obligation","Obligation","obligation"),pick("npc","Institutional contact","nearby_npc")),EvolutionTasks::obligationVersion,
                c->{var o=selectedObligation(c);return EvolutionContracts.offer(c.player,o.id(),c.uuid("npc"),EvolutionContracts.kind(o.duty()));});
    }

    private static void transferTasks(){
        task("transfer.overview","Transfers","Browse recruit transfers","Read your bilateral proposals and retained recovery state.",false,false,List.of(),EvolutionTasks::transferVersion,EvolutionTasks::transferOverview);
        task("transfer.inspect","Transfers","Inspect recruit transfer","Read the private parties, notice, phase and recovery detail.",false,false,List.of(pick("transfer","Transfer","transfer")),EvolutionTasks::selectedTransferVersion,c->transferLines(c,selectedTransfer(c)));
        task("transfer.propose","Transfers","Propose recruit transfer","Offer one nearby owned native recruit to an online player and their active native group.",false,true,
                List.of(pick("unit","Recruit","native_recruit"),pick("recipient","Recipient","player"),pick("group","Recipient group","native_group"),toggle("family","Family-backed introduction")),EvolutionTasks::transferProposalVersion,
                c->Outcomes.transferProposal(c,()->transfers(c).propose(c.player,c.uuid("unit"),c.uuid("recipient"),c.uuid("group"),c.bool("family"))));
        probe("transfer.propose",(c,operation)->Outcomes.transfer(c,"propose",UUID.fromString(operation)));
        task("transfer.share_destination","Transfers","Share a recruit transfer destination","Allow one named online sender to select one of your native groups for this connection only.",false,true,
                List.of(pick("sender","Allowed sender",c->c.choices("player").stream().filter(v->!v.value().equals(c.player.getUUID().toString())).toList()),pick("group","Your destination group","own_native_group")),c->RecruitTransferDestinations.revision(c.server())+":"+c.text("sender")+":"+c.text("group"),
                c->RecruitTransferDestinations.share(c.player,c.uuid("sender"),c.uuid("group"),c.choice("group").label()));
        task("transfer.revoke_destination","Transfers","Revoke a shared transfer destination","Remove a sender's connection-scoped permission to see and select your destination group.",false,true,
                List.of(pick("destination","Shared destination","shared_transfer_destination")),c->RecruitTransferDestinations.revision(c.server())+":"+c.text("destination"),
                c->RecruitTransferDestinations.revoke(c.player,UUID.fromString(c.meta("destination","sender")),UUID.fromString(c.meta("destination","group"))));
        transferAction("consent","Consent to recruit transfer","Recipient accepts the reviewed terms.",(s,c)->s.consent(c.player,c.uuid("transfer"),c.revision("transfer")));
        transferAction("cancel","Cancel recruit transfer","Either party cancels before native application.",(s,c)->s.cancel(c.player,c.uuid("transfer"),c.revision("transfer")));
        transferAction("apply","Apply recruit transfer","Source owner applies after consent and the notice period.",(s,c)->s.apply(c.player,c.uuid("transfer"),c.revision("transfer")));
        transferAction("confirm","Confirm native transfer","Retry native persistence acknowledgment for retained intent.",(s,c)->s.confirm(c.player,c.uuid("transfer")));
        transferAction("restore","Restore original ownership","Guardedly restore the saved original native state with both parties online.",(s,c)->s.restore(c.player,c.uuid("transfer")));
        task("transfer.reconciliation_preview","Transfers","Review native reconciliation","Inspect the current native fingerprint and observation without writing accounting.",true,false,
                List.of(pick("transfer","Pending transfer",EvolutionTasks::reconciliationChoices)),EvolutionTasks::reconciliationVersion,c->reconciliationLines(transfers(c).reconciliationReview(c.player,c.uuid("transfer"))));
        task("transfer.reconcile","Transfers","Acknowledge native reconciliation","Record the reviewed native state and operator reason without rewriting provider counts.",true,true,
                List.of(pick("transfer","Reviewed transfer",EvolutionTasks::reconciliationChoices),text("reason","Operator reconciliation note")),EvolutionTasks::reconciliationVersion,
                c->transfers(c).reconcile(c.player,c.uuid("transfer"),c.revision("transfer"),c.meta("transfer","fingerprint"),c.text("reason")));
    }

    private static void dramaTasks(){
        task("drama.overview","World events","Browse world events","Read the world events you take part in and the proposals you may currently consent to.",false,false,List.of(),c->Long.toString(drama(c).revision()),EvolutionTasks::dramaOverview);
        task("drama.inspect","World events","Inspect a world event","Preview its frozen objective, terms, parties and remaining online time.",false,false,List.of(pick("drama","World event","drama")),EvolutionTasks::dramaVersion,c->dramaLines(c,selectedDrama(c)));
        task("drama.propose","World events","Propose a world event","Create a bounded proposal from an installed world event template and real authority evidence.",false,true,
                List.of(pick("template","World event template",c->drama(c).templates().stream().map(t->Choice.of(t.id(),Component.literal(t.title()),e("template.detail","%s · %s",enumLabel(t.kind()),t.objective()))).toList()),
                        pick("settlement","Involved settlement","settlement"),pick("subject","Source organization",EvolutionTasks::dramaSubjectChoices)),c->Long.toString(drama(c).revision()),
                c->{UUID request=UUID.randomUUID();return Outcomes.dramaAction(c,"propose",request,()->drama(c).propose(c.player,request,drama(c).revision(),c.text("template"),c.uuid("settlement"),c.text("subject")));});
        probe("drama.propose",(c,operation)->Outcomes.drama(c,"propose",UUID.fromString(operation)));
        dramaAction("consent","Consent to a world event","Accept as the eligible opposing commander or independent active source member.",(s,c)->s.consent(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("execute","Carry out a world event's outcome","The proposer begins the one authority-checked native or organization operation.",(s,c)->s.execute(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("negotiate","Negotiate a world event","Propose the installed nonviolent accord outcome.",(s,c)->s.negotiate(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("exit","Leave a world event","Choose the frozen nonviolent exit; civic identity and assets are kept.",(s,c)->s.exit(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("recover","Recover a world event's operation","Retry or withdraw a retained provider operation using its durable intent.",(s,c)->s.recover(c.player,c.uuid("drama"),c.revision("drama")));
    }

    private static void organizationTasks(){
        task("organization.lifecycle_overview","Organizations","Organization history and status","Read founded organizations, merges, dissolutions and the proposals visible to you.",false,false,List.of(),
                c->Long.toString(organizations(c).revision()),EvolutionTasks::organizationOverview);
        task("organization.lifecycle_inspect","Organizations","Inspect an organization's history and status","Read a founded organization's sponsor government, state and, after a merge, where its records now resolve.",false,false,
                List.of(pick("organization","Organization","organization_lifecycle")),EvolutionTasks::organizationVersion,c->lifecycleLines(c,organizations(c).lifecycle(c.id("organization")).orElseThrow()));
        task("organization.found","Organizations","Found chartered organization","Create a stable organization id from its readable name using an installed charter template.",false,true,
                List.of(pick("template","Charter template","organization_template"),pick("kingdom","Sponsor government","kingdom"),text("name","Organization name")),c->Long.toString(organizations(c).revision()),
                c->{var id=slug(c.text("name"));return organizationResult(organizations(c).found(c.player,id,c.id("template"),c.text("kingdom"),c.text("name"),organizations(c).revision()));});
        task("organization.merge_propose","Organizations","Propose organization merge","Ask a second active dynamic organization to merge while preserving standing and member choice.",false,true,
                List.of(pick("source","Source organization",EvolutionTasks::activeLifecycleChoices),pick("target","Target organization",EvolutionTasks::activeLifecycleChoices)),c->Long.toString(organizations(c).revision()),
                c->organizationResult(organizations(c).proposeMerge(c.player,c.id("source"),c.id("target"),organizations(c).revision())));
        organizationMergeAction("merge_consent","Consent to organization merge","Target charter authority accepts the proposal.",(s,c)->s.consentMerge(c.player,c.uuid("merge"),s.revision()));
        organizationMergeAction("merge_opt_out","Opt out of organization merge","An active source member preserves their independent membership choice.",(s,c)->s.optOutMerge(c.player,c.uuid("merge"),s.revision()));
        organizationMergeAction("merge_finalize","Finalize organization merge","Apply both consents once every accepted commission is completed, cancelled or transferred to the joined organization.",(s,c)->s.finalizeMerge(c.player,c.uuid("merge"),s.revision()));
        task("organization.merge_novate","Organizations","Transfer accepted work","Move one accepted commission of the merging organization to the organization it joins, with the required authority and consent.",false,true,
                List.of(pick("merge","Merge proposal","merge"),pick("obligation","Accepted commission","merge_obligation")),EvolutionTasks::mergeVersion,
                c->organizationResult(organizations(c).novateMergeObligation(c.player,c.uuid("merge"),c.uuid("obligation"),organizations(c).revision())));
        task("organization.dissolve","Organizations","Dissolve organization","Freeze benefits and retain a historical tombstone after obligations are cleared.",false,true,
                List.of(pick("organization","Organization",EvolutionTasks::activeLifecycleChoices)),EvolutionTasks::organizationVersion,
                c->organizationResult(organizations(c).dissolve(c.player,c.id("organization"),organizations(c).revision())));
    }

    private static void task(String id,String category,String title,String help,boolean operator,boolean consequential,List<Field> fields,java.util.function.Function<Context,String> version,Handler handler){
        add(new Task(id,category,title,help,operator,consequential,fields,version,handler));
    }
    private interface TransferAction{String run(RecruitTransferService service,Context context);}
    /** A transfer action whose result is read from the transfer's phase afterwards, and again on Check (F07). */
    private static void transferAction(String id,String title,String help,TransferAction action){task("transfer."+id,"Transfers",title,help,false,true,List.of(pick("transfer","Transfer","transfer")),EvolutionTasks::selectedTransferVersion,
            c->Outcomes.transferAction(c,id,c.uuid("transfer"),()->action.run(transfers(c),c)));
        probe("transfer."+id,(c,operation)->Outcomes.transfer(c,id,UUID.fromString(operation)));}
    private interface DramaAction{String run(DramaService service,Context context);}
    /** A world-event action whose result is read from the event's phase afterwards, and again on Check (F07). */
    private static void dramaAction(String id,String title,String help,DramaAction action){task("drama."+id,"World events",title,help,false,true,List.of(pick("drama","World event","drama")),EvolutionTasks::dramaVersion,
            c->Outcomes.dramaAction(c,id,c.uuid("drama"),()->action.run(drama(c),c)));
        probe("drama."+id,(c,operation)->Outcomes.drama(c,id,UUID.fromString(operation)));}
    private interface MergeAction{OrganizationLifecycleResult run(OrganizationService service,Context context);}
    private static void organizationMergeAction(String id,String title,String help,MergeAction action){task("organization."+id,"Organizations",title,help,false,true,List.of(pick("merge","Merge proposal","merge")),EvolutionTasks::mergeVersion,c->organizationResult(action.run(organizations(c),c)));}

    private static Component e(String key,String fallback,Object... args){return tr("interaction.mcakingdoms.evolution."+key,fallback,args);}
    /** A kingdom by its display name; ids only when no definition names it. */
    private static Component kingdom(Context c,String id){return c.choices("kingdom").stream().filter(k->k.value().equals(id)).findFirst().map(Choice::shown).orElse(Component.literal(id==null?"":id));}
    private static Choice choice(Object value,Component label,Component detail,long revision){return Choice.of(value.toString(),label,detail,Map.of("revision",Long.toString(revision)));}
    private static List<Choice> scenarioChoices(Context c){return evolution(c).scenarios(c.player).stream().map(v->choice(v.id(),Component.literal(v.title()),e("scenario.choice","%s · %s",enumLabel(v.phase()),BasicTargets.settlement(c,v.settlement())),v.revision())).toList();}
    private static List<Choice> pactChoices(Context c){return protection(c).pacts(c.player).stream().map(v->choice(v.id(),e("pact.parties","%s → %s",kingdom(c,v.protector()),kingdom(c,v.subordinate())),e("pact.choice","%s · %s",enumLabel(v.phase()),v.terms()),v.revision())).toList();}
    private static List<Choice> obligationChoices(Context c){return protection(c).obligations(c.player).stream().map(v->choice(v.id(),enumLabel(v.duty()),e("obligation.choice","%s · due %s",enumLabel(v.status()),Names.remainingText(c.server(),v.deadline())),v.revision())).toList();}
    private static List<Choice> transferChoices(Context c){return transfers(c).transfers(c.player).stream().map(v->Choice.of(v.id().toString(),e("transfer.parties","%s → %s",recruitName(c,v.unit()),BasicTargets.player(c,v.recipient())),
            e("transfer.choice","From %s · to the group %s · %s · notice ends: %s · deadline: %s · equipment as recorded at the proposal",BasicTargets.player(c,v.owner()),nativeGroupName(c,v.recipient(),v.recipientGroup()),
                    enumLabel(v.phase()),Names.remainingText(c.server(),v.noticeUntil()),Names.remainingText(c.server(),v.deadline())),
            Map.of("revision",Long.toString(v.revision()),"equipment",v.equipment()))).toList();}
    private static List<Choice> dramaChoices(Context c){return drama(c).dramas(c.player).stream().map(v->choice(v.id(),Component.literal(v.title()),v.canConsent()?e("drama.awaiting","Awaiting your consent · %s",v.objective()):Component.literal(v.objective()),v.revision())).toList();}
    private static List<Choice> mergeChoices(Context c){return organizations(c).merges(c.player).stream().map(v->choice(v.id(),e("merge.parties","%s → %s",organizationName(c,v.source()),organizationName(c,v.target())),enumLabel(v.state()),v.revision())).toList();}
    private static List<Choice> mergeObligationChoices(Context c){if(c.text("merge").isBlank())return List.of();return organizations(c).mergeObligations(c.player,c.uuid("merge")).stream()
            .map(o->Choice.of(o.id().toString(),e("merge.commission","Commission %s",o.quest()),e("merge.accepted_by","Accepted by %s",BasicTargets.player(c,o.player())))).toList();}
    private static List<Choice> templateChoices(Context c){return organizations(c).foundingTemplates().stream().map(v->Choice.of(v.id().toString(),tr(v.nameKey(),PlayerWords.text(v.nameKey())),tr(v.descriptionKey(),PlayerWords.text(v.descriptionKey())))).toList();}
    private static List<Choice> lifecycleChoices(Context c){return organizations(c).lifecycles(c.player).stream().map(v->choice(v.id(),Component.literal(v.displayName()),e("lifecycle.choice","%s · sponsored by %s",enumLabel(v.state()),kingdom(c,v.sponsorKingdom())),v.revision())).toList();}
    private static List<Choice> activeLifecycleChoices(Context c){return organizations(c).lifecycles(c.player).stream().filter(v->v.state()==OrganizationLifecycleView.State.ACTIVE).map(v->choice(v.id(),Component.literal(v.displayName()),e("lifecycle.choice","%s · sponsored by %s",enumLabel(v.state()),kingdom(c,v.sponsorKingdom())),v.revision())).toList();}
    private static List<Choice> recruitChoices(Context c){return RecruitsMobilization.nearbyOwned(c.player,32).stream().map(e->{var o=RecruitsMobilization.snapshot(e);return Choice.of(e.getUUID().toString(),e.getDisplayName(),tr("interaction.mcakingdoms.evolution.recruit.nearby","Your recruit, nearby"),Map.of("group",o.group().toString(),"equipment",RecruitsTransfer.equipment(e)));}).toList();}
    private static List<Choice> groupChoices(Context c){if(c.text("recipient").isBlank())return List.of();UUID recipient=c.uuid("recipient");var shared=RecruitTransferDestinations.forSender(c.player,recipient);if(shared.isEmpty())return List.of();
        var active=nativeGroups(c,recipient).stream().collect(Collectors.toMap(Choice::value,v->v));return shared.stream().map(v->active.get(v.group().toString())).filter(Objects::nonNull)
                .map(v->Choice.of(v.value(),Component.literal(v.label()),e("group.shared","Shared with you by the recipient"),Map.of("share_revision",Long.toString(RecruitTransferDestinations.revision(c.server()))))).toList();}
    private static List<Choice> ownGroupChoices(Context c){return nativeGroups(c,c.player.getUUID());}
    private static List<Choice> ownedShareChoices(Context c){return RecruitTransferDestinations.owned(c.player).stream().map(v->Choice.of(v.sender()+"|"+v.group(),Component.literal(v.name()),e("group.shared_with","Shared with %s",BasicTargets.player(c,v.sender())),Map.of("sender",v.sender().toString(),"group",v.group().toString(),"share_revision",Long.toString(v.revision())))).toList();}
    private static List<Choice> reconciliationChoices(Context c){if(!c.player.hasPermissions(2))return List.of();var service=transfers(c);var out=new ArrayList<Choice>();
        for(var v:service.operatorTransfers(c.player))try{var r=service.reconciliationReview(c.player,v.id());out.add(Choice.of(v.id().toString(),e("reconcile.choice","Recruit transfer to %s",BasicTargets.player(c,v.recipient())),Component.literal(r.observation()),Map.of("revision",Long.toString(r.revision()),"fingerprint",r.fingerprint())));}catch(IllegalArgumentException ignored){}return List.copyOf(out);}

    private static List<Choice> contributionChoices(Context c){return selectedScenario(c).outcomes().stream().filter(o->o!=EvolutionState.Outcome.DECLINE).map(o->Choice.of(o.name(),enumLabel(o))).toList();}
    private static List<Choice> outcomeChoices(Context c){return selectedScenario(c).outcomes().stream().map(o->Choice.of(o.name(),enumLabel(o))).toList();}
    private static List<Choice> contractChoices(Context c){return selectedScenario(c).outcomes().stream().filter(o->Set.of(EvolutionState.Outcome.AID,EvolutionState.Outcome.MEDIATE,EvolutionState.Outcome.NEGOTIATE).contains(o)).map(o->Choice.of(o.name(),enumLabel(o))).toList();}
    private static List<Choice> counterpartChoices(Context c){return EvolutionState.Outcome.INTRODUCE.name().equals(c.text("outcome"))?c.choices("kingdom"):List.of(Choice.of("",e("no_counterpart","No counterpart"),e("no_counterpart.detail","This outcome does not address another government.")));}
    private static List<Choice> dramaSubjectChoices(Context c){return drama(c).templates().stream().filter(t->t.id().equals(c.text("template"))).findFirst().filter(t->t.kind()==DramaState.Kind.SCHISM).isPresent()
            ?c.choices("organization"):List.of(Choice.of("",e("no_source","No source organization"),e("no_source.detail","This template uses native faction evidence.")));}
    private static List<Choice> pactKingdomChoices(Context c){var p=selectedPact(c);return List.of(Choice.of(p.protector(),kingdom(c,p.protector())),Choice.of(p.subordinate(),kingdom(c,p.subordinate())));}
    private static List<Choice> pactDutyChoices(Context c){return selectedPact(c).duties().stream().map(d->Choice.of(d.name(),enumLabel(d))).toList();}

    private static EvolutionService.ScenarioView selectedScenario(Context c){return evolution(c).scenario(c.player,c.uuid("scenario")).orElseThrow(()->new IllegalArgumentException("Opportunity unavailable."));}
    private static ProtectionService.PactView selectedPact(Context c){return protection(c).pacts(c.player).stream().filter(v->v.id().equals(c.uuid("pact"))).findFirst().orElseThrow(()->new IllegalArgumentException("Pact unavailable."));}
    private static ProtectionService.ObligationView selectedObligation(Context c){return protection(c).obligations(c.player).stream().filter(v->v.id().equals(c.uuid("obligation"))).findFirst().orElseThrow(()->new IllegalArgumentException("Obligation unavailable."));}
    private static RecruitTransferService.TransferView selectedTransfer(Context c){return transfers(c).transfers(c.player).stream().filter(v->v.id().equals(c.uuid("transfer"))).findFirst().orElseThrow(()->new IllegalArgumentException("Transfer unavailable."));}
    private static DramaService.DramaView selectedDrama(Context c){return drama(c).dramas(c.player).stream().filter(v->v.id().equals(c.uuid("drama"))).findFirst().orElseThrow(()->new IllegalArgumentException("Drama unavailable."));}

    private static String scenarioVersion(Context c){return Long.toString(selectedScenario(c).revision());}
    private static String pactVersion(Context c){return Long.toString(selectedPact(c).revision());}
    private static String obligationVersion(Context c){return Long.toString(selectedObligation(c).revision());}
    private static String transferVersion(Context c){return transfers(c).version(c.player);}
    private static String selectedTransferVersion(Context c){return Long.toString(selectedTransfer(c).revision());}
    private static String transferProposalVersion(Context c){return RecruitTransferDestinations.revision(c.server())+":"+c.text("unit")+":"+c.meta("unit","group")+":"+c.meta("unit","equipment")+":"+c.text("recipient")+":"+c.text("group")+":"+c.text("family");}
    private static String reconciliationVersion(Context c){return c.meta("transfer","revision")+":"+c.meta("transfer","fingerprint");}
    private static String dramaVersion(Context c){return Long.toString(selectedDrama(c).revision());}
    private static String mergeVersion(Context c){return organizations(c).revision()+":"+organizations(c).merge(c.uuid("merge")).orElseThrow().revision();}
    private static String organizationVersion(Context c){return organizations(c).revision()+":"+organizations(c).lifecycle(c.id("organization")).orElseThrow().revision();}

    private static List<Object> evolutionOverview(Context c){var settings=evolution(c).settings(c.player);var scenarios=evolution(c).scenarios(c.player);var lines=new ArrayList<Object>();
        lines.add(e("overview.world","World evolution is %s; world events (dramatic opportunities) are %s.",settings.enabled()?e("enabled","enabled"):e("paused","paused"),settings.drama()?e("enabled","enabled"):e("disabled","disabled")));
        lines.add(e("overview.digest","Your political digest is %s. Eligible regions: %s; currently active regions: %s.",settings.subscribed()?e("enabled","enabled"):e("disabled","disabled"),settings.eligibleRegions().size(),settings.activeRegions().size()));
        if(!settings.writable())lines.add(Unavailable.READ_ONLY.explain(e("overview.read_only","evolving-world records")));
        // Off for this world, nothing known to you yet, and waiting for participants are three different answers.
        if(scenarios.isEmpty())lines.add(!settings.enabled()?e("overview.none.disabled","World evolution is turned off for this world, so there are no opportunities to join. Existing records stay readable; an operator can turn it on.")
                :e("overview.none","You have no visible opportunities. Visit a known eligible settlement while prerequisites are available."));
        else scenarios.forEach(v->lines.add(e("overview.scenario","%s · %s · %s · %s",v.title(),enumLabel(v.phase()),BasicTargets.settlement(c,v.settlement()),nextScenario(v))));
        return List.copyOf(lines);}
    private static List<Object> scenarioLines(Context c,EvolutionService.ScenarioView v){var lines=new ArrayList<Object>();lines.add(e("scenario.title","%s · %s",v.title(),enumLabel(v.phase())));
        lines.add(e("scenario.where","Settlement: %s. Government: %s. Closes in: %s.",BasicTargets.settlement(c,v.settlement()),kingdom(c,v.kingdom()),Names.remainingText(c.server(),v.deadline())));
        lines.add(e("scenario.cause","Cause: %s",v.cause()));
        lines.add(e("scenario.choices","Choices: %s. Verified contributions required: %s.",joined(v.outcomes().stream().map(ActionRegistry::enumLabel).toList()),v.requiredContributions()));
        v.contributions().forEach((outcome,count)->lines.add(e("scenario.contributions","%s: %s verified contribution(s)",enumLabel(outcome),count)));
        v.ownChoice().ifPresent(choice->lines.add(e("scenario.own","Your current choice: %s. You may withdraw it while the opportunity remains open.",enumLabel(choice))));
        lines.add(e("scenario.next","Next: %s",nextScenario(v)));if(!v.result().isBlank())lines.add(e("scenario.result","Recorded outcome: %s",v.result()));return List.copyOf(lines);}
    private static Component nextScenario(EvolutionService.ScenarioView v){return switch(v.phase()){case OPEN->e("scenario.next.open","contribute, withdraw your own choice, or let an authorized reviewer resolve a supported outcome");
        case RESOLVING->e("scenario.next.resolving","the original resolver may retry or release an uncommitted rejected request");default->e("scenario.next.closed","the opportunity is closed and kept as history");};}
    private static Component joined(List<Component> parts){var out=Component.empty();for(int i=0;i<parts.size();i++){if(i>0)out.append(", ");out.append(parts.get(i));}return out;}

    private static List<Object> protectionOverview(Context c){var pacts=protection(c).pacts(c.player);var obligations=protection(c).obligations(c.player);var lines=new ArrayList<Object>();
        lines.add(e("protection.counts","Protection pacts you can see: %s. Beneficiary obligations you can see: %s.",pacts.size(),obligations.size()));
        if(pacts.isEmpty())lines.add(e("protection.none","No public active pact or private proposal is available to your current authority."));
        else pacts.forEach(v->lines.add(e("protection.line","%s → %s · %s · duties: %s",kingdom(c,v.protector()),kingdom(c,v.subordinate()),enumLabel(v.phase()),joined(v.duties().stream().map(ActionRegistry::enumLabel).toList()))));return List.copyOf(lines);}
    private static List<Object> pactLines(Context c,ProtectionService.PactView v){var lines=new ArrayList<Object>();lines.add(e("pact.title","%s → %s · %s",kingdom(c,v.protector()),kingdom(c,v.subordinate()),enumLabel(v.phase())));
        lines.add(Component.literal(v.terms()));lines.add(e("pact.duties","Duties: %s. Ends in: %s; exit notice: %s.",joined(v.duties().stream().map(ActionRegistry::enumLabel).toList()),Names.remainingText(c.server(),v.expires()),Names.durationText(v.noticeTicks())));
        if(v.settlementVisible())lines.add(e("pact.beneficiary","Beneficiary: %s.",BasicTargets.settlement(c,v.beneficiary())));else lines.add(e("pact.beneficiary.hidden","The beneficiary stays undisclosed until you know the settlement."));
        var obligations=protection(c).obligations(c.player).stream().filter(o->o.pact().equals(v.id())).toList();if(obligations.isEmpty())lines.add(e("pact.no_obligations","No visible obligations are recorded."));
        else obligations.forEach(o->lines.add(e("pact.obligation","%s · %s · due %s · %s",enumLabel(o.duty()),enumLabel(o.status()),Names.remainingText(c.server(),o.deadline()),o.explanation())));return List.copyOf(lines);}

    private static List<Object> transferOverview(Context c){var values=transfers(c).transfers(c.player);var lines=new ArrayList<Object>();lines.add(e("transfer.count","Your private recruit transfers: %s.",values.size()));
        if(values.isEmpty())lines.add(e("transfer.none","No proposal names you as source owner or recipient."));else values.forEach(v->lines.add(e("transfer.line","%s · %s → %s · %s · %s · %s",recruitName(c,v.unit()),BasicTargets.player(c,v.owner()),BasicTargets.player(c,v.recipient()),nativeGroupName(c,v.recipient(),v.recipientGroup()),enumLabel(v.phase()),transferNext(v))));return List.copyOf(lines);}
    private static List<Object> transferLines(Context c,RecruitTransferService.TransferView v){var lines=new ArrayList<Object>();lines.add(e("transfer.title","Recruit transfer · %s",enumLabel(v.phase())));
        lines.add(e("transfer.people","Recruit: %s. Source owner: %s. Recipient: %s.",recruitName(c,v.unit()),BasicTargets.player(c,v.owner()),BasicTargets.player(c,v.recipient())));
        lines.add(e("transfer.group","Destination group: %s. Family-backed introduction: %s.",nativeGroupName(c,v.recipient(),v.recipientGroup()),Component.translatable(v.family()?"gui.yes":"gui.no")));
        lines.add(e("transfer.times","Notice ends in: %s; deadline in: %s. The recruit's equipment is fixed as recorded when it was proposed.",Names.remainingText(c.server(),v.noticeUntil()),Names.remainingText(c.server(),v.deadline())));
        lines.add(Component.literal(v.detail()));lines.add(e("transfer.next","Next: %s",transferNext(v)));
        if(v.accountingDiverged())lines.add(e("transfer.diverged","Native accounting changed separately; an operator must review reconciliation."));return List.copyOf(lines);}
    private static Component transferNext(RecruitTransferService.TransferView v){return switch(v.phase()){case OFFERED->e("transfer.next.offered","the recipient may consent, or either party may cancel");
        case CONSENTED->e("transfer.next.consented","the source owner may apply after the notice while both players and the recruit are present");case APPLYING,CONFIRMING->e("transfer.next.confirming","either party may confirm the retained native save");
        case RESTORING->e("transfer.next.restoring","either party may restore with both players present, then confirm");case COMPLETE->e("transfer.next.complete","the negotiated transfer is complete");
        case CANCELLED->e("transfer.next.cancelled","native ownership remains with, or was restored to, the source");case RECONCILED->e("transfer.next.reconciled","an operator review is recorded; the divergence evidence remains");
        case EXPIRED->e("transfer.next.expired","the offer expired before anything changed; propose again if both players still agree");};}
    private static List<Object> reconciliationLines(RecruitTransferService.ReconciliationReview review){return List.of(e("reconcile.revision","Pending native intent, revision %s.",review.revision()),Component.literal(review.observation()),
            e("reconcile.note","The current fingerprint is captured with this reviewed choice. Applying reconciliation records an operator note; it does not rewrite native counts."));}

    private static List<Object> dramaOverview(Context c){var values=drama(c).dramas(c.player);var lines=new ArrayList<Object>();lines.add(e("drama.count","World events open to you: %s.",values.size()));
        if(values.isEmpty())lines.add(e("drama.none","No world event names you as a participant or as an eligible independent consenter."));
        else values.forEach(v->lines.add(e("drama.line","%s · %s · %s%s",v.title(),enumLabel(v.phase()),BasicTargets.settlement(c,v.settlement()),v.canConsent()?e("drama.line.awaiting"," · awaiting your consent"):Component.empty())));return List.copyOf(lines);}
    private static List<Object> dramaLines(Context c,DramaService.DramaView v){var lines=new ArrayList<Object>();lines.add(e("drama.title","%s · %s · %s",v.title(),enumLabel(v.kind()),enumLabel(v.phase())));
        lines.add(e("drama.where","Settlement: %s. Remaining online time: %s.",BasicTargets.settlement(c,v.settlement()),Names.durationText(v.remainingTicks())));lines.add(e("drama.objective","Objective: %s",v.objective()));
        lines.add(e("drama.outcomes","Authorized outcomes: %s.",joined(v.outcomes().stream().map(ActionRegistry::enumLabel).toList())));if(!v.subject().isBlank())lines.add(e("drama.subject","Recorded subject: %s.",organizationNameText(c,v.subject())));
        lines.add(v.canConsent()?e("drama.next.consent","Next: review the frozen objective and consent as the eligible independent participant."):v.participant()?e("drama.next.participant","Next: use only an outcome these frozen terms authorize."):e("drama.next.preview","Read-only preview."));
        if(!v.result().isBlank())lines.add(e("drama.status","Status: %s",v.result()));return List.copyOf(lines);}

    private static List<Object> organizationOverview(Context c){var lifecycles=organizations(c).lifecycles(c.player);var merges=organizations(c).merges(c.player);var lines=new ArrayList<Object>();
        lines.add(e("organization.counts","Founded organizations: %s. Merge proposals you can see: %s.",lifecycles.size(),merges.size()));
        if(lifecycles.isEmpty())lines.add(e("organization.none","No organization has been founded, merged or dissolved yet."));
        else lifecycles.forEach(v->lines.add(e("organization.line","%s · %s · sponsored by %s",v.displayName(),enumLabel(v.state()),kingdom(c,v.sponsorKingdom()))));
        merges.forEach(v->lines.add(e("organization.merge","Merge %s → %s · %s · %s member opt-out(s)",organizationName(c,v.source()),organizationName(c,v.target()),enumLabel(v.state()),v.memberOptOuts().size())));return List.copyOf(lines);}
    private static List<Object> lifecycleLines(Context c,OrganizationLifecycleView v){var lines=new ArrayList<Object>();lines.add(e("lifecycle.title","%s · %s",v.displayName(),enumLabel(v.state())));
        lines.add(e("lifecycle.sponsor","Sponsor government: %s.",kingdom(c,v.sponsorKingdom())));
        if(v.redirect().isPresent())lines.add(e("lifecycle.redirect","It merged into %s; its old records resolve there and its original record is kept as history.",organizationName(c,v.redirect().get())));
        else if(v.state()==OrganizationLifecycleView.State.ACTIVE)lines.add(e("lifecycle.active","Its benefits are active. Merging or dissolving it needs current authority and settled accepted commissions."));
        else lines.add(e("lifecycle.frozen","Its benefits are frozen; its history and standing evidence are kept."));
        lines.add(e("lifecycle.technical","Technical ids (for operators and authors): %s, from the charter template %s.",v.id().toString(),v.template().toString()));return List.copyOf(lines);}
    private static String organizationNameText(Context c,String value){var id=ResourceLocation.tryParse(value);return id==null?value:organizationName(c,id);}
    private static String recruitName(Context c,UUID id){for(var level:c.server().getAllLevels()){var entity=level.getEntity(id);if(entity!=null)return entity.getDisplayName().getString();}return "Recruit (unloaded)";}
    private static String nativeGroupName(Context c,UUID owner,UUID id){try{Class<?> events=Class.forName("com.talhanation.recruits.RecruitEvents",false,EvolutionTasks.class.getClassLoader());Object manager=events.getField("recruitsGroupsManager").get(null);
        Object group=manager.getClass().getMethod("getGroup",UUID.class).invoke(manager,id);if(group==null||!owner.equals(group.getClass().getMethod("getPlayerUUID").invoke(group))||(boolean)group.getClass().getMethod("isDisabled").invoke(group))return "Destination group unavailable";
        return String.valueOf(group.getClass().getMethod("getName").invoke(group));}catch(ReflectiveOperationException|LinkageError failure){return "Destination group unavailable";}}


    private static Set<ProtectionState.Duty> duties(Context c){try{return Arrays.stream(c.text("duties").split(",")).filter(s->!s.isBlank()).map(s->ProtectionState.Duty.valueOf(s.toUpperCase(Locale.ROOT))).collect(Collectors.toCollection(()->EnumSet.noneOf(ProtectionState.Duty.class)));}
        catch(IllegalArgumentException failure){throw new IllegalArgumentException("Choose one or more listed duties.");}}
    private static String organizationName(Context c,ResourceLocation id){return organizations(c).lifecycle(id).map(OrganizationLifecycleView::displayName)
            .or(()->organizations(c).definition(id).map(d->PlayerWords.text(d.nameKey()))).orElse(id.toString());}
    /** The service's reason is its own paragraph, so a bundled key reaches the player translated, never raw. */
    private static List<Object> organizationResult(OrganizationLifecycleResult result){var lines=new ArrayList<Object>();lines.add(result.reason());lines.add(enumLabel(result.status()));
        if(!result.obligations().isEmpty())lines.add(e("organization.obligations","%s accepted commission(s) still need completing, cancelling or transferring to the joined organization.",result.obligations().size()));return lines;}
    private static ResourceLocation slug(String name){String path=Normalizer.normalize(name,Normalizer.Form.NFKD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        if(path.isBlank())throw new IllegalArgumentException("Organization name must contain letters or numbers.");if(path.length()>64)path=path.substring(0,64).replaceAll("_+$","");return new ResourceLocation("mcakingdoms",path);}

    private static List<Choice> nativeGroups(Context c,UUID owner){try{
        var recipient=c.server().getPlayerList().getPlayer(owner);if(recipient==null)throw new IllegalArgumentException("Recipient must remain online.");
        Class<?> events=Class.forName("com.talhanation.recruits.RecruitEvents",false,EvolutionTasks.class.getClassLoader());Object manager=events.getField("recruitsGroupsManager").get(null);
        Object groups=manager.getClass().getMethod("getPlayerGroups",net.minecraft.world.entity.player.Player.class).invoke(manager,recipient);
        if(!(groups instanceof Iterable<?> iterable))return List.of();var out=new ArrayList<Choice>();for(Object group:iterable){
            if((boolean)group.getClass().getMethod("isDisabled").invoke(group))continue;UUID id=(UUID)group.getClass().getMethod("getUUID").invoke(group);String name=String.valueOf(group.getClass().getMethod("getName").invoke(group));
            out.add(new Choice(id.toString(),name,"Active native group"));}return List.copyOf(out);
        }catch(ReflectiveOperationException|LinkageError failure){throw new IllegalArgumentException("Native recruit groups are unavailable.",failure);}}
    private EvolutionTasks(){}
}
