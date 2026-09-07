package net.rasanovum.roxy.mixin.tfc;

import net.minecraft.nbt.CompoundTag;
import net.rasanovum.roxyhost.tfc.RoxyTfcImport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.importers.WorldImporter", remap = false)
public abstract class RoxyTfcImportMixin {
    @Inject(method = "importChunkNBT", at = @At("HEAD"), remap = false)
    private void roxy$importClimate(CompoundTag tag, int regionX, int regionZ, CallbackInfo callback) {
        RoxyTfcImport.capture(this, tag);
    }
}
