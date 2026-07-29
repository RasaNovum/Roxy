package net.rasanovum.roxy.mixin;

import net.minecraft.world.level.Level;
import net.rasanovum.roxy.compat.RoxyChunksmithCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.kishku7.chunksmith.lod.client.render.VoxyTarget", remap = false)
public abstract class RoxyChunksmithVoxyTargetMixin {
    @Inject(method = "supported", at = @At("HEAD"), cancellable = true, remap = false)
    private static void roxy$supportVoxy(CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(true);
    }

    @Inject(method = "available", at = @At("HEAD"), cancellable = true, remap = false)
    private static void roxy$findVoxy(CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(RoxyChunksmithVoxyTargetMixin.class.getClassLoader() != null
                && RoxyChunksmithCompat.available(RoxyChunksmithVoxyTargetMixin.class.getClassLoader()));
    }

    @Inject(method = "inject", at = @At("HEAD"), cancellable = true, remap = false)
    private static void roxy$injectVoxy(
            Level level,
            @Coerce Object record,
            CallbackInfoReturnable<Integer> callback
    ) {
        callback.setReturnValue(RoxyChunksmithCompat.inject(level, record));
    }
}
