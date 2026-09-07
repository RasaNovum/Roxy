package net.rasanovum.roxy.mixin.tfc;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.rasanovum.roxyhost.tfc.RoxyTfcImport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.ethan.voxyworldgenv2.integration.VoxyIntegration", remap = false)
public abstract class RoxyTfcWorldGenMixin {
    @Inject(method = "rawIngest(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/DataLayer;)V",
            at = @At("RETURN"), remap = false, require = 0)
    private static void roxy$captureGeneratedClimate(LevelChunk chunk, DataLayer light, CallbackInfo callback) {
        RoxyTfcImport.captureChunk(null, chunk);
    }
}
