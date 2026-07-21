package net.fabricmc.fabric.api.client.command.v2;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public interface FabricClientCommandSource extends SharedSuggestionProvider {
    default void sendError(Component message) {
        if (this instanceof CommandSourceStack source) {
            source.sendFailure(message);
        }
    }
}
