package net.fabricmc.fabric.api.client.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

@FunctionalInterface
public interface ClientCommandRegistrationCallback {
    Event<ClientCommandRegistrationCallback> EVENT = EventFactory.createArrayBacked(
            ClientCommandRegistrationCallback.class,
            callbacks -> (dispatcher, registryAccess) -> {
                for (ClientCommandRegistrationCallback callback : callbacks) {
                    callback.register(dispatcher, registryAccess);
                }
            }
    );

    void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess);

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void fire(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        CommandDispatcher fabricDispatcher = dispatcher;
        EVENT.invoker().register(fabricDispatcher, registryAccess);
    }
}
