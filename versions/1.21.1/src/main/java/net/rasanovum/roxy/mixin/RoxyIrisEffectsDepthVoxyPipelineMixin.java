package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline", remap = false)
public abstract class RoxyIrisEffectsDepthVoxyPipelineMixin {
    @Inject(method = "patchOpaqueShader", at = @At("RETURN"), cancellable = true, require = 0)
    private void roxy$patchCompletedOpaqueShader(@Coerce Object renderer, String source,
                                                  CallbackInfoReturnable<String> callback) {
        String result = callback.getReturnValue();
        callback.setReturnValue(RoxyIrisEffectsDepth.patchFragmentSource(result));
    }

    @Inject(method = "patchTranslucentShader", at = @At("RETURN"), cancellable = true, require = 0)
    private void roxy$patchCompletedTranslucentShader(@Coerce Object renderer, String source,
                                                       CallbackInfoReturnable<String> callback) {
        String result = callback.getReturnValue();
        callback.setReturnValue(RoxyIrisEffectsDepth.patchFragmentSource(result));
    }
}
