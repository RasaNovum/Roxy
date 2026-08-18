package net.rasanovum.roxy.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.rasanovum.roxy.compat.RoxyIrisViewportCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class RoxyIrisLevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void roxy$captureIrisViewport(
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo callbackInfo
    ) {
        RoxyIrisViewportCompat.capture(this, camera, positionMatrix, projectionMatrix);
    }
}
