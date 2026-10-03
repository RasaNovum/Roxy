package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.shader.RoxyLodEntityShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/** Adds dynamic samplers after Iris has created an ExtendedShader's sampler holder. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class RoxyLodIrisExtendedShaderMixin {
    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"),
            require = 0
    )
    private void roxy$registerSamplers(BiConsumer<?, ?> creator, Object samplers, Object images,
                                       Operation<Void> original, @Local(argsOnly = true) String name) {
        if (RoxyLodEntityShader.isIrisRole(name)) {
            RoxyLodEntityOcclusion.registerSamplers(this, samplers);
        }
        original.call(creator, samplers, images);
    }

    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private void roxy$applyOcclusionUniforms(CallbackInfo callbackInfo) {
        RoxyLodEntityOcclusion.applyUniforms(this);
    }
}
