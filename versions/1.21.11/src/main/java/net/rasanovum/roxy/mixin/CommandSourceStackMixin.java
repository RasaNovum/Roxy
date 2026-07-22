package net.rasanovum.roxy.mixin;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin implements FabricClientCommandSource {
}
