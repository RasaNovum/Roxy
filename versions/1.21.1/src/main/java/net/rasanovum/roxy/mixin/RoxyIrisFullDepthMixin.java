package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.rasanovum.roxy.blend.RoxyLodFade;
import net.rasanovum.roxy.blend.RoxyLodFadeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public abstract class RoxyIrisFullDepthMixin {
    @WrapOperation(method = "runPipeline", at = @At(value = "INVOKE", target =
            "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;)V"))
    private void roxy$fullOpaqueDepth(@Coerce Object renderer, @Coerce Object viewport, Operation<Void> original) {
        render(renderer, viewport, original, false);
    }

    @WrapOperation(method = "runPipeline", at = @At(value = "INVOKE", target =
            "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;renderTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;)V"))
    private void roxy$fullTranslucentDepth(@Coerce Object renderer, @Coerce Object viewport, Operation<Void> original) {
        render(renderer, viewport, original, true);
    }

    @Unique
    private void render(Object renderer, Object viewport, Operation<Void> original, boolean translucent) {
        if ((Object) this instanceof RoxyLodFadeAccess access) {
            RoxyLodFade fade = access.roxy$getLodFade();
            if (fade.beginFullDepth(this, translucent)) {
                boolean completed = false;
                try {
                    original.call(renderer, viewport);
                    completed = true;
                } finally {
                    fade.endFullDepth(completed);
                }
            }
        }
        original.call(renderer, viewport);
    }
}
