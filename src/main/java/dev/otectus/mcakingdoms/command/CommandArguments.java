package dev.otectus.mcakingdoms.command;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

public final class CommandArguments {
    private static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARGUMENT_TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, McaKingdomsApi.MOD_ID);

    static {
        ARGUMENT_TYPES.register("settlement", () -> ArgumentTypeInfos.registerByClass(
                SettlementSelectorArgument.class,
                SingletonArgumentInfo.contextFree(SettlementSelectorArgument::settlement)));
    }

    private CommandArguments() {
    }

    public static void register(IEventBus modBus) {
        ARGUMENT_TYPES.register(modBus);
    }
}
