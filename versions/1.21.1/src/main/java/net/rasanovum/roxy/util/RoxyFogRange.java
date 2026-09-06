package net.rasanovum.roxy.util;

public record RoxyFogRange(float start, float end) {
    public static RoxyFogRange extend(float originalStart, float originalEnd, int originalShape,
                                     float start, float end, int shape, float vanillaDistance, float lodDistance) {
        if (!Float.isFinite(originalStart) || !Float.isFinite(originalEnd)
                || !Float.isFinite(start) || !Float.isFinite(end)
                || !Float.isFinite(vanillaDistance) || !Float.isFinite(lodDistance)
                || vanillaDistance <= 0 || lodDistance <= 0 || end <= start
                || originalStart < 0 || originalEnd < vanillaDistance - 0.01F) return null;
        if (start == originalStart && end == originalEnd && shape == originalShape) return null;
        return extendModified(start, end, vanillaDistance, lodDistance);
    }

    public static RoxyFogRange extendModified(float start, float end, float vanillaDistance, float lodDistance) {
        if (!Float.isFinite(start) || !Float.isFinite(end) || !Float.isFinite(vanillaDistance)
                || !Float.isFinite(lodDistance) || vanillaDistance <= 0 || lodDistance <= 0 || end <= start) return null;
        double scale = Math.sqrt(Math.max(1.0, (double) lodDistance / vanillaDistance));
        float extendedEnd = (float) Math.min(start + ((double) end - start) * scale, Math.max(end, lodDistance));
        if (!Float.isFinite(extendedEnd) || extendedEnd - start <= 1.0F) return null;
        return new RoxyFogRange(start, extendedEnd);
    }
}
