package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisNativeTransFade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class RoxyIrisNativeSamplerMixin {
    @Inject(method = "addGbufferOrShadowSamplers", at = @At("HEAD"), require = 0)
    private void roxy$registerNativeFadeSamplers(@Coerce Object samplers, @Coerce Object images,
                                                Supplier<?> customTextures, boolean shadow, boolean overlay,
                                                boolean lightmap, boolean hasPbr, CallbackInfo callbackInfo) {
        RoxyIrisNativeTransFade.registerSamplers(samplers);
    }
}
