package net.rasanovum.roxy.tfc;

import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TfcVoxyUploadPatch {
    private static final String UPLOADER = "me.cortex.voxy.client.core.model.ModelFactory$ModelBakeResultUpload";
    private static final long MODEL_BYTES = 64;
    private static final long TEXTURE_BUFFER_BYTES = 6L * (16L * 16 + 8L * 8 + 4L * 4 + 2L * 2 + 1L) * Integer.BYTES;
    private static final int TEXTURE_UPLOAD_BYTES = 48 * 32 * Integer.BYTES
            + 24 * 16 * Integer.BYTES
            + 12 * 8 * Integer.BYTES
            + 6 * 4 * Integer.BYTES;
    private static final ThreadLocal<Object> ATLAS_ONLY = new ThreadLocal<>();
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private TfcVoxyUploadPatch() {}

    public static byte[] modelBytes(Object uploader) {
        return copyBuffer(uploader, "model", MODEL_BYTES);
    }

    public static boolean sameModelBytes(Object uploader, byte[] expected) {
        byte[] actual = modelBytes(uploader);
        return actual != null && expected != null && Arrays.equals(actual, expected);
    }

    public static boolean modelUsesBiomeColours(Object uploader) {
        byte[] model = modelBytes(uploader);
        return model == null || (ByteBuffer.wrap(model).order(ByteOrder.nativeOrder()).getInt(24) & 2) != 0;
    }

    public static byte[] textureAlphaBytes(Object uploader) {
        byte[] texture = copyBuffer(uploader, "texture", TEXTURE_BUFFER_BYTES);
        if (texture == null) return null;
        byte[] alpha = new byte[TEXTURE_UPLOAD_BYTES / Integer.BYTES];
        for (int i = 0; i < alpha.length; i++) alpha[i] = texture[i * Integer.BYTES + 3];
        return alpha;
    }

    public static boolean sameTextureAlpha(Object uploader, byte[] expected) {
        byte[] actual = textureAlphaBytes(uploader);
        return actual != null && expected != null && Arrays.equals(actual, expected);
    }

    public static void armAtlasOnly(Object uploader) {
        if (!isUploader(uploader)) throw new IllegalArgumentException("Unsupported Voxy texture uploader");
        if (ATLAS_ONLY.get() != null) throw new IllegalStateException("Voxy atlas-only upload was already armed");
        ATLAS_ONLY.set(uploader);
    }

    public static boolean atlasOnly(Object uploader) {
        Object armed = ATLAS_ONLY.get();
        if (armed == null) return false;
        ATLAS_ONLY.remove();
        if (armed != uploader || !isUploader(uploader)) {
            throw new IllegalStateException("Voxy atlas-only upload token did not match the active uploader");
        }
        if (intField(uploader, "biomeUploadIndex") != -1 || objectField(uploader, "biomeUpload") != null
                || modelUsesBiomeColours(uploader)) {
            throw new IllegalStateException("Refusing TFC texture-only upload with a biome-colour update");
        }
        return true;
    }

    private static byte[] copyBuffer(Object uploader, String name, long expectedSize) {
        if (!isUploader(uploader)) return null;
        Object buffer = objectField(uploader, name);
        if (buffer == null) return null;
        long size = longField(buffer, "size");
        long address = longField(buffer, "address");
        if (size != expectedSize || address == 0 || size > Integer.MAX_VALUE) return null;
        byte[] bytes = new byte[(int) size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = MemoryUtil.memGetByte(address + i);
        return bytes;
    }

    private static boolean isUploader(Object value) {
        return value != null && value.getClass().getName().equals(UPLOADER);
    }

    private static Object objectField(Object owner, String name) {
        try {
            return field(owner.getClass(), name).get(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read Voxy uploader field " + name, failure);
        }
    }

    private static int intField(Object owner, String name) {
        try {
            return field(owner.getClass(), name).getInt(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read Voxy uploader field " + name, failure);
        }
    }

    private static long longField(Object owner, String name) {
        try {
            return field(owner.getClass(), name).getLong(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read Voxy buffer field " + name, failure);
        }
    }

    private static Field field(Class<?> type, String name) {
        return FIELDS.get(type).computeIfAbsent(name, key -> {
            Class<?> current = type;
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(key);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException absent) {
                    current = current.getSuperclass();
                }
            }
            throw new IllegalStateException("Missing Voxy compatibility field " + type.getName() + "." + key);
        });
    }
}
