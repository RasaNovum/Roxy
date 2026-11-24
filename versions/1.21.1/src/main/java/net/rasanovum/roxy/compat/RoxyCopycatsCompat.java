package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.util.RoxyClassLoaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyCopycatsCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final ConcurrentHashMap<MethodKey, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final ThreadLocal<IngestSnapshot> INGEST_SNAPSHOT = new ThreadLocal<>();
    private static final Map<Object, Set<Long>> PENDING_INGESTS = new IdentityHashMap<>();
    private static final AtomicBoolean SNAPSHOT_FAILURE_LOGGED = new AtomicBoolean();
    private static final ClassValue<Optional<Method>> SECTION_SNAPSHOT = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> sourceType) {
            try {
                ClassLoader loader = sourceType.getClassLoader();
                Class<?> sectionType = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection", false, loader);
                Class<?> helper = Class.forName("net.rasanovum.roxyhost.RoxyPalettedContainerFactory", false, loader);
                return Optional.of(helper.getMethod("snapshotSection", sectionType));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                logSnapshotFailure(sourceType, failure);
                return Optional.empty();
            }
        }
    };
    private static final ClassValue<Optional<Method>> SHAPE_FALLBACK = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> stateType) {
            try {
                ClassLoader loader = stateType.getClassLoader();
                Class<?> helper = Class.forName("net.rasanovum.roxyhost.RoxyCopycatShapeFallback", false, loader);
                return Optional.of(helper.getMethod("approximate", stateType, stateType));
            } catch (ReflectiveOperationException | LinkageError failure) {
                LOGGER.warn("Unable to resolve Copycat LOD shape mapping", failure);
                return Optional.empty();
            }
        }
    };
    private static volatile boolean ingestFailureLogged;
    private static volatile boolean rawFailureLogged;
    private static volatile boolean modCheckComplete;
    private static volatile boolean copycatModLoaded;

    private RoxyCopycatsCompat() {
    }

    public static void beginChunkIngest(Object chunk) {
        INGEST_SNAPSHOT.remove();
        if (!RoxyCompatConfig.copycatsEnabled() || chunk == null || !hasCopycatMod(chunk.getClass().getClassLoader())) return;
        try {
            Object blockEntities = invoke(chunk, "getBlockEntities");
            if (!(blockEntities instanceof Map<?, ?> entities)) return;
            INGEST_SNAPSHOT.set(new IngestSnapshot(chunk, indexBlockEntities(entities)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            INGEST_SNAPSHOT.set(new IngestSnapshot(chunk, null));
        }
    }

    public static void endChunkIngest() {
        INGEST_SNAPSHOT.remove();
    }

    public static void tick() {
        if (!RoxyCompatConfig.copycatsEnabled()) return;
        Map<Object, Set<Long>> pending = drainPendingIngests();
        if (pending.isEmpty()) return;
        try {
            ClassLoader[] hints = pending.keySet().stream()
                    .filter(level -> level != null)
                    .map(level -> level.getClass().getClassLoader())
                    .toArray(ClassLoader[]::new);
            ClassLoader loader = RoxyClassLoaders.findGameLoader(hints);
            Class<?> minecraftType = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object minecraft = minecraftType.getMethod("getInstance").invoke(null);
            Object currentLevel = field(minecraft, "level");
            if (currentLevel == null) return;
            for (Map.Entry<Object, Set<Long>> entry : pending.entrySet()) {
                if (entry.getKey() != currentLevel) continue;
                for (long chunkPos : entry.getValue()) {
                    ingestChunk(currentLevel, (int) chunkPos, (int) (chunkPos >> 32), loader);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
        }
    }

    public static Object snapshotSection(Object source, Object chunk, int sectionY) {
        Object copy = cloneSection(source);
        if (copy == null || chunk == null || !RoxyCompatConfig.copycatsEnabled()) return copy;
        if (!hasCopycatMod(source.getClass().getClassLoader())) return copy;
        try {
            ClassLoader loader = source.getClass().getClassLoader();
            for (BlockEntityAt blockEntity : sectionBlockEntities(chunk, sectionY)) {
                Object state = invoke(copy, "getBlockState", blockEntity.x, blockEntity.y, blockEntity.z);
                if (!isPotentialCopycatState(state)) continue;
                if (!isCopycat(state, blockEntity.blockEntity)) continue;
                Object material = representativeMaterial(state, blockEntity.blockEntity, loader);
                if (material != null) {
                    Object lodState = approximateShape(state, material);
                    invoke(copy, "setBlockState", blockEntity.x, blockEntity.y, blockEntity.z, lodState, false);
                }
            }
            return copy;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logSnapshotFailure(source.getClass(), failure);
            return copy;
        }
    }

    private static Object approximateShape(Object state, Object material) {
        Method method = SHAPE_FALLBACK.get(state.getClass()).orElse(null);
        if (method == null) return material;
        try {
            return method.invoke(null, state, material);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
            return material;
        }
    }

    public static Object snapshotRawSection(Object source, int sectionX, int sectionY, int sectionZ) {
        if (source == null) return null;
        if (!RoxyCompatConfig.copycatsEnabled()) return cloneSection(source);
        if (!hasCopycatMod(source.getClass().getClassLoader())) return cloneSection(source);
        try {
            ClassLoader loader = source.getClass().getClassLoader();
            Class<?> minecraftType = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object minecraft = minecraftType.getMethod("getInstance").invoke(null);
            if (minecraft == null || !Boolean.TRUE.equals(invoke(minecraft, "isSameThread"))) {
                return cloneSection(source);
            }
            Object level = field(minecraft, "level");
            if (level == null) return cloneSection(source);
            Object chunkSource = invoke(level, "getChunkSource");
            if (!Boolean.TRUE.equals(invoke(chunkSource, "hasChunk", sectionX, sectionZ))) return cloneSection(source);
            Object chunk = invoke(chunkSource, "getChunk", sectionX, sectionZ, false);
            if (chunk == null) return cloneSection(source);
            int index = ((Number) invoke(chunk, "getSectionIndexFromSectionY", sectionY)).intValue();
            Object sections = invoke(chunk, "getSections");
            if (index < 0 || index >= Array.getLength(sections) || Array.get(sections, index) != source) {
                return cloneSection(source);
            }
            return snapshotSection(source, chunk, sectionY);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!rawFailureLogged) {
                rawFailureLogged = true;
                LOGGER.warn("Unable to resolve a live Voxy section for Copycat LoD input", failure);
            }
            return cloneSection(source);
        }
    }

    public static void blockEntityDataUpdated(Object packet) {
        if (!RoxyCompatConfig.copycatsEnabled()) return;
        try {
            ClassLoader loader = packet.getClass().getClassLoader();
            Class<?> minecraftType = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object minecraft = minecraftType.getMethod("getInstance").invoke(null);
            Object level = field(minecraft, "level");
            if (level == null) return;
            Object pos = invoke(packet, "getPos");
            Object state = invoke(level, "getBlockState", pos);
            if (!isPotentialCopycatState(state)) return;
            Object chunkSource = invoke(level, "getChunkSource");
            int chunkX = ((Number) invoke(pos, "getX")).intValue() >> 4;
            int chunkZ = ((Number) invoke(pos, "getZ")).intValue() >> 4;
            if (!Boolean.TRUE.equals(invoke(chunkSource, "hasChunk", chunkX, chunkZ))) return;
            Object blockEntity = invoke(level, "getBlockEntity", pos);
            if (!isCopycat(state, blockEntity)) return;
            queueChunkIngest(level, chunkX, chunkZ);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
        }
    }

    private static List<BlockEntityAt> sectionBlockEntities(Object chunk, int sectionY) throws ReflectiveOperationException {
        IngestSnapshot snapshot = INGEST_SNAPSHOT.get();
        if (snapshot != null && snapshot.chunk == chunk && snapshot.bySection != null) {
            return snapshot.bySection.getOrDefault(sectionY, List.of());
        }
        Object blockEntities = invoke(chunk, "getBlockEntities");
        if (!(blockEntities instanceof Map<?, ?> entities)) return List.of();
        return indexBlockEntities(entities).getOrDefault(sectionY, List.of());
    }

    static Map<Integer, List<BlockEntityAt>> indexBlockEntities(Map<?, ?> entities) throws ReflectiveOperationException {
        Map<Integer, List<BlockEntityAt>> bySection = new HashMap<>();
        for (Map.Entry<?, ?> entry : entities.entrySet()) {
            Object pos = entry.getKey();
            int blockY = ((Number) invoke(pos, "getY")).intValue();
            bySection.computeIfAbsent(blockY >> 4, ignored -> new ArrayList<>()).add(new BlockEntityAt(
                    ((Number) invoke(pos, "getX")).intValue() & 15,
                    blockY & 15,
                    ((Number) invoke(pos, "getZ")).intValue() & 15,
                    entry.getValue()));
        }
        return bySection;
    }

    static boolean queueChunkIngest(Object level, int chunkX, int chunkZ) {
        long chunkPos = ((long) chunkZ << 32) | (chunkX & 0xffffffffL);
        synchronized (PENDING_INGESTS) {
            return PENDING_INGESTS.computeIfAbsent(level, ignored -> new HashSet<>()).add(chunkPos);
        }
    }

    static Map<Object, Set<Long>> drainPendingIngests() {
        synchronized (PENDING_INGESTS) {
            Map<Object, Set<Long>> pending = new IdentityHashMap<>(PENDING_INGESTS);
            PENDING_INGESTS.clear();
            return pending;
        }
    }

    private static void ingestChunk(Object level, int chunkX, int chunkZ, ClassLoader loader) {
        try {
            Object chunkSource = invoke(level, "getChunkSource");
            if (!Boolean.TRUE.equals(invoke(chunkSource, "hasChunk", chunkX, chunkZ))) return;
            Object chunk = invoke(chunkSource, "getChunk", chunkX, chunkZ, false);
            if (chunk == null) return;
            Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", false, loader);
            invokeStatic(service, "tryAutoIngestChunk", chunk);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
        }
    }

    private static void logIngestFailure(Throwable failure) {
        if (!ingestFailureLogged) {
            ingestFailureLogged = true;
            LOGGER.warn("Unable to refresh Copycat LoD material after a block entity update", failure);
        }
    }

    static Object cloneSection(Object source) {
        if (source == null) return null;
        try {
            Method snapshot = SECTION_SNAPSHOT.get(source.getClass()).orElse(null);
            return snapshot == null ? null : snapshot.invoke(null, source);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logSnapshotFailure(source.getClass(), failure);
            return null;
        }
    }

    private static void logSnapshotFailure(Class<?> sourceType, Throwable failure) {
        if (SNAPSHOT_FAILURE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("Unable to snapshot Voxy's input section ({}); skipping ingestion",
                    sourceType.getName(), failure);
        }
    }

    private static boolean hasCopycatMod(ClassLoader loader) {
        if (!modCheckComplete) {
            synchronized (RoxyCopycatsCompat.class) {
                if (!modCheckComplete) {
                    try {
                        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList", false, loader);
                        Object mods = type.getMethod("get").invoke(null);
                        Object create = mods == null ? null : invoke(mods, "getModFileById", "create");
                        Object copycats = mods == null ? null : invoke(mods, "getModFileById", "copycats");
                        copycatModLoaded = create != null || copycats != null;
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                        copycatModLoaded = false;
                    }
                    modCheckComplete = true;
                }
            }
        }
        return copycatModLoaded;
    }

    private static boolean isPotentialCopycatState(Object state) {
        try {
            String key = registryKey(state);
            if (key.contains("copycat")) return true;
            Object block = invoke(state, "getBlock");
            for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
                for (Class<?> iface : type.getInterfaces()) {
                    String name = iface.getName();
                    if (name.equals("com.copycatsplus.copycats.foundation.copycat.ICopycatBlock")
                            || name.equals("com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlock")
                            || name.equals("com.simibubi.create.content.decoration.copycat.ICopycatBlock")) return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isCopycat(Object state, Object blockEntity) {
        if (isPotentialCopycatState(state)) return true;
        if (blockEntity == null) return false;
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                String name = iface.getName();
                if (name.equals("com.copycatsplus.copycats.foundation.copycat.ICopycatBlockEntity")
                        || name.equals("com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity")
                        || name.equals("com.simibubi.create.content.decoration.copycat.ICopycatBlockEntity")) return true;
            }
        }
        try {
            String key = registryKey(state);
            return (key.startsWith("create:") || key.startsWith("copycats:"))
                    && findMethod(blockEntity.getClass(), "getMaterial", 0) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Object representativeMaterial(Object copycatState, Object blockEntity, ClassLoader loader) {
        if (blockEntity == null) return null;
        try {
            Method storageMethod = findMethod(blockEntity.getClass(), "getMaterialItemStorage", 0);
            if (storageMethod != null) {
                Object storage = storageMethod.invoke(blockEntity);
                Method mapMethod = storage == null ? null : findMethod(storage.getClass(), "getMaterialMap", 0);
                Object map = mapMethod == null ? null : mapMethod.invoke(storage);
                if (map instanceof Map<?, ?> materials) {
                    Object block = invoke(copycatState, "getBlock");
                    Method partExists = findCompatibleMethod(block.getClass(), "partExists",
                            new Object[]{copycatState, ""});
                    List<Object> candidates = new ArrayList<>(materials.size());
                    for (Map.Entry<?, ?> entry : materials.entrySet()) {
                        if (partExists != null && entry.getKey() instanceof String part
                                && !Boolean.TRUE.equals(partExists.invoke(block, copycatState, part))) continue;
                        candidates.add(entry.getValue());
                    }
                    Object selected = CopycatMaterialPolicy.representative(candidates,
                            value -> usable(value, loader), value -> stableKey(value, loader));
                    if (selected != null || partExists != null) return selected;
                }
            }
            Method single = findMethod(blockEntity.getClass(), "getMaterial", 0);
            Object material = single == null ? null : single.invoke(blockEntity);
            return usable(material, loader) ? material : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean usable(Object state, ClassLoader loader) {
        if (state == null) return false;
        try {
            if (Boolean.TRUE.equals(invoke(state, "isAir"))) return false;
            String key = registryKey(state, loader);
            return !key.equals("create:copycat_base") && !key.equals("copycats:copycat_base");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String stableKey(Object state, ClassLoader loader) {
        try {
            return registryKey(state, loader) + '|' + state;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return String.valueOf(state);
        }
    }

    private static String registryKey(Object state) throws ReflectiveOperationException {
        return registryKey(state, state.getClass().getClassLoader());
    }

    private static String registryKey(Object state, ClassLoader loader) throws ReflectiveOperationException {
        Object block = invoke(state, "getBlock");
        Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, loader);
        Object blocks = field(registries, "BLOCK");
        return String.valueOf(invoke(blocks, "getKey", block));
    }

    private static Object invokeStatic(Class<?> type, String name, Object... arguments) throws ReflectiveOperationException {
        Method method = findCompatibleMethod(type, name, arguments);
        if (method == null) throw new NoSuchMethodException(type.getName() + '.' + name);
        return method.invoke(null, arguments);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        Method method = findCompatibleMethod(target.getClass(), name, arguments);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + '.' + name);
        return method.invoke(target, arguments);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] arguments) {
        Class<?>[] argumentTypes = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            argumentTypes[i] = arguments[i] == null ? Void.class : arguments[i].getClass();
        }
        MethodKey key = new MethodKey(type, name, List.of(argumentTypes));
        return METHODS.computeIfAbsent(key, ignored -> Optional.ofNullable(findCompatibleMethodUncached(type, name, arguments)))
                .orElse(null);
    }

    private static Method findCompatibleMethodUncached(Class<?> type, String name, Object[] arguments) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (arguments[i] != null && !box(parameters[i]).isInstance(arguments[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return method;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
            }
        }
        return null;
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class<?> value ? value : target.getClass();
        Field field = type.getField(name);
        return field.get(target instanceof Class<?> ? null : target);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private record MethodKey(Class<?> type, String name, List<Class<?>> parameterTypes) {
    }

    record BlockEntityAt(int x, int y, int z, Object blockEntity) {
    }

    private record IngestSnapshot(Object chunk, Map<Integer, List<BlockEntityAt>> bySection) {
    }
}
