package net.rasanovum.roxy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(targets = "com.ethan.voxyworldgenv2.network.NetworkClientHandler", remap = false)
public abstract class RoxyVoxyWorldgenIngestMixin {
    private static final int ROXY_WORLDGEN_QUEUE_LIMIT = 65_536;

    @ModifyConstant(method = "drainIngestQueue", constant = @Constant(intValue = 8192), require = 0)
    private static int roxy$raiseVoxyWorldgenDrainQueueLimit(int original) {
        return ROXY_WORLDGEN_QUEUE_LIMIT;
    }

    @ModifyConstant(method = "handleLODData", constant = @Constant(intValue = 8192), require = 0)
    private static int roxy$raiseVoxyWorldgenReceiveQueueLimit(int original) {
        return ROXY_WORLDGEN_QUEUE_LIMIT;
    }
}
