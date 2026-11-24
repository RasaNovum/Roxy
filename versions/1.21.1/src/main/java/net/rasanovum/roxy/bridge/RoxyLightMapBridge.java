package net.rasanovum.roxy.bridge;

import net.rasanovum.roxy.util.RoxyClassLoaders;

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
            if (minecraft == null) throw new IllegalStateException("Minecraft instance is not available");
            Object gameRenderer = resolved.gameRenderer.get(minecraft);
            if (gameRenderer == null) throw new IllegalStateException("GameRenderer is not available");
            Object lightTexture = resolved.lightTexture.get(gameRenderer);
            if (lightTexture == null) throw new IllegalStateException("LightTexture is not available");
            Object dynamicTexture = resolved.dynamicTexture.get(lightTexture);
            if (dynamicTexture == null) throw new IllegalStateException("Lightmap DynamicTexture is not available");
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
            ClassLoader loader = RoxyClassLoaders.findGameLoader();
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Field gameRenderer = findField(minecraft, "gameRenderer", "net.minecraft.client.renderer.GameRenderer");
            Field lightTexture = findField(gameRenderer.getType(), "lightTexture", "net.minecraft.client.renderer.LightTexture");
            Field dynamicTexture = findField(
                    lightTexture.getType(),
                    "lightTexture",
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

    private static Field findField(Class<?> owner, String preferredName, String typeName) throws NoSuchFieldException {
        Class<?> current = owner;
        Field fallback = null;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getName().equals(typeName)) continue;
                if (field.getName().equals(preferredName)) {
                    field.setAccessible(true);
                    return field;
                }
                if (fallback == null) fallback = field;
            }
            current = current.getSuperclass();
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            return fallback;
        }
        throw new NoSuchFieldException("Unable to find field of type " + typeName);
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
