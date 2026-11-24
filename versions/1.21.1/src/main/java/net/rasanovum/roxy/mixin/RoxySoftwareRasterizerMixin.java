package net.rasanovum.roxy.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "me.cortex.voxy.client.core.model.bakery.SoftwareRasterizer", remap = false)
public abstract class RoxySoftwareRasterizerMixin {
    private static final int ROXY_FIXED_POINT_BITS = 18;
    private static final long ROXY_FIXED_POINT_SCALE = (1L << ROXY_FIXED_POINT_BITS) - 1L;

    @Shadow @Final private Vector3f scratchR1;
    @Shadow @Final private Vector3f scratchR2;
    @Shadow @Final private Vector3f scratchR3;
    @Shadow @Final private int targetSize;
    @Shadow private boolean cullBackFace;

    @Invoker("rasterPixel")
    protected abstract void roxy$invokeRasterPixel(int index, float b1, float b2, float b3);

    @Inject(method = "rasterTriangle(Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void roxy$rasterTriangleFixedPoint(boolean includeEdges, CallbackInfo callback) {
        long x1 = roxy$toFixed(this.scratchR1.x);
        long y1 = roxy$toFixed(this.scratchR1.y);
        long x2 = roxy$toFixed(this.scratchR2.x);
        long y2 = roxy$toFixed(this.scratchR2.y);
        long x3 = roxy$toFixed(this.scratchR3.x);
        long y3 = roxy$toFixed(this.scratchR3.y);
        long area = roxy$edge(x1, y1, x2, y2, x3, y3);

        if ((area < 0L) == this.cullBackFace || Math.abs(roxy$fromFixed(area)) < 0.001f) {
            callback.cancel();
            return;
        }

        int minX = roxy$fromFixedToInt(Math.max(Math.min(Math.min(x1, x2), x3), 0L));
        int maxX = roxy$fromFixedToInt(Math.min(Math.max(Math.max(x1, x2), x3), roxy$toFixed(this.targetSize - 1)));
        int minY = roxy$fromFixedToInt(Math.max(Math.min(Math.min(y1, y2), y3), 0L));
        int maxY = roxy$fromFixedToInt(Math.min(Math.max(Math.max(y1, y2), y3), roxy$toFixed(this.targetSize - 1)));
        long half = roxy$toFixed(0.5f);
        long one = roxy$toFixed(1);

        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                long cx = roxy$toFixed(px) + half;
                long cy = roxy$toFixed(py) + half;
                long w1 = roxy$fixedDiv(roxy$edge(x2, y2, x3, y3, cx, cy), area);
                long w2 = roxy$fixedDiv(roxy$edge(x3, y3, x1, y1, cx, cy), area);
                long w3 = one - w1 - w2;
                if ((w1 > 0L && w2 > 0L && w3 > 0L)
                        || (includeEdges && w1 >= 0L && w2 >= 0L && w3 >= 0L)) {
                    this.roxy$invokeRasterPixel(
                            px + py * this.targetSize,
                            roxy$fromFixed(w1),
                            roxy$fromFixed(w2),
                            roxy$fromFixed(w3)
                    );
                }
            }
        }

        callback.cancel();
    }

    private static long roxy$edge(long ax, long ay, long bx, long by, long cx, long cy) {
        return roxy$fixedMul(cx - ax, by - ay) - roxy$fixedMul(cy - ay, bx - ax);
    }

    private static long roxy$toFixed(float value) {
        return (long) ((double) value * ROXY_FIXED_POINT_SCALE);
    }

    private static long roxy$toFixed(int value) {
        return value * ROXY_FIXED_POINT_SCALE;
    }

    private static float roxy$fromFixed(long value) {
        return (float) (value / (double) ROXY_FIXED_POINT_SCALE);
    }

    private static int roxy$fromFixedToInt(long value) {
        return (int) (value / ROXY_FIXED_POINT_SCALE);
    }

    private static long roxy$fixedMul(long first, long second) {
        return (first * second) / ROXY_FIXED_POINT_SCALE;
    }

    private static long roxy$fixedDiv(long numerator, long denominator) {
        return (numerator * ROXY_FIXED_POINT_SCALE) / denominator;
    }
}
