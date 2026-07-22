package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.loader.RoxyGpuCompat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class SodiumGpuTextureMixin {
    @Redirect(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getColorTexture()Lcom/mojang/blaze3d/textures/GpuTexture;"
            ),
            remap = false
    )
    private GpuTexture roxy$unwrapColorTexture(RenderTarget target) {
        return RoxyGpuCompat.unwrapGlTexture(target.getColorTexture());
    }

    @Redirect(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getDepthTexture()Lcom/mojang/blaze3d/textures/GpuTexture;"
            ),
            remap = false
    )
    private GpuTexture roxy$unwrapDepthTexture(RenderTarget target) {
        return RoxyGpuCompat.unwrapGlTexture(target.getDepthTexture());
    }

    @Redirect(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;"
            ),
            remap = false
    )
    private GpuDevice roxy$unwrapGpuDevice() {
        return RoxyGpuCompat.unwrapGlDevice(RenderSystem.getDevice());
    }
}
