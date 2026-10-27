package dev.otectus.mcakingdoms.command;

import com.mojang.brigadier.context.CommandContext;
import dev.otectus.mcakingdoms.evolution.EvolutionSavedData;
import dev.otectus.mcakingdoms.evolution.RecruitTransferData;
import dev.otectus.mcakingdoms.persistence.Capacity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/**
 * Operator capacity maintenance (F12): {@code /kingdoms maintenance capacity} reports every bounded collection, and
 * {@code /kingdoms maintenance compact transfers|opportunities} shows what would be archived (a dry run) until
 * {@code apply} is added. Applying writes the records to {@code <world>/mcakingdoms_archive/} first, then removes them
 * from the store in one save. Nothing else is ever compacted automatically.
 */
public final class MaintenanceCommands {
    private MaintenanceCommands() {
    }

    private static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("command.mcakingdoms.maintenance." + key, fallback, args);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("maintenance").requires(source -> source.hasPermission(2))
                .then(Commands.literal("capacity").executes(MaintenanceCommands::capacity))
                .then(Commands.literal("compact")
                        .then(Commands.literal("transfers").executes(c -> transfers(c, false))
                                .then(Commands.literal("apply").executes(c -> transfers(c, true))))
                        .then(Commands.literal("opportunities").executes(c -> opportunities(c, false))
                                .then(Commands.literal("apply").executes(c -> opportunities(c, true)))))));
    }

    private static int capacity(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        for (Capacity capacity : Capacity.all(source.getServer())) {
            source.sendSuccess(() -> line(capacity), false);
        }
        source.sendSuccess(() -> t("capacity.note", "Near is 80% or more; full refuses new records. Run a compaction without apply first: it changes nothing."), false);
        return 1;
    }

    public static Component line(Capacity capacity) {
        Component level = switch (capacity.level()) {
            case NORMAL -> t("level.normal", "ok");
            case NEAR -> t("level.near", "near capacity");
            case FULL -> t("level.full", "full");
        };
        Component relief = t("relief." + capacity.relief().name().toLowerCase(Locale.ROOT), capacity.relief().name());
        return t("capacity.line", "%s / %s: %s of %s (%s%%, %s). %s", capacity.store(), capacity.collection(), capacity.used(), capacity.limit(),
                capacity.percent(), level, relief);
    }

    private static int transfers(CommandContext<CommandSourceStack> context, boolean apply) {
        var source = context.getSource();
        var server = source.getServer();
        var data = RecruitTransferData.get(server);
        long now = server.overworld().getGameTime();
        int candidates = data.archivable(now).size();
        if (!apply) {
            source.sendSuccess(() -> t("transfers.dry_run", "Dry run: %s finished recruit transfers (complete, cancelled or expired, past their deadline by three days) would be archived. Reconciled and unfinished transfers stay. Back up the world, then add apply.", candidates), false);
            return candidates;
        }
        try {
            var result = data.compact(server, now);
            if (result.isEmpty()) source.sendSuccess(() -> t("nothing", "Nothing is due for archiving."), true);
            else source.sendSuccess(() -> t("transfers.done", "Archived %s finished recruit transfers to %s and removed them from the store.",
                    result.get().getValue(), result.get().getKey().getFileName().toString()), true);
            return result.map(java.util.Map.Entry::getValue).orElse(0);
        } catch (java.io.IOException | RuntimeException failure) {
            source.sendFailure(t("failed", "Archiving failed: %s. Nothing was removed that is not also in an archive file.", String.valueOf(failure.getMessage())));
            return 0;
        }
    }

    private static int opportunities(CommandContext<CommandSourceStack> context, boolean apply) {
        var source = context.getSource();
        var server = source.getServer();
        var data = EvolutionSavedData.get(server);
        long now = server.overworld().getGameTime();
        var plan = data.compactable(now);
        if (!apply) {
            source.sendSuccess(() -> t("opportunities.dry_run", "Dry run: %s finished opportunities would be archived, %s lapsed cooldowns and %s schedules of players no longer subscribed would be dropped. Used evidence is always kept. Back up the world, then add apply.",
                    plan.scenarios().size(), plan.lapsedCooldowns().size(), plan.idleDigests().size()), false);
            return plan.scenarios().size();
        }
        try {
            var file = data.compact(server, now);
            if (plan.empty()) source.sendSuccess(() -> t("nothing", "Nothing is due for archiving."), true);
            else source.sendSuccess(() -> t("opportunities.done", "Archived %s finished opportunities (%s) and dropped %s lapsed cooldowns and %s idle digest schedules.",
                    plan.scenarios().size(), file.map(f -> f.getFileName().toString()).orElse("-"), plan.lapsedCooldowns().size(), plan.idleDigests().size()), true);
            return plan.scenarios().size();
        } catch (java.io.IOException | RuntimeException failure) {
            source.sendFailure(t("failed", "Archiving failed: %s. Nothing was removed that is not also in an archive file.", String.valueOf(failure.getMessage())));
            return 0;
        }
    }
}
