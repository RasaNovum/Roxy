package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.rasanovum.roxy.compat.RoxyBetterFogCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.NormalRenderPipeline", remap = false)
public abstract class RoxyBetterFogNormalRenderPipelineMixin {
    @WrapOperation(
            method = "finish",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33C;glUniform4f(IFFFF)V"),
            require = 0,
            remap = false
    )
    private void roxy$applyBetterFogLodUniforms(int location, float x, float y, float z, float w,
                                               Operation<Void> original) {
        float[] values = RoxyBetterFogCompat.uniform(location);
        if (values == null || values.length < 4) {
            original.call(location, x, y, z, w);
            return;
        }
        original.call(location, values[0], values[1], values[2], values[3]);
    }
}
