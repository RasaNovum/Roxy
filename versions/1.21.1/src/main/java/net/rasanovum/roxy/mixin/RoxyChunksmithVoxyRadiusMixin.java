package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyChunksmithCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.kishku7.chunksmith.lod.client.render.VoxyRadius", remap = false)
public abstract class RoxyChunksmithVoxyRadiusMixin {
    @Inject(method = "blocks", at = @At("HEAD"), cancellable = true, remap = false)
    private static void roxy$readVoxyRadius(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(RoxyChunksmithCompat.configuredRadiusBlocks());
    }
}
