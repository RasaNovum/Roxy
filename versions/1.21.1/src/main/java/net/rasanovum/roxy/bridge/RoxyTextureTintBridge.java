package net.rasanovum.roxy.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.MemoryUtil;

public final class RoxyTextureTintBridge {
    private static final ConcurrentHashMap<Class<?>, Accessors> ACCESSORS = new ConcurrentHashMap<>();

    private RoxyTextureTintBridge() {}

    public static void putTextures(boolean darkened, Object[] textures, Object buffer, Object layer) {
        try {
            Accessors a = ACCESSORS.get(textures.getClass());
            if (a == null) {
                Class<?> data = textures.getClass().getComponentType();
                ClassLoader loader = data.getClassLoader();
                a = new Accessors(Class.forName("me.cortex.voxy.client.core.model.MipGen", false, loader)
                        .getMethod("putTextures", boolean.class, textures.getClass(), buffer.getClass()),
                        data.getMethod("colour"), data.getMethod("depth"), data.getMethod("width"), data.getMethod("height"),
                        buffer.getClass().getField("address"), buffer.getClass().getField("size"),
                        Class.forName("net.minecraft.client.renderer.RenderType", false, loader).getMethod("translucent").invoke(null));
                ACCESSORS.put(textures.getClass(), a);
            }
            a.upload.invoke(null, darkened, textures, buffer);
            if (layer == a.translucent) return;
            long address = a.address.getLong(buffer);
            if (textures.length != 6 || a.size.getLong(buffer) < 6L * 16 * 16 * 4)
                throw new IllegalStateException("Unsupported Voxy model atlas layout");
            for (int face = 0; face < textures.length; face++) {
                Object texture = textures[face];
                if ((int) a.width.invoke(texture) != 16 || (int) a.height.invoke(texture) != 16)
                    throw new IllegalStateException("Unsupported Voxy model face size");
                int[] colour = (int[]) a.colour.invoke(texture);
                int[] depth = (int[]) a.depth.invoke(texture);
                encodeFace(colour, depth, address + ((face >> 1) * 16L + (face & 1) * 16L * 48) * 4);
            }
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Unable to upload Voxy tint metadata", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to upload Voxy tint metadata", exception);
        }
    }

    public static void encodeFace(int[] colour, int[] depth, long address) {
        if (colour.length != 256 || depth.length != 256) throw new IllegalStateException("Unsupported Voxy face data");
        boolean tinted = false, untinted = false;
        for (int i = 0; i < 256; i++) {
            if ((colour[i] >>> 24) == 0 || (colour[i] & 0xFFFFFF) == 0) continue;
            if ((depth[i] & 128) != 0) tinted = true;
            else untinted = true;
        }
        if (!tinted || !untinted) return;
        for (int i = 0; i < 256; i++) {
            if ((colour[i] >>> 24) <= 25) continue;
            long pixel = address + ((i & 15) + (i >> 4) * 48L) * 4;
            int alpha = (depth[i] & 128) != 0 ? 255 : 254;
            MemoryUtil.memPutInt(pixel, MemoryUtil.memGetInt(pixel) & 0xFFFFFF | alpha << 24);
        }
    }

    private record Accessors(Method upload, Method colour, Method depth, Method width, Method height,
                             Field address, Field size, Object translucent) {}
}
