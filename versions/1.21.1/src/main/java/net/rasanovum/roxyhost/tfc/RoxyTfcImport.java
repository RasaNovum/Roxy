package net.rasanovum.roxyhost.tfc;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.LoggerFactory;

/** Reads the versioned TFC 4.2.x chunk attachment without constructing a chunk. */
public final class RoxyTfcImport {
    private static volatile Method engine;
    private static volatile Method capture;
    private static boolean warned;
    private static volatile ChunkCapture chunkCapture;
    private static volatile boolean chunkCaptureAbsent;

    private record ChunkCapture(Method get, Field[] layers, Method[] corners, Method accept,
                                Method instance, Method identifier, Method nullable) {}

    private RoxyTfcImport() {}

    public static void captureChunk(Object targetEngine, LevelChunk chunk) {
        if (chunk == null || chunkCaptureAbsent) return;
        try {
            ChunkCapture access = chunkCapture;
            if (access == null) {
                ClassLoader loader = RoxyTfcImport.class.getClassLoader();
                Class<?> data = Class.forName("net.dries007.tfc.world.chunkdata.ChunkData", false, loader);
                Class<?> layer = Class.forName("net.dries007.tfc.world.chunkdata.LerpFloatLayer", false, loader);
                String[] names = {"rainfallLayer", "rainVarianceLayer", "baseGroundwaterLayer", "temperatureLayer"};
                Field[] fields = new Field[4];
                for (int i = 0; i < 4; i++) {
                    fields[i] = data.getDeclaredField(names[i]);
                    fields[i].setAccessible(true);
                }
                Class<?> identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
                access = new ChunkCapture(data.getMethod("get", ChunkAccess.class), fields,
                        new Method[]{layer.getMethod("value00"), layer.getMethod("value01"),
                                layer.getMethod("value10"), layer.getMethod("value11")},
                        Class.forName("net.rasanovum.roxy.tfc.TfcVoxyBridge", true, loader)
                                .getMethod("captureImported", Object.class, int.class, int.class, float[].class),
                        Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader).getMethod("getInstance"),
                        identifier.getMethod("of", Level.class),
                        Class.forName("me.cortex.voxy.commonImpl.VoxyInstance", false, loader).getMethod("getNullable", identifier));
                chunkCapture = access;
            }
            if (targetEngine == null) {
                Object instance = access.instance.invoke(null);
                Object identifier = access.identifier.invoke(null, chunk.getLevel());
                if (instance == null || identifier == null) return;
                targetEngine = access.nullable.invoke(instance, identifier);
                if (targetEngine == null) return;
            }
            Object data = access.get.invoke(null, chunk);
            float[] snapshot = new float[16];
            for (int i = 0; i < 4; i++) {
                Object layer = access.layers[i].get(data);
                if (layer == null) return;
                for (int j = 0; j < 4; j++) {
                    float value = ((Number) access.corners[j].invoke(layer)).floatValue();
                    if (!Float.isFinite(value)) return;
                    snapshot[i * 4 + j] = value;
                }
            }
            access.accept.invoke(null, targetEngine, chunk.getPos().x, chunk.getPos().z, snapshot);
        } catch (ClassNotFoundException absent) {
            chunkCaptureAbsent = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!warned) {
                warned = true;
                LoggerFactory.getLogger("Roxy").warn("TFC generated chunk climate capture unavailable; terrain ingestion will continue", failure);
            }
        }
    }

    public static float[] decode(Object value) {
        if (!(value instanceof CompoundTag root) || !root.contains("xPos", Tag.TAG_INT)
                || !root.contains("zPos", Tag.TAG_INT) || !root.contains("neoforge:attachments", Tag.TAG_COMPOUND)) return null;
        CompoundTag attachments = root.getCompound("neoforge:attachments");
        if (!attachments.contains("tfc:chunk_data", Tag.TAG_COMPOUND)) return null;
        CompoundTag data = attachments.getCompound("tfc:chunk_data");
        if (!data.contains("status", Tag.TAG_BYTE)) return null;
        int status = data.getByte("status");
        if (status != 2 && status != 3) return null;
        String[] names = {"rainfall", "rainVariance", "baseGroundwater", "temperature"};
        String[] corners = {"00", "01", "10", "11"};
        float[] snapshot = new float[16];
        for (int i = 0; i < 4; i++) {
            if (!data.contains(names[i], Tag.TAG_COMPOUND)) return null;
            CompoundTag layer = data.getCompound(names[i]);
            for (int j = 0; j < 4; j++) {
                if (!layer.contains(corners[j], Tag.TAG_FLOAT)) return null;
                float sample = layer.getFloat(corners[j]);
                if (!Float.isFinite(sample)) return null;
                snapshot[i * 4 + j] = sample;
            }
        }
        return snapshot;
    }

    public static void capture(Object importer, Object value) {
        float[] snapshot = decode(value);
        if (snapshot == null) return;
        CompoundTag root = (CompoundTag) value;
        try {
            Method getEngine = engine;
            Method accept = capture;
            if (getEngine == null || accept == null) {
                getEngine = importer.getClass().getMethod("getEngine");
                accept = Class.forName("net.rasanovum.roxy.tfc.TfcVoxyBridge", true,
                                RoxyTfcImport.class.getClassLoader())
                        .getMethod("captureImported", Object.class, int.class, int.class, float[].class);
                engine = getEngine;
                capture = accept;
            }
            accept.invoke(null, getEngine.invoke(importer), root.getInt("xPos"), root.getInt("zPos"), snapshot);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!warned) {
                warned = true;
                LoggerFactory.getLogger("Roxy").warn("TFC import climate capture unavailable; terrain import will continue", failure);
            }
        }
    }
}
