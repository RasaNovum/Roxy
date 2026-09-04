package net.rasanovum.roxy.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyLightMapBridge {
    private static volatile Accessors accessors;

    private RoxyLightMapBridge() {
    }

    public static int getLightmapTextureId() {
        try {
            Accessors resolved = accessors();
            Object minecraft = resolved.minecraftInstance.invoke(null);
            Object gameRenderer = resolved.gameRenderer.get(minecraft);
            Object lightTexture = resolved.lightTexture.get(gameRenderer);
            Object dynamicTexture = resolved.dynamicTexture.get(lightTexture);
            return ((Number) resolved.textureId.invoke(dynamicTexture)).intValue();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to access Minecraft 1.21.1's lightmap texture", exception);
        }
    }

    private static Accessors accessors() throws ReflectiveOperationException {
        Accessors resolved = accessors;
        if (resolved != null) return resolved;
        synchronized (RoxyLightMapBridge.class) {
            if (accessors != null) return accessors;
            ClassLoader loader = findMinecraftLoader();
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Field gameRenderer = findField(minecraft, "net.minecraft.client.renderer.GameRenderer");
            Field lightTexture = findField(gameRenderer.getType(), "net.minecraft.client.renderer.LightTexture");
            Field dynamicTexture = findField(
                    lightTexture.getType(),
                    "net.minecraft.client.renderer.texture.DynamicTexture"
            );
            accessors = new Accessors(
                    minecraft.getMethod("getInstance"),
                    gameRenderer,
                    lightTexture,
                    dynamicTexture,
                    dynamicTexture.getType().getMethod("getId")
            );
            return accessors;
        }
    }

    private static Field findField(Class<?> owner, String typeName) throws NoSuchFieldException {
        Class<?> current = owner;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getName().equals(typeName)) continue;
                field.setAccessible(true);
                return field;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException("Unable to find field of type " + typeName);
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyLightMapBridge.class.getClassLoader(),
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

    private record Accessors(
            Method minecraftInstance,
            Field gameRenderer,
            Field lightTexture,
            Field dynamicTexture,
            Method textureId
    ) {
    }
}
