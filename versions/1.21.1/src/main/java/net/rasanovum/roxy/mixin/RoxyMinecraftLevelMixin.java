package net.rasanovum.roxy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.rasanovum.roxy.compat.RoxyVoxyWorldgenCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class RoxyMinecraftLevelMixin {
    @Inject(
            method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/ReceivingLevelScreen$Reason;)V",
            at = @At("HEAD"),
            require = 0
    )
    private void roxy$clearVoxyWorldgenQueue(ClientLevel level, ReceivingLevelScreen.Reason reason, CallbackInfo callbackInfo) {
        RoxyVoxyWorldgenCompat.clearQueuedPayloads();
    }
}
