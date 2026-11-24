package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.compat.RoxyVoxyRendererReloadCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class RoxyIrisReloadMixin {
    @WrapMethod(method = "reload", require = 0)
    private static void roxy$wrapReload(Operation<Void> original) {
        RoxyLodEntityOcclusion.reset();
        RoxyVoxyRendererReloadCompat.beginIrisReload();
        try {
            original.call();
        } finally {
            RoxyVoxyRendererReloadCompat.finishIrisReload();
        }
    }
}
