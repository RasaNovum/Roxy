package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.iris.VoxySamplers", remap = false)
public abstract class RoxyIrisEffectsDepthVoxySamplerMixin {
    @Inject(method = "addSamplers", at = @At("HEAD"), require = 0)
    private static void roxy$registerEffectsDepthSamplers(@Coerce Object pipeline, @Coerce Object samplers,
                                                          CallbackInfo callbackInfo) {
        RoxyIrisEffectsDepth.registerSamplers(pipeline, samplers);
    }
}
