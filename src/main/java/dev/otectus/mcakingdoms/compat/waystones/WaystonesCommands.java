package dev.otectus.mcakingdoms.compat.waystones;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/**
 * {@code /kingdoms waystones status|backfill [apply]} (WAY-01), for operators. A backfill always starts as a dry run that
 * counts every registered waystone by reason; applying names only stones that are still unnamed and inside a settlement,
 * at most 64 per run, and never a named stone. Output names no player.
 */
public final class WaystonesCommands {
    static final int BACKFILL_STEP = 64;

    private WaystonesCommands() {
    }

    private static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("waystones.mcakingdoms." + key, fallback, args);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("waystones").requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(WaystonesCommands::status))
                .then(Commands.literal("backfill").executes(c -> backfill(c, false))
                        .then(Commands.literal("apply").executes(c -> backfill(c, true))))));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        var status = WaystonesIntegration.status();
        c.getSource().sendSuccess(() -> t("command.status", "Waystones settlement names: %s (Waystones %s, Balm %s). %s", status.readiness().name().toLowerCase(Locale.ROOT),
                status.waystones().isEmpty() ? "-" : status.waystones(), status.balm().isEmpty() ? "-" : status.balm(), status.reason()), false);
        if (status.readiness() == WaystonesIntegration.Readiness.ABSENT) return 1;
        var data = WaystonesNamingData.get(c.getSource().getServer());
        for (var stage : WaystonesNamingData.Stage.values())
            c.getSource().sendSuccess(() -> t("command.stage", "  %s: %s", stage.name().toLowerCase(Locale.ROOT), data.count(stage)), false);
        c.getSource().sendSuccess(() -> t("command.observer", "Player edits observed: %s.", Component.translatable(WaystonesIntegration.editsObserved() ? "gui.yes" : "gui.no")), false);
        return 1;
    }

    private static int backfill(CommandContext<CommandSourceStack> c, boolean apply) {
        var status = WaystonesIntegration.status();
        if (!status.ready()) {
            c.getSource().sendFailure(t("command.unavailable", "Settlement names for waystones are not active: %s", status.reason()));
            return 0;
        }
        var counts = WaystonesIntegration.backfill(c.getSource().getServer(), apply, BACKFILL_STEP);
        c.getSource().sendSuccess(() -> apply ? t("command.backfill_applied", "Backfill: unnamed waystones in settlements were named (at most %s per run; run again for more). Named waystones were left as they are.", BACKFILL_STEP)
                : t("command.backfill_dry", "Backfill dry run: nothing was changed. Run /kingdoms waystones backfill apply to name the eligible ones."), true);
        counts.forEach((reason, count) -> c.getSource().sendSuccess(() -> t("command.reason", "  %s: %s", reason.name().toLowerCase(Locale.ROOT), count), false));
        return 1;
    }
}
