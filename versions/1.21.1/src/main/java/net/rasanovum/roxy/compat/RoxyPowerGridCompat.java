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

public final class RoxyPowerGridCompat {
    private static final double CACHE_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final Map<UUID, Object> WIRES = new LinkedHashMap<>();
    private static final Map<UUID, Object> STORED_WIRES = new LinkedHashMap<>();
    private static final Map<UUID, Object> PENDING_WIRES = new LinkedHashMap<>();
    private static Object level;
    private static Path storePath;
    private static boolean reflectionFailureReported;

    private RoxyPowerGridCompat() {
    }

    public static void track(Object entity) {
        if (!isPowerGridWire(entity)) return;
        try {
            ensureLevel(invoke(entity, "level"));
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
            Object player = field(minecraft, "player");
            UUID uuid = (UUID) invoke(entity, "getUUID");
            if (player == null || ((Number) invoke(entity, "distanceToSqr", player)).doubleValue() < CACHE_DISTANCE_SQUARED) {
                WIRES.remove(uuid);
                PENDING_WIRES.remove(uuid);
                return;
            }
            ensureLevel(invoke(entity, "level"));
            Object tag = saveTag(entity);
            Object snapshot = snapshot(tag, invoke(entity, "level"));
            if (snapshot == null) return;
            PENDING_WIRES.remove(uuid);
            STORED_WIRES.put(uuid, tag);
            writeStore();
            WIRES.put(uuid, snapshot);
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
            ensureLevel(currentLevel);
            persistPending();
            if (WIRES.isEmpty()) return;

            Object camera = invoke(event, "getCamera");
            Object cameraPosition = invoke(camera, "getPosition");
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

            for (Object wire : WIRES.values()) {
                int id = ((Number) invoke(wire, "getId")).intValue();
                if (invoke(currentLevel, "getEntity", id) == wire) continue;
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
            invoke(buffers, "endBatch");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            reportReflectionFailure(exception);
        }
    }

    private static Object minecraft() throws ReflectiveOperationException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, loader);
        return type.getMethod("getInstance").invoke(null);
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
        if (changed) writeStore();
    }

    private static void ensureLevel(Object currentLevel) throws ReflectiveOperationException {
        if (level == currentLevel) return;
        WIRES.clear();
        STORED_WIRES.clear();
        PENDING_WIRES.clear();
        reflectionFailureReported = false;
        level = currentLevel;
        storePath = storePath(currentLevel);
        readStore(currentLevel);
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
        if (storePath == null) return;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Object root = Class.forName("net.minecraft.nbt.CompoundTag", false, loader).getConstructor().newInstance();
        Object tags = Class.forName("net.minecraft.nbt.ListTag", false, loader).getConstructor().newInstance();
        for (Object tag : STORED_WIRES.values()) invoke(tags, "add", tag);
        invoke(root, "put", "Wires", tags);
        try {
            Files.createDirectories(storePath.getParent());
        } catch (IOException exception) {
            throw new ReflectiveOperationException(exception);
        }
        staticInvoke(Class.forName("net.minecraft.nbt.NbtIo", false, loader), "write", root, storePath);
    }

    private static boolean isPowerGridWire(Object entity) {
        if (entity == null) return false;
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("org.patryk3211.powergrid.electricity.wire.BaseWireEntity")) return true;
        }
        return false;
    }

    private static Object invoke(Object owner, String name, Object... arguments) throws ReflectiveOperationException {
        for (Method candidate : owner.getClass().getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!accepts(parameters[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return candidate.invoke(owner, arguments);
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name + "/" + arguments.length);
    }

    private static Method method(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + parameterCount);
    }

    private static Object staticInvoke(Class<?> type, String name, Object... arguments) throws ReflectiveOperationException {
        for (Method candidate : type.getMethods()) {
            if (!Modifier.isStatic(candidate.getModifiers()) || !candidate.getName().equals(name) || candidate.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!accepts(parameters[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return candidate.invoke(null, arguments);
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arguments.length);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getField(name);
        return field.get(owner);
    }

    private static Object publicField(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
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

    private static void reportReflectionFailure(Throwable exception) {
        if (reflectionFailureReported) return;
        reflectionFailureReported = true;
        System.err.println("Roxy Power Grid compatibility disabled: " + exception);
    }
}
