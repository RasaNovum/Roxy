package net.rasanovum.roxy.mixin;

import net.minecraft.resources.ResourceLocation;
import net.rasanovum.roxy.blend.RoxyBlendUniforms;
import net.rasanovum.roxy.shader.RoxyRenderDistanceShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public final class RoxySodiumShaderResourceMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, remap = false)
    private static void roxy$patchTerrainFragment(
            ResourceLocation location,
            CallbackInfoReturnable<String> callback
    ) {
        if (!location.getNamespace().equals("sodium")) return;
        String original = callback.getReturnValue();
        if (original == null) return;
        String patched;
        if (location.getPath().equals("blocks/block_layer_opaque.vsh")) {
            patched = RoxyRenderDistanceShader.patchSodiumVertex(original);
        } else if (location.getPath().equals("blocks/block_layer_opaque.fsh")) {
            patched = RoxyRenderDistanceShader.patchSodiumFragment(original);
        } else {
            return;
        }
        if (!patched.equals(original)) {
            RoxyBlendUniforms.invalidate();
            callback.setReturnValue(patched);
        }
    }
}
