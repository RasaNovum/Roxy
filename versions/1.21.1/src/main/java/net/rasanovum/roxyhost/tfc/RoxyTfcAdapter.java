package net.rasanovum.roxyhost.tfc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The optional TFC API boundary. Evaluations run on the client/render thread. */
public final class RoxyTfcAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final ThreadLocal<Object> CLIMATE = new ThreadLocal<>();
    private static final ThreadLocal<long[]> CALENDAR = new ThreadLocal<>();
    private static boolean attempted;
    private static boolean available;
    private static boolean warned;
    private static Class<?> leafClass;
    private static IntegerProperty leafDistance;
    private static BooleanProperty leafPersistent;
    private static Class<?> modelClass;
    private static Constructor<?> chunkConstructor;
    private static Constructor<?> layerConstructor;
    private static Method updatePacket;
    private static Method selectModel;
    private static Field denseModel;
    private static Method day;
    private static Method calendarTicks;
    private static Method monthDays;
    private static Object calendar;
    private static Object forest;
    private static Method capture;
    private static Field[] layers;
    private static Method[] corners;
    private static Method chunkPosition;
    private static Method climateModel;
    private static Class<?> overworldClimate;
    private static boolean snapshotHookInstalled;
    private static boolean calendarHookInstalled;

    private RoxyTfcAdapter() {}

    public static synchronized boolean available() {
        if (attempted) return available;
        attempted = true;
        try {
            ClassLoader loader = RoxyTfcAdapter.class.getClassLoader();
            leafClass = Class.forName("net.dries007.tfc.common.blocks.wood.TFCLeavesBlock", false, loader);
            leafDistance = (IntegerProperty) leafClass.getField("DISTANCE").get(null);
            leafPersistent = (BooleanProperty) leafClass.getField("PERSISTENT").get(null);
            climateModel = Class.forName("net.dries007.tfc.util.climate.Climate", false, loader)
                    .getMethod("get", net.minecraft.world.level.Level.class);
            overworldClimate = Class.forName("net.dries007.tfc.util.climate.OverworldClimateModel", false, loader);
            modelClass = Class.forName("net.dries007.tfc.client.model.LeavesBlockModel", false, loader);
            Class<?> chunk = Class.forName("net.dries007.tfc.world.chunkdata.ChunkData", true, loader);
            Class<?> layer = Class.forName("net.dries007.tfc.world.chunkdata.LerpFloatLayer", false, loader);
            Class<?> forestClass = Class.forName("net.dries007.tfc.world.chunkdata.ForestType", false, loader);
            chunkConstructor = chunk.getConstructor(ChunkPos.class);
            layerConstructor = layer.getConstructor(float.class, float.class, float.class, float.class);
            updatePacket = chunk.getMethod("onUpdatePacket", layer, layer, layer, layer, forestClass);
            forest = forestClass.getEnumConstants()[0];
            selectModel = modelClass.getDeclaredMethod("getModelFromBlockState", BlockState.class, BlockPos.class);
            selectModel.setAccessible(true);
            denseModel = modelClass.getDeclaredField("denseLeavesBakedModel");
            denseModel.setAccessible(true);
            Class<?> calendars = Class.forName("net.dries007.tfc.util.calendar.Calendars", true, loader);
            calendar = calendars.getField("CLIENT").get(null);
            Class<?> calendarApi = Class.forName("net.dries007.tfc.util.calendar.ICalendar", false, loader);
            day = calendarApi.getMethod("getTotalCalendarDays");
            calendarTicks = calendarApi.getMethod("getCalendarTicks");
            monthDays = calendarApi.getMethod("getCalendarDaysInMonth");
            layers = new Field[4];
            String[] names = {"rainfallLayer", "rainVarianceLayer", "baseGroundwaterLayer", "temperatureLayer"};
            for (int i = 0; i < 4; i++) {
                layers[i] = chunk.getDeclaredField(names[i]);
                layers[i].setAccessible(true);
            }
            corners = new Method[]{layer.getMethod("value00"), layer.getMethod("value01"),
                    layer.getMethod("value10"), layer.getMethod("value11")};
            chunkPosition = chunk.getMethod("getPos");
            capture = Class.forName("net.rasanovum.roxy.tfc.TfcVoxyBridge", true, loader)
                    .getMethod("capture", Object.class, int.class, int.class, float[].class);
            available = true;
        } catch (ClassNotFoundException absent) {
            // TFC is optional.
        } catch (ReflectiveOperationException | LinkageError failure) {
            warn(failure);
        }
        return available;
    }

    public static boolean isLeaf(Object state) {
        return available() && state instanceof BlockState blockState && leafClass.isInstance(blockState.getBlock());
    }

    public static Object normalize(Object state) {
        if (!isLeaf(state)) return state;
        BlockState leaf = (BlockState) state;
        BlockState defaults = leaf.getBlock().defaultBlockState();
        return leaf.setValue(leafDistance, defaults.getValue(leafDistance))
                .setValue(leafPersistent, defaults.getValue(leafPersistent));
    }

    public static long calendarDay() {
        if (!available()) return Long.MIN_VALUE;
        try { return ((Number) day.invoke(calendar)).longValue(); }
        catch (ReflectiveOperationException failure) { warn(failure); return Long.MIN_VALUE; }
    }

    public static long[] calendarSnapshot() {
        if (!available()) return null;
        try { return new long[]{((Number) calendarTicks.invoke(calendar)).longValue(),
                ((Number) monthDays.invoke(calendar)).longValue()}; }
        catch (ReflectiveOperationException failure) { warn(failure); return null; }
    }

    public static Object clientLevel() { return Minecraft.getInstance().level; }

    public static void requestClimate(Object level, int x, int z) { RoxyTfcBackfill.request(level, x, z); }
    public static void tickClimate() { RoxyTfcBackfill.tick(); }
    public static int pendingClimate() { return RoxyTfcBackfill.pendingCount(); }

    public static int[] cameraChunk() {
        var position = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return new int[]{net.minecraft.util.Mth.floor(position.x) >> 4, net.minecraft.util.Mth.floor(position.z) >> 4};
    }

    public static Object[] evaluate(Object state, int x, int y, int z, Object climateSnapshot, Object calendarSnapshot) {
        if (!isLeaf(state)) return null;
        long[] time = calendarSnapshot instanceof long[] values && values.length == 2
                && values[1] > 0 && values[1] <= Integer.MAX_VALUE ? values : null;
        float[] snapshot = snapshotHookInstalled && calendarHookInstalled && time != null
                && climateSnapshot instanceof float[] values && values.length == 16 ? values : null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.isSameThread()) return null;
        BlockState blockState = (BlockState) state;
        Object sourceModel = minecraft.getBlockRenderer().getBlockModel(blockState);
        if (!modelClass.isInstance(sourceModel)) return null;
        Object previous = CLIMATE.get();
        long[] previousCalendar = CALENDAR.get();
        try {
            if (snapshot != null && climateModel.invoke(null, minecraft.level).getClass() != overworldClimate) snapshot = null;
            if (snapshot == null) {
                Object selected = denseModel.get(sourceModel);
                int tint = minecraft.getBlockColors().getColor(blockState, null, null, 0);
                return selected == null ? null : new Object[]{selected, tint};
            }
            Object chunk = chunkConstructor.newInstance(new ChunkPos(x >> 4, z >> 4));
            CLIMATE.set(chunk);
            CALENDAR.set(time);
            Object[] values = new Object[5];
            for (int i = 0; i < 4; i++) {
                int offset = i * 4;
                values[i] = layerConstructor.newInstance(snapshot[offset], snapshot[offset + 1],
                        snapshot[offset + 2], snapshot[offset + 3]);
            }
            values[4] = forest;
            updatePacket.invoke(chunk, values);
            BlockPos position = new BlockPos(x, y, z);
            Object selected = selectModel.invoke(sourceModel, blockState, position);
            int tint = minecraft.getBlockColors().getColor(blockState, minecraft.level, position, 0);
            return new Object[]{selected, tint};
        } catch (ReflectiveOperationException | RuntimeException failure) {
            warn(failure);
            return null;
        } finally {
            if (previous == null) CLIMATE.remove(); else CLIMATE.set(previous);
            if (previousCalendar == null) CALENDAR.remove(); else CALENDAR.set(previousCalendar);
        }
    }

    public static Object scopedClimate() { return CLIMATE.get(); }

    public static void snapshotHookInstalled() { snapshotHookInstalled = true; }

    public static long[] scopedCalendar() { return CALENDAR.get(); }

    public static void calendarHookInstalled() { calendarHookInstalled = true; }

    public static void capture(Object data) {
        if (CLIMATE.get() != null || !available()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.isSameThread()) return;
        try {
            float[] snapshot = new float[16];
            for (int i = 0; i < 4; i++) {
                Object layer = layers[i].get(data);
                if (layer == null) return;
                for (int j = 0; j < 4; j++) {
                    float value = ((Number) corners[j].invoke(layer)).floatValue();
                    if (!Float.isFinite(value)) return;
                    snapshot[i * 4 + j] = value;
                }
            }
            ChunkPos position = (ChunkPos) chunkPosition.invoke(data);
            capture.invoke(null, minecraft.level, position.x, position.z, snapshot);
        } catch (ReflectiveOperationException | RuntimeException failure) { warn(failure); }
    }

    private static void warn(Throwable failure) {
        if (!warned) {
            warned = true;
            LOGGER.warn("TFC seasonal LoD adapter could not use the installed API; keeping existing appearances", failure);
        }
    }
}
