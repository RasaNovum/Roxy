package net.rasanovum.roxy.compat;

import java.lang.ref.WeakReference;
import net.rasanovum.roxy.util.RoxyFogRange;

public final class RoxyWeatherFog {
    private WeakReference<Object> world = new WeakReference<>(null);
    private double lastTick = Double.NaN;
    private float rollIn;

    public static float fallbackProgress(float rain, float thunder) {
        return Math.max(unit(rain), unit(thunder));
    }

    public RoxyFogRange apply(RoxyFogRange baseline, Object level, double tick, float weatherProgress, int percent) {
        return apply(baseline, level, tick, weatherProgress, percent, Float.POSITIVE_INFINITY);
    }

    public RoxyFogRange apply(RoxyFogRange baseline, Object level, double tick, float weatherProgress, int percent, float viewDistance) {
        if (baseline == null) return null;
        if (level == null || !Double.isFinite(tick)) return baseline;
        if (world.get() != level || !Double.isFinite(lastTick)) {
            world = new WeakReference<>(level);
            lastTick = tick;
            rollIn = 0;
        }
        double elapsed = Math.max(0, Math.min(100, tick - lastTick));
        lastTick = Math.max(lastTick, tick);
        float amount = unit(percent / 100.0F);
        float target = unit(weatherProgress) * amount;
        float step = (float) (elapsed / 100.0);
        rollIn += Math.max(-step, Math.min(step, target - rollIn));
        float scale = 1 - rollIn;
        float end = Math.max(Math.min(16.0F, baseline.end()), baseline.end() * scale);
        float start = Math.min(baseline.start() * scale, end - Math.min(2.0F, end));
        if (Float.isFinite(viewDistance) && viewDistance > 0 && rollIn > 0) {
            float localEnd = Math.max(16, viewDistance * 0.5F);
            float weatherEnd = 1 / ((1 - rollIn) / baseline.end() + rollIn / localEnd);
            end = Math.min(end, weatherEnd);
            start = Math.min(start, end * (1 - 0.75F * unit(rollIn / Math.max(amount, 0.0001F))));
            start = Math.min(start, end - Math.min(2.0F, end));
        }
        return new RoxyFogRange(start, end);
    }

    private static float unit(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
