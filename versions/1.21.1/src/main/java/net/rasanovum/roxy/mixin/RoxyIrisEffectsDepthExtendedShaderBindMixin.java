package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class RoxyIrisEffectsDepthExtendedShaderBindMixin {
    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private void roxy$updateEffectsDepthUniforms(CallbackInfo callbackInfo) {
        RoxyIrisEffectsDepth.applyUniforms();
    }
}
