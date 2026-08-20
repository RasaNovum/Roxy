package net.rasanovum.roxy.compat;

import java.lang.reflect.Method;

public final class RoxyFogParameters {
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
            Class<?> renderSystem = Class.forName(
                    "com.mojang.blaze3d.systems.RenderSystem",
                    false,
                    findMinecraftLoader()
            );
            Method getStart = renderSystem.getMethod("getShaderFogStart");
            Method getEnd = renderSystem.getMethod("getShaderFogEnd");
            Method getColor = renderSystem.getMethod("getShaderFogColor");
            float start = ((Number) getStart.invoke(null)).floatValue();
            float end = ((Number) getEnd.invoke(null)).floatValue();
            float[] color = (float[]) getColor.invoke(null);
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

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyFogParameters.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("net.minecraft.client.Minecraft", false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
                // Try the next loader.
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }

    public float environmentalStart() {
        return environmentalStart;
    }

    public float environmentalEnd() {
        return environmentalEnd;
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
