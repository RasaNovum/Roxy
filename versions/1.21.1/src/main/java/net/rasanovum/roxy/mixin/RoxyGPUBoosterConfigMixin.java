package net.rasanovum.roxy.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.mr_toad.gpu_booster.client.config.GBConfig", remap = false)
public final class RoxyGPUBoosterConfigMixin {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    @Unique
    private static boolean logged;

    @Inject(method = "canCreateRenderbuffer", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void roxy$keepSampleableDepthTexture(CallbackInfoReturnable<Boolean> callback) {
        if (!logged) {
            logged = true;
            LOGGER.info("Disabled GPUBooster renderbuffer depth because Voxy requires a sampleable depth texture");
        }
        callback.setReturnValue(false);
    }
}
