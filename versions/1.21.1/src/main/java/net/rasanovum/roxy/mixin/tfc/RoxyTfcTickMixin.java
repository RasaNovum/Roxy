package net.rasanovum.roxy.mixin.tfc;

import net.minecraft.client.Minecraft;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public final class RoxyTfcTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void roxy$seasonalUpdates(CallbackInfo ci) {
        Minecraft minecraft=(Minecraft)(Object)this;
        if(minecraft.level==null || !minecraft.isPaused())TfcVoxyBridge.tick(minecraft.level);
    }
}
