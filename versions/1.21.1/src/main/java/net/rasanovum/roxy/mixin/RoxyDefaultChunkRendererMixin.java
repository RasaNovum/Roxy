package net.rasanovum.roxy.mixin;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.rasanovum.roxy.blend.RoxyBlendUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public final class RoxyDefaultChunkRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void roxy$beginTerrainPass(
            ChunkRenderMatrices matrices,
            CommandList commands,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass pass,
            CameraTransform camera,
            boolean indexedTranslucency,
            CallbackInfo callback
    ) {
        RoxyBlendUniforms.beginPass(pass.isTranslucent());
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void roxy$endTerrainPass(
            ChunkRenderMatrices matrices,
            CommandList commands,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass pass,
            CameraTransform camera,
            boolean indexedTranslucency,
            CallbackInfo callback
    ) {
        RoxyBlendUniforms.endPass();
    }

    @Inject(method = "setModelMatrixUniforms", at = @At("HEAD"), remap = false)
    private static void roxy$uploadBlendBand(
            ChunkShaderInterface shader,
            RenderRegion region,
            CameraTransform camera,
            CallbackInfo callback
    ) {
        RoxyBlendUniforms.applyRegion(region, camera);
    }
}
