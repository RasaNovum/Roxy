package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
public abstract class RoxyIrisEffectsDepthSodiumProgramMixin {
    @Inject(method = "createGlShaders", at = @At("HEAD"), require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void roxy$patchSodiumFragment(String passName, @Coerce Map transformedStages,
                                         CallbackInfoReturnable<Map<?, ?>> callbackInfo) {
        if (transformedStages == null) return;
        Object fragmentStage = null;
        for (Object stage : transformedStages.keySet()) {
            if ("FRAGMENT".equals(String.valueOf(stage))) {
                fragmentStage = stage;
                break;
            }
        }
        if (fragmentStage == null) return;
        Object fragment = transformedStages.get(fragmentStage);
        if (fragment instanceof String source) {
            transformedStages.put(fragmentStage, RoxyIrisEffectsDepth.patchFragmentSource(source));
        }
    }
}
