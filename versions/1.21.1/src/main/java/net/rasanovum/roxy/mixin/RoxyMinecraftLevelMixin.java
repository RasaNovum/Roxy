package net.rasanovum.roxy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.rasanovum.roxy.compat.RoxyVoxyWorldgenCompat;
import net.rasanovum.roxy.patch.RoxyVoxyRenderPatch;
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
        if (level == null) RoxyVoxyRenderPatch.retireWorld();
        else RoxyVoxyRenderPatch.activateWorld();
        RoxyVoxyWorldgenCompat.clearQueuedPayloads();
    }

    @Inject(
            method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V",
            at = @At("HEAD"),
            require = 0
    )
    private void roxy$retireVoxyWorldOnDisconnect(net.minecraft.client.gui.screens.Screen screen,
                                                   boolean keepResourcePacks, CallbackInfo callbackInfo) {
        RoxyVoxyRenderPatch.retireWorld();
    }
}
