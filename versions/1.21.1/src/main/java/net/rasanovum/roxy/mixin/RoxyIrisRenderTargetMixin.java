package net.rasanovum.roxy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(
        targets = "net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives",
        remap = false
)
public abstract class RoxyIrisRenderTargetMixin {
    @ModifyConstant(
            method = "<clinit>",
            constant = @Constant(intValue = 16)
    )
    private static int roxy$includeVoxyAndColorwheelTargets(int value) {
        return 20;
    }
}
