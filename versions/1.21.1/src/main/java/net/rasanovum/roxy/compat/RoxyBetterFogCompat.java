package net.rasanovum.roxy.compat;

import org.lwjgl.opengl.GL33C;

import java.lang.reflect.Method;

public final class RoxyBetterFogCompat {
    private static volatile Accessors accessors;
    private static volatile boolean lookupAttempted;

    private RoxyBetterFogCompat() {}

    public static void glUniform4f(int location, float x, float y, float z, float w) {
        float[] values = uniform(location);
        if (values != null && values.length >= 4) {
            GL33C.glUniform4f(location, values[0], values[1], values[2], values[3]);
            return;
        }
        GL33C.glUniform4f(location, x, y, z, w);
    }

    public static float[] uniform(int location) {
        if (location != 4 && location != 5) return null;
        if (!RoxyFogModCompat.betterFogUniformsEnabled()) return null;
        Accessors current = getAccessors();
        if (current == null) return null;
        try {
            if (Boolean.FALSE.equals(current.environmentalFogEnabled.invoke(null))) {
                return new float[4];
            }
            if (!Boolean.TRUE.equals(current.lodFogActive.invoke(current.engine))) {
                return null;
            }

            if (location == 4) {
                Object value = current.lodFogEndParams.invoke(current.engine);
                if (!(value instanceof float[] parameters) || parameters.length < 3) return null;
                return new float[]{parameters[0], parameters[1], parameters[2], 0.0F};
            }
            if (location == 5) {
                Object value = current.lodFogColor.invoke(current.engine);
                if (!(value instanceof float[] color) || color.length < 4) return null;
                return color;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Better Fog is optional; retain Voxy's original uniform on API failures.
        }
        return null;
    }

    public static boolean available() {
        return getAccessors() != null;
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null || lookupAttempted) return current;
        if (!RoxyFogModCompat.betterFogPresent()) return null;
        synchronized (RoxyBetterFogCompat.class) {
            current = accessors;
            if (current != null || lookupAttempted) return current;
            lookupAttempted = true;
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) loader = RoxyBetterFogCompat.class.getClassLoader();
                Class<?> client = Class.forName("net.cloud.betterfog.client.BetterFogClient", false, loader);
                Object engine = client.getField("ENGINE").get(null);
                if (engine == null) return null;
                Class<?> engineType = engine.getClass();
                Class<?> bridge = Class.forName("net.cloud.betterfog.client.compat.VoxyBridge", false, loader);
                current = new Accessors(
                        engine,
                        engineType.getMethod("lodFogActive"),
                        engineType.getMethod("lodFogEndParams"),
                        engineType.getMethod("lodFogColor"),
                        bridge.getMethod("envFogEnabled")
                );
                accessors = current;
                return current;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                return null;
            }
        }
    }

    private record Accessors(Object engine, Method lodFogActive, Method lodFogEndParams, Method lodFogColor,
                             Method environmentalFogEnabled) {}
}
