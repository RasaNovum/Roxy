package net.rasanovum.roxy.mixin;

import net.minecraft.client.Camera;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.FogShape;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.rasanovum.roxy.patch.RoxyVoxyFogPatch;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FogRenderer.class)
public final class RoxyFogRendererMixin {
    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onFogRender(Lnet/minecraft/client/renderer/FogRenderer$FogMode;Lnet/minecraft/world/level/material/FogType;Lnet/minecraft/client/Camera;FFFFLcom/mojang/blaze3d/shaders/FogShape;)V"))
    private static void roxy$overrideFog(
            FogRenderer.FogMode fogMode, FogType type, Camera camera, float tickDelta,
            float viewDistance, float start, float end, FogShape shape, Operation<Void> original,
            @Local(argsOnly = true) boolean thickFog
    ) {
        original.call(fogMode, type, camera, tickDelta, viewDistance, start, end, shape);
        boolean statusFog = camera.getEntity() instanceof LivingEntity entity
                && (entity.hasEffect(MobEffects.BLINDNESS) || entity.hasEffect(MobEffects.DARKNESS));
        var level = net.minecraft.client.Minecraft.getInstance().level;
        RoxyVoxyFogPatch.apply(fogMode, camera.getFluidInCamera() == FogType.NONE && !thickFog && !statusFog,
                viewDistance, start, end, shape.ordinal(), RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogShape().ordinal(), level,
                level == null ? 0 : level.getGameTime() + (double) tickDelta,
                level == null ? 0 : level.getRainLevel(tickDelta),
                level == null ? 0 : level.getThunderLevel(tickDelta), tickDelta);
    }
}
