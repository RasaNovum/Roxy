package net.rasanovum.roxy.bridge;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;

import java.lang.reflect.Method;

public final class RoxyTextureBridge {
    private static final String BLOCK_ATLAS = "textures/atlas/blocks.png";

    private RoxyTextureBridge() {
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
            TextureData data = readTexture(textureId);

            Method setSamplerTexture = rasterizer.getClass().getMethod(
                    "setSamplerTexture", int[].class, int.class, int.class
            );
            setSamplerTexture.invoke(rasterizer, data.pixels, data.width, data.height);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Minecraft 1.21.1's block atlas", exception);
        }
    }

    private static TextureData readTexture(int textureId) {
        int pixelPackBuffer = GL11.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int imageHeight = GL11.glGetInteger(GL12.GL_PACK_IMAGE_HEIGHT);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int swapBytes = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        int lsbFirst = GL11.glGetInteger(GL11.GL_PACK_LSB_FIRST);
        try {
            GL11.glFlush();
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || (long) width * (long) height > Integer.MAX_VALUE) {
                throw new IllegalStateException("Block atlas has invalid OpenGL dimensions: " + width + "x" + height);
            }
            int[] pixels = new int[width * height];
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, width);
            GL11.glPixelStorei(GL12.GL_PACK_IMAGE_HEIGHT, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, 0);
            GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            return new TextureData(pixels, width, height);
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL12.GL_PACK_IMAGE_HEIGHT, imageHeight);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, swapBytes);
            GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, lsbFirst);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
        }
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyTextureBridge.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("net.minecraft.client.Minecraft", false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }

    private record TextureData(int[] pixels, int width, int height) {
    }
}
