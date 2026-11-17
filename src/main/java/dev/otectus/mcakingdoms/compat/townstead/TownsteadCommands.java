package dev.otectus.mcakingdoms.compat.townstead;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.command.SettlementSelectorArgument;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * {@code /kingdoms compat townstead status | village <s> | buildings <s> | authority <s> [preview adopt|release, adopt,
 * release, clear, acknowledge] | queues | reconcile <s>} for operators (spec 12.2). {@code reconcile} re-reads only:
 * nothing is awarded, no government changes, nothing is reset or replayed. Townstead itself is never written.
 */
public final class TownsteadCommands {
    private TownsteadCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("compat").requires(source -> source.hasPermission(2))
                .then(Commands.literal("townstead")
                        .then(Commands.literal("status").executes(c -> lines(c, TownsteadPresentation.status(c.getSource().getServer()))))
                        .then(Commands.literal("queues").executes(c -> lines(c, TownsteadPresentation.queues(c.getSource().getServer()))))
                        .then(Commands.literal("village").then(Commands.argument("settlement", SettlementSelectorArgument.settlement())
                                .executes(c -> settlement(c, TownsteadPresentation::village))))
                        .then(Commands.literal("buildings").then(Commands.argument("settlement", SettlementSelectorArgument.settlement())
                                .executes(c -> settlement(c, TownsteadPresentation::buildings))))
                        .then(Commands.literal("reconcile").then(Commands.argument("settlement", SettlementSelectorArgument.settlement())
                                .executes(c -> settlement(c, TownsteadPresentation::reconcile))))
                        .then(Commands.literal("authority").then(Commands.argument("settlement", SettlementSelectorArgument.settlement())
                                .executes(c -> settlement(c, TownsteadPresentation::authority))
                                .then(Commands.literal("preview")
                                        .then(Commands.literal("adopt").executes(c -> settlement(c, (server, id) -> authority(server).preview(id, TownsteadSavedData.Owner.KINGDOMS))))
                                        .then(Commands.literal("release").executes(c -> settlement(c, (server, id) -> authority(server).preview(id, TownsteadSavedData.Owner.TOWNSTEAD)))))
                                .then(Commands.literal("adopt").executes(c -> decide(c, TownsteadSavedData.Owner.KINGDOMS, ""))
                                        .then(Commands.argument("note", StringArgumentType.greedyString()).executes(c -> decide(c, TownsteadSavedData.Owner.KINGDOMS, StringArgumentType.getString(c, "note")))))
                                .then(Commands.literal("release").executes(c -> decide(c, TownsteadSavedData.Owner.TOWNSTEAD, ""))
                                        .then(Commands.argument("note", StringArgumentType.greedyString()).executes(c -> decide(c, TownsteadSavedData.Owner.TOWNSTEAD, StringArgumentType.getString(c, "note")))))
                                .then(Commands.literal("clear").executes(c -> settlement(c, (server, id) -> List.of(authority(server).clearDecision(id)))))
                                .then(Commands.literal("acknowledge").executes(c -> settlement(c, (server, id) -> List.of(authority(server).acknowledge(id))))))))));
    }

    private static TownsteadAuthority authority(net.minecraft.server.MinecraftServer server) {
        return TownsteadAuthority.get(server).orElseThrow(() -> TownsteadText.refuse("inactive", "The Townstead integration is not attached on this server."));
    }

    private static int lines(CommandContext<CommandSourceStack> c, List<Component> lines) {
        lines.forEach(line -> c.getSource().sendSuccess(() -> line, false));
        return lines.size();
    }

    private static int settlement(CommandContext<CommandSourceStack> c, BiFunction<net.minecraft.server.MinecraftServer, UUID, List<Component>> body) {
        try {
            SettlementView settlement = McaKingdomsApi.get(c.getSource().getServer())
                    .findSettlement(SettlementSelectorArgument.getSettlement(c, "settlement"))
                    .orElseThrow(() -> TownsteadText.refuse("unknown_settlement", "That settlement is not available."));
            return lines(c, body.apply(c.getSource().getServer(), settlement.id()));
        } catch (IllegalArgumentException | IllegalStateException refused) {
            c.getSource().sendFailure(Keyed.component(refused));
            return 0;
        }
    }

    private static int decide(CommandContext<CommandSourceStack> c, TownsteadSavedData.Owner owner, String note) {
        UUID operator = c.getSource().getEntity() == null ? null : c.getSource().getEntity().getUUID();
        return settlement(c, (server, id) -> {
            Component result = authority(server).decide(id, owner, operator, note);
            return List.of(result);
        });
    }
}
