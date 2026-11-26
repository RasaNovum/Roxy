package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.core.RoxyPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class RoxyCopycatsCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final Set<String> COPYCAT_BLOCK_INTERFACES = Set.of(
            "com.copycatsplus.copycats.foundation.copycat.ICopycatBlock",
            "com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlock",
            "com.simibubi.create.content.decoration.copycat.ICopycatBlock"
    );
    private static final Set<String> COPYCAT_ENTITY_INTERFACES = Set.of(
            "com.copycatsplus.copycats.foundation.copycat.ICopycatBlockEntity",
            "com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity",
            "com.simibubi.create.content.decoration.copycat.ICopycatBlockEntity"
    );
    private static final ConcurrentHashMap<MethodKey, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ArityMethodKey, Optional<Method>> ARITY_METHODS = new ConcurrentHashMap<>();
    private static final ThreadLocal<IngestSnapshot> INGEST_SNAPSHOT = new ThreadLocal<>();
    private static final Map<Object, Set<Long>> PENDING_INGESTS = new IdentityHashMap<>();
    private static final AtomicBoolean SNAPSHOT_FAILURE_LOGGED = new AtomicBoolean();
    private static volatile HostAdapter hostAdapter;
    private static volatile BooleanSupplier enabledSupplier = () -> true;
    private static volatile Method tryAutoIngestChunk;
    private static volatile boolean ingestMethodChecked;
    private static volatile boolean ingestFailureLogged;
    private static volatile boolean rawFailureLogged;

    private RoxyCopycatsCompat() {
    }

    public static void registerHost(HostAdapter adapter) {
        hostAdapter = adapter;
    }

    public static void setEnabledSupplier(BooleanSupplier supplier) {
        enabledSupplier = supplier == null ? () -> true : supplier;
    }

    public static void reset() {
        INGEST_SNAPSHOT.remove();
        synchronized (PENDING_INGESTS) {
            PENDING_INGESTS.clear();
        }
    }

    public static boolean isModLoaded() {
        return enabledSupplier.getAsBoolean()
                && (RoxyPlatform.isModLoaded("create") || RoxyPlatform.isModLoaded("copycats"));
    }

    public static void beginChunkIngest(Object chunk) {
        INGEST_SNAPSHOT.remove();
        HostAdapter host = hostAdapter;
        if (host == null || chunk == null || !isModLoaded()) return;
        try {
            INGEST_SNAPSHOT.set(new IngestSnapshot(chunk, host.indexBlockEntities(chunk)));
        } catch (RuntimeException | LinkageError ignored) {
            INGEST_SNAPSHOT.set(new IngestSnapshot(chunk, null));
        }
    }

    public static void endChunkIngest() {
        INGEST_SNAPSHOT.remove();
    }

    public static void tick() {
        HostAdapter host = hostAdapter;
        if (host == null || !isModLoaded()) return;
        Map<Object, Set<Long>> pending = drainPendingIngests();
        if (pending.isEmpty()) return;
        try {
            Object currentLevel = host.currentClientLevel();
            if (currentLevel == null) return;
            for (Map.Entry<Object, Set<Long>> entry : pending.entrySet()) {
                if (entry.getKey() != currentLevel) continue;
                for (long chunkPos : entry.getValue()) {
                    Object chunk = host.loadedChunk(currentLevel, (int) chunkPos, (int) (chunkPos >> 32));
                    if (chunk != null) ingestChunk(chunk);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
        }
    }

    public static Object snapshotSection(Object source, Object chunk, int sectionY) {
        HostAdapter host = hostAdapter;
        if (host == null) return source;
        Object copy = cloneSection(source, host);
        if (copy == null || chunk == null || !isModLoaded()) return copy;
        try {
            for (BlockEntityAt blockEntity : sectionBlockEntities(chunk, sectionY, host)) {
                Object state = host.getBlockState(copy, blockEntity.x, blockEntity.y, blockEntity.z);
                if (!isPotentialCopycatState(state, host)) continue;
                if (!isCopycat(state, blockEntity.blockEntity, host)) continue;
                Object material = representativeMaterial(state, blockEntity.blockEntity, host);
                if (material != null) {
                    Object lodState = host.approximateShape(state, material);
                    if (lodState != null) {
                        host.setBlockState(copy, blockEntity.x, blockEntity.y, blockEntity.z, lodState);
                    }
                }
            }
            return copy;
        } catch (RuntimeException | LinkageError failure) {
            logSnapshotFailure(source.getClass(), failure);
            return copy;
        }
    }

    public static Object snapshotRawSection(Object source, int sectionX, int sectionY, int sectionZ) {
        if (source == null) return null;
        HostAdapter host = hostAdapter;
        if (host == null) return source;
        try {
            Object chunk = host.liveChunkForSection(source, sectionX, sectionY, sectionZ);
            if (chunk == null) return source;
            return snapshotSection(source, chunk, sectionY);
        } catch (RuntimeException | LinkageError failure) {
            if (!rawFailureLogged) {
                rawFailureLogged = true;
                LOGGER.warn("Unable to resolve a live Voxy section for Copycat LoD input", failure);
            }
            return source;
        }
    }

    public static void blockEntityDataUpdated(Object packet) {
        HostAdapter host = hostAdapter;
        if (host == null || packet == null || !isModLoaded()) return;
        try {
            BlockEntityUpdate update = host.inspectBlockEntityUpdate(packet);
            if (update == null) return;
            if (!isPotentialCopycatState(update.state, host)) return;
            if (!isCopycat(update.state, update.blockEntity, host)) return;
            queueChunkIngest(update.level, update.chunkX, update.chunkZ);
        } catch (RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
        }
    }

    private static List<BlockEntityAt> sectionBlockEntities(Object chunk, int sectionY, HostAdapter host) {
        IngestSnapshot snapshot = INGEST_SNAPSHOT.get();
        if (snapshot != null && snapshot.chunk == chunk && snapshot.bySection != null) {
            return snapshot.bySection.getOrDefault(sectionY, List.of());
        }
        Map<Integer, List<BlockEntityAt>> indexed = host.indexBlockEntities(chunk);
        return indexed == null ? List.of() : indexed.getOrDefault(sectionY, List.of());
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

    private static void ingestChunk(Object chunk) {
        try {
            Method ingest = resolveTryAutoIngestChunk(chunk.getClass().getClassLoader());
            if (ingest != null) ingest.invoke(null, chunk);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logIngestFailure(failure);
        }
    }

    private static Method resolveTryAutoIngestChunk(ClassLoader loader) {
        if (ingestMethodChecked) return tryAutoIngestChunk;
        synchronized (RoxyCopycatsCompat.class) {
            if (ingestMethodChecked) return tryAutoIngestChunk;
            Method resolved = null;
            try {
                ClassLoader effective = loader != null ? loader : RoxyCopycatsCompat.class.getClassLoader();
                Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", false, effective);
                for (Method candidate : service.getMethods()) {
                    if (candidate.getName().equals("tryAutoIngestChunk") && candidate.getParameterCount() == 1) {
                        candidate.trySetAccessible();
                        resolved = candidate;
                        break;
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                logIngestFailure(failure);
            }
            tryAutoIngestChunk = resolved;
            ingestMethodChecked = true;
            return resolved;
        }
    }

    private static void logIngestFailure(Throwable failure) {
        if (!ingestFailureLogged) {
            ingestFailureLogged = true;
            LOGGER.warn("Unable to refresh Copycat LoD material after a block entity update", failure);
        }
    }

    private static Object cloneSection(Object source, HostAdapter host) {
        if (source == null) return null;
        try {
            return host.cloneSection(source);
        } catch (RuntimeException | LinkageError failure) {
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

    private static boolean implementsAnyInterface(Class<?> type, Set<String> targets) {
        if (type == null) return false;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (matchesInterfaceHierarchy(current.getInterfaces(), targets)) return true;
        }
        return false;
    }

    private static boolean matchesInterfaceHierarchy(Class<?>[] interfaces, Set<String> targets) {
        for (Class<?> iface : interfaces) {
            if (targets.contains(iface.getName())) return true;
            if (matchesInterfaceHierarchy(iface.getInterfaces(), targets)) return true;
        }
        return false;
    }

    private static boolean isPotentialCopycatState(Object state, HostAdapter host) {
        if (state == null) return false;
        try {
            String key = host.registryKey(state);
            if (key != null && key.contains("copycat")) return true;
            Object block = host.getBlock(state);
            return block != null && implementsAnyInterface(block.getClass(), COPYCAT_BLOCK_INTERFACES);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isCopycat(Object state, Object blockEntity, HostAdapter host) {
        if (isPotentialCopycatState(state, host)) return true;
        if (blockEntity == null) return false;
        if (implementsAnyInterface(blockEntity.getClass(), COPYCAT_ENTITY_INTERFACES)) return true;
        try {
            String key = host.registryKey(state);
            return key != null
                    && (key.startsWith("create:") || key.startsWith("copycats:"))
                    && findMethod(blockEntity.getClass(), "getMaterial", 0) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Object representativeMaterial(Object copycatState, Object blockEntity, HostAdapter host) {
        if (blockEntity == null) return null;
        try {
            Method storageMethod = findMethod(blockEntity.getClass(), "getMaterialItemStorage", 0);
            if (storageMethod != null) {
                Object storage = storageMethod.invoke(blockEntity);
                Method mapMethod = storage == null ? null : findMethod(storage.getClass(), "getMaterialMap", 0);
                Object map = mapMethod == null ? null : mapMethod.invoke(storage);
                if (map instanceof Map<?, ?> materials) {
                    Object block = host.getBlock(copycatState);
                    Method partExists = block == null ? null
                            : findCompatibleMethod(block.getClass(), "partExists", new Object[]{copycatState, ""});
                    List<Object> candidates = new ArrayList<>(materials.size());
                    for (Map.Entry<?, ?> entry : materials.entrySet()) {
                        if (partExists != null && entry.getKey() instanceof String part
                                && !Boolean.TRUE.equals(partExists.invoke(block, copycatState, part))) continue;
                        Object value = unwrapMaterialState(entry.getValue(), host);
                        if (value != null) candidates.add(value);
                    }
                    Object selected = CopycatMaterialPolicy.representative(
                            candidates,
                            value -> usable(value, host),
                            value -> stableKey(value, host)
                    );
                    if (selected != null || partExists != null) return selected;
                }
            }
            Method single = findMethod(blockEntity.getClass(), "getMaterial", 0);
            Object material = single == null ? null : unwrapMaterialState(single.invoke(blockEntity), host);
            return usable(material, host) ? material : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object unwrapMaterialState(Object value, HostAdapter host) throws ReflectiveOperationException {
        if (value == null || host.isBlockState(value)) return value;
        Method material = findMethod(value.getClass(), "material", 0);
        if (material != null) {
            Object unwrapped = material.invoke(value);
            if (host.isBlockState(unwrapped)) return unwrapped;
        }
        return null;
    }

    private static boolean usable(Object state, HostAdapter host) {
        if (state == null || !host.isBlockState(state)) return false;
        try {
            if (host.isAir(state)) return false;
            String key = host.registryKey(state);
            return key != null && !key.equals("create:copycat_base") && !key.equals("copycats:copycat_base");
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String stableKey(Object state, HostAdapter host) {
        try {
            return host.registryKey(state) + '|' + state;
        } catch (RuntimeException | LinkageError ignored) {
            return String.valueOf(state);
        }
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
                if (matches) {
                    method.trySetAccessible();
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        return ARITY_METHODS.computeIfAbsent(new ArityMethodKey(type, name, parameterCount), key -> {
            for (Class<?> current = key.type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getMethods()) {
                    if (method.getName().equals(key.name) && method.getParameterCount() == key.parameterCount) {
                        method.trySetAccessible();
                        return Optional.of(method);
                    }
                }
            }
            return Optional.empty();
        }).orElse(null);
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

    public interface HostAdapter {
        Object cloneSection(Object section);

        Object getBlockState(Object section, int x, int y, int z);

        void setBlockState(Object section, int x, int y, int z, Object state);

        Object approximateShape(Object copycatState, Object materialState);

        Map<Integer, List<BlockEntityAt>> indexBlockEntities(Object chunk);

        Object currentClientLevel();

        Object loadedChunk(Object level, int chunkX, int chunkZ);

        Object liveChunkForSection(Object section, int sectionX, int sectionY, int sectionZ);

        BlockEntityUpdate inspectBlockEntityUpdate(Object packet);

        boolean isBlockState(Object value);

        boolean isAir(Object state);

        Object getBlock(Object state);

        String registryKey(Object state);
    }

    public record BlockEntityAt(int x, int y, int z, Object blockEntity) {
    }

    public record BlockEntityUpdate(Object level, int chunkX, int chunkZ, Object state, Object blockEntity) {
    }

    private record ArityMethodKey(Class<?> type, String name, int parameterCount) {
    }

    private record MethodKey(Class<?> type, String name, List<Class<?>> parameterTypes) {
    }

    private record IngestSnapshot(Object chunk, Map<Integer, List<BlockEntityAt>> bySection) {
    }
}
