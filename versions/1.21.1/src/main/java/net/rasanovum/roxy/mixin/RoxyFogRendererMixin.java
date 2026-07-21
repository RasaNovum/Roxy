package net.rasanovum.roxy.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public final class RoxyFogRendererMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void roxy$overrideFog(
            Camera camera,
            FogRenderer.FogMode fogMode,
            float viewDistance,
            boolean thickFog,
            float tickDelta,
            CallbackInfo callbackInfo
    ) {
        RoxyFogCompat.apply(fogMode, camera.getFluidInCamera() == FogType.NONE);
    }
}
