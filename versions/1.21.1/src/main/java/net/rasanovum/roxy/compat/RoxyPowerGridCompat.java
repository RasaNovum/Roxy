package net.rasanovum.roxy.compat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RoxyPowerGridCompat {
    private static final double CACHE_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 1024.0 * 1024.0;
    private static final int STORE_VERSION = 1;
    private static final Map<UUID, Object> WIRES = new LinkedHashMap<>();
    private static final Map<UUID, Object> STORED_WIRES = new LinkedHashMap<>();
    private static final Map<UUID, Object> PENDING_WIRES = new LinkedHashMap<>();
    private static final Set<UUID> DESTROYED_WIRES = ConcurrentHashMap.newKeySet();
    private static final Map<MethodKey, Method[]> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<FieldKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ClassValue<Boolean> WIRE_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
                if (parent.getName().equals("org.patryk3211.powergrid.electricity.wire.BaseWireEntity")) return true;
            }
            return false;
        }
    };
    private static Object level;
    private static Path storePath;
    private static boolean storeDirty;
    private static boolean reflectionFailureReported;

    private RoxyPowerGridCompat() {
    }

    public static void track(Object entity) {
        if (!isPowerGridWire(entity)) return;
        try {
            ensureLevel(invoke(entity, "level"));
            purgeDestroyedWires();
            UUID uuid = (UUID) invoke(entity, "getUUID");
            WIRES.remove(uuid);
            PENDING_WIRES.put(uuid, entity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    public static void cache(Object entity, Object reason) {
        if (!isPowerGridWire(entity)) return;
        try {
            Object minecraft = minecraft();
            Object entityLevel = invoke(entity, "level");
            ensureLevel(entityLevel);
            purgeDestroyedWires();
            UUID uuid = (UUID) invoke(entity, "getUUID");
            if (DESTROYED_WIRES.contains(uuid)) {
                removeWire(uuid);
                return;
            }
            Object player = field(minecraft, "player");
            if (player == null || ((Number) invoke(entity, "distanceToSqr", player)).doubleValue() < CACHE_DISTANCE_SQUARED) {
                removeWire(uuid);
                return;
            }
            Object tag = saveTag(entity);
            Object snapshot = snapshot(tag, entityLevel);
            if (snapshot == null) return;
            PENDING_WIRES.remove(uuid);
            STORED_WIRES.put(uuid, tag);
            storeDirty = true;
            WIRES.put(uuid, snapshot);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    public static void tick() {
        try {
            Object minecraft = minecraft();
            Object currentLevel = field(minecraft, "level");
            ensureLevel(currentLevel);
            if (currentLevel == null) return;
            purgeDestroyedWires();
            persistPending();
            if (storeDirty) writeStore();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    public static void render(Object event) {
        try {
            if (!invoke(event, "getStage").toString().endsWith("after_entities")) return;
            Object minecraft = minecraft();
            Object currentLevel = field(minecraft, "level");
            Object player = field(minecraft, "player");
            if (currentLevel == null || player == null) return;
            if (level != currentLevel) return;
            purgeDestroyedWires();
            if (WIRES.isEmpty()) return;

            Object camera = invoke(event, "getCamera");
            Object cameraPosition = invoke(camera, "getPosition");
            Object frustum = invoke(event, "getFrustum");
            double cameraX = ((Number) publicField(cameraPosition, "x")).doubleValue();
            double cameraY = ((Number) publicField(cameraPosition, "y")).doubleValue();
            double cameraZ = ((Number) publicField(cameraPosition, "z")).doubleValue();
            Object renderBuffers = invoke(minecraft, "renderBuffers");
            Object buffers = invoke(renderBuffers, "bufferSource");
            Object partialTick = invoke(event, "getPartialTick");
            float tick = ((Number) invoke(partialTick, "getGameTimeDeltaPartialTick", false)).floatValue();
            Object dispatcher = invoke(minecraft, "getEntityRenderDispatcher");
            Object poseStack = invoke(event, "getPoseStack");
            Method render = method(dispatcher.getClass(), "render", 9);
            boolean renderedAny = false;

            for (Object wire : WIRES.values()) {
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
                renderedAny = true;
            }
            if (renderedAny) invoke(buffers, "endBatch");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    private static Object minecraft() throws ReflectiveOperationException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, loader);
        return method(type, "getInstance", 0).invoke(null);
    }

    private static Object saveTag(Object entity) throws ReflectiveOperationException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Object tag = Class.forName("net.minecraft.nbt.CompoundTag", false, loader).getConstructor().newInstance();
        invoke(entity, "save", tag);
        return tag;
    }

    private static Object snapshot(Object tag, Object currentLevel) throws ReflectiveOperationException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType", false, loader);
        Optional<?> type = (Optional<?>) staticInvoke(entityType, "byString", invoke(tag, "getString", "id"));
        if (type.isEmpty()) return null;
        Object copy = invoke(type.get(), "create", currentLevel);
        if (copy != null) invoke(copy, "load", tag);
        return copy;
    }

    private static void persistPending() throws ReflectiveOperationException {
        boolean changed = false;
        for (var iterator = PENDING_WIRES.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Object> entry = iterator.next();
            try {
                STORED_WIRES.put(entry.getKey(), saveTag(entry.getValue()));
                iterator.remove();
                changed = true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (changed) storeDirty = true;
    }

    private static void ensureLevel(Object currentLevel) throws ReflectiveOperationException {
        if (level == currentLevel) return;
        if (level != null) persistPending();
        if (storeDirty) writeStore();
        WIRES.clear();
        STORED_WIRES.clear();
        PENDING_WIRES.clear();
        DESTROYED_WIRES.clear();
        storeDirty = false;
        reflectionFailureReported = false;
        storePath = currentLevel == null ? null : storePath(currentLevel);
        if (currentLevel != null) readStore(currentLevel);
        level = currentLevel;
    }

    private static Path storePath(Object currentLevel) throws ReflectiveOperationException {
        Object server = invoke(minecraft(), "getSingleplayerServer");
        if (server == null) return null;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class<?> levelResource = Class.forName("net.minecraft.world.level.storage.LevelResource", false, loader);
        Object root = levelResource.getField("ROOT").get(null);
        Path world = (Path) invoke(server, "getWorldPath", root);
        String dimension = invoke(invoke(currentLevel, "dimension"), "location").toString().replace(':', '_').replace('/', '_');
        return world.resolve("data").resolve("roxy-power-grid-" + dimension + ".dat");
    }

    private static void readStore(Object currentLevel) throws ReflectiveOperationException {
        if (storePath == null || !Files.isRegularFile(storePath)) return;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
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
            STORED_WIRES.put(uuid, tag);
            WIRES.put(uuid, wire);
        }
    }

    private static void writeStore() throws ReflectiveOperationException {
        if (storePath == null) {
            storeDirty = false;
            return;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Object root = Class.forName("net.minecraft.nbt.CompoundTag", false, loader).getConstructor().newInstance();
        Object tags = Class.forName("net.minecraft.nbt.ListTag", false, loader).getConstructor().newInstance();
        invoke(root, "putInt", "Version", STORE_VERSION);
        for (Object tag : STORED_WIRES.values()) invoke(tags, "add", tag);
        invoke(root, "put", "Wires", tags);
        try {
            Files.createDirectories(storePath.getParent());
        } catch (IOException exception) {
            throw new ReflectiveOperationException(exception);
        }
        staticInvoke(Class.forName("net.minecraft.nbt.NbtIo", false, loader), "write", root, storePath);
        storeDirty = false;
    }

    private static void removeWire(UUID uuid) throws ReflectiveOperationException {
        WIRES.remove(uuid);
        PENDING_WIRES.remove(uuid);
        if (STORED_WIRES.remove(uuid) != null) storeDirty = true;
    }

    private static void purgeDestroyedWires() throws ReflectiveOperationException {
        for (var iterator = DESTROYED_WIRES.iterator(); iterator.hasNext();) {
            UUID uuid = iterator.next();
            WIRES.remove(uuid);
            PENDING_WIRES.remove(uuid);
            if (STORED_WIRES.remove(uuid) != null) storeDirty = true;
            iterator.remove();
        }
    }

    public static void markServerRemoval(Object entity, Object level) {
        if (!isPowerGridWire(entity)) return;
        try {
            if (Boolean.TRUE.equals(invoke(level, "isClientSide"))) return;
            Object reason = invoke(entity, "getRemovalReason");
            if (reason != null && Boolean.TRUE.equals(invoke(reason, "shouldDestroy"))) {
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

    private static Object publicField(Object owner, String name) throws ReflectiveOperationException {
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
        System.err.println("Roxy Power Grid compatibility disabled: " + exception);
    }
}
