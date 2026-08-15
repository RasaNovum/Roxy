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
        int x1 = roxy$toFixed(this.scratchR1.x);
        int y1 = roxy$toFixed(this.scratchR1.y);
        int x2 = roxy$toFixed(this.scratchR2.x);
        int y2 = roxy$toFixed(this.scratchR2.y);
        int x3 = roxy$toFixed(this.scratchR3.x);
        int y3 = roxy$toFixed(this.scratchR3.y);
        int area = roxy$edge(x1, y1, x2, y2, x3, y3);

        if ((area < 0) == this.cullBackFace || Math.abs(roxy$fromFixed(area)) < 0.001f) {
            callback.cancel();
            return;
        }

        int minX = roxy$fromFixedToInt(Math.max(Math.min(Math.min(x1, x2), x3), 0));
        int maxX = roxy$fromFixedToInt(Math.min(Math.max(Math.max(x1, x2), x3), roxy$toFixed(this.targetSize - 1)));
        int minY = roxy$fromFixedToInt(Math.max(Math.min(Math.min(y1, y2), y3), 0));
        int maxY = roxy$fromFixedToInt(Math.min(Math.max(Math.max(y1, y2), y3), roxy$toFixed(this.targetSize - 1)));
        int half = roxy$toFixed(0.5f);
        int one = roxy$toFixed(1);

        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                int cx = roxy$toFixed(px) + half;
                int cy = roxy$toFixed(py) + half;
                int w1 = roxy$fixedDiv(roxy$edge(x2, y2, x3, y3, cx, cy), area);
                int w2 = roxy$fixedDiv(roxy$edge(x3, y3, x1, y1, cx, cy), area);
                int w3 = one - w1 - w2;
                if ((w1 > 0 && w2 > 0 && w3 > 0)
                        || (includeEdges && w1 >= 0 && w2 >= 0 && w3 >= 0)) {
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

    private static int roxy$edge(int ax, int ay, int bx, int by, int cx, int cy) {
        return roxy$fixedMul(cx - ax, by - ay) - roxy$fixedMul(cy - ay, bx - ax);
    }

    private static int roxy$toFixed(float value) {
        return (int) (value * ROXY_FIXED_POINT_SCALE);
    }

    private static int roxy$toFixed(int value) {
        return (int) (value * ROXY_FIXED_POINT_SCALE);
    }

    private static float roxy$fromFixed(int value) {
        return (float) (value / (double) ROXY_FIXED_POINT_SCALE);
    }

    private static int roxy$fromFixedToInt(int value) {
        return (int) (value / ROXY_FIXED_POINT_SCALE);
    }

    private static int roxy$fixedMul(int first, int second) {
        return (int) (((long) first * second) / ROXY_FIXED_POINT_SCALE);
    }

    private static int roxy$fixedDiv(int numerator, int denominator) {
        return (int) ((numerator * ROXY_FIXED_POINT_SCALE) / denominator);
    }
}
