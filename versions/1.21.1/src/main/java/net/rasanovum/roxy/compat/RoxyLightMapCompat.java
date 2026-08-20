package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyLightMapCompat {
    private RoxyLightMapCompat() {
    }

    public static int getLightmapTextureId() {
        try {
            ClassLoader minecraftLoader = findMinecraftLoader();
            Class<?> minecraftClass = Class.forName(
                    "net.minecraft.client.Minecraft",
                    false,
                    minecraftLoader
            );
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            Object gameRenderer = findFieldValue(
                    minecraft,
                    "net.minecraft.client.renderer.GameRenderer"
            );
            Object lightTexture = findFieldValue(
                    gameRenderer,
                    "net.minecraft.client.renderer.LightTexture"
            );
            Object dynamicTexture = findFieldValue(
                    lightTexture,
                    "net.minecraft.client.renderer.texture.DynamicTexture"
            );
            Method getId = dynamicTexture.getClass().getMethod("getId");
            return ((Number) getId.invoke(dynamicTexture)).intValue();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to access Minecraft 1.21.1's lightmap texture", exception);
        }
    }

    private static Object findFieldValue(Object instance, String typeName)
            throws IllegalAccessException {
        if (instance == null) {
            throw new IllegalStateException("Minecraft lightmap owner is null");
        }

        Class<?> current = instance.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getName().equals(typeName)) {
                    continue;
                }
                field.setAccessible(true);
                return field.get(instance);
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("Unable to find field of type " + typeName);
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyLightMapCompat.class.getClassLoader(),
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
}
