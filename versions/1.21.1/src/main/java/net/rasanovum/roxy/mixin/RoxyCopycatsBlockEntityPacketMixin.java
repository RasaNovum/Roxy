package net.rasanovum.roxy.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.rasanovum.roxy.compat.RoxyCopycatsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class RoxyCopycatsBlockEntityPacketMixin {
    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void roxy$refreshCopycatLods(ClientboundBlockEntityDataPacket packet, CallbackInfo callback) {
        RoxyCopycatsCompat.blockEntityDataUpdated(packet);
    }
}
