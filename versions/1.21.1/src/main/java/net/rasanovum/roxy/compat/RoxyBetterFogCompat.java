package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import org.lwjgl.opengl.GL33C;

import java.lang.reflect.Method;

public final class RoxyBetterFogCompat {
    private static final String BETTER_FOG_CLIENT = "net.cloud.betterfog.client.BetterFogClient";
    private static final float[] ZERO_4 = new float[4];
    private static volatile Accessors accessors;
    private static volatile boolean lookupFailedPermanently;

    private RoxyBetterFogCompat() {}

    public static void glUniform4f(int location, float x, float y, float z, float w) {
        if (location == 5 && RoxyVoxyFogPatch.terrainFogActive()) {
            w = 0.0F;
        }
        if ((location == 4 || location == 5) && RoxyFogModCompat.betterFogUniformsEnabled()) {
            Accessors current = getAccessors();
            if (current != null) {
                try {
                    if (Boolean.FALSE.equals(current.environmentalFogEnabled.invoke(null))) {
                        GL33C.glUniform4f(location, 0.0F, 0.0F, 0.0F, 0.0F);
                        return;
                    }
                    if (Boolean.TRUE.equals(current.lodFogActive.invoke(current.engine))) {
                        if (location == 4) {
                            Object value = current.lodFogEndParams.invoke(current.engine);
                            if (value instanceof float[] parameters && parameters.length >= 3) {
                                GL33C.glUniform4f(location, parameters[0], parameters[1], parameters[2], 0.0F);
                                return;
                            }
                        } else {
                            Object value = current.lodFogColor.invoke(current.engine);
                            if (value instanceof float[] color && color.length >= 4) {
                                GL33C.glUniform4f(location, color[0], color[1], color[2], color[3]);
                                return;
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                }
            }
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
                return ZERO_4;
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
        }
        return null;
    }

    public static boolean available() {
        return getAccessors() != null;
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null || lookupFailedPermanently) return current;
        if (!RoxyFogModCompat.betterFogPresent()) return null;
        synchronized (RoxyBetterFogCompat.class) {
            current = accessors;
            if (current != null || lookupFailedPermanently) return current;
            try {
                ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor(BETTER_FOG_CLIENT);
                if (loader == null) return null;
                Class<?> client = Class.forName(BETTER_FOG_CLIENT, false, loader);
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
            } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException | LinkageError ignored) {
                lookupFailedPermanently = true;
                return null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
    }

    private record Accessors(Object engine, Method lodFogActive, Method lodFogEndParams, Method lodFogColor,
                             Method environmentalFogEnabled) {}
}
