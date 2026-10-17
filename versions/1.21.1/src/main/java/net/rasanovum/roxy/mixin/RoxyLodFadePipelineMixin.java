package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.blend.RoxyLodFade;
import net.rasanovum.roxy.blend.RoxyLodFadeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "me.cortex.voxy.client.core.NormalRenderPipeline", remap = false)
public abstract class RoxyLodFadePipelineMixin implements RoxyLodFadeAccess {
    @Unique private RoxyLodFade roxy$lodFade;

    @Override
    public RoxyLodFade roxy$getLodFade() {
        if (roxy$lodFade == null) roxy$lodFade = new RoxyLodFade();
        return roxy$lodFade;
    }

    @Inject(method = "setup", at = @At("HEAD"))
    private void roxy$captureRealDepth(@org.spongepowered.asm.mixin.injection.Coerce Object viewport,
                                      int sourceFramebuffer, int width, int height,
                                      CallbackInfoReturnable<Integer> callback) {
        roxy$getLodFade().captureDepth(sourceFramebuffer, width, height);
    }

    @Inject(method = "finish", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;bind()V", shift = At.Shift.AFTER))
    private void roxy$bindFade(CallbackInfo callback) {
        roxy$getLodFade().bindComposite();
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void roxy$freeFade(CallbackInfo callback) {
        if (roxy$lodFade != null) roxy$lodFade.free();
    }
}
