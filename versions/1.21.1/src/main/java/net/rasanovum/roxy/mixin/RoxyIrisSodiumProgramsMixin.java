package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.blend.RoxyBlendUniforms;
import net.rasanovum.roxy.shader.RoxyRenderDistanceShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
public final class RoxyIrisSodiumProgramsMixin {
    @Inject(method = "transformShaders", at = @At("RETURN"), remap = false, require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void roxy$patchTerrainFragments(CallbackInfoReturnable<Map> callback) {
        Map shaders = callback.getReturnValue();
        if (shaders == null) return;
        for (Object value : shaders.entrySet()) {
            Map.Entry entry = (Map.Entry) value;
            String stage = entry.getKey().toString();
            if ((stage.equals("GEOMETRY") || stage.equals("TESS_CONTROL") || stage.equals("TESS_EVAL"))
                    && entry.getValue() instanceof String) return;
        }
        Map.Entry vertex = null;
        Map.Entry fragment = null;
        for (Object value : shaders.entrySet()) {
            Map.Entry entry = (Map.Entry) value;
            String stage = entry.getKey().toString();
            if (stage.equals("VERTEX")) vertex = entry;
            else if (stage.equals("FRAGMENT")) fragment = entry;
        }
        if (vertex == null || fragment == null
                || !(vertex.getValue() instanceof String vertexSource)
                || !(fragment.getValue() instanceof String fragmentSource)) return;
        String patchedVertex = RoxyRenderDistanceShader.patchIrisVertex(vertexSource);
        String patchedFragment = RoxyRenderDistanceShader.patchIrisFragment(fragmentSource);
        if (patchedVertex.equals(vertexSource) || patchedFragment.equals(fragmentSource)) return;
        vertex.setValue(patchedVertex);
        fragment.setValue(patchedFragment);
        RoxyBlendUniforms.invalidate();
    }
}
