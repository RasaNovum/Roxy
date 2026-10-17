package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.blend.RoxyLodFade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.Shader", remap = false)
public abstract class RoxyLodFadeShaderBindMixin {
    @Inject(method = "bind", at = @At("RETURN"))
    private void roxy$bindTerrainFade(CallbackInfo callback) {
        RoxyLodFade.bindTerrainShader();
    }
}
