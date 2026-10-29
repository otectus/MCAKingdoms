package dev.otectus.mcakingdoms.acceptance;
import dev.otectus.mcakingdoms.test.McaGameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
@Mod("mcakingdoms_acceptance")
public final class ProductionMcaHarness {
    private MinecraftServer server;
    private long tick;
    private boolean finished;
    private final TreeMap<Long,List<Runnable>> scheduled=new TreeMap<>();
    public ProductionMcaHarness(){MinecraftForge.EVENT_BUS.register(this);}
    /** Saved data a scenario needs on disk before the levels load, as an earlier build would have left it. */
    @SubscribeEvent public void aboutToStart(ServerAboutToStartEvent event){
        if(!"split-village".equals(System.getProperty("mcakingdoms.acceptance.integration")))return;
        try{SplitVillageScenario.seed(event.getServer());}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    @SubscribeEvent public void start(ServerStartedEvent event){
        server=event.getServer();
        if(Boolean.getBoolean("mcakingdoms.acceptance.r3Multiplayer")||Boolean.getBoolean("mcakingdoms.acceptance.r4Multiplayer")){finished=true;return;}
        try {
            if(System.getProperty("mcakingdoms.acceptance.integration")!=null){
                IntegrationScenario.run(server,(delay,action)->scheduled.computeIfAbsent(tick+delay,ignored->new ArrayList<>()).add(action),this::finish);return;
            }
            if(Boolean.getBoolean("mcakingdoms.acceptance.commands")){
                dev.otectus.mcakingdoms.test.KingdomGameTests.runCommandScenario(server.overworld(),new BlockPos(0,65,0));
                finish("PASS packaged dispatcher: unquoted kingdom IDs/slugs, actual cross-kingdom mutation, quoted name/alias, executable suggestions");return;
            }
            McaGameTests.runScenario(server.overworld(),new BlockPos(0,65,0),
                (delay,action)->scheduled.computeIfAbsent(tick+delay,ignored->new ArrayList<>()).add(action),
                ()->finish("PASS exact MCA production lifecycle: native home/newborn origin/short visit/periodic migration/history/native data/name/NBT reload"));}
        catch(Throwable failure){fail(failure);}
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||server==null||finished)return;
        tick++;
        try {while(!scheduled.isEmpty()&&scheduled.firstKey()<=tick){for(Runnable action:scheduled.pollFirstEntry().getValue())action.run();}
            String phase=System.getProperty("mcakingdoms.acceptance.integration","");
            if(tick>("politics-cycle".equals(phase)?25000:phase.equals("lv")||phase.startsWith("lv-")?4000:650))throw new IllegalStateException("MCA lifecycle timed out");}
        catch(Throwable failure){fail(failure);}
    }
    private void fail(Throwable failure){failure.printStackTrace();finish("FAIL "+failure);}
    private void finish(String result){if(finished)return;finished=true;
        try{Files.writeString(Path.of(Boolean.getBoolean("mcakingdoms.acceptance.commands")?"command-results.txt":"mca-lifecycle-results.txt"),result+System.lineSeparator());}
        catch(Exception e){throw new RuntimeException(e);}System.out.println(result);server.halt(false);
    }
}
