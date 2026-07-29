package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyChunksmithCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.platform.NeoForgeWorld", remap = false)
public abstract class RoxyChunkyNeoForgeWorldMixin {
    @Inject(method = "getChunkAtAsync", at = @At("RETURN"), remap = false)
    private void roxy$ingestGeneratedChunk(
            int chunkX,
            int chunkZ,
            CallbackInfoReturnable<CompletableFuture<Void>> callback
    ) {
        Object world = this;
        callback.getReturnValue().thenRun(() -> RoxyChunksmithCompat.ingestChunkyChunk(world, chunkX, chunkZ));
    }
}
