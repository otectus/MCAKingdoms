package dev.otectus.mcakingdoms.politics.dynasty;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.politics.PoliticalCommittedEvent;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * Drives the dynasty from committed political changes and a maintenance pass every two seconds, and registers
 * {@code /kingdoms dynasty status|law|retry} for operators. Nothing is announced on load or restart: a restart only
 * resumes the cases already recorded.
 */
public final class DynastyEvents {
    static final int PERIOD = 40;

    private DynastyEvents() {
    }

    @SubscribeEvent
    public static void committed(PoliticalCommittedEvent event) {
        if (!event.server().isSameThread()) return;
        try { DynastyService.get(event.server()).ifPresent(service -> service.observe(event.notice())); }
        catch (RuntimeException failure) { LogUtils.getLogger().error("[MCA: Kingdoms] The dynasty records could not follow a political change; the next maintenance pass retries", failure); }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % PERIOD != 7) return;
        try { DynastyService.get(event.getServer()).ifPresent(DynastyService::maintain); }
        catch (RuntimeException failure) { LogUtils.getLogger().error("[MCA: Kingdoms] Dynasty maintenance failed; it is retried", failure); }
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("dynasty").requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").then(Commands.argument("kingdom", StringArgumentType.string()).executes(DynastyEvents::status)))
                .then(Commands.literal("law").then(Commands.argument("kingdom", StringArgumentType.string()).then(Commands.argument("law", StringArgumentType.string())
                        .executes(c -> run(c, service -> service.adoptLaw(c.getSource().getPlayerOrException(), UUID.randomUUID(), StringArgumentType.getString(c, "kingdom"),
                                new ResourceLocation(StringArgumentType.getString(c, "law"))))))))
                .then(Commands.literal("retry").then(Commands.argument("kingdom", StringArgumentType.string())
                        .executes(c -> run(c, service -> service.review(c.getSource().getPlayerOrException(), UUID.randomUUID(), StringArgumentType.getString(c, "kingdom"), true)))))));
    }

    @FunctionalInterface
    private interface Step { String run(DynastyService service) throws com.mojang.brigadier.exceptions.CommandSyntaxException; }

    private static int run(CommandContext<CommandSourceStack> c, Step step) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            String result = step.run(DynastyService.require(c.getSource().getServer()));
            c.getSource().sendSuccess(() -> Keyed.component(result), true);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            c.getSource().sendFailure(Keyed.component(refused));
            return 0;
        }
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        var server = c.getSource().getServer();
        String kingdom = StringArgumentType.getString(c, "kingdom");
        try {
            var service = DynastyService.require(server);
            c.getSource().sendSuccess(() -> Component.translatableWithFallback("dynasty.mcakingdoms.command.law", "Law of succession: %s", DynastyService.lawTitle(service.law(kingdom))), false);
            var current = service.currentCase(kingdom);
            if (current.isEmpty()) c.getSource().sendSuccess(() -> Component.translatableWithFallback("dynasty.mcakingdoms.command.no_case", "No succession is under way."), false);
            current.ifPresent(k -> {
                c.getSource().sendSuccess(() -> Component.translatableWithFallback("dynasty.mcakingdoms.command.case", "Succession of %s: %s (%s)", k.rulerName(),
                        dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(k.status()), Keyed.component(k.reasonKey())), false);
                for (var candidate : k.candidates())
                    c.getSource().sendSuccess(() -> Component.translatableWithFallback("dynasty.mcakingdoms.command.candidate", "  %s · %s · %s · %s",
                            candidate.name().isBlank() ? candidate.id().toString() : candidate.name(), dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(candidate.relation()),
                            dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(candidate.stage()), Keyed.component(candidate.reasonKey())), false);
            });
            return 1;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            c.getSource().sendFailure(Keyed.component(refused));
            return 0;
        }
    }
}
