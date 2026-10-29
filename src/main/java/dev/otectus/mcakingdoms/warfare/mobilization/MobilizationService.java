package dev.otectus.mcakingdoms.warfare.mobilization;

import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Names;

import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.compat.recruits.*;
import dev.otectus.mcakingdoms.warfare.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.*;

/** Temporary, owner-consented order composition. Native ownership and equipment remain untouched. */
public final class MobilizationService {
    private final MinecraftServer server;private final MobilizationSavedData data;
    private final List<UUID> startupRecovery;
    private final Map<UUID,Long> restorationPendingSince=new HashMap<>();
    private int startupRecoveryCursor,expirationCursor,verificationCursor;
    public MobilizationService(MinecraftServer server){this.server=server;data=MobilizationSavedData.get(server);startupRecovery=data.leases().stream()
            .filter(l->!l.terminal()).sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).thenComparing(MobilizationSavedData.Lease::id))
            .map(MobilizationSavedData.Lease::unit).toList();}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Mobilization requires server thread");}
    private record Authority(String faction,String kingdom){ }
    private Authority authorize(ServerPlayer actor){
        thread();if(actor.getServer()!=server||!WarfareConfig.enabled()||!WarfareConfig.MILITARY.get()||!data.writable())throw Keyed.refuse("warfare.mcakingdoms.mobilization.unavailable","Deployments are unavailable: military actions are off on this server or deployment records are read-only.");
        RecruitsMilitary.ready(server);String faction=RecruitsMilitary.commandedFaction(actor);var mapping=WarfareRuntime.get(server).mapping(faction)
                .orElseThrow(()->Keyed.refuse("warfare.mcakingdoms.mobilization.faction_unmapped","Your Recruits faction is not linked to a recognized kingdom."));
        if(!KingdomsPoliticsApi.get(server).authorized(actor,mapping.kingdom(),Politics.Permission.PROPOSE))
            throw Keyed.refuse("warfare.mcakingdoms.mobilization.mandate","You need the Propose agreements responsibility in your faction's kingdom to deploy recruits.");return new Authority(faction,mapping.kingdom());
    }
    private void authorizeRestoration(ServerPlayer actor,MobilizationSavedData.Lease lease){
        thread();if(actor.getServer()!=server||actor.hasDisconnected()||!data.writable())throw Keyed.refuse("warfare.mcakingdoms.mobilization.restoration_unavailable","Restoring deployments is unavailable right now: deployment records are read-only or you are not connected.");
        if(!actor.hasPermissions(2)&&!lease.actor().equals(actor.getUUID()))throw Keyed.refuse("warfare.mcakingdoms.mobilization.other_commander","This deployment belongs to another commander.");
    }
    public String muster(ServerPlayer actor,UUID unitId,MobilizationDoctrine doctrine){
        Authority authority=authorize(actor);Entity unit=findLoaded(unitId);if(unit==null)return Keyed.text("warfare.mcakingdoms.mobilization.unit_unloaded","That recruit is not loaded, and its area was not loaded for this request.");
        if(unit.level()!=actor.level()||actor.distanceToSqr(unit)>32*32)return Keyed.text("warfare.mcakingdoms.mobilization.unit_distance","The recruit must be loaded within 32 blocks of you.");
        return muster(actor,unit,doctrine,authority);
    }
    public List<String> musterNearby(ServerPlayer actor,MobilizationDoctrine doctrine){
        Authority authority=authorize(actor);long active=data.leases().stream().filter(l->l.actor().equals(actor.getUUID())&&!l.terminal()).count();
        int room=(int)Math.max(0,WarfareConfig.UNIT_BUDGET.get()-active);if(room==0)return List.of(Keyed.text("warfare.mcakingdoms.mobilization.budget","You already have as many deployments as this server allows."));
        List<String> result=new ArrayList<>();for(Entity unit:RecruitsMobilization.nearbyOwned(actor,room))result.add(muster(actor,unit,doctrine,authority));
        return result.isEmpty()?List.of(Keyed.text("warfare.mcakingdoms.mobilization.none_nearby","None of your recruits is loaded within 32 blocks.")):List.copyOf(result);
    }
    private String muster(ServerPlayer actor,Entity unit,MobilizationDoctrine doctrine,Authority authority){
        if(!RecruitsMobilization.ownedBy(unit,actor))return Keyed.text("warfare.mcakingdoms.mobilization.not_owned","%s is not currently yours to deploy.",Names.unitText(server,unit.getUUID()));
        if(data.activeUnit(unit.getUUID()).isPresent())return Keyed.text("warfare.mcakingdoms.mobilization.already_deployed","%s already has a deployment on record.",Names.unitText(server,unit.getUUID()));
        if(dev.otectus.mcakingdoms.evolution.RecruitTransferService.blocksMobilization(server,unit.getUUID()))return Keyed.text("warfare.mcakingdoms.mobilization.transfer_pending","This recruit has a pending negotiated transfer; cancel or complete it before deploying.");
        long active=data.leases().stream().filter(l->l.actor().equals(actor.getUUID())&&!l.terminal()).count();if(active>=WarfareConfig.UNIT_BUDGET.get())return Keyed.text("warfare.mcakingdoms.mobilization.budget","You already have as many deployments as this server allows.");
        var before=RecruitsMobilization.snapshot(unit);if(before.owner()==null)return Keyed.text("warfare.mcakingdoms.mobilization.identity_incomplete","Recruits does not report a complete owner for this recruit, so it cannot be deployed.");
        var applied=RecruitsMobilization.planned(unit,actor,doctrine);long now=actor.level().getGameTime();UUID leaseId=UUID.randomUUID();
        var lease=new MobilizationSavedData.Lease(leaseId,unit.getUUID(),actor.getUUID(),authority.faction(),authority.kingdom(),doctrine,before,applied,now,
                now+WarfareConfig.MOBILIZATION_TICKS.get(),MobilizationSavedData.Phase.PREPARED,Keyed.text("warfare.mcakingdoms.mobilization.prepared","The deployment is saved; the recruit's orders have not been changed yet."));
        var next=data.snapshot();next.leases.put(leaseId.toString(),lease);next.revision++;if(!data.commit(server,next))return Keyed.text("warfare.mcakingdoms.mobilization.save_failed","The deployment could not be saved, so the recruit's orders are unchanged.");
        try{
            if(!RecruitsMobilization.identity(unit,before)||!RecruitsMobilization.apply(unit,applied))return failedApply(lease,unit);
            return phase(lease,MobilizationSavedData.Phase.ACTIVE_PENDING_NATIVE_SAVE,
                    Keyed.text("warfare.mcakingdoms.mobilization.mobilized","Deployed %s for %s. The deployment is saved; Recruits has not saved the new orders yet.",Names.unitText(server,unit.getUUID()),doctrine));
        }catch(RuntimeException failure){return failedApply(lease,unit);}
    }
    private String failedApply(MobilizationSavedData.Lease lease,Entity unit){
        MobilizationSavedData.Phase phase;String detail;try{var current=RecruitsMobilization.snapshot(unit);
            if(current.equals(lease.before())){phase=MobilizationSavedData.Phase.RESTORED;detail=Keyed.text("warfare.mcakingdoms.mobilization.write_refused","Recruits refused the new orders; the original orders were kept.");}
            else{phase=MobilizationSavedData.Phase.DIVERGED;detail=Keyed.text("warfare.mcakingdoms.mobilization.write_diverged","The recruit's orders differ from both the old and the new ones; use Recover to restore the saved orders.");}}
        catch(RuntimeException unavailable){phase=MobilizationSavedData.Phase.SUSPENDED;detail=Keyed.text("warfare.mcakingdoms.mobilization.provider_lost","Recruits became unavailable after the deployment was saved; it is kept for a later retry.");}
        return phase(lease,phase,detail);
    }
    public String dismiss(ServerPlayer actor,UUID leaseId){thread();var lease=data.lease(leaseId).orElseThrow(()->Keyed.refuse("warfare.mcakingdoms.mobilization.lease_unavailable","That deployment is not available."));authorizeRestoration(actor,lease);
        if(lease.terminal())return Keyed.text("warfare.mcakingdoms.mobilization.already_restored","This deployment was already ended; the recruit's orders were not changed again.");
        Entity unit=findLoaded(lease.unit());if(unit==null)return phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING,Keyed.text("warfare.mcakingdoms.mobilization.dismissed_pending","Dismissed. The original orders return when the recruit is next loaded."));
        return restore(lease,unit,false);
    }
    public String recover(ServerPlayer actor,UUID leaseId){thread();var lease=data.lease(leaseId).orElseThrow(()->Keyed.refuse("warfare.mcakingdoms.mobilization.lease_unavailable","That deployment is not available."));authorizeRestoration(actor,lease);
        if(lease.terminal())return Keyed.text("warfare.mcakingdoms.mobilization.already_restored","This deployment was already ended; the recruit's orders were not changed again.");
        Entity unit=findLoaded(lease.unit());if(unit==null)return Keyed.text("warfare.mcakingdoms.mobilization.recover_unloaded","The recruit is not loaded; recovery does not load its area. Try again near the recruit.");return restore(lease,unit,true);
    }
    /** One bounded pass catches lease units that joined before ServerStarted installed the event queue. */
    public int recoverLoadedAtStartup(int budget){
        thread();int checked=0;while(checked<budget&&startupRecoveryCursor<startupRecovery.size()){
            Entity unit=findLoaded(startupRecovery.get(startupRecoveryCursor++));checked++;if(unit!=null&&!unit.isRemoved())loaded(unit,unit.level().getGameTime(),true);
        }return checked;
    }
    public void tick(){
        thread();long now=server.overworld().getGameTime();int budget=8;
        var confirmations=data.leases().stream().filter(l->l.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE
                &&!restorationPendingSince.containsKey(l.id())).sorted(Comparator.comparing(MobilizationSavedData.Lease::id)).toList();
        if(!confirmations.isEmpty()){int count=Math.min(budget,confirmations.size()),start=Math.floorMod(verificationCursor,confirmations.size());verificationCursor+=count;
            for(int i=0;i<count;i++){var lease=confirmations.get((start+i)%confirmations.size());Entity unit=findLoaded(lease.unit());budget--;if(unit!=null)loaded(unit,now,true);}}
        if(budget==0)return;var expired=data.leases().stream().filter(l->!l.terminal()&&l.expiresAt()<=now)
                .filter(l->l.phase()!=MobilizationSavedData.Phase.RESTORE_PENDING&&l.phase()!=MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE
                        &&l.phase()!=MobilizationSavedData.Phase.DIVERGED)
                .sorted(Comparator.comparing(MobilizationSavedData.Lease::expiresAt).thenComparing(MobilizationSavedData.Lease::id)).toList();
        if(expired.isEmpty())return;int count=Math.min(budget,expired.size()),start=Math.floorMod(expirationCursor,expired.size());expirationCursor+=count;
        for(int i=0;i<count;i++){var lease=expired.get((start+i)%expired.size());Entity unit=findLoaded(lease.unit());if(unit==null){
                phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING,Keyed.text("warfare.mcakingdoms.mobilization.expired_pending","The deployment ended. The original orders return when the recruit is next loaded."));continue;}restore(lease,unit,false);
        }
    }
    public void loaded(Entity unit,long observedAt,boolean startupRecovery){
        thread();var lease=data.activeUnit(unit.getUUID()).orElse(null);if(lease==null)return;
        if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE){long pendingSince=restorationPendingSince.getOrDefault(lease.id(),Long.MIN_VALUE);
            if(startupRecovery||observedAt>pendingSince)restorationPendingSince.remove(lease.id());}
        try{RecruitsMilitary.ready(server);var current=RecruitsMobilization.snapshot(unit);long now=unit.level().getGameTime();
            if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE){
                if(current.equals(lease.before())&&!restorationPendingSince.containsKey(lease.id())){
                    restorationPendingSince.remove(lease.id());phase(lease,MobilizationSavedData.Phase.RESTORED,Keyed.text("warfare.mcakingdoms.mobilization.reload_confirmed","Reloading the recruit confirmed that its original orders are saved."));
                }else if(current.equals(lease.applied()))restore(lease,unit,false);
                else if(!current.equals(lease.before()))phase(lease,MobilizationSavedData.Phase.DIVERGED,Keyed.text("warfare.mcakingdoms.mobilization.reload_diverged","The recruit's orders changed while the restoration waited to be saved; use Recover."));
                return;
            }
            if(now>=lease.expiresAt()||lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING){restore(lease,unit,false);return;}
            if(current.equals(lease.applied()))phase(lease,MobilizationSavedData.Phase.DURABLE_ACTIVE,Keyed.text("warfare.mcakingdoms.mobilization.durable_active","The deployment and the recruit's new orders are both saved."));
            else if((lease.phase()==MobilizationSavedData.Phase.PREPARED||lease.phase()==MobilizationSavedData.Phase.ACTIVE_PENDING_NATIVE_SAVE
                    ||lease.phase()==MobilizationSavedData.Phase.SUSPENDED)&&current.equals(lease.before()))
                phase(lease,MobilizationSavedData.Phase.RESTORED,Keyed.text("warfare.mcakingdoms.mobilization.recovered_original","The deployment ended before the new orders were saved; the recruit kept its original orders."));
            else phase(lease,MobilizationSavedData.Phase.DIVERGED,Keyed.text("warfare.mcakingdoms.mobilization.loaded_diverged","The recruit's current orders differ from the deployment; you can use Recover."));
        }catch(RuntimeException unavailable){if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE)
                phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,Keyed.text("warfare.mcakingdoms.mobilization.provider_wait","Recruits is unavailable; the restoration still waits for the recruit to be saved."));
            else phase(lease,MobilizationSavedData.Phase.SUSPENDED,Keyed.text("warfare.mcakingdoms.mobilization.provider_retained","Recruits is unavailable; the deployment is kept unchanged until it returns."));}
    }
    private String restore(MobilizationSavedData.Lease lease,Entity unit,boolean explicit){
        var retained=data.restorableLease(lease.id());if(retained.isEmpty())return Keyed.text("warfare.mcakingdoms.mobilization.already_restored","This deployment was already ended; the recruit's orders were not changed again.");lease=retained.get();
        try{
            if(!RecruitsMobilization.identity(unit,lease.before()))return phase(lease,MobilizationSavedData.Phase.DIVERGED,Keyed.text("warfare.mcakingdoms.mobilization.identity_changed","The recruit's owner or group changed, so the original orders were not restored."));
            var current=RecruitsMobilization.snapshot(unit);
            if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE&&current.equals(lease.before())
                    &&!restorationPendingSince.containsKey(lease.id()))return phase(lease,MobilizationSavedData.Phase.RESTORED,Keyed.text("warfare.mcakingdoms.mobilization.reload_confirmed","Reloading the recruit confirmed that its original orders are saved."));
            if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE&&!current.equals(lease.applied())&&!current.equals(lease.before()))
                return phase(lease,MobilizationSavedData.Phase.DIVERGED,Keyed.text("warfare.mcakingdoms.mobilization.changed_while_pending","The orders changed while the restoration waited to be saved; the pending restoration did not overwrite them."));
            if(!explicit&&!current.equals(lease.applied())&&!current.equals(lease.before()))
                return phase(lease,MobilizationSavedData.Phase.DIVERGED,Keyed.text("warfare.mcakingdoms.mobilization.commander_changed","You changed the orders after deploying, so they were not restored automatically. Use Recover to restore the saved orders."));
            String pending=phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,Keyed.text("warfare.mcakingdoms.mobilization.restore_pending","The original orders are back in place; waiting for the recruit to be saved to confirm them."));
            var durable=data.restorableLease(lease.id());if(durable.isEmpty()||durable.get().phase()!=MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE)
                return Keyed.text("warfare.mcakingdoms.mobilization.restore_save_failed","The restoration could not be saved, so the recruit's orders were not changed.");
            lease=durable.get();restorationPendingSince.put(lease.id(),unit.level().getGameTime());
            if(!current.equals(lease.before())&&!RecruitsMobilization.apply(unit,lease.before()))
                return phase(lease,MobilizationSavedData.Phase.DIVERGED,Keyed.text("warfare.mcakingdoms.mobilization.restore_refused","Recruits refused the restoration; Recover stays available."));
            return pending;
        }catch(RuntimeException unavailable){return lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE
                ?phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,Keyed.text("warfare.mcakingdoms.mobilization.provider_wait","Recruits is unavailable; the restoration still waits for the recruit to be saved."))
                :phase(lease,MobilizationSavedData.Phase.SUSPENDED,Keyed.text("warfare.mcakingdoms.mobilization.restore_retry","Recruits is unavailable; the restoration is kept for a retry."));}
    }
    private String phase(MobilizationSavedData.Lease lease,MobilizationSavedData.Phase phase,String detail){var next=data.snapshot();var current=next.leases.get(lease.id().toString());
        if(current==null)return Keyed.text("warfare.mcakingdoms.mobilization.lease_unavailable","That deployment is not available.");if(current.phase()==phase&&current.detail().equals(detail))return detail;
        next.leases.put(lease.id().toString(),current.phase(phase,detail));next.revision++;return data.commit(server,next)?detail:Keyed.text("warfare.mcakingdoms.mobilization.status_save_failed","The deployment status could not be saved; inspect it before retrying.");}
    private Entity findLoaded(UUID id){for(var level:server.getAllLevels()){Entity entity=level.getEntity(id);if(entity!=null)return entity;}return null;}
    public List<MobilizationSavedData.Lease> deployments(ServerPlayer viewer,int offset,int limit){thread();if(viewer.getServer()!=server||viewer.hasDisconnected()||offset<0||offset>8192||limit<1||limit>64)throw Keyed.refuse("warfare.mcakingdoms.mobilization.invalid_query","That deployment list request is not valid.");return data.leases().stream().filter(l->viewer.hasPermissions(2)||l.actor().equals(viewer.getUUID())).sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).reversed().thenComparing(MobilizationSavedData.Lease::id)).skip(offset).limit(limit).toList();}
    /** The viewer's own temporary orders as translatable lines: order, stage, recruit and time left on the game clock. */
    public List<net.minecraft.network.chat.Component> statusLines(ServerPlayer viewer){thread();return data.leases().stream().filter(l->viewer.hasPermissions(2)||l.actor().equals(viewer.getUUID()))
            .sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).reversed()).limit(8)
            .map(l->(net.minecraft.network.chat.Component)net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.room.order","%s · %s · %s · ends in %s",
                    dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(l.doctrine()),dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(l.phase()),
                    dev.otectus.mcakingdoms.interaction.Names.entity(server,l.unit()),dev.otectus.mcakingdoms.interaction.Names.remainingText(server,l.expiresAt()))).toList();}
    public List<String> status(ServerPlayer viewer){thread();return data.leases().stream().filter(l->viewer.hasPermissions(2)||l.actor().equals(viewer.getUUID()))
            .sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).reversed()).limit(32)
            .map(l->Keyed.text("warfare.mcakingdoms.mobilization.status_line","%s · %s · %s · ends in %s · %s",l.doctrine(),l.phase(),Names.unitText(server,l.unit()),Names.remainingText(server,l.expiresAt()),Keyed.component(l.detail()))).toList();}
}
