package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.djefrey.colorwheel.compile.ClrwlProgram", remap = false)
public abstract class RoxyLodColorwheelProgramMixin {
    @WrapOperation(
            method = "postLink",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/program/ProgramSamplers$Builder;build()Lnet/irisshaders/iris/gl/program/ProgramSamplers;"),
            require = 0
    )
    @Coerce
    private Object roxy$registerSamplers(@Coerce Object builder, Operation<Object> original) {
        RoxyLodEntityOcclusion.registerSamplers(this, builder);
        return original.call(builder);
    }

    @Inject(method = "bind", at = @At("RETURN"), require = 0)
    private void roxy$applyOcclusionUniforms(CallbackInfo callbackInfo) {
        RoxyLodEntityOcclusion.applyUniforms(this);
    }
}
