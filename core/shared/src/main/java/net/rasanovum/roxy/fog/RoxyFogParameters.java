package net.rasanovum.roxy.fog;

import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class RoxyFogParameters {
    private static final RoxyFogParameters FALLBACK = new RoxyFogParameters(0.0F, 1.0E9F, 0.0F, 0.0F, 0.0F, 1.0F);
    private static volatile FogGetters fogGetters;
    private static volatile boolean lookupFailed;

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
            if (getters == null) return FALLBACK;
            float start = (float) getters.getStart.invokeExact();
            float end = (float) getters.getEnd.invokeExact();
            float[] color = (float[]) getters.getColor.invokeExact();
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
        } catch (Throwable throwable) {
            if (throwable instanceof Error error && !(error instanceof LinkageError)) throw error;
            return FALLBACK;
        }
    }

    private static FogGetters getters() {
        FogGetters resolved = fogGetters;
        if (resolved != null || lookupFailed) return resolved;
        synchronized (RoxyFogParameters.class) {
            resolved = fogGetters;
            if (resolved != null || lookupFailed) return resolved;
            try {
                Class<?> renderSystem = RoxyClassLoaders.loadMinecraftClass(
                        "com.mojang.blaze3d.systems.RenderSystem",
                        RoxyClassLoaders.findGameLoader()
                );
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle getStart = lookup.unreflect(RoxyReflection.findMethod(renderSystem, "getShaderFogStart"))
                        .asType(MethodType.methodType(float.class));
                MethodHandle getEnd = lookup.unreflect(RoxyReflection.findMethod(renderSystem, "getShaderFogEnd"))
                        .asType(MethodType.methodType(float.class));
                MethodHandle getColor = lookup.unreflect(RoxyReflection.findMethod(renderSystem, "getShaderFogColor"))
                        .asType(MethodType.methodType(float[].class));
                resolved = new FogGetters(getStart, getEnd, getColor);
                fogGetters = resolved;
                return resolved;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                lookupFailed = true;
                return null;
            }
        }
    }

    private record FogGetters(MethodHandle getStart, MethodHandle getEnd, MethodHandle getColor) {
    }

    public float environmentalStart() {
        return environmentalStart;
    }

    public float environmentalEnd() {
        return environmentalEnd;
    }

    public float cullingEnd() {
        return RoxyVoxyFogPatch.hasExtendedFog() ? 1.0E9F : environmentalEnd;
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
