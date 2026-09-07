package net.rasanovum.roxy.mixin.tfc;

import net.rasanovum.roxyhost.tfc.RoxyTfcAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.dries007.tfc.world.chunkdata.ChunkData", remap = false)
public abstract class RoxyTfcChunkDataMixin {
    @Inject(method = "<clinit>", at = @At("RETURN"), remap = false)
    private static void roxy$installSnapshotHook(CallbackInfo callback) {
        RoxyTfcAdapter.snapshotHookInstalled();
    }

    @Inject(method = "get(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Lnet/dries007/tfc/world/chunkdata/ChunkData;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void roxy$climateSnapshot(CallbackInfoReturnable<Object> callback) {
        Object snapshot = RoxyTfcAdapter.scopedClimate();
        if (snapshot != null) callback.setReturnValue(snapshot);
    }

    @Inject(method = "onUpdatePacket", at = @At("RETURN"), require = 0, remap = false)
    private void roxy$captureClimate(CallbackInfo callback) {
        RoxyTfcAdapter.capture(this);
    }
}
