package net.rasanovum.roxy.patch;

import net.rasanovum.roxy.render.RoxyVoxyWorkDrain;
import net.rasanovum.roxy.shader.RoxyVoxyRequestShader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class RoxyVoxyRequestPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final int REQUEST_ID_MASK = (1 << 19) - 1;
    private static final int NODE_ID_MASK = (1 << 24) - 1;
    private static final int NODE_TYPE_MASK = 0b11 << 30;
    private static final int NODE_TYPE_REQUEST = 0b10 << 30;
    private static final int REQUEST_TYPE_MASK = 1 << 29;
    private static final int REQUEST_TYPE_CHILD = 1 << 29;
    private static final int DEFAULT_UPDATE_FLAGS = 3;
    private static final int SWEEP_SLICE = 8192;
    private static final long SWEEP_INTERVAL_NANOS = 2_000_000_000L;
    private static final ConcurrentMap<Class<?>, NodeAccess> NODE_ACCESS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, AsyncAccess> ASYNC_ACCESS = new ConcurrentHashMap<>();
    private static final ClassValue<MethodHandle> MAP_PUT = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveHandle(type, "put", MethodType.methodType(int.class, Object.class, long.class, int.class), long.class, int.class);
        }
    };
    private static final ClassValue<MethodHandle> WATCHER_GET = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveHandle(type, "get", MethodType.methodType(int.class, Object.class, long.class), long.class);
        }
    };
    private static final ClassValue<MethodHandle> WATCHER_WATCH = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveHandle(type, "watch", MethodType.methodType(boolean.class, Object.class, long.class, int.class), long.class, int.class);
        }
    };
    private static final ClassValue<MethodHandle> REQUEST_POSITION = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveHandle(type, "getPosition", MethodType.methodType(long.class, Object.class));
        }
    };
    private static final ClassValue<MethodHandle> PROCESS_REQUEST = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveHandle(type, "processRequest", MethodType.methodType(void.class, Object.class, long.class), long.class);
        }
    };
    private static final ConcurrentMap<Object, ConcurrentMap<Long, Long>> PENDING = new ConcurrentHashMap<>();
    private static final Map<Object, Long> ASYNC_GENERATIONS = new WeakHashMap<>();
    private static final Object MANAGER_LOCK = new Object();
    private static final AtomicLong DEFERRED = new AtomicLong();
    private static final AtomicLong RETRIED = new AtomicLong();
    private static final AtomicLong REQUEST_GENERATION = new AtomicLong();
    private static final ThreadLocal<LeafRequest> LEAF_REQUEST = new ThreadLocal<>();
    private static volatile WatchdogState watchdogState;
    private static volatile Object activeAsyncNodeManager;
    private static volatile boolean managerRegistrationRequired = true;
    private static volatile boolean accessFailed;
    private static volatile boolean cleanupFailed;
    private static volatile boolean retryFailed;

    private static final ScheduledExecutorService WATCHDOG_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new WatchdogThreadFactory());

    static {
        WATCHDOG_EXECUTOR.scheduleWithFixedDelay(
                RoxyVoxyRequestPatch::wakeWatchdog,
                2L,
                2L,
                TimeUnit.SECONDS
        );
    }

    private RoxyVoxyRequestPatch() {
    }

    public static void reset() {
        synchronized (MANAGER_LOCK) {
            REQUEST_GENERATION.incrementAndGet();
            watchdogState = null;
            activeAsyncNodeManager = null;
            managerRegistrationRequired = true;
            PENDING.clear();
        }
        DEFERRED.set(0L);
        RETRIED.set(0L);
        LEAF_REQUEST.remove();
        accessFailed = false;
        cleanupFailed = false;
        retryFailed = false;
    }

    public static void registerAsyncNodeManager(Object asyncNodeManager) {
        if (asyncNodeManager == null) return;
        synchronized (MANAGER_LOCK) {
            if (activeAsyncNodeManager != asyncNodeManager) {
                PENDING.clear();
            }
            long generation = REQUEST_GENERATION.get();
            ASYNC_GENERATIONS.put(asyncNodeManager, generation);
            activeAsyncNodeManager = asyncNodeManager;
            managerRegistrationRequired = false;
        }
    }

    public static void defer(Object nodeManager, long position) {
        if (nodeManager == null) return;
        synchronized (MANAGER_LOCK) {
            if (managerRegistrationRequired) return;
            WatchdogState state = watchdogState;
            if (state != null && state.nodeManager != nodeManager) return;
            ConcurrentMap<Long, Long> pending = PENDING.computeIfAbsent(
                    nodeManager,
                    ignored -> new ConcurrentHashMap<>()
            );
            long generation = REQUEST_GENERATION.get();
            if (pending.putIfAbsent(position, generation) == null) {
                long count = DEFERRED.incrementAndGet();
                if (count <= 8 || count % 100 == 0) {
                    LOGGER.debug(
                            "Deferred Voxy node request at {} while a request was already in flight ({} total)",
                            position,
                            count
                    );
                }
            }
        }
    }

    public static void retry(Object nodeManager, long position) {
        if (nodeManager == null) return;
        long generation;
        synchronized (MANAGER_LOCK) {
            WatchdogState state = watchdogState;
            if (state == null || state.nodeManager != nodeManager) return;
            ConcurrentMap<Long, Long> pending = PENDING.get(nodeManager);
            Long knownGeneration = pending == null ? null : pending.get(position);
            generation = REQUEST_GENERATION.get();
            if (knownGeneration == null || knownGeneration.longValue() != generation) return;
            if (!pending.remove(position, generation)) return;
        }
        try {
            MethodHandle processRequest = PROCESS_REQUEST.get(nodeManager.getClass());
            long count = RETRIED.incrementAndGet();
            if (count <= 8 || count % 100 == 0) {
                LOGGER.debug("Retried deferred Voxy node request at {} ({} total)", position, count);
            }
            processRequest.invokeExact(nodeManager, position);
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            synchronized (MANAGER_LOCK) {
                WatchdogState state = watchdogState;
                if (REQUEST_GENERATION.get() == generation
                        && state != null
                        && state.nodeManager == nodeManager) {
                    PENDING.computeIfAbsent(nodeManager, ignored -> new ConcurrentHashMap<>())
                            .putIfAbsent(position, generation);
                }
            }
            if (!retryFailed) {
                retryFailed = true;
                LOGGER.warn("Unable to retry a deferred Voxy node request", exception);
            }
        }
    }

    public static void processAsync(Object asyncNodeManager) {
        if (asyncNodeManager == null) return;
        RoxyVoxyWorkDrain.endProduction(asyncNodeManager);
        try {
            WatchdogState state;
            long generation;
            boolean initialized = false;
            synchronized (MANAGER_LOCK) {
                if (managerRegistrationRequired) return;
                generation = REQUEST_GENERATION.get();
                Long knownGeneration = ASYNC_GENERATIONS.putIfAbsent(asyncNodeManager, generation);
                if (knownGeneration != null && knownGeneration.longValue() != generation) return;
                Object activeManager = activeAsyncNodeManager;
                if (activeManager != null && activeManager != asyncNodeManager) return;
                if (activeManager == null) activeAsyncNodeManager = asyncNodeManager;
                state = watchdogState;
                if (state == null || state.asyncNodeManager != asyncNodeManager) {
                    AsyncAccess async = asyncAccess(asyncNodeManager);
                    Object nodeManager = async.manager.invokeExact(asyncNodeManager);
                    if (nodeManager == null) return;
                    Thread thread = (Thread) async.thread.invokeExact(asyncNodeManager);
                    state = new WatchdogState(
                            asyncNodeManager,
                            nodeManager,
                            thread,
                            async.nodeAccess,
                            new SweepState()
                    );
                    watchdogState = state;
                    PENDING.keySet().removeIf(key -> key != nodeManager);
                    LOGGER.info("Voxy request-state watchdog active");
                    initialized = true;
                }
            }
            if (generation != REQUEST_GENERATION.get()) return;
            state.sweep.maintenanceRequested = false;
            state.sweep.maintenanceChanged = false;
            if (initialized) state.sweep.maintenanceChanged = replayPending(state, generation);
            long now = System.nanoTime();
            if (now < state.sweep.nextSweepAt) return;
            state.sweep.nextSweepAt = now + SWEEP_INTERVAL_NANOS;
            if (sweep(state, state.sweep)) state.sweep.maintenanceChanged = true;
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logAccessFailure("Voxy request-state watchdog is unavailable", exception);
        }
    }

    private static boolean replayPending(WatchdogState state, long generation) {
        ConcurrentMap<Long, Long> pending = PENDING.get(state.nodeManager);
        if (pending == null) return false;
        boolean changed = false;
        for (Map.Entry<Long, Long> entry : pending.entrySet()) {
            if (entry.getValue() != generation) continue;
            retry(state.nodeManager, entry.getKey());
            changed = true;
        }
        return changed;
    }

    public static int workOrMaintenance(Object manager, int work) {
        WatchdogState state = watchdogState;
        return work == 0 && state != null && state.asyncNodeManager == manager
                && state.sweep.maintenanceRequested ? 1 : work;
    }

    public static boolean shouldPublish(Object manager, int processed) {
        WatchdogState state = watchdogState;
        return processed != 0 || state != null && state.asyncNodeManager == manager
                && state.sweep.maintenanceChanged;
    }

    public static void processGpuRequest(Object nodeManager, long position) {
        if (RoxyVoxyRequestShader.retriesEnabled()) try {
            NodeAccess access = nodeAccess(nodeManager);
            Object map = access.activeSectionMap.invokeExact(nodeManager);
            int encoded = (int) access.mapGet.invokeExact(map, position);
            if (encoded == -1 || isRequestEntry(encoded)) return;
            int nodeId = encoded & NODE_ID_MASK;
            Object nodeData = access.nodeData.invokeExact(nodeManager);
            if ((boolean) access.nodeIsRequestInFlight.invokeExact(nodeData, nodeId)
                    && repairInFlightRequest(nodeManager, position, nodeId)) return;
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logAccessFailure("Unable to inspect a repeated Voxy GPU request", exception);
        }
        try {
            PROCESS_REQUEST.get(nodeManager.getClass()).invokeExact(nodeManager, position);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to process a Voxy GPU request", exception);
        }
    }

    public static boolean repairInFlightRequest(Object nodeManager, long position, int nodeId) {
        try {
            NodeAccess access = nodeAccess(nodeManager);
            Object nodeData = access.nodeData.invokeExact(nodeManager);
            int requestId = (int) access.nodeGetRequest.invokeExact(nodeData, nodeId);
            if (requestId == REQUEST_ID_MASK) return false;
            Object childRequests = access.childRequests.invokeExact(nodeManager);
            Object request = access.childGetOrNull.invokeExact(childRequests, requestId);
            if (request != null && isLiveRequest(nodeManager, access, position, requestId, request)) return true;
            clearStaleRequest(nodeManager, access, position, nodeId, requestId, true);
            return false;
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logCleanupFailure("Unable to repair a stranded Voxy request", exception);
            return true;
        }
    }

    public static boolean isRequestEntry(int encoded) {
        return (encoded & NODE_TYPE_MASK) == NODE_TYPE_REQUEST;
    }

    public static void verifyNodeState(Object nodeManager, long position) {
        try {
            NodeAccess access = nodeAccess(nodeManager);
            Object map = access.activeSectionMap.invokeExact(nodeManager);
            int encoded = (int) access.mapGet.invokeExact(map, position);
            if (encoded == -1 || (encoded & NODE_TYPE_MASK) == NODE_TYPE_REQUEST) return;
            int nodeId = encoded & NODE_ID_MASK;
            Object nodeData = access.nodeData.invokeExact(nodeManager);
            boolean inFlight = (boolean) access.nodeIsRequestInFlight.invokeExact(nodeData, nodeId);
            if (inFlight) repairInFlightRequest(nodeManager, position, nodeId);
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logAccessFailure("Unable to verify Voxy request state", exception);
        }
    }

    public static void beginLeafRequest(Object nodeManager, long position, int nodeId, int requestId) {
        LEAF_REQUEST.set(new LeafRequest(nodeManager, position, nodeId, requestId));
    }

    public static void completeLeafRequest(Object nodeManager) {
        LeafRequest request = LEAF_REQUEST.get();
        if (request != null && request.nodeManager == nodeManager) LEAF_REQUEST.remove();
    }

    public static void abortLeafRequest(Object nodeManager, int nodeId, Throwable failure) {
        try {
            LeafRequest request = LEAF_REQUEST.get();
            NodeAccess access = nodeAccess(nodeManager);
            if (request != null && request.nodeManager == nodeManager && request.nodeId == nodeId) {
                clearPending(nodeManager, request.position);
                cleanupRequest(nodeManager, access, request.position, request.requestId, false);
            }
            Object nodeData = access.nodeData.invokeExact(nodeManager);
            access.nodeSetRequest.invokeExact(nodeData, nodeId, REQUEST_ID_MASK);
            access.invalidateNode.invokeExact(nodeManager, nodeId);
            LOGGER.warn("Recovered a failed Voxy leaf request and left it eligible for retry", failure);
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logCleanupFailure("Unable to roll back a failed Voxy leaf request", exception);
        } finally {
            LEAF_REQUEST.remove();
        }
    }

    public static int putChildMapping(Object map, long position, int value) {
        try {
            MethodHandle put = MAP_PUT.get(map.getClass());
            int previous = (int) put.invokeExact(map, position, value);
            if (previous != -1) {
                int ignored = (int) put.invokeExact(map, position, previous);
                if (previous == value) return -1;
            }
            return previous;
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logAccessFailure("Unable to preserve a Voxy active-map collision", exception);
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Unable to preserve a Voxy active-map collision", exception);
        }
    }

    public static boolean watchChild(Object watcher, long position, int flags) {
        try {
            MethodHandle get = WATCHER_GET.get(watcher.getClass());
            int current = (int) get.invokeExact(watcher, position);
            if ((current & flags) == flags) return true;
            MethodHandle watch = WATCHER_WATCH.get(watcher.getClass());
            return (boolean) watch.invokeExact(watcher, position, flags);
        } catch (Throwable exception) {
            rethrowNonLinkageError(exception);
            logAccessFailure("Unable to update a Voxy section watcher", exception);
            return false;
        }
    }

    private static boolean sweep(WatchdogState target, SweepState state) throws Throwable {
        if (watchdogState != target) return false;
        Object nodeManager = target.nodeManager;
        NodeAccess access = target.nodeAccess;
        Object nodeData = access.nodeData.invokeExact(nodeManager);
        int end = (int) access.nodeEnd.invokeExact(nodeData);
        if (end <= 0) {
            state.cursor = 0;
            return false;
        }

        Object childRequests = access.childRequests.invokeExact(nodeManager);
        int repaired = 0;
        for (int scanned = 0; scanned < Math.min(end, SWEEP_SLICE); scanned++) {
            if (watchdogState != target) return repaired > 0;
            if (state.cursor >= end) state.cursor = 0;
            int nodeId = state.cursor++;
            if (!(boolean) access.nodeExists.invokeExact(nodeData, nodeId)) continue;
            if (!(boolean) access.nodeIsRequestInFlight.invokeExact(nodeData, nodeId)) continue;
            long position = (long) access.nodePosition.invokeExact(nodeData, nodeId);
            if (position == -1L) continue;
            int requestId = (int) access.nodeGetRequest.invokeExact(nodeData, nodeId);
            Object request = access.childGetOrNull.invokeExact(childRequests, requestId);
            if (request != null && isLiveRequest(nodeManager, access, position, requestId, request)) continue;

            try {
                state.maintenanceChanged = true;
                clearStaleRequest(nodeManager, access, position, nodeId, requestId, true);
                repaired++;
                access.processRequest.invokeExact(nodeManager, position);
            } catch (Throwable exception) {
                rethrowNonLinkageError(exception);
                logCleanupFailure("Voxy request-state watchdog could not repair a request", exception);
            }
        }
        if (repaired > 0) {
            LOGGER.info("Voxy request-state watchdog repaired {} stale request(s)", repaired);
        }
        return repaired > 0;
    }

    private static void clearStaleRequest(
            Object nodeManager,
            NodeAccess access,
            long position,
            int nodeId,
            int requestId,
            boolean decrementRequestCount
    ) throws Throwable {
        clearPending(nodeManager, position);
        Object childRequests = access.childRequests.invokeExact(nodeManager);
        Object request = requestId == REQUEST_ID_MASK
                ? null
                : access.childGetOrNull.invokeExact(childRequests, requestId);
        boolean sameOwner = request == null || requestPosition(request) == position;
        if (sameOwner) cleanupRequest(nodeManager, access, position, requestId, decrementRequestCount);
        Object nodeData = access.nodeData.invokeExact(nodeManager);
        access.nodeSetRequest.invokeExact(nodeData, nodeId, REQUEST_ID_MASK);
        access.invalidateNode.invokeExact(nodeManager, nodeId);
    }

    private static void clearPending(Object nodeManager, long position) {
        ConcurrentMap<Long, Long> pending = PENDING.get(nodeManager);
        if (pending == null) return;
        pending.remove(position);
    }

    private static void cleanupRequest(
            Object nodeManager,
            NodeAccess access,
            long position,
            int requestId,
            boolean decrementRequestCount
    ) throws Throwable {
        if (requestId != REQUEST_ID_MASK) {
            Object map = access.activeSectionMap.invokeExact(nodeManager);
            Object watcher = access.watcher.invokeExact(nodeManager);
            for (int child = 0; child < 8; child++) {
                long childPosition = (long) access.makeChildPos.invokeExact(position, child);
                int encoded = (int) access.mapGet.invokeExact(map, childPosition);
                if ((encoded & NODE_TYPE_MASK) != NODE_TYPE_REQUEST
                        || (encoded & REQUEST_TYPE_MASK) != REQUEST_TYPE_CHILD
                        || (encoded & NODE_ID_MASK) != requestId) continue;
                access.mapRemove.invokeExact(map, childPosition);
                access.watcherUnwatch.invokeExact(watcher, childPosition, DEFAULT_UPDATE_FLAGS);
            }
            Object childRequests = access.childRequests.invokeExact(nodeManager);
            Object childRequest = access.childGetOrNull.invokeExact(childRequests, requestId);
            if (childRequest != null && requestPosition(childRequest) == position) {
                access.childRelease.invokeExact(childRequests, requestId);
                if (decrementRequestCount) {
                    int count = (int) access.getActiveNodeRequestCount.invokeExact(nodeManager);
                    if (count > 0) access.setActiveNodeRequestCount.invokeExact(nodeManager, count - 1);
                }
            }
        }
    }

    private static long requestPosition(Object request) throws Throwable {
        return (long) REQUEST_POSITION.get(request.getClass()).invokeExact(request);
    }

    private static boolean isLiveRequest(
            Object nodeManager,
            NodeAccess access,
            long position,
            int requestId,
            Object request
    ) throws Throwable {
        return requestPosition(request) == position;
    }

    private static NodeAccess nodeAccess(Object nodeManager) throws ReflectiveOperationException {
        NodeAccess cached = NODE_ACCESS.get(nodeManager.getClass());
        if (cached != null) return cached;
        NodeAccess resolved = NodeAccess.resolve(nodeManager.getClass());
        NodeAccess previous = NODE_ACCESS.putIfAbsent(nodeManager.getClass(), resolved);
        return previous == null ? resolved : previous;
    }

    private static AsyncAccess asyncAccess(Object asyncNodeManager) throws ReflectiveOperationException {
        AsyncAccess cached = ASYNC_ACCESS.get(asyncNodeManager.getClass());
        if (cached != null) return cached;
        AsyncAccess resolved = AsyncAccess.resolve(asyncNodeManager.getClass());
        AsyncAccess previous = ASYNC_ACCESS.putIfAbsent(asyncNodeManager.getClass(), resolved);
        return previous == null ? resolved : previous;
    }

    private static MethodHandle resolveHandle(
            Class<?> type,
            String name,
            MethodType adaptedType,
            Class<?>... parameterTypes
    ) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(adaptedType);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static MethodHandle unreflectMethod(Method method, MethodType adaptedType) throws IllegalAccessException {
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method).asType(adaptedType);
    }

    private static MethodHandle unreflectGetter(Field field, MethodType adaptedType) throws IllegalAccessException {
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(field).asType(adaptedType);
    }

    private static MethodHandle unreflectSetter(Field field, MethodType adaptedType) throws IllegalAccessException {
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectSetter(field).asType(adaptedType);
    }

    private static void rethrowNonLinkageError(Throwable throwable) {
        if (throwable instanceof Error error && !(error instanceof LinkageError)) {
            throw error;
        }
    }

    private static void wakeWatchdog() {
        WatchdogState state = watchdogState;
        if (state != null) {
            state.sweep.maintenanceRequested = true;
            LockSupport.unpark(state.thread);
        }
    }

    private static void logAccessFailure(String message, Throwable exception) {
        if (!accessFailed) {
            accessFailed = true;
            LOGGER.warn(message, exception);
        }
    }

    private static void logCleanupFailure(String message, Throwable exception) {
        if (!cleanupFailed) {
            cleanupFailed = true;
            LOGGER.error(message, exception);
        }
    }

    private record LeafRequest(Object nodeManager, long position, int nodeId, int requestId) {
    }

    private static final class SweepState {
        private volatile long nextSweepAt;
        private volatile int cursor;
        private volatile boolean maintenanceRequested;
        private boolean maintenanceChanged;
    }

    private record WatchdogState(
            Object asyncNodeManager,
            Object nodeManager,
            Thread thread,
            NodeAccess nodeAccess,
            SweepState sweep
    ) {
    }

    private record AsyncAccess(MethodHandle thread, MethodHandle manager, NodeAccess nodeAccess) {
        private static AsyncAccess resolve(Class<?> asyncNodeManager) throws ReflectiveOperationException {
            Field thread = asyncNodeManager.getDeclaredField("thread");
            Field manager = asyncNodeManager.getDeclaredField("manager");
            return new AsyncAccess(
                    unreflectGetter(thread, MethodType.methodType(Thread.class, Object.class)),
                    unreflectGetter(manager, MethodType.methodType(Object.class, Object.class)),
                    NodeAccess.resolve(manager.getType())
            );
        }
    }

    private record NodeAccess(
            MethodHandle activeSectionMap,
            MethodHandle nodeData,
            MethodHandle childRequests,
            MethodHandle watcher,
            MethodHandle getActiveNodeRequestCount,
            MethodHandle setActiveNodeRequestCount,
            MethodHandle mapGet,
            MethodHandle mapRemove,
            MethodHandle nodeGetRequest,
            MethodHandle nodeSetRequest,
            MethodHandle nodeIsRequestInFlight,
            MethodHandle nodeExists,
            MethodHandle nodePosition,
            MethodHandle nodeEnd,
            MethodHandle childGetOrNull,
            MethodHandle childRelease,
            MethodHandle watcherUnwatch,
            MethodHandle makeChildPos,
            MethodHandle invalidateNode,
            MethodHandle processRequest
    ) {
        private static NodeAccess resolve(Class<?> nodeManager) throws ReflectiveOperationException {
            Field activeSectionMap = nodeManager.getDeclaredField("activeSectionMap");
            Field nodeData = nodeManager.getDeclaredField("nodeData");
            Field childRequests = nodeManager.getDeclaredField("childRequests");
            Field watcher = nodeManager.getDeclaredField("watcher");
            Field activeNodeRequestCount = nodeManager.getDeclaredField("activeNodeRequestCount");

            Class<?> nodeStore = nodeData.getType();
            Class<?> allocationList = childRequests.getType();
            Class<?> map = activeSectionMap.getType();
            Class<?> watcherType = watcher.getType();

            return new NodeAccess(
                    unreflectGetter(activeSectionMap, MethodType.methodType(Object.class, Object.class)),
                    unreflectGetter(nodeData, MethodType.methodType(Object.class, Object.class)),
                    unreflectGetter(childRequests, MethodType.methodType(Object.class, Object.class)),
                    unreflectGetter(watcher, MethodType.methodType(Object.class, Object.class)),
                    unreflectGetter(activeNodeRequestCount, MethodType.methodType(int.class, Object.class)),
                    unreflectSetter(activeNodeRequestCount, MethodType.methodType(void.class, Object.class, int.class)),
                    unreflectMethod(map.getMethod("get", long.class), MethodType.methodType(int.class, Object.class, long.class)),
                    unreflectMethod(map.getMethod("remove", long.class), MethodType.methodType(void.class, Object.class, long.class)),
                    unreflectMethod(nodeStore.getMethod("getNodeRequest", int.class), MethodType.methodType(int.class, Object.class, int.class)),
                    unreflectMethod(nodeStore.getMethod("setNodeRequest", int.class, int.class), MethodType.methodType(void.class, Object.class, int.class, int.class)),
                    unreflectMethod(nodeStore.getMethod("isNodeRequestInFlight", int.class), MethodType.methodType(boolean.class, Object.class, int.class)),
                    unreflectMethod(nodeStore.getMethod("nodeExists", int.class), MethodType.methodType(boolean.class, Object.class, int.class)),
                    unreflectMethod(nodeStore.getMethod("nodePosition", int.class), MethodType.methodType(long.class, Object.class, int.class)),
                    unreflectMethod(nodeStore.getMethod("getEndNodeId"), MethodType.methodType(int.class, Object.class)),
                    unreflectMethod(allocationList.getMethod("getOrNull", int.class), MethodType.methodType(Object.class, Object.class, int.class)),
                    unreflectMethod(allocationList.getMethod("release", int.class), MethodType.methodType(void.class, Object.class, int.class)),
                    unreflectMethod(watcherType.getMethod("unwatch", long.class, int.class), MethodType.methodType(void.class, Object.class, long.class, int.class)),
                    unreflectMethod(nodeManager.getDeclaredMethod("makeChildPos", long.class, int.class), MethodType.methodType(long.class, long.class, int.class)),
                    unreflectMethod(nodeManager.getDeclaredMethod("invalidateNode", int.class), MethodType.methodType(void.class, Object.class, int.class)),
                    unreflectMethod(nodeManager.getMethod("processRequest", long.class), MethodType.methodType(void.class, Object.class, long.class))
            );
        }
    }

    private static final class WatchdogThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Roxy Voxy Request Watchdog");
            thread.setDaemon(true);
            return thread;
        }
    }
}
