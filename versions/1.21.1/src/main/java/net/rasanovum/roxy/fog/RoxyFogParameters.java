package net.rasanovum.roxy.fog;

import net.rasanovum.roxy.util.RoxyClassLoaders;

import java.lang.reflect.Method;

public final class RoxyFogParameters {
    private static volatile FogGetters fogGetters;

    private final float environmentalStart;
    private final float environmentalEnd;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    public RoxyFogParameters(
            float environmentalStart,
            float environmentalEnd,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        this.environmentalStart = environmentalStart;
        this.environmentalEnd = environmentalEnd;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public static RoxyFogParameters current() {
        try {
            FogGetters getters = getters();
            float start = ((Number) getters.getStart.invoke(null)).floatValue();
            float end = ((Number) getters.getEnd.invoke(null)).floatValue();
            float[] color = (float[]) getters.getColor.invoke(null);
            if (color == null || color.length < 3) {
                return new RoxyFogParameters(start, end, 0.0F, 0.0F, 0.0F, 1.0F);
            }
            return new RoxyFogParameters(
                    start,
                    end,
                    color[0],
                    color[1],
                    color[2],
                    color.length > 3 ? color[3] : 1.0F
            );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Minecraft's current fog state", exception);
        }
    }

    private static FogGetters getters() throws ReflectiveOperationException {
        FogGetters resolved = fogGetters;
        if (resolved != null) return resolved;
        synchronized (RoxyFogParameters.class) {
            resolved = fogGetters;
            if (resolved != null) return resolved;
            Class<?> renderSystem = Class.forName(
                    "com.mojang.blaze3d.systems.RenderSystem",
                    false,
                    RoxyClassLoaders.findGameLoader()
            );
            resolved = new FogGetters(
                    renderSystem.getMethod("getShaderFogStart"),
                    renderSystem.getMethod("getShaderFogEnd"),
                    renderSystem.getMethod("getShaderFogColor")
            );
            fogGetters = resolved;
            return resolved;
        }
    }

    private record FogGetters(Method getStart, Method getEnd, Method getColor) {
    }

    public float environmentalStart() {
        return environmentalStart;
    }

    public float environmentalEnd() {
        return environmentalEnd;
    }

    public float cullingEnd() {
        return net.rasanovum.roxy.fog.RoxyVoxyFogPatch.hasExtendedFog() ? 1.0E9F : environmentalEnd;
    }

    public float red() {
        return red;
    }

    public float green() {
        return green;
    }

    public float blue() {
        return blue;
    }

    public float alpha() {
        return alpha;
    }
}
