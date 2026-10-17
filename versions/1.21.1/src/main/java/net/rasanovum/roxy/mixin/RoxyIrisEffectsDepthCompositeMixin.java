package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
public abstract class RoxyIrisEffectsDepthCompositeMixin {
    @ModifyArg(
            method = "createProgram",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/program/ProgramBuilder;begin(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/google/common/collect/ImmutableSet;)Lnet/irisshaders/iris/gl/program/ProgramBuilder;"),
            index = 3,
            require = 0
    )
    private String roxy$patchCompositeFragment(String fragmentSource) {
        return RoxyIrisEffectsDepth.patchFragmentSource(fragmentSource);
    }
}
