package net.rasanovum.roxy.mixin.tfc;

import net.minecraft.world.level.chunk.LevelChunk;
import net.rasanovum.roxyhost.tfc.RoxyTfcImport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public abstract class RoxyTfcIngestMixin {
    @Inject(method = "enqueueIngest", at = @At("HEAD"), remap = false)
    private void roxy$captureIngestClimate(@Coerce Object engine, LevelChunk chunk, CallbackInfoReturnable<Boolean> callback) {
        RoxyTfcImport.captureChunk(engine, chunk);
    }
}
