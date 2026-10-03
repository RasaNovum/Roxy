package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.shader.RoxyLodEntityShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/** Optional Iris hooks. Every target is resolved only when Iris is present. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class RoxyLodIrisShaderMixin {
    @WrapOperation(
            method = "create",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 0
    )
    private static Object roxy$patchFragment(Map<?, ?> sources, Object key, Operation<Object> original,
                                               @Local(argsOnly = true) String name) {
        Object value = original.call(sources, key);
        if (!(value instanceof String) || key == null || !key.toString().toLowerCase().contains("fragment")) {
            return value;
        }
        return RoxyLodEntityShader.patchIris(name, (String) value);
    }
}
