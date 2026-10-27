package dev.otectus.mcakingdoms.data;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Starts a data reload for the command and the reload task, and reports that attempt's outcome to both alike. */
public final class DefinitionReloads {
    private DefinitionReloads() {
    }

    /** The outcome of one reload: the attempt this mod observed (if any) and the reload future's failure (if any). */
    public record Result(Optional<ReloadReport.Attempt> attempt, Throwable error) {
        public ReloadReport.State state() {
            // No attempt observed: this mod's definitions were not read, so their state is unknown rather than accepted.
            if (attempt.isEmpty()) return error == null ? ReloadReport.State.IN_PROGRESS : ReloadReport.State.FAILED;
            if (error != null) ReloadReport.failed(attempt.get(), error);
            return attempt.get().state();
        }

        public List<Component> lines() {
            return ReloadReport.describe(attempt, error);
        }
    }

    /**
     * Reloads the selected data packs. The future completes on the server thread with the attempt's outcome; it never
     * completes exceptionally, because a rejected definition is a reportable outcome rather than a crash.
     */
    public static CompletableFuture<Result> start(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Reloads start on the server thread");
        long before = ReloadReport.latestId();
        CompletableFuture<Void> reload;
        try {
            reload = server.reloadResources(server.getPackRepository().getSelectedIds());
        } catch (RuntimeException immediate) {
            reload = CompletableFuture.failedFuture(immediate);
        }
        return reload.handle((ignored, error) -> error)
                .thenApplyAsync(error -> new Result(ReloadReport.since(before), error), server);
    }
}
