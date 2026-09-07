package net.rasanovum.roxy.fog;

public final class RoxyFogDawn {
    private RoxyFogDawn() {}

    public static float dayBlend(float original, long time) {
        long day = Math.floorMod(time, 24000L);
        return day >= 22000 ? ramp(day) : original;
    }

    public static float sunriseInfluence(long time, float partialTick) {
        long day = Math.floorMod(time, 24000L);
        return day >= 18000 ? ramp(day + partialTick) : 1;
    }

    private static float ramp(double time) {
        float progress = (float) Math.max(0, Math.min(1, (time - 23000) / 1000));
        return progress * progress * (3 - 2 * progress);
    }
}
