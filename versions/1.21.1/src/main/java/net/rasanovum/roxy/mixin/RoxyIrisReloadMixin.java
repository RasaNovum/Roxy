package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyVoxyRendererReloadCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class RoxyIrisReloadMixin {
    @Inject(method = "reload", at = @At("HEAD"), require = 0)
    private static void roxy$beginReload(CallbackInfo callbackInfo) {
        RoxyVoxyRendererReloadCompat.beginIrisReload();
    }

    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private static void roxy$finishReload(CallbackInfo callbackInfo) {
        RoxyVoxyRendererReloadCompat.finishIrisReload();
    }
}
