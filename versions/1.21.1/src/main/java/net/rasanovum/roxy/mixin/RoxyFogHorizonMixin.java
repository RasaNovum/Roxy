package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.material.FogType;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public final class RoxyFogHorizonMixin {
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V", ordinal = 0))
    private void roxy$fadeHorizon(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection,
                                  ShaderInstance shader, Operation<Void> original,
                                  @Local(argsOnly = true) Camera camera, @Local(argsOnly = true) boolean thickFog) {
        if (thickFog || camera.getFluidInCamera() != FogType.NONE || !RoxyVoxyFogPatch.fadeFogHorizon()) {
            original.call(buffer, modelView, projection, shader);
            return;
        }
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        FogShape shape = RenderSystem.getShaderFogShape();
        try {
            // Match the sky disc's centre height and radius, independently of terrain visibility.
            RenderSystem.setShaderFogStart(16);
            RenderSystem.setShaderFogEnd(512);
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
            original.call(buffer, modelView, projection, shader);
        } finally {
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
            RenderSystem.setShaderFogShape(shape);
        }
    }
}
