package dev.otectus.mcakingdoms.compat.capitals;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/** {@code /kingdoms capitals report|accept <capital>|release <capital>} for operators (C3). Capitals itself is never written. */
public final class CapitalsCommands {
    private CapitalsCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("capitals").requires(source -> source.hasPermission(2))
                .then(Commands.literal("report").executes(c -> { CapitalsReport.lines(c.getSource().getServer()).forEach(line -> c.getSource().sendSuccess(() -> line, false)); return 1; }))
                .then(Commands.literal("accept").then(Commands.argument("capital", StringArgumentType.word()).executes(c -> decide(c, true))))
                .then(Commands.literal("release").then(Commands.argument("capital", StringArgumentType.word()).executes(c -> decide(c, false))))));
    }

    private static int decide(CommandContext<CommandSourceStack> c, boolean accept) {
        try {
            UUID capital = UUID.fromString(StringArgumentType.getString(c, "capital"));
            var interop = CapitalsInterop.get(c.getSource().getServer());
            UUID operator = c.getSource().getEntity() == null ? new UUID(0, 0) : c.getSource().getEntity().getUUID();
            String result = accept ? interop.accept(operator, capital) : interop.release(operator, capital);
            c.getSource().sendSuccess(() -> Keyed.component(result), true);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            c.getSource().sendFailure(Keyed.component(refused));
            return 0;
        }
    }
}
