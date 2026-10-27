package dev.otectus.mcakingdoms.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration.Check;
import dev.otectus.mcakingdoms.citizen.ResidenceRegistration.Intent;
import dev.otectus.mcakingdoms.interaction.ResidenceTasks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * {@code /kingdoms residence status|register|move [now]}: the same verified residence registration as the Kingdom Tasks
 * entries (A01), with the same service rules. {@code move now} skips the wait between moves and is for operators only.
 */
public final class ResidenceCommands {
    private ResidenceCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("residence")
                .then(Commands.literal("status").executes(c -> status(c)))
                .then(Commands.literal("register").executes(c -> apply(c, Intent.REGISTER, false)))
                .then(Commands.literal("move").executes(c -> apply(c, Intent.MOVE, false))
                        .then(Commands.literal("now").requires(source -> source.hasPermission(2)).executes(c -> apply(c, Intent.MOVE, true))))));
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        var intent = dev.otectus.mcakingdoms.api.McaKingdomsApi.get(player.getServer()).getResidence(player).isPresent() ? Intent.MOVE : Intent.REGISTER;
        var review = ResidenceRegistration.review(player, intent, false);
        context.getSource().sendSuccess(() -> ResidenceTasks.explain(review), false);
        return review.decision() == Check.READY ? 1 : 0;
    }

    private static int apply(CommandContext<CommandSourceStack> context, Intent intent, boolean override) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        var review = ResidenceRegistration.apply(player, intent, override);
        if (review.decision() == Check.READY) {
            var place = review.evidence().settlement().map(s -> (Component) Component.literal(s.displayName())).orElse(Component.empty());
            context.getSource().sendSuccess(() -> intent == Intent.REGISTER
                    ? Component.translatableWithFallback("interaction.mcakingdoms.residence.done.registered", "Registered: %s is your civic residence.", place)
                    : Component.translatableWithFallback("interaction.mcakingdoms.residence.done.moved", "Moved: %s is your civic residence now.", place), false);
            return 1;
        }
        if (review.decision() == Check.SAME) { context.getSource().sendSuccess(() -> ResidenceTasks.explain(review), false); return 1; }
        context.getSource().sendFailure(ResidenceTasks.explain(review));
        return 0;
    }
}
