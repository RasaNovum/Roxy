package net.rasanovum.roxy.mixin;

import net.rasanovum.roxy.compat.RoxyVoxyWorldgenCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;

@Mixin(targets = "com.ethan.voxyworldgenv2.network.NetworkClientHandler", remap = false)
public abstract class RoxyVoxyWorldgenIngestMixin {
    @Shadow @Final private static ArrayDeque<?> INGEST_QUEUE;
    @Shadow @Final private static Object QUEUE_LOCK;
    private static final ThreadLocal<Boolean> roxy$drainedPayload = ThreadLocal.withInitial(() -> false);

    @ModifyConstant(method = "drainIngestQueue", constant = @Constant(intValue = 96), require = 0)
    private static int roxy$limitVoxyWorldgenBatch(int original) {
        return 1;
    }

    @Inject(method = "drainIngestQueue", at = @At("HEAD"), cancellable = true, require = 0)
    private static void roxy$gateVoxyWorldgenIngestion(CallbackInfo callback) {
        synchronized (QUEUE_LOCK) {
            roxy$drainedPayload.set(!INGEST_QUEUE.isEmpty());
        }
        if (!RoxyVoxyWorldgenCompat.canDrain()) {
            roxy$drainedPayload.set(false);
            callback.cancel();
        }
    }

    @Inject(method = "drainIngestQueue", at = @At("RETURN"), require = 0)
    private static void roxy$waitAfterVoxyWorldgenIngestion(CallbackInfo callback) {
        if (roxy$drainedPayload.get()) {
            RoxyVoxyWorldgenCompat.drained();
            roxy$drainedPayload.set(false);
        }
    }
}
