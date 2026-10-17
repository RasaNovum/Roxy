package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.blend.RoxyLodFade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public abstract class RoxyLodFadeDepthMixin {
    @Inject(method = "preSetup", at = @At("HEAD"))
    private void roxy$prepareFade(@Coerce Object viewport, CallbackInfo callback) {
        RoxyLodFade.prepare(this, viewport);
    }

    @Inject(method = "initDepthStencil", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;blit()V"))
    private void roxy$allowTransitionOverlap(CallbackInfo callback) {
        RoxyLodFade.bindStencil(this);
    }

    @Inject(method = "runPipeline", at = @At("RETURN"))
    private void roxy$finishFade(CallbackInfo callback) {
        RoxyLodFade.finish(this);
    }
}
