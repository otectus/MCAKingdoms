package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public class PoliticalGameTests {
    private static final String SERENUM="mcakingdoms:serenum", LUNARI="mcakingdoms:lunari";
    private static void check(boolean condition,String message) { if(!condition)throw new GameTestAssertException(message); }
    @GameTest(template="empty",timeoutTicks=100)
    public static void hospitalityRequiresExplicitLiveFrozenTerms(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var identity=McaKingdomsApi.get(server);
        var data=new PoliticalSavedData();
        var enabled=new java.util.concurrent.atomic.AtomicBoolean(false);
        var politics=new GovernmentService(server,identity,McaKingdoms.POLITICS,data,false,id->true,enabled::get);
        var terms=new Definition(1,"agreement","Hospitality",List.of(),Set.of(),List.of(),Set.of(),
                720000,2,true,"",Set.of(Clause.HOSPITALITY));
        var a=settlement(helper,identity,"hospitality-a",0,SERENUM);
        var b=settlement(helper,identity,"hospitality-b",48,LUNARI);
        var first=UUID.randomUUID(); var second=UUID.randomUUID(); var id=UUID.randomUUID();
        var state=data.transaction();
        for(var pair:List.of(Map.entry(SERENUM,a.id()),Map.entry(LUNARI,b.id()))) {
            var constitution=McaKingdoms.POLITICS.get(pair.getKey()+"_charter","government");
            state.governments.put(pair.getKey(),new Government(pair.getKey(),pair.getValue(),pair.getKey()+"_charter",
                    constitution,State.ACTIVE,Map.of(),Map.of(),null,0));
        }
        state.agreements.put(id,new Agreement(id,SERENUM,LUNARI,"mcakingdoms:hospitality_accord",terms,"Visit",
                "a".repeat(64),Map.of(SERENUM,first,LUNARI,second),AgreementState.ACTIVE,0,server.overworld().getGameTime()+1000,0));
        data.commit(state);
        
        try {
            enabled.set(false);
            check(!politics.clause(SERENUM,LUNARI,Clause.HOSPITALITY).operational(),"Disabled effect was active");
            enabled.set(true);
            check(politics.clause(SERENUM,LUNARI,Clause.HOSPITALITY).operational(),"Signed hospitality did not activate");
            check(politics.clause(LUNARI,SERENUM,Clause.HOSPITALITY).operational(),"Hospitality was not reciprocal");
            check(!politics.clause(SERENUM,LUNARI,Clause.WORKSHOP_ACCESS).operational(),"Hospitality granted unrelated service");
            check(!politics.clause(SERENUM,SERENUM,Clause.HOSPITALITY).operational(),"Same kingdom borrowed a treaty");
            var saved=PoliticalSavedData.load(data.save(new CompoundTag()));
            var restarted=new GovernmentService(server,identity,McaKingdoms.POLITICS,saved,false,id2->true,enabled::get);
            check(restarted.clause(SERENUM,LUNARI,Clause.HOSPITALITY).operational(),"Restart lost frozen hospitality");
            var next=data.transaction();var agreement=next.agreements.get(id);
            next.agreements.put(id,new Agreement(id,SERENUM,LUNARI,agreement.definitionId(),terms,"Visit",agreement.termsHash(),
                    agreement.signatures(),AgreementState.TERMINATED,0,agreement.expiresAt(),0));
            data.commit(next);
            check(!politics.clause(SERENUM,LUNARI,Clause.HOSPITALITY).operational(),"Termination retained service");
        } finally { enabled.set(false); }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void authenticatedPoliticalLoopAndSettlementSafety(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var identity=McaKingdomsApi.get(server);
        var data=new PoliticalSavedData(); var knownPlayers=new HashSet<UUID>(); var politics=new GovernmentService(server,identity,McaKingdoms.POLITICS,data,false,knownPlayers::contains);
        var one=new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PoliticalOne")) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
        var two=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PoliticalTwo"));
        var stranger=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PoliticalOther"));
        knownPlayers.addAll(List.of(one.getUUID(),two.getUUID(),stranger.getUUID()));
        MinecraftForge.EVENT_BUS.register(politics);
        try {
            var a=settlement(helper,identity,"politics-a",0,SERENUM);var b=settlement(helper,identity,"politics-b",48,LUNARI);
            var replacement=settlement(helper,identity,"politics-c",96,SERENUM);
            long originalRevision=identity.getSettlement(a.id()).orElseThrow().revision();
            Request bootstrap=req(politics,Action.BOOTSTRAP,SERENUM,SERENUM+"_charter",a.id().toString(),one,"","","");
            check(!politics.execute(stranger,bootstrap).success(),"Unauthorised founding succeeded");
            Result founded=politics.execute(one,bootstrap);check(founded.success(),founded.message());
            check(politics.execute(one,bootstrap).equals(founded),"Replay did not return the original receipt");
            check(politics.receipt(one,bootstrap.requestId()).orElseThrow().equals(founded),"Actor could not recover durable receipt");
            check(politics.receipt(stranger,bootstrap.requestId()).isEmpty(),"Another actor recovered a private receipt");
            check(politics.government(SERENUM).orElseThrow().capital().equals(a.id()),"Trusted government read lost capital");
            check(!politics.execute(stranger,bootstrap).success(),"Another actor stole a receipt");
            Result second=politics.execute(one,req(politics,Action.BOOTSTRAP,LUNARI,LUNARI+"_charter",b.id().toString(),two,"","",""));check(second.success(),second.message());
            check(identity.getSettlement(a.id()).orElseThrow().revision()==originalRevision,"Politics changed identity data");
            try { identity.setKingdom(a.id(),new ResourceLocation(LUNARI));throw new GameTestAssertException("Capital reassignment was allowed"); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains("capital"),"Wrong rejection reason"); }
            Villager villager=EntityType.VILLAGER.create(helper.getLevel());villager.moveTo(a.anchor().getX(),a.anchor().getY(),a.anchor().getZ());
            helper.getLevel().addFreshEntity(villager);identity.setResidence(villager,a.id());
            CompoundTag before=villager.saveWithoutId(new CompoundTag());
            Request appoint=new Request(UUID.randomUUID(),politics.revision(),Action.APPOINT,SERENUM,"mcakingdoms:keeper_of_records","",new Person(villager.getUUID(),Kind.NPC),"","","",0,0,0);
            Result appointed=politics.execute(one,appoint);check(appointed.success(),appointed.message());
            check(before.equals(villager.saveWithoutId(new CompoundTag())),"Office appointment changed NPC data");
            Request stale=req(politics,Action.SEAT,SERENUM,"",replacement.id().toString(),null,"","","");
            Result proposal=politics.execute(one,req(politics,Action.PROPOSE,SERENUM,"mcakingdoms:diplomatic_recognition","",null,LUNARI,"Mutual recognition",""));check(proposal.success(),proposal.message());
            check(!politics.execute(one,stale).success(),"Stale revision accepted");
            check(politics.page(stranger,UUID.randomUUID(),SERENUM,"agreements",0).rows().isEmpty(),"Private proposal leaked");
            check(politics.history(stranger,0,50).stream().noneMatch(f -> f.recordId().equals(proposal.recordId())),"Private proposal leaked through history");
            check(politics.history(two,0,50).stream().anyMatch(f -> f.recordId().equals(proposal.recordId())),"Counterpart government could not read proposed agreement history");
            Agreement agreement=data.records().agreements.get(UUID.fromString(proposal.recordId()));
            check(!politics.execute(two,req(politics,Action.SIGN,LUNARI,"",proposal.recordId(),null,"","","wrong")).success(),"Wrong terms hash accepted");
            check(politics.execute(one,req(politics,Action.SIGN,SERENUM,"",proposal.recordId(),null,"","",agreement.termsHash())).success(),"First signature rejected");
            check(politics.execute(two,req(politics,Action.SIGN,LUNARI,"",proposal.recordId(),null,"","",agreement.termsHash())).success(),"Counterpart signature rejected");
            check(data.records().agreements.get(agreement.id()).state()==AgreementState.ACTIVE,"Two signatures did not activate agreement");
            check(politics.page(stranger,UUID.randomUUID(),SERENUM,"agreements",0).rows().size()==1,"Ratified agreement not public");
            Result petition=politics.execute(stranger,req(politics,Action.PETITION,SERENUM,"mcakingdoms:honor_petition",a.id().toString(),stranger,"","Service nomination",""));check(petition.success(),petition.message());
            check(politics.page(two,UUID.randomUUID(),SERENUM,"petitions",0).rows().isEmpty(),"Private petition leaked");
            check(politics.history(two,0,50).stream().noneMatch(f -> f.recordId().equals(petition.recordId())),"Private petition leaked through history");
            check(politics.history(stranger,0,50).stream().anyMatch(f -> f.recordId().equals(petition.recordId())),"Petitioner could not read own history");
            check(politics.execute(one,req(politics,Action.APPROVE,SERENUM,"",petition.recordId(),null,"","Recognized civic service","")).success(),"Honor petition did not approve");
            check(data.records().honors.size()==1,"Petition did not produce one honor");
            check(!politics.execute(one,req(politics,Action.APPROVE,SERENUM,"",petition.recordId(),null,"","Again","")).success(),"Closed petition approved twice");
            Result scholarship=politics.execute(one,req(politics,Action.PROPOSE,SERENUM,"mcakingdoms:scholarly_exchange","",null,LUNARI,"Archive exchange",""));check(scholarship.success(),scholarship.message());
            Agreement scholarly=data.records().agreements.get(UUID.fromString(scholarship.recordId()));
            check(politics.execute(one,req(politics,Action.DELEGATE,SERENUM,"RATIFY","",stranger,"","","")).success(),"Mandate grant failed");
            check(politics.execute(stranger,req(politics,Action.SIGN,SERENUM,"",scholarship.recordId(),null,"","",scholarly.termsHash())).success(),"Delegated signature failed");
            check(politics.execute(one,req(politics,Action.REVOKE,SERENUM,"","",stranger,"","","")).success(),"Mandate revocation failed");
            check(!politics.execute(two,req(politics,Action.SIGN,LUNARI,"",scholarship.recordId(),null,"","",scholarly.termsHash())).success(),"Revoked first signature activated treaty");
            check(politics.execute(one,req(politics,Action.WITHDRAW,SERENUM,"",scholarship.recordId(),null,"","","")).success(),"Proposal withdrawal failed");
            check(politics.page(stranger,UUID.randomUUID(),SERENUM,"agreements",0).rows().size()==1,"Withdrawn private proposal leaked");
            long day=helper.getLevel().getDayTime();helper.getLevel().setDayTime(day+999999999L);
            check(politics.page(two,UUID.randomUUID(),SERENUM,"agreements",0).rows().stream().anyMatch(row->row.detail().startsWith("ACTIVE")),"Changing day time expired a mechanical agreement");helper.getLevel().setDayTime(day);
            var conflict=new GovernmentService(server,identity,McaKingdoms.POLITICS,data,true,knownPlayers::contains);
            check(!conflict.execute(one,req(conflict,Action.SEAT,SERENUM,"",replacement.id().toString(),null,"","","")).success(),"Capitals conflict guard allowed mutation");
            var dispatcher=server.getCommands().getDispatcher();String command="kingdoms politics inspect mcakingdoms:serenum";
            check(!dispatcher.parse(command,one.createCommandSourceStack()).getReader().canRead(),"Namespaced political command did not parse");
            check(politics.execute(one,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:local_steward",a.id().toString(),one,"","","")).success(),"Source steward rejected");
            check(politics.execute(one,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:local_steward",replacement.id().toString(),two,"","","")).success(),"Target steward rejected");
            try { identity.merge(a.id(),replacement.id()); throw new GameTestAssertException("Conflicting local offices merged"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("local office"),"Wrong scope conflict reason"); }
            check(politics.execute(one,req(politics,Action.REMOVE_OFFICE,SERENUM,"mcakingdoms:local_steward",replacement.id().toString(),null,"","","")).success(),"Target steward removal failed");
            identity.merge(a.id(),replacement.id());
            check(!politics.execute(one,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:local_steward",replacement.id().toString(),stranger,"","","")).success(),"Merged office scope permitted duplicate appointment");
            check(data.records().governments.get(SERENUM).capital().equals(replacement.id()),"Capital did not follow merge redirect");
            check(politics.execute(one,req(politics,Action.NAME_SUCCESSOR,SERENUM,"","",two,"","","")).success(),"Named successor rejected");
            check(politics.execute(one,req(politics,Action.ABDICATE,SERENUM,"","",null,"","","")).success(),"Abdication rejected");
            check(data.records().governments.get(SERENUM).state()==State.INTERREGNUM,"Abdication did not enter interregnum");
            check(!politics.execute(one,req(politics,Action.ABDICATE,SERENUM,"","",null,"","","")).success(),"Duplicate abdication changed state");
            check(politics.execute(two,req(politics,Action.SUCCEED,SERENUM,"","",null,"","","")).success(),"Named successor could not confirm");
            check(data.records().agreements.get(agreement.id()).state()==AgreementState.ACTIVE,"Succession invalidated accepted treaty");
            check(!politics.execute(one,req(politics,Action.SEAT,SERENUM,"",replacement.id().toString(),null,"","","")).success(),"Former leader retained authority");
            CompoundTag saved=data.save(new CompoundTag());var loaded=PoliticalSavedData.load(saved);
            check(loaded.writable(),loaded.diagnostic());
            check(loaded.records().governments.equals(data.records().governments) && loaded.records().agreements.equals(data.records().agreements)
                    && loaded.records().petitions.equals(data.records().petitions) && loaded.records().honors.equals(data.records().honors)
                    && loaded.records().receipts.equals(data.records().receipts) && loaded.records().facts.equals(data.records().facts),"Restart round trip changed political records");
            String islands="mcakingdoms:shimaguni";
            var islandSeat=settlement(helper,identity,"politics-islands",192,islands);
            check(politics.execute(one,req(politics,Action.BOOTSTRAP,islands,islands+"_charter",islandSeat.id().toString(),one,"","","")).success(),"Island Covenant founding failed");
            Result islandPetition=politics.execute(stranger,req(politics,Action.PETITION,islands,"mcakingdoms:honor_petition",islandSeat.id().toString(),stranger,"","Archive service",""));
            check(islandPetition.success(),islandPetition.message());
            check(politics.execute(one,req(politics,Action.APPROVE,islands,"",islandPetition.recordId(),null,"","Recognized archive service","")).success(),"Shimaguni honor approval failed");
            check(data.records().honors.values().stream().anyMatch(h -> h.kingdom().equals(islands)&&h.definitionId().equals("mcakingdoms:tide_archive")),"Shimaguni received another kingdom's honor");
            villager.discard(); helper.succeed();
        } finally { MinecraftForge.EVENT_BUS.unregister(politics); }
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void onlyConfirmedNpcDeathVacatesLeadershipOnce(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var identity=McaKingdomsApi.get(server);var data=new PoliticalSavedData();
        var politics=new GovernmentService(server,identity,McaKingdoms.POLITICS,data,false,id->true);
        var operator=new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"DeathTest")) {
            @Override public boolean hasPermissions(int level){return level<=2;}
        };
        var seat=settlement(helper,identity,"death-seat",0,SERENUM);
        Villager npc=EntityType.VILLAGER.create(helper.getLevel());npc.moveTo(seat.anchor().getX(),seat.anchor().getY(),seat.anchor().getZ());helper.getLevel().addFreshEntity(npc);identity.setResidence(npc,seat.id());
        Request bootstrap=new Request(UUID.randomUUID(),0,Action.BOOTSTRAP,SERENUM,SERENUM+"_charter",seat.id().toString(),new Person(npc.getUUID(),Kind.NPC),"","","",0,0,0);
        Result result=politics.execute(operator,bootstrap);check(result.success(),result.message());
        MinecraftForge.EVENT_BUS.register(politics);
        var canceled=new net.minecraftforge.event.entity.living.LivingDeathEvent(npc,npc.damageSources().genericKill());canceled.setCanceled(true);politics.deathObserved(canceled);
        helper.runAfterDelay(2,()->{
            try {
                check(data.records().governments.get(SERENUM).state()==State.ACTIVE,"Canceled death caused vacancy");
                npc.hurt(npc.damageSources().genericKill(),Float.MAX_VALUE);
                politics.deathObserved(new net.minecraftforge.event.entity.living.LivingDeathEvent(npc,npc.damageSources().genericKill()));
                helper.runAfterDelay(3,()->{
                    try {
                        check(data.records().governments.get(SERENUM).state()==State.INTERREGNUM,"Confirmed death did not vacate leadership");
                        check(data.records().revision==2,"Duplicate confirmed death produced another transaction");
                        helper.succeed();
                    } finally {MinecraftForge.EVENT_BUS.unregister(politics);npc.discard();}
                });
            } catch(Throwable failure){MinecraftForge.EVENT_BUS.unregister(politics);throw failure;}
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void electedMandateOutranksAppointment(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var identity=McaKingdomsApi.get(server);
        var data=new PoliticalSavedData(); var knownPlayers=new HashSet<UUID>(); var politics=new GovernmentService(server,identity,McaKingdoms.POLITICS,data,false,knownPlayers::contains);
        var one=new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"ElectionOne")) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
        var two=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"ElectionTwo"));
        var three=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"ElectionThree"));
        knownPlayers.addAll(List.of(one.getUUID(),two.getUUID(),three.getUUID()));
        MinecraftForge.EVENT_BUS.register(politics);
        try {
            var a=settlement(helper,identity,"election-a",144,SERENUM);
            Result founded=politics.execute(one,req(politics,Action.BOOTSTRAP,SERENUM,SERENUM+"_charter",a.id().toString(),one,"","",""));check(founded.success(),founded.message());
            var legacyRule=new dev.otectus.mcakingdoms.api.politics.PoliticalTransition.Rule(true,false,1200,200,1200,4,Set.of());
            check(legacyRule.electorate()==dev.otectus.mcakingdoms.api.politics.PoliticalTransition.Electorate.COUNCIL,"Legacy rule constructor did not default to a council electorate");
            Result adopted=politics.adoptTransitionRule(one,UUID.randomUUID(),politics.revision(),SERENUM,legacyRule);check(adopted.success(),adopted.message());
            Result steward=politics.execute(one,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:local_steward",a.id().toString(),two,"","",""));check(steward.success(),steward.message());
            Result opened=politics.openElection(one,UUID.randomUUID(),politics.revision(),SERENUM,List.of(two.getUUID(),three.getUUID()));check(opened.success(),opened.message());
            UUID election=UUID.fromString(opened.recordId());
            check(data.records().elections.get(election).electorate().equals(Set.of(one.getUUID(),two.getUUID())),"Council electorate was not frozen from offices");
            check(politics.castBallot(one,UUID.randomUUID(),politics.revision(),election,two.getUUID()).success(),"Leader ballot rejected");
            check(politics.castBallot(two,UUID.randomUUID(),politics.revision(),election,two.getUUID()).success(),"Steward ballot rejected");
            Result closed=politics.closeElection(one,UUID.randomUUID(),politics.revision(),election);check(closed.success(),closed.message());
            var government=data.records().governments.get(SERENUM);
            check(government.successor()!=null&&government.successor().id().equals(two.getUUID()),"Election winner did not become the named successor");
            check(two.getUUID().equals(data.records().electedMandates.get(SERENUM)),"Elected mandate was not recorded");
            Result renamed=politics.execute(one,req(politics,Action.NAME_SUCCESSOR,SERENUM,"","",three,"","",""));
            check(!renamed.success()&&renamed.message().contains("elected"),"Appointment authority displaced an elected successor: "+renamed.message());
            check(politics.execute(one,req(politics,Action.ABDICATE,SERENUM,"","",null,"","","")).success(),"Abdication rejected");
            check(politics.execute(two,req(politics,Action.SUCCEED,SERENUM,"","",null,"","","")).success(),"Elected successor could not take office");
            check(two.getUUID().equals(data.records().electedMandates.get(SERENUM)),"Elected mandate did not follow the successor into office");
            check(politics.execute(two,req(politics,Action.ABDICATE,SERENUM,"","",null,"","","")).success(),"Elected leader could not abdicate");
            check(data.records().electedMandates.get(SERENUM)==null,"Abdication did not release the elected mandate");
            helper.succeed();
        } finally { MinecraftForge.EVENT_BUS.unregister(politics); }
    }
    /**
     * H02: with several offices, the page still offers removal generically, but the elected leader's office is refused for
     * that target by the preview, listed as unavailable in the removal form, and refused by the mutation with the same key.
     * Another office is removed normally; a stale revision and an unauthorized viewer are refused with their own keys.
     */
    @GameTest(template="empty",timeoutTicks=200)
    public static void electedLeaderOfficeIsProtectedAmongSeveralOffices(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var identity=McaKingdomsApi.get(server);
        var data=new PoliticalSavedData(); var knownPlayers=new HashSet<UUID>(); var politics=new GovernmentService(server,identity,McaKingdoms.POLITICS,data,false,knownPlayers::contains);
        var one=new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PolicyOne")) { @Override public boolean hasPermissions(int level) { return level <= 2; } };
        var two=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PolicyTwo"));
        var three=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PolicyThree"));
        knownPlayers.addAll(List.of(one.getUUID(),two.getUUID(),three.getUUID()));
        String leader=GovernmentActionPolicy.LEADER;
        var a=settlement(helper,identity,"policy-a",152,SERENUM);
        check(politics.execute(one,req(politics,Action.BOOTSTRAP,SERENUM,SERENUM+"_charter",a.id().toString(),one,"","","")).success(),"Founding failed");
        check(politics.adoptTransitionRule(one,UUID.randomUUID(),politics.revision(),SERENUM,new dev.otectus.mcakingdoms.api.politics.PoliticalTransition.Rule(true,false,1200,200,1200,4,Set.of())).success(),"Rule rejected");
        check(politics.execute(one,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:local_steward",a.id().toString(),two,"","","")).success(),"Steward rejected");
        Result opened=politics.openElection(one,UUID.randomUUID(),politics.revision(),SERENUM,List.of(two.getUUID(),three.getUUID()));check(opened.success(),opened.message());
        UUID election=UUID.fromString(opened.recordId());
        check(politics.castBallot(one,UUID.randomUUID(),politics.revision(),election,two.getUUID()).success(),"Ballot one rejected");
        check(politics.castBallot(two,UUID.randomUUID(),politics.revision(),election,two.getUUID()).success(),"Ballot two rejected");
        check(politics.closeElection(one,UUID.randomUUID(),politics.revision(),election).success(),"Close rejected");
        check(politics.execute(one,req(politics,Action.ABDICATE,SERENUM,"","",null,"","","")).success(),"Abdication rejected");
        check(politics.execute(two,req(politics,Action.SUCCEED,SERENUM,"","",null,"","","")).success(),"Elected successor could not take office");
        Result chancellor=politics.execute(two,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:chancellor","",three,"","",""));check(chancellor.success(),chancellor.message());
        Result envoy=politics.execute(two,req(politics,Action.APPOINT,SERENUM,"mcakingdoms:envoy","",one,"","",""));check(envoy.success(),envoy.message());
        check(data.records().governments.get(SERENUM).offices().size()==4,"Expected four occupied offices");
        var page=politics.page(two,UUID.randomUUID(),SERENUM,"council",0);
        check(page.actionDenials().get(Action.REMOVE_OFFICE).isEmpty(),"Removal should stay generically available with other offices: "+page.actionDenials().get(Action.REMOVE_OFFICE));
        String elected="politics.mcakingdoms.refusal.elected_leader";
        check(politics.actionDenial(two,SERENUM,Action.REMOVE_OFFICE,GovernmentActionPolicy.Target.office(leader)).equals(Optional.of(elected)),"Target preview did not protect the elected leader");
        var listed=politics.occupiedOffices(two,SERENUM);
        check(listed.stream().filter(o->o.key().equals(leader)).allMatch(o->o.removal().equals(Optional.of(elected))),"The removal form offered the elected leader's office");
        check(listed.stream().filter(o->o.key().equals("mcakingdoms:chancellor")).allMatch(o->o.removal().isEmpty()),"The chancellor's office should be removable");
        Result refused=politics.execute(two,req(politics,Action.REMOVE_OFFICE,SERENUM,leader,"",null,"","",""));
        check(!refused.success()&&refused.message().equals(elected),"Mutation and preview disagree: "+refused.message());
        Result stale=politics.execute(two,new Request(UUID.randomUUID(),politics.revision()-1,Action.REMOVE_OFFICE,SERENUM,"mcakingdoms:chancellor","",null,"","","",0,0,0));
        check(!stale.success()&&stale.message().equals("politics.mcakingdoms.refusal.state_changed"),"A stale revision was not refused: "+stale.message());
        Result removed=politics.execute(two,req(politics,Action.REMOVE_OFFICE,SERENUM,"mcakingdoms:chancellor","",null,"","",""));check(removed.success(),removed.message());
        check(politics.actionDenial(three,SERENUM,Action.REMOVE_OFFICE,GovernmentActionPolicy.Target.office("mcakingdoms:envoy")).equals(Optional.of("politics.mcakingdoms.refusal.requires.appoint")),"An unauthorized viewer was offered removal");
        Result unauthorized=politics.execute(three,req(politics,Action.REMOVE_OFFICE,SERENUM,"mcakingdoms:envoy","",null,"","",""));
        check(!unauthorized.success()&&unauthorized.message().equals("politics.mcakingdoms.refusal.requires.appoint"),"Unauthorized removal: "+unauthorized.message());
        helper.succeed();
    }
    /** politics.openFounding off refuses a resident's founding in the service itself, whatever reaches it; on, the same request founds. */
    @GameTest(template="empty",timeoutTicks=100)
    public static void residentFoundingFollowsTheSetting(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var identity=McaKingdomsApi.get(server);
        var resident=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"SettingResident"));
        var home=settlement(helper,identity,"politics-setting",144,SERENUM);
        identity.setResidence(resident,home.id());
        java.util.function.Predicate<UUID> known=id->id.equals(resident.getUUID());
        var closed=new GovernmentService(server,identity,McaKingdoms.POLITICS,new PoliticalSavedData(),false,known,()->true,()->false);
        Result refused=closed.execute(resident,req(closed,Action.BOOTSTRAP,SERENUM,SERENUM+"_charter",home.id().toString(),resident,"","",""));
        check(!refused.success()&&closed.government(SERENUM).isEmpty(),"A resident founded a government with resident founding off");
        var open=new GovernmentService(server,identity,McaKingdoms.POLITICS,new PoliticalSavedData(),false,known,()->true,()->true);
        Result founded=open.execute(resident,req(open,Action.BOOTSTRAP,SERENUM,SERENUM+"_charter",home.id().toString(),resident,"","",""));
        check(founded.success(),"An eligible resident could not found with the setting on: "+founded.message());
        check(open.government(SERENUM).map(g->g.capital().equals(home.id())).orElse(false),"The founded capital is not the residence");
        helper.succeed();
    }
    private static SettlementView settlement(GameTestHelper helper,KingdomsService service,String key,int dx,String kingdom) {
        BlockPos position=helper.absolutePos(new BlockPos(dx,2,2));
        var result=service.registerCandidate(helper.getLevel(),SettlementCandidate.structure(helper.getLevel().dimension(),position,4,new ResourceLocation("mcakingdoms:political_test"),key+position,null));
        return service.setKingdom(result.id(),new ResourceLocation(kingdom));
    }
    private static Request req(GovernmentService service,Action action,String kingdom,String definition,String target,ServerPlayer person,String counterpart,String text,String hash) {
        return new Request(UUID.randomUUID(),service.revision(),action,kingdom,definition,target,person==null?null:new Person(person.getUUID(),Kind.PLAYER),counterpart,text,hash,0,0,0);
    }
}
