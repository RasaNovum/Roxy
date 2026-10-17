package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.blend.RoxyLodFade;
import net.rasanovum.roxy.blend.RoxyLodFadeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline", remap = false)
public abstract class RoxyLodFadeIrisPipelineMixin implements RoxyLodFadeAccess {
    @Unique private RoxyLodFade roxy$irisFade;

    @Override
    public RoxyLodFade roxy$getLodFade() {
        if (roxy$irisFade == null) roxy$irisFade = new RoxyLodFade(true);
        return roxy$irisFade;
    }

    @Inject(method = "setup", at = @At("HEAD"))
    private void roxy$captureRealDepth(@Coerce Object viewport, int framebuffer, int width, int height,
                                      CallbackInfoReturnable<Integer> callback) {
        roxy$getLodFade().captureDepth(framebuffer, width, height);
    }

    @Inject(method = "setupAndBindOpaque", at = @At("RETURN"))
    private void roxy$activateFade(CallbackInfo callback) {
        roxy$getLodFade().activateIris(this, false);
        RoxyLodFade.bindIrisShader();
    }

    @Inject(method = "setupAndBindTranslucent", at = @At("RETURN"))
    private void roxy$activateTranslucentFade(CallbackInfo callback) {
        roxy$getLodFade().activateIris(this, true);
        RoxyLodFade.bindIrisShader();
    }

    @Inject(method = "finish", at = @At("RETURN"))
    private void roxy$reconcileDepth(@Coerce Object viewport, int framebuffer, int width, int height,
                                     CallbackInfo callback) {
        roxy$getLodFade().reconcileIrisDepth(this, framebuffer);
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void roxy$freeFade(CallbackInfo callback) {
        if (roxy$irisFade != null) roxy$irisFade.free();
    }
}
