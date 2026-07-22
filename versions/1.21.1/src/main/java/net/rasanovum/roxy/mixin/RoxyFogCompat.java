package net.rasanovum.roxy.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyFogCompat {
    private static final String VOXY_RENDER_BRIDGE = "me.cortex.voxy.client.core.IGetVoxyRenderSystem";
    private static final String VOXY_CONFIG = "me.cortex.voxy.client.config.VoxyConfig";
    private static final String MINECRAFT_RENDER_SYSTEM = "com.mojang.blaze3d.systems.RenderSystem";
    private static final float NO_FOG = 1.0E9F;

    private static volatile Accessors accessors;
    private static volatile boolean lookupAttempted;
    private static volatile FogSetter fogSetter;
    private static volatile boolean fogSetterLookupAttempted;

    private RoxyFogCompat() {
    }

    public static void apply(Object fogMode, boolean noFluid) {
        // Avoid exposing Minecraft's nested FogMode type through this helper's descriptor.
        if (!"FOG_TERRAIN".equals(String.valueOf(fogMode)) || !noFluid) {
            return;
        }

        Accessors current = getAccessors();
        if (current == null) return;

        try {
            if (current.getNullable.invoke(null) == null) return;
            Object config = current.config.get(null);
            if (config == null
                    || !(Boolean) current.renderingEnabled.invoke(config)
                    || current.environmentalFog.getBoolean(config)) {
                return;
            }
            setNoFog();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep vanilla fog when optional Voxy lookup fails.
        }
    }

    private static void setNoFog() throws ReflectiveOperationException {
        FogSetter current = getFogSetter();
        if (current == null) return;
        current.start.invoke(null, NO_FOG);
        current.end.invoke(null, NO_FOG);
    }

    private static FogSetter getFogSetter() {
        FogSetter current = fogSetter;
        if (current != null || fogSetterLookupAttempted) return current;
        synchronized (RoxyFogCompat.class) {
            current = fogSetter;
            if (current != null || fogSetterLookupAttempted) return current;
            fogSetterLookupAttempted = true;
            try {
                Class<?> renderSystem = Class.forName(
                        MINECRAFT_RENDER_SYSTEM,
                        false,
                        findMinecraftLoader()
                );
                current = new FogSetter(
                        renderSystem.getMethod("setShaderFogStart", float.class),
                        renderSystem.getMethod("setShaderFogEnd", float.class)
                );
                fogSetter = current;
                return current;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                return null;
            }
        }
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null || lookupAttempted) return current;
        synchronized (RoxyFogCompat.class) {
            current = accessors;
            if (current != null || lookupAttempted) return current;
            lookupAttempted = true;
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) loader = RoxyFogCompat.class.getClassLoader();
                Class<?> bridge = Class.forName(VOXY_RENDER_BRIDGE, false, loader);
                Class<?> configClass = Class.forName(VOXY_CONFIG, false, loader);
                current = new Accessors(
                        bridge.getMethod("getNullable"),
                        configClass.getField("CONFIG"),
                        configClass.getMethod("isRenderingEnabled"),
                        configClass.getField("useEnvironmentalFog")
                );
                accessors = current;
                return current;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }
    }

    private record Accessors(Method getNullable, Field config, Method renderingEnabled, Field environmentalFog) {
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyFogCompat.class.getClassLoader(),
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

    private record FogSetter(Method start, Method end) {
    }
}
