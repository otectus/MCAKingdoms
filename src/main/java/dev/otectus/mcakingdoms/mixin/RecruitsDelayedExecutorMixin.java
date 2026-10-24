package dev.otectus.mcakingdoms.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Gives Recruits' process-wide delayed task executor an explicit server lifecycle. */
@Pseudo
@Mixin(targets = "com.talhanation.recruits.util.DelayedExecutor", remap = false)
public abstract class RecruitsDelayedExecutorMixin {
    @Shadow @Final @Mutable
    private static ScheduledExecutorService scheduler;

    @Unique
    private static volatile long mcaKingdoms$generation;

    @Redirect(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/Executors;newSingleThreadScheduledExecutor()Ljava/util/concurrent/ScheduledExecutorService;",
                    remap = false
            ),
            remap = false,
            require = 1
    )
    private static ScheduledExecutorService mcaKingdoms$createInitialScheduler() {
        return mcaKingdoms$newScheduler();
    }

    @Unique
    private static ScheduledExecutorService mcaKingdoms$newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "Recruits-Delayed");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * Preserves native delay while returning provider callbacks to the server thread.
     * Tasks submitted after server shutdown are stale and are discarded.
     */
    @Overwrite(remap = false)
    public static synchronized void runLater(Runnable task, long delay) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isStopped() || scheduler.isShutdown()) return;
        long generation = mcaKingdoms$generation;
        var submittingScheduler = scheduler;
        submittingScheduler.schedule(() -> {
            if (generation != mcaKingdoms$generation
                    || submittingScheduler.isShutdown()
                    || server.isStopped()) return;
            server.execute(() -> {
                if (generation == mcaKingdoms$generation
                        && submittingScheduler == scheduler
                        && !submittingScheduler.isShutdown()
                        && ServerLifecycleHooks.getCurrentServer() == server
                        && !server.isStopped()) task.run();
            });
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Unique
    private static synchronized void mcaKingdoms$startScheduler() {
        if (scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = mcaKingdoms$newScheduler();
        }
        mcaKingdoms$generation++;
    }

    @Unique
    private static synchronized void mcaKingdoms$stopScheduler() {
        scheduler.shutdownNow();
        mcaKingdoms$generation++;
    }
}
