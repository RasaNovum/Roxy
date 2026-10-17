package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumShader", remap = false)
public abstract class RoxyIrisEffectsDepthSodiumBindMixin {
    @Inject(method = "setupState", at = @At("RETURN"), require = 0)
    private void roxy$updateEffectsDepthUniforms(CallbackInfo callbackInfo) {
        RoxyIrisEffectsDepth.applyUniforms();
    }
}
