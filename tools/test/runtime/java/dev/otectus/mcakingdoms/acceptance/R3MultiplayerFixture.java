package dev.otectus.mcakingdoms.acceptance;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsMilitary;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import dev.otectus.mcakingdoms.warfare.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
import static dev.otectus.mcakingdoms.acceptance.IntegrationScenario.*;

@Mod.EventBusSubscriber(modid="mcakingdoms_acceptance")
public final class R3MultiplayerFixture {
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if(!Boolean.getBoolean("mcakingdoms.acceptance.r3Multiplayer"))return;
        event.getDispatcher().register(Commands.literal("r3-fixture").requires(s->s.hasPermission(4))
            .then(Commands.literal("setup").executes(c->{try{
                var server=c.getSource().getServer();var level=server.overworld();var one=server.getPlayerList().getPlayerByName("PoliticalLeader");var two=server.getPlayerList().getPlayerByName("PoliticalOther");
                Objects.requireNonNull(one);Objects.requireNonNull(two);server.getPlayerList().op(one.getGameProfile());
                one.setGameMode(net.minecraft.world.level.GameType.CREATIVE);two.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                one.teleportTo(level,0,64,0,0,0);two.teleportTo(level,500,64,500,0,0);
                Object factions=Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
                for(var player:List.of(one,two)) {String name=player==one?"multi_a":"multi_b";call(factions,"addTeam",name,name,player.getUUID(),player.getScoreboardName(),new CompoundTag(),(byte)1,net.minecraft.ChatFormatting.BLUE);
                    var team=server.getScoreboard().getPlayerTeam(name);if(team==null)team=server.getScoreboard().addPlayerTeam(name);server.getScoreboard().addPlayerToTeam(player.getScoreboardName(),team);}
                Object owner=call(factions,"getFactionByStringID","multi_b"), manager=Class.forName("com.talhanation.recruits.ClaimEvents").getField("recruitsClaimManager").get(null);
                Class<?> type=Class.forName("com.talhanation.recruits.world.RecruitsClaim");Object claim=type.getConstructor(String.class,owner.getClass()).newInstance("Multiplayer Harbor",owner);
                call(claim,"setCenter",new ChunkPos(0,0));call(claim,"addChunk",new ChunkPos(0,0));call(manager,"addOrUpdateClaim",level,claim);
                var pos=new BlockPos(0,64,0);var k=new ResourceLocation("mcakingdoms:lunari");var settlement=McaKingdomsApi.get(server).registerCandidate(level,SettlementCandidate.external(level.dimension(),pos,12,SettlementBounds.around(pos,12),new ResourceLocation("mcakingdoms_acceptance:multi"),"harbor",k,Map.of(),"Multiplayer Harbor"));
                var control=WarfareRuntime.get(server);control.mapHere(one,k);call(manager,"save",level);call(factions,"save",level);server.saveEverything(false,true,true);control.bindHere(one,settlement.id());
                SettlementKnowledge.get(server).discover(one.getUUID(),settlement.id());Files.writeString(Path.of("../SETTLEMENT"),settlement.id().toString());
                System.out.println("R3_MULTIPLAYER_READY");return 1;
            }catch(Exception e){throw new RuntimeException(e);}}))
            .then(Commands.literal("audit").then(Commands.argument("expected",com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c->{
                String expected=com.mojang.brigadier.arguments.StringArgumentType.getString(c,"expected");var actual=RecruitsMilitary.relation(c.getSource().getServer(),"multi_a","multi_b");
                if(!actual.name().equals(expected))throw new AssertionError("Spoofed native policy: expected "+expected+" actual "+actual);
                System.out.println("R3_MULTIPLAYER_AUDIT_"+expected);return 1;
            }))));
    }
}
