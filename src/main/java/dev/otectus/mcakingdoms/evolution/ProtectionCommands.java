package dev.otectus.mcakingdoms.evolution;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

public final class ProtectionCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("kingdoms-protection");
        root.then(Commands.literal("list").executes(c -> run(c, () -> {
            var p = c.getSource().getPlayerOrException(); new ProtectionService(p.getServer()).page(p, 0).forEach(s -> p.sendSystemMessage(dev.otectus.mcakingdoms.interaction.Keyed.component(s))); return dev.otectus.mcakingdoms.interaction.Keyed.text("evolution.mcakingdoms.protection.inspect_hint", "Use inspect <pact> for terms and obligations.");
        })));
        root.then(Commands.literal("inspect").then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("pact","pact").executes(c -> run(c, () -> {
            var p = c.getSource().getPlayerOrException(); new ProtectionService(p.getServer()).inspect(p, dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"pact","pact")).forEach(s -> p.sendSystemMessage(dev.otectus.mcakingdoms.interaction.Keyed.component(s))); return "Pact inspected.";
        }))));
        root.then(Commands.literal("propose").then(Commands.argument("protector", StringArgumentType.word()).then(Commands.argument("subordinate", StringArgumentType.word())
                .then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("beneficiary","settlement").then(Commands.argument("duty", StringArgumentType.word())
                        .then(Commands.argument("duration", LongArgumentType.longArg(24000, 24000000)).then(Commands.argument("notice", LongArgumentType.longArg(1200, 24000000))
                                .then(Commands.argument("terms", StringArgumentType.greedyString()).executes(c -> run(c, () -> new ProtectionService(c.getSource().getServer()).propose(
                                        c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "protector"), StringArgumentType.getString(c, "subordinate"),
                                        dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"beneficiary","settlement"), Arrays.stream(StringArgumentType.getString(c, "duty").split(",")).map(s -> ProtectionState.Duty.valueOf(s.toUpperCase(Locale.ROOT))).collect(java.util.stream.Collectors.toSet()),
                                        LongArgumentType.getLong(c, "duration"), LongArgumentType.getLong(c, "notice"), StringArgumentType.getString(c, "terms"))))))))))));
        for (String verb : List.of("sign", "exit")) root.then(Commands.literal(verb).then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("pact","pact")
                .then(Commands.argument("kingdom", StringArgumentType.word()).then(Commands.argument("revision", LongArgumentType.longArg(1)).executes(c -> run(c, () -> {
                    var service = new ProtectionService(c.getSource().getServer()); var p = c.getSource().getPlayerOrException();
                    return verb.equals("sign") ? service.sign(p, dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"pact","pact"), StringArgumentType.getString(c, "kingdom"), LongArgumentType.getLong(c, "revision"))
                            : service.exit(p, dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"pact","pact"), StringArgumentType.getString(c, "kingdom"), LongArgumentType.getLong(c, "revision"));
                }))))));
        root.then(Commands.literal("request").then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("pact","pact").then(Commands.argument("duty", StringArgumentType.word())
                .then(Commands.argument("revision", LongArgumentType.longArg(1)).executes(c -> run(c, () -> new ProtectionService(c.getSource().getServer()).request(c.getSource().getPlayerOrException(),
                        dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"pact","pact"), ProtectionState.Duty.valueOf(StringArgumentType.getString(c, "duty").toUpperCase(Locale.ROOT)), LongArgumentType.getLong(c, "revision"))))))));
        root.then(Commands.literal("fulfill").then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("obligation","obligation").then(Commands.argument("revision", LongArgumentType.longArg(1)).executes(c -> run(c, () ->
                new ProtectionService(c.getSource().getServer()).fulfill(c.getSource().getPlayerOrException(), dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"obligation","obligation"), LongArgumentType.getLong(c, "revision")))))));
        root.then(Commands.literal("refuse").then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("obligation","obligation").then(Commands.argument("revision", LongArgumentType.longArg(1))
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> run(c, () -> new ProtectionService(c.getSource().getServer()).refuse(c.getSource().getPlayerOrException(),
                        dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"obligation","obligation"), LongArgumentType.getLong(c, "revision"), StringArgumentType.getString(c, "reason"))))))));
        var node = event.getDispatcher().register(root); // "/kingdoms protection ..." is the same command tree under the shared root
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("protection").redirect(node)));
    }
    private interface Work { String run() throws com.mojang.brigadier.exceptions.CommandSyntaxException; }
    private static int run(CommandContext<CommandSourceStack> c, Work work) {
        try { var result = work.run(); c.getSource().sendSuccess(() -> dev.otectus.mcakingdoms.interaction.Keyed.component(result), false); return 1; }
        catch (IllegalArgumentException | IllegalStateException | com.mojang.brigadier.exceptions.CommandSyntaxException failure) { c.getSource().sendFailure(dev.otectus.mcakingdoms.interaction.Keyed.component(failure)); return 0; }
    }
    private ProtectionCommands() { }
}
