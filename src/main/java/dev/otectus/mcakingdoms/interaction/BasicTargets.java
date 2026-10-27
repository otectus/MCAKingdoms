package dev.otectus.mcakingdoms.interaction;

import dev.otectus.mcakingdoms.civic.CivicConfig;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.factions.organization.OrganizationApi;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.world.entity.*;
import java.util.*;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

public final class BasicTargets {
    public static void init(){
        // Kingdom and organization names are translation keys, so each player reads them in their own language.
        targets("kingdom",c->McaKingdomsApi.get(c.server()).getKingdoms().stream().map(k->Choice.of(k.id().toString(),tr(k.translationKey(),PlayerWords.text(k.translationKey())))).toList());
        targets("settlement",c->{var api=McaKingdomsApi.get(c.server());var out=new ArrayList<Choice>();
            for(var s:SettlementKnowledge.get(c.server()).all(c.player,api,Optional.empty(),4096))out.add(new Choice(s.id().toString(),s.displayName(),s.dimension().location()+" · "+s.anchor().toShortString(),Map.of("revision",Long.toString(s.revision()),"kingdom",s.kingdomId().toString())));return out;});
        targets("player",c->c.server().getPlayerList().getPlayers().stream().map(p->new Choice(p.getUUID().toString(),p.getGameProfile().getName())).toList());
        targets("person",c->{var result=new ArrayList<>(c.choices("player"));result.addAll(c.choices("npc"));return result;});
        targets("npc",c->c.player.serverLevel().getEntities(c.player,c.player.getBoundingBox().inflate(32),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)&&e.isAlive()&&c.player.hasLineOfSight(e)).stream().map(e->Choice.of(e.getUUID().toString(),e.getDisplayName(),tr("interaction.mcakingdoms.choice.nearby","Nearby and visible"))).toList());
        // Contacts for guild and contract services: exactly the people those services will accept (contactRange).
        targets("nearby_npc",c->{int range=CivicConfig.CONTACT_RANGE.get();return c.player.serverLevel().getEntities(c.player,c.player.getBoundingBox().inflate(range),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)&&e.isAlive()&&e.distanceToSqr(c.player)<=(double)range*range&&c.player.hasLineOfSight(e)).stream().map(e->Choice.of(e.getUUID().toString(),e.getDisplayName(),tr("interaction.mcakingdoms.choice.within","Within %s blocks and visible",range))).toList();});
        targets("organization",c->OrganizationApi.get(c.server()).definitions().stream().map(d->Choice.of(d.id().toString(),tr(d.nameKey(),PlayerWords.text(d.nameKey())))).toList());
    }
    public static Entity entity(Context c,String key){UUID id=c.uuid(key);if(id.equals(c.player.getUUID()))return c.player;var player=c.server().getPlayerList().getPlayer(id);if(player!=null)return player;var entity=c.player.serverLevel().getEntity(id);if(entity==null||entity.distanceToSqr(c.player)>32*32||!c.player.hasLineOfSight(entity))throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.person_gone","That person is no longer nearby and visible. Stand within 32 blocks with a clear line of sight, then choose them again."));return entity;}
    /** A contact picked from {@code nearby_npc}, still within contactRange and visible when the task runs. */
    public static Entity contact(Context c,String key){int range=CivicConfig.CONTACT_RANGE.get();var entity=c.player.serverLevel().getEntity(c.uuid(key));if(entity==null||!entity.isAlive()||entity.distanceToSqr(c.player)>(double)range*range||!c.player.hasLineOfSight(entity))throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.contact_gone","That contact is no longer within %s blocks and in sight.",range));return entity;}
    public static String settlement(Context c,UUID id){return McaKingdomsApi.get(c.server()).getSettlement(id).filter(s->SettlementKnowledge.get(c.server()).visible(c.player,id)).map(SettlementView::displayName).orElse("Unknown settlement");}
    public static String player(Context c,UUID id){var player=c.server().getPlayerList().getPlayer(id);return player==null?(c.server().getProfileCache()==null?java.util.Optional.<com.mojang.authlib.GameProfile>empty():c.server().getProfileCache().get(id)).map(com.mojang.authlib.GameProfile::getName).orElse("Player (offline)"):player.getGameProfile().getName();}
    private BasicTargets(){}
}
