package net.rasanovum.roxy.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.MemoryUtil;

public final class RoxyTextureTintBridge {
    private static final ClassValue<ConcurrentHashMap<Class<?>, Accessors>> ACCESSORS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<Class<?>, Accessors> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private RoxyTextureTintBridge() {}

    public static void putTextures(boolean darkened, Object[] textures, Object buffer, Object layer) {
        if (textures == null || buffer == null) {
            throw new IllegalStateException("Unsupported Voxy model atlas layout");
        }
        try {
            Accessors a = ACCESSORS.get(textures.getClass()).computeIfAbsent(
                    buffer.getClass(),
                    bufferType -> resolveAccessors(textures.getClass(), bufferType)
            );
            a.upload.invoke(null, darkened, textures, buffer);
            if (layer == a.translucent) return;
            long address = a.address.getLong(buffer);
            if (address == 0L || textures.length != 6 || a.size.getLong(buffer) < 6L * 16 * 16 * 4) {
                throw new IllegalStateException("Unsupported Voxy model atlas layout");
            }
            for (int face = 0; face < textures.length; face++) {
                Object texture = textures[face];
                if (texture == null) {
                    throw new IllegalStateException("Unsupported Voxy model face data");
                }
                if ((int) a.width.invoke(texture) != 16 || (int) a.height.invoke(texture) != 16) {
                    throw new IllegalStateException("Unsupported Voxy model face size");
                }
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
        if (address == 0L || colour == null || depth == null || colour.length != 256 || depth.length != 256) {
            throw new IllegalStateException("Unsupported Voxy face data");
        }
        boolean tinted = false;
        boolean untinted = false;
        for (int i = 0; i < 256; i++) {
            if ((colour[i] >>> 24) <= 25) continue;
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

    private static Accessors resolveAccessors(Class<?> texturesType, Class<?> bufferType) {
        try {
            Class<?> data = texturesType.getComponentType();
            ClassLoader loader = data.getClassLoader();
            Method upload = Class.forName("me.cortex.voxy.client.core.model.MipGen", false, loader)
                    .getMethod("putTextures", boolean.class, texturesType, bufferType);
            Method colour = data.getMethod("colour");
            Method depth = data.getMethod("depth");
            Method width = data.getMethod("width");
            Method height = data.getMethod("height");
            Field address = bufferType.getField("address");
            Field size = bufferType.getField("size");
            upload.trySetAccessible();
            colour.trySetAccessible();
            depth.trySetAccessible();
            width.trySetAccessible();
            height.trySetAccessible();
            address.trySetAccessible();
            size.trySetAccessible();
            Object translucent = Class.forName("net.minecraft.client.renderer.RenderType", false, loader)
                    .getMethod("translucent").invoke(null);
            return new Accessors(upload, colour, depth, width, height, address, size, translucent);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve Voxy tint metadata accessors", exception);
        }
    }

    private record Accessors(Method upload, Method colour, Method depth, Method width, Method height,
                             Field address, Field size, Object translucent) {}
}
