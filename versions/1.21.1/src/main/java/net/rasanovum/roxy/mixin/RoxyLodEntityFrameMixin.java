package net.rasanovum.roxy.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the capture window before the main world pass; shadow rendering cannot overwrite it. */
@Mixin(LevelRenderer.class)
public abstract class RoxyLodEntityFrameMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void roxy$beginLodFrame(DeltaTracker tickCounter, boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightTexture lightTexture,
                                    Matrix4f positionMatrix, Matrix4f projectionMatrix,
                                    CallbackInfo callbackInfo) {
        RoxyLodEntityOcclusion.beginFrame();
    }
}
