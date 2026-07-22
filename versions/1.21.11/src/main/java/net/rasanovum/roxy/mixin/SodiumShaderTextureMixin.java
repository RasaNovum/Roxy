package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.loader.RoxyGpuCompat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface", remap = false)
public abstract class SodiumShaderTextureMixin {
    @Redirect(
            method = "bindTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/textures/GpuTextureView;texture()Lcom/mojang/blaze3d/textures/GpuTexture;"
            ),
            remap = false
    )
    private GpuTexture roxy$unwrapTexture(GpuTextureView view) {
        return RoxyGpuCompat.unwrapGlTexture(view.texture());
    }
}
