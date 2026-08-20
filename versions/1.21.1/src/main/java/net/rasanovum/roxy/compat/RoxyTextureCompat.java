package net.rasanovum.roxy.compat;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyTextureCompat {
    private static final String BLOCK_ATLAS = "textures/atlas/blocks.png";

    private RoxyTextureCompat() {
    }

    public static void setupBlockAtlas(Object rasterizer) {
        try {
            ClassLoader minecraftLoader = findMinecraftLoader();
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, minecraftLoader);
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            Object textureManager = minecraftClass.getMethod("getTextureManager").invoke(minecraft);

            Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation", false, minecraftLoader);
            Object location = resourceLocationClass
                    .getMethod("withDefaultNamespace", String.class)
                    .invoke(null, BLOCK_ATLAS);
            Object texture = textureManager.getClass()
                    .getMethod("getTexture", resourceLocationClass)
                    .invoke(textureManager, location);

            int textureId = (Integer) texture.getClass().getMethod("getId").invoke(texture);
            int width = readDimension(texture, "width", "getWidth");
            int height = readDimension(texture, "height", "getHeight");
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("Block atlas has invalid dimensions: " + width + "x" + height);
            }

            int[] pixels = new int[width * height];
            readTexture(textureId, width, pixels);

            Method setSamplerTexture = rasterizer.getClass().getMethod(
                    "setSamplerTexture", int[].class, int.class, int.class
            );
            setSamplerTexture.invoke(rasterizer, pixels, width, height);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Minecraft 1.21.1's block atlas", exception);
        }
    }

    private static void readTexture(int textureId, int width, int[] pixels) {
        int framebuffer = GL11.glGetInteger(36006);
        int pixelPackBuffer = GL11.glGetInteger(35053);
        int texture = GL11.glGetInteger(32873);
        int rowLength = GL11.glGetInteger(3330);
        int imageHeight = GL11.glGetInteger(32876);
        int skipRows = GL11.glGetInteger(3331);
        int skipPixels = GL11.glGetInteger(3332);
        int alignment = GL11.glGetInteger(3333);
        try {
            GL11.glFlush();
            GL11.glFinish();
            GL30C.glBindFramebuffer(36160, 0);
            GL15C.glBindBuffer(35051, 0);
            GL11.glPixelStorei(3330, width);
            GL11.glPixelStorei(32876, 0);
            GL11.glPixelStorei(3331, 0);
            GL11.glPixelStorei(3332, 0);
            GL11.glPixelStorei(3333, 4);
            GL11.glBindTexture(3553, textureId);
            GL11.glGetTexImage(3553, 0, 6408, 5121, pixels);
        } finally {
            GL11.glPixelStorei(3330, rowLength);
            GL11.glPixelStorei(32876, imageHeight);
            GL11.glPixelStorei(3331, skipRows);
            GL11.glPixelStorei(3332, skipPixels);
            GL11.glPixelStorei(3333, alignment);
            GL11.glBindTexture(3553, texture);
            GL15C.glBindBuffer(35051, pixelPackBuffer);
            GL30C.glBindFramebuffer(36160, framebuffer);
        }
    }

    private static int readDimension(Object texture, String fieldName, String methodName)
            throws ReflectiveOperationException {
        try {
            Field field = texture.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(texture);
        } catch (NoSuchFieldException ignored) {
            Method method = texture.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (Integer) method.invoke(texture);
        }
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyTextureCompat.class.getClassLoader(),
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
