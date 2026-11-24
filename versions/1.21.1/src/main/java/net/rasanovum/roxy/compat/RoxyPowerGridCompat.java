package net.rasanovum.roxy.compat;

import net.neoforged.fml.loading.LoadingModList;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RoxyPowerGridCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final double CACHE_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 1024.0 * 1024.0;
    private static final int STORE_VERSION = 1;
    private static final int MAX_TRACKED_WIRES = 8192;
    private static final long WRITE_RETRY_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final Object STATE_LOCK = new Object();
    private static final Map<UUID, Object> WIRES = new LinkedHashMap<>();
    private static final Map<UUID, Object> STORED_WIRES = new LinkedHashMap<>();
    private static final Map<UUID, Object> PENDING_WIRES = new LinkedHashMap<>();
    private static final Set<UUID> DESTROYED_WIRES = ConcurrentHashMap.newKeySet();
    private static final Map<MethodKey, Method[]> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<FieldKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService STORE_IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Roxy PowerGrid wire store");
        thread.setDaemon(true);
        return thread;
    });
    private static final ClassValue<Boolean> WIRE_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
                if (parent.getName().equals("org.patryk3211.powergrid.electricity.wire.BaseWireEntity")) return true;
            }
            return false;
        }
    };
    private static volatile Boolean powerGridInstalled;
    private static Object level;
    private static Path storePath;
    private static boolean storeDirty;
    private static boolean writeInFlight;
    private static long nextWriteAttemptNanos;
    private static boolean reflectionFailureReported;

    private RoxyPowerGridCompat() {
    }

    public static boolean isPowerGridInstalled() {
        Boolean cached = powerGridInstalled;
        if (cached != null) return cached;
        try {
            LoadingModList mods = LoadingModList.get();
            if (mods == null) return false;
            boolean present = mods.getModFileById("powergrid") != null;
            powerGridInstalled = present;
            return present;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static void onWorldUnload() {
        synchronized (STATE_LOCK) {
            try {
                if (level != null) persistPendingLocked();
                if (storeDirty) scheduleWriteStoreLocked(true);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                reportReflectionFailure(exception);
            }
            WIRES.clear();
            STORED_WIRES.clear();
            PENDING_WIRES.clear();
            DESTROYED_WIRES.clear();
            storeDirty = false;
            storePath = null;
            level = null;
            nextWriteAttemptNanos = 0L;
        }
    }

    public static void track(Object entity) {
        if (!RoxyCompatConfig.powerGridEnabled() || !isPowerGridInstalled()) return;
        if (!isPowerGridWire(entity)) return;
        synchronized (STATE_LOCK) {
            try {
                ensureLevelLocked(invoke(entity, "level"));
                purgeDestroyedWiresLocked();
                UUID uuid = (UUID) invoke(entity, "getUUID");
                WIRES.remove(uuid);
                putBounded(PENDING_WIRES, uuid, entity);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                reportReflectionFailure(exception);
            }
        }
    }

    public static void cache(Object entity, Object reason) {
        if (!RoxyCompatConfig.powerGridEnabled() || !isPowerGridInstalled()) return;
        if (!isPowerGridWire(entity)) return;
        synchronized (STATE_LOCK) {
            try {
                Object minecraft = minecraft();
                Object entityLevel = invoke(entity, "level");
                ensureLevelLocked(entityLevel);
                purgeDestroyedWiresLocked();
                UUID uuid = (UUID) invoke(entity, "getUUID");
                if (reason != null && Boolean.TRUE.equals(invoke(reason, "shouldDestroy"))) {
                    removeWireLocked(uuid);
                    return;
                }
                if (DESTROYED_WIRES.contains(uuid)) {
                    removeWireLocked(uuid);
                    return;
                }
                Object player = field(minecraft, "player");
                if (player == null || ((Number) invoke(entity, "distanceToSqr", player)).doubleValue() < CACHE_DISTANCE_SQUARED) {
                    removeWireLocked(uuid);
                    return;
                }
                Object tag = saveTag(entity);
                Object snapshot = snapshot(tag, entityLevel);
                if (snapshot == null) return;
                PENDING_WIRES.remove(uuid);
                putBounded(STORED_WIRES, uuid, tag);
                storeDirty = true;
                putBounded(WIRES, uuid, snapshot);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                reportReflectionFailure(exception);
            }
        }
    }

    public static void tick() {
        if (!RoxyCompatConfig.powerGridEnabled() || !isPowerGridInstalled()) return;
        synchronized (STATE_LOCK) {
            try {
                Object minecraft = minecraft();
                Object currentLevel = field(minecraft, "level");
                ensureLevelLocked(currentLevel);
                if (currentLevel == null) return;
                purgeDestroyedWiresLocked();
                persistPendingLocked();
                if (storeDirty && !writeInFlight && System.nanoTime() - nextWriteAttemptNanos >= 0L) {
                    scheduleWriteStoreLocked(false);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                reportReflectionFailure(exception);
            }
        }
    }

    public static void render(Object event) {
        if (!RoxyCompatConfig.powerGridEnabled() || !isPowerGridInstalled()) return;
        List<Object> wiresSnapshot;
        Object currentLevel;
        Object player;
        Object minecraft;
        try {
            if (!invoke(event, "getStage").toString().endsWith("after_entities")) return;
            minecraft = minecraft();
            currentLevel = field(minecraft, "level");
            player = field(minecraft, "player");
            if (currentLevel == null || player == null) return;
            synchronized (STATE_LOCK) {
                if (level != currentLevel) return;
                purgeDestroyedWiresLocked();
                if (WIRES.isEmpty()) return;
                wiresSnapshot = new ArrayList<>(WIRES.values());
            }

            Object camera = invoke(event, "getCamera");
            Object cameraPosition = invoke(camera, "getPosition");
            Object frustum = invoke(event, "getFrustum");
            double cameraX = ((Number) field(cameraPosition, "x")).doubleValue();
            double cameraY = ((Number) field(cameraPosition, "y")).doubleValue();
            double cameraZ = ((Number) field(cameraPosition, "z")).doubleValue();
            Object renderBuffers = invoke(minecraft, "renderBuffers");
            Object buffers = invoke(renderBuffers, "bufferSource");
            Object partialTick = invoke(event, "getPartialTick");
            float tick = ((Number) invoke(partialTick, "getGameTimeDeltaPartialTick", false)).floatValue();
            Object dispatcher = invoke(minecraft, "getEntityRenderDispatcher");
            Object poseStack = invoke(event, "getPoseStack");
            Method render = method(dispatcher.getClass(), "render", 9);

            for (Object wire : wiresSnapshot) {
                int id = ((Number) invoke(wire, "getId")).intValue();
                if (invoke(currentLevel, "getEntity", id) == wire) continue;
                double distance = ((Number) invoke(wire, "distanceToSqr", player)).doubleValue();
                if (distance <= CACHE_DISTANCE_SQUARED || distance > MAX_RENDER_DISTANCE_SQUARED) continue;
                if (frustum != null && !Boolean.TRUE.equals(invoke(frustum, "isVisible", invoke(wire, "getBoundingBox")))) continue;
                render.invoke(
                        dispatcher,
                        wire,
                        ((Number) invoke(wire, "getX")).doubleValue() - cameraX,
                        ((Number) invoke(wire, "getY")).doubleValue() - cameraY,
                        ((Number) invoke(wire, "getZ")).doubleValue() - cameraZ,
                        ((Number) invoke(wire, "getYRot")).floatValue(),
                        tick,
                        poseStack,
                        buffers,
                        0x00F000F0
                );
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    private static void putBounded(Map<UUID, Object> map, UUID key, Object value) {
        map.put(key, value);
        while (map.size() > MAX_TRACKED_WIRES) {
            var it = map.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    private static Object minecraft() throws ReflectiveOperationException {
        ClassLoader loader = RoxyClassLoaders.findGameLoader();
        Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, loader);
        return method(type, "getInstance", 0).invoke(null);
    }

    private static Object saveTag(Object entity) throws ReflectiveOperationException {
        ClassLoader loader = RoxyClassLoaders.findGameLoader(entity.getClass().getClassLoader());
        Object tag = Class.forName("net.minecraft.nbt.CompoundTag", false, loader).getConstructor().newInstance();
        invoke(entity, "save", tag);
        return tag;
    }

    private static Object snapshot(Object tag, Object currentLevel) throws ReflectiveOperationException {
        ClassLoader loader = RoxyClassLoaders.findGameLoader(currentLevel.getClass().getClassLoader());
        Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType", false, loader);
        Optional<?> type = (Optional<?>) staticInvoke(entityType, "byString", invoke(tag, "getString", "id"));
        if (type.isEmpty()) return null;
        Object copy = invoke(type.get(), "create", currentLevel);
        if (copy != null) invoke(copy, "load", tag);
        return copy;
    }

    private static void persistPendingLocked() throws ReflectiveOperationException {
        boolean changed = false;
        for (var iterator = PENDING_WIRES.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Object> entry = iterator.next();
            try {
                putBounded(STORED_WIRES, entry.getKey(), saveTag(entry.getValue()));
                iterator.remove();
                changed = true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (changed) storeDirty = true;
    }

    private static void ensureLevelLocked(Object currentLevel) throws ReflectiveOperationException {
        if (level == currentLevel) return;
        if (level != null) persistPendingLocked();
        if (storeDirty) scheduleWriteStoreLocked(true);
        WIRES.clear();
        STORED_WIRES.clear();
        PENDING_WIRES.clear();
        DESTROYED_WIRES.clear();
        storeDirty = false;
        nextWriteAttemptNanos = 0L;
        reflectionFailureReported = false;
        storePath = currentLevel == null ? null : storePath(currentLevel);
        if (currentLevel != null) readStoreLocked(currentLevel);
        level = currentLevel;
    }

    private static Path storePath(Object currentLevel) throws ReflectiveOperationException {
        Object server = invoke(minecraft(), "getSingleplayerServer");
        if (server == null) return null;
        ClassLoader loader = RoxyClassLoaders.findGameLoader(currentLevel.getClass().getClassLoader());
        Class<?> levelResource = Class.forName("net.minecraft.world.level.storage.LevelResource", false, loader);
        Object root = levelResource.getField("ROOT").get(null);
        Path world = (Path) invoke(server, "getWorldPath", root);
        String dimension = invoke(invoke(currentLevel, "dimension"), "location").toString().replace(':', '_').replace('/', '_');
        return world.resolve("data").resolve("roxy-power-grid-" + dimension + ".dat");
    }

    private static void readStoreLocked(Object currentLevel) throws ReflectiveOperationException {
        if (storePath == null || !Files.isRegularFile(storePath)) return;
        ClassLoader loader = RoxyClassLoaders.findGameLoader(currentLevel.getClass().getClassLoader());
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo", false, loader);
        Object root = staticInvoke(nbtIo, "read", storePath);
        if (root == null) return;
        if (((Number) invoke(root, "getInt", "Version")).intValue() != STORE_VERSION) {
            storeDirty = true;
            return;
        }
        Object tags = invoke(root, "getList", "Wires", 10);
        for (Object tag : (Iterable<?>) tags) {
            Object wire = snapshot(tag, currentLevel);
            if (!isPowerGridWire(wire)) continue;
            UUID uuid = (UUID) invoke(wire, "getUUID");
            putBounded(STORED_WIRES, uuid, tag);
            putBounded(WIRES, uuid, wire);
        }
    }

    private static void scheduleWriteStoreLocked(boolean force) throws ReflectiveOperationException {
        Path targetPath = storePath;
        if (targetPath == null) {
            storeDirty = false;
            return;
        }
        if (writeInFlight && !force) return;
        ClassLoader loader = RoxyClassLoaders.findGameLoader();
        Object root = Class.forName("net.minecraft.nbt.CompoundTag", false, loader).getConstructor().newInstance();
        Object tags = Class.forName("net.minecraft.nbt.ListTag", false, loader).getConstructor().newInstance();
        invoke(root, "putInt", "Version", STORE_VERSION);
        for (Object tag : STORED_WIRES.values()) invoke(tags, "add", tag);
        invoke(root, "put", "Wires", tags);
        storeDirty = false;
        writeInFlight = true;
        STORE_IO.execute(() -> {
            try {
                Path parent = targetPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                staticInvoke(Class.forName("net.minecraft.nbt.NbtIo", false, loader), "write", root, targetPath);
            } catch (IOException | ReflectiveOperationException | RuntimeException exception) {
                synchronized (STATE_LOCK) {
                    if (storePath == targetPath) {
                        storeDirty = true;
                        nextWriteAttemptNanos = System.nanoTime() + WRITE_RETRY_BACKOFF_NANOS;
                    }
                }
                LOGGER.warn("Unable to persist Roxy PowerGrid wire store", exception);
            } finally {
                synchronized (STATE_LOCK) {
                    writeInFlight = false;
                }
            }
        });
    }

    private static void removeWireLocked(UUID uuid) {
        WIRES.remove(uuid);
        PENDING_WIRES.remove(uuid);
        if (STORED_WIRES.remove(uuid) != null) storeDirty = true;
    }

    private static void purgeDestroyedWiresLocked() {
        for (var iterator = DESTROYED_WIRES.iterator(); iterator.hasNext();) {
            UUID uuid = iterator.next();
            WIRES.remove(uuid);
            PENDING_WIRES.remove(uuid);
            if (STORED_WIRES.remove(uuid) != null) storeDirty = true;
            iterator.remove();
        }
    }

    public static void markServerRemoval(Object entity, Object level) {
        if (!RoxyCompatConfig.powerGridEnabled() || !isPowerGridInstalled()) return;
        if (!isPowerGridWire(entity)) return;
        try {
            if (Boolean.TRUE.equals(invoke(level, "isClientSide"))) return;
            Object reason = invoke(entity, "getRemovalReason");
            if (reason != null && Boolean.TRUE.equals(invoke(reason, "shouldDestroy"))) {
                if (DESTROYED_WIRES.size() >= MAX_TRACKED_WIRES) {
                    DESTROYED_WIRES.clear();
                }
                DESTROYED_WIRES.add((UUID) invoke(entity, "getUUID"));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    private static boolean isPowerGridWire(Object entity) {
        return entity != null && WIRE_TYPES.get(entity.getClass());
    }

    private static Object invoke(Object owner, String name, Object... arguments) throws ReflectiveOperationException {
        for (Method candidate : methods(owner.getClass(), name, arguments.length)) {
            if (accepts(candidate.getParameterTypes(), arguments)) return candidate.invoke(owner, arguments);
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name + "/" + arguments.length);
    }

    private static Method method(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Method[] methods = methods(type, name, parameterCount);
        if (methods.length > 0) return methods[0];
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + parameterCount);
    }

    private static Object staticInvoke(Class<?> type, String name, Object... arguments) throws ReflectiveOperationException {
        for (Method candidate : methods(type, name, arguments.length)) {
            if (Modifier.isStatic(candidate.getModifiers()) && accepts(candidate.getParameterTypes(), arguments)) {
                return candidate.invoke(null, arguments);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arguments.length);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        return field(owner.getClass(), name).get(owner);
    }

    private static Method[] methods(Class<?> type, String name, int parameterCount) {
        return METHOD_CACHE.computeIfAbsent(new MethodKey(type, name, parameterCount), key -> {
            Method[] all = key.type().getMethods();
            int matching = 0;
            for (Method candidate : all) {
                if (candidate.getName().equals(key.name()) && candidate.getParameterCount() == key.parameterCount()) {
                    matching++;
                }
            }
            Method[] result = new Method[matching];
            int index = 0;
            for (Method candidate : all) {
                if (candidate.getName().equals(key.name()) && candidate.getParameterCount() == key.parameterCount()) {
                    result[index++] = candidate;
                }
            }
            return result;
        });
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return FIELD_CACHE.computeIfAbsent(new FieldKey(type, name), key -> {
                try {
                    return key.type().getField(key.name());
                } catch (NoSuchFieldException exception) {
                    throw new FieldLookupException(exception);
                }
            });
        } catch (FieldLookupException exception) {
            throw exception.cause;
        }
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            if (!accepts(parameters[i], arguments[i])) return false;
        }
        return true;
    }

    private static boolean accepts(Class<?> parameter, Object argument) {
        if (argument == null) return !parameter.isPrimitive();
        if (!parameter.isPrimitive()) return parameter.isInstance(argument);
        return (parameter == boolean.class && argument instanceof Boolean)
                || (parameter == byte.class && argument instanceof Byte)
                || (parameter == short.class && argument instanceof Short)
                || (parameter == int.class && argument instanceof Integer)
                || (parameter == long.class && argument instanceof Long)
                || (parameter == float.class && argument instanceof Float)
                || (parameter == double.class && argument instanceof Double)
                || (parameter == char.class && argument instanceof Character);
    }

    private record MethodKey(Class<?> type, String name, int parameterCount) {
    }

    private record FieldKey(Class<?> type, String name) {
    }

    private static final class FieldLookupException extends RuntimeException {
        private final NoSuchFieldException cause;

        private FieldLookupException(NoSuchFieldException cause) {
            this.cause = cause;
        }
    }

    private static void reportReflectionFailure(Throwable exception) {
        if (reflectionFailureReported) return;
        reflectionFailureReported = true;
        LOGGER.warn("Roxy Power Grid compatibility encountered an error", exception);
    }
}
