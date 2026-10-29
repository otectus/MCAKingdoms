package dev.otectus.mcakingdoms.warfare.mobilization;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=McaKingdomsApi.MOD_ID)
public final class MobilizationCommands {
    private MobilizationCommands(){ }
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("kingdoms-mobilization");var muster=Commands.literal("muster");
        muster.then(Commands.literal("nearby").then(Commands.argument("doctrine",StringArgumentType.word()).executes(c->{
            var actor=c.getSource().getPlayerOrException();var lines=MobilizationEvents.get(actor.getServer()).musterNearby(actor,doctrine(c));
            lines.forEach(v->c.getSource().sendSuccess(()->dev.otectus.mcakingdoms.interaction.Keyed.component(v),false));return lines.size();})));
        muster.then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("unit","native_recruit").then(Commands.argument("doctrine",StringArgumentType.word()).executes(c->{
            var actor=c.getSource().getPlayerOrException();String result=MobilizationEvents.get(actor.getServer()).muster(actor,dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"unit","native_recruit"),doctrine(c));
            c.getSource().sendSuccess(()->dev.otectus.mcakingdoms.interaction.Keyed.component(result),false);return 1;})));
        root.then(muster);
        root.then(Commands.literal("dismiss").then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("lease","deployment").executes(c->{
            var actor=c.getSource().getPlayerOrException();String result=MobilizationEvents.get(actor.getServer()).dismiss(actor,dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"lease","deployment"));
            c.getSource().sendSuccess(()->dev.otectus.mcakingdoms.interaction.Keyed.component(result),false);return 1;})));
        root.then(Commands.literal("recover").then(dev.otectus.mcakingdoms.interaction.NamedTargets.argument("lease","deployment").executes(c->{
            var actor=c.getSource().getPlayerOrException();String result=MobilizationEvents.get(actor.getServer()).recover(actor,dev.otectus.mcakingdoms.interaction.NamedTargets.uuid(c,"lease","deployment"));
            c.getSource().sendSuccess(()->dev.otectus.mcakingdoms.interaction.Keyed.component(result),false);return 1;})));
        root.then(Commands.literal("status").executes(c->{var actor=c.getSource().getPlayerOrException();var lines=MobilizationEvents.get(actor.getServer()).status(actor);
            lines.forEach(v->c.getSource().sendSuccess(()->dev.otectus.mcakingdoms.interaction.Keyed.component(v),false));return lines.size();}));
        var node = event.getDispatcher().register(root); // "/kingdoms mobilization ..." is the same command tree under the shared root
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("mobilization").redirect(node)));
    }
    private static MobilizationDoctrine doctrine(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context){
        try{return MobilizationDoctrine.valueOf(StringArgumentType.getString(context,"doctrine").toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Doctrine must be defense, escort or scout.");}}
}
