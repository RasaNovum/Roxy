package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class RoxyIrisEffectsDepthShaderCreatorMixin {
    @WrapOperation(
            method = "create",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 0
    )
    private static Object roxy$patchTransformedFragment(Map<?, ?> sources, Object stage,
                                                        Operation<Object> original) {
        Object source = original.call(sources, stage);
        if ("FRAGMENT".equals(String.valueOf(stage)) && source instanceof String fragment) {
            return RoxyIrisEffectsDepth.patchFragmentSource(fragment);
        }
        return source;
    }
}
