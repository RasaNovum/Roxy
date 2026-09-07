package net.rasanovum.roxy.patch;

import net.rasanovum.roxy.shader.RoxyVoxyRequestShader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final ConcurrentMap<Class<?>, Method> MAP_PUT = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> WATCHER_GET = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> WATCHER_WATCH = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> REQUEST_POSITION = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> PROCESS_REQUEST = new ConcurrentHashMap<>();
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
            long generation = REQUEST_GENERATION.get();
            ASYNC_GENERATIONS.put(asyncNodeManager, generation);
            activeAsyncNodeManager = asyncNodeManager;
            managerRegistrationRequired = false;
        }
    }

    public static void defer(Object nodeManager, long position) {
        if (nodeManager == null) return;
        synchronized (MANAGER_LOCK) {
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
            Method processRequest = PROCESS_REQUEST.computeIfAbsent(
                    nodeManager.getClass(),
                    RoxyVoxyRequestPatch::resolveProcessRequest
            );
            long count = RETRIED.incrementAndGet();
            if (count <= 8 || count % 100 == 0) {
                LOGGER.debug("Retried deferred Voxy node request at {} ({} total)", position, count);
            }
            processRequest.invoke(nodeManager, position);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
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
                    Object nodeManager = async.manager.get(asyncNodeManager);
                    if (nodeManager == null) return;
                    state = new WatchdogState(
                            asyncNodeManager,
                            nodeManager,
                            (Thread) async.thread.get(asyncNodeManager),
                            async.nodeAccess,
                            new SweepState()
                    );
                    watchdogState = state;
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
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
            int encoded = (int) access.mapGet.invoke(access.activeSectionMap.get(nodeManager), position);
            if (encoded == -1 || isRequestEntry(encoded)) return;
            int nodeId = encoded & NODE_ID_MASK;
            Object nodeData = access.nodeData.get(nodeManager);
            if ((boolean) access.nodeIsRequestInFlight.invoke(nodeData, nodeId)
                    && repairInFlightRequest(nodeManager, position, nodeId)) return;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Unable to inspect a repeated Voxy GPU request", exception);
        }
        try {
            PROCESS_REQUEST.computeIfAbsent(nodeManager.getClass(), RoxyVoxyRequestPatch::resolveProcessRequest)
                    .invoke(nodeManager, position);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to process a Voxy GPU request", exception);
        }
    }

    public static boolean repairInFlightRequest(Object nodeManager, long position, int nodeId) {
        try {
            NodeAccess access = nodeAccess(nodeManager);
            Object nodeData = access.nodeData.get(nodeManager);
            int requestId = (int) access.nodeGetRequest.invoke(nodeData, nodeId);
            if (requestId == REQUEST_ID_MASK) return false;
            Object request = access.childGetOrNull.invoke(access.childRequests.get(nodeManager), requestId);
            if (request != null && isLiveRequest(nodeManager, access, position, requestId, request)) return true;
            clearStaleRequest(nodeManager, access, position, nodeId, requestId, true);
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
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
            Object map = access.activeSectionMap.get(nodeManager);
            int encoded = (int) access.mapGet.invoke(map, position);
            if (encoded == -1 || (encoded & NODE_TYPE_MASK) == NODE_TYPE_REQUEST) return;
            int nodeId = encoded & NODE_ID_MASK;
            Object nodeData = access.nodeData.get(nodeManager);
            boolean inFlight = (boolean) access.nodeIsRequestInFlight.invoke(nodeData, nodeId);
            if (inFlight) repairInFlightRequest(nodeManager, position, nodeId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
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
            Object nodeData = access.nodeData.get(nodeManager);
            access.nodeSetRequest.invoke(nodeData, nodeId, REQUEST_ID_MASK);
            access.invalidateNode.invoke(nodeManager, nodeId);
            LOGGER.warn("Recovered a failed Voxy leaf request and left it eligible for retry", failure);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logCleanupFailure("Unable to roll back a failed Voxy leaf request", exception);
        } finally {
            LEAF_REQUEST.remove();
        }
    }

    public static int putChildMapping(Object map, long position, int value) {
        try {
            Method put = MAP_PUT.computeIfAbsent(map.getClass(), RoxyVoxyRequestPatch::resolveMapPut);
            int previous = (int) put.invoke(map, position, value);
            if (previous != -1) {
                put.invoke(map, position, previous);
                if (previous == value) return -1;
            }
            return previous;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Unable to preserve a Voxy active-map collision", exception);
            throw new IllegalStateException("Unable to preserve a Voxy active-map collision", exception);
        }
    }

    public static boolean watchChild(Object watcher, long position, int flags) {
        try {
            Method get = WATCHER_GET.computeIfAbsent(watcher.getClass(), RoxyVoxyRequestPatch::resolveWatcherGet);
            int current = (int) get.invoke(watcher, position);
            if ((current & flags) == flags) return true;
            Method watch = WATCHER_WATCH.computeIfAbsent(watcher.getClass(), RoxyVoxyRequestPatch::resolveWatcherWatch);
            return (boolean) watch.invoke(watcher, position, flags);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Unable to update a Voxy section watcher", exception);
            return false;
        }
    }

    private static boolean sweep(WatchdogState target, SweepState state)
            throws ReflectiveOperationException {
        if (watchdogState != target) return false;
        Object nodeManager = target.nodeManager;
        NodeAccess access = target.nodeAccess;
        Object nodeData = access.nodeData.get(nodeManager);
        int end = (int) access.nodeEnd.invoke(nodeData);
        if (end <= 0) {
            state.cursor = 0;
            return false;
        }

        int repaired = 0;
        for (int scanned = 0; scanned < Math.min(end, SWEEP_SLICE); scanned++) {
            if (watchdogState != target) return repaired > 0;
            if (state.cursor >= end) state.cursor = 0;
            int nodeId = state.cursor++;
            if (!(boolean) access.nodeExists.invoke(nodeData, nodeId)) continue;
            if (!(boolean) access.nodeIsRequestInFlight.invoke(nodeData, nodeId)) continue;
            long position = (long) access.nodePosition.invoke(nodeData, nodeId);
            if (position == -1L) continue;
            int requestId = (int) access.nodeGetRequest.invoke(nodeData, nodeId);
            Object request = access.childGetOrNull.invoke(access.childRequests.get(nodeManager), requestId);
            if (request != null && isLiveRequest(nodeManager, access, position, requestId, request)) continue;

            try {
                state.maintenanceChanged = true;
                clearStaleRequest(nodeManager, access, position, nodeId, requestId, true);
                repaired++;
                access.processRequest.invoke(nodeManager, position);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
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
    ) throws ReflectiveOperationException {
        clearPending(nodeManager, position);
        Object childRequests = access.childRequests.get(nodeManager);
        Object request = requestId == REQUEST_ID_MASK
                ? null
                : access.childGetOrNull.invoke(childRequests, requestId);
        boolean sameOwner = request == null || requestPosition(request) == position;
        if (sameOwner) cleanupRequest(nodeManager, access, position, requestId, decrementRequestCount);
        Object nodeData = access.nodeData.get(nodeManager);
        access.nodeSetRequest.invoke(nodeData, nodeId, REQUEST_ID_MASK);
        access.invalidateNode.invoke(nodeManager, nodeId);
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
    ) throws ReflectiveOperationException {
        if (requestId != REQUEST_ID_MASK) {
            Object map = access.activeSectionMap.get(nodeManager);
            Object watcher = access.watcher.get(nodeManager);
            for (int child = 0; child < 8; child++) {
                long childPosition = (long) access.makeChildPos.invoke(null, position, child);
                int encoded = (int) access.mapGet.invoke(map, childPosition);
                if ((encoded & NODE_TYPE_MASK) != NODE_TYPE_REQUEST
                        || (encoded & REQUEST_TYPE_MASK) != REQUEST_TYPE_CHILD
                        || (encoded & NODE_ID_MASK) != requestId) continue;
                access.mapRemove.invoke(map, childPosition);
                access.watcherUnwatch.invoke(watcher, childPosition, DEFAULT_UPDATE_FLAGS);
            }
            Object childRequest = access.childGetOrNull.invoke(access.childRequests.get(nodeManager), requestId);
            if (childRequest != null && requestPosition(childRequest) == position) {
                access.childRelease.invoke(access.childRequests.get(nodeManager), requestId);
                if (decrementRequestCount) {
                    int count = access.activeNodeRequestCount.getInt(nodeManager);
                    if (count > 0) access.activeNodeRequestCount.setInt(nodeManager, count - 1);
                }
            }
        }
    }

    private static long requestPosition(Object request) throws ReflectiveOperationException {
        Method method = REQUEST_POSITION.computeIfAbsent(request.getClass(), RoxyVoxyRequestPatch::resolveRequestPosition);
        return (long) method.invoke(request);
    }

    private static boolean isLiveRequest(
            Object nodeManager,
            NodeAccess access,
            long position,
            int requestId,
            Object request
    ) throws ReflectiveOperationException {
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

    private static Method resolveMapPut(Class<?> type) {
        try {
            return accessible(type.getMethod("put", long.class, int.class));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method resolveWatcherGet(Class<?> type) {
        try {
            return accessible(type.getMethod("get", long.class));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method resolveWatcherWatch(Class<?> type) {
        try {
            return accessible(type.getMethod("watch", long.class, int.class));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method resolveRequestPosition(Class<?> type) {
        try {
            return accessible(type.getMethod("getPosition"));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method resolveProcessRequest(Class<?> type) {
        try {
            return accessible(type.getMethod("processRequest", long.class));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
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

    private record AsyncAccess(Field thread, Field manager, NodeAccess nodeAccess) {
        private static AsyncAccess resolve(Class<?> asyncNodeManager) throws ReflectiveOperationException {
            Field thread = asyncNodeManager.getDeclaredField("thread");
            Field manager = asyncNodeManager.getDeclaredField("manager");
            thread.setAccessible(true);
            manager.setAccessible(true);
            return new AsyncAccess(thread, manager, NodeAccess.resolve(manager.getType()));
        }
    }

    private record NodeAccess(
            Field activeSectionMap,
            Field nodeData,
            Field childRequests,
            Field watcher,
            Field activeNodeRequestCount,
            Method mapGet,
            Method mapRemove,
            Method nodeGetRequest,
            Method nodeSetRequest,
            Method nodeIsRequestInFlight,
            Method nodeExists,
            Method nodePosition,
            Method nodeEnd,
            Method childGetOrNull,
            Method childRelease,
            Method watcherUnwatch,
            Method makeChildPos,
            Method invalidateNode,
            Method processRequest
    ) {
        private static NodeAccess resolve(Class<?> nodeManager) throws ReflectiveOperationException {
            Field activeSectionMap = nodeManager.getDeclaredField("activeSectionMap");
            Field nodeData = nodeManager.getDeclaredField("nodeData");
            Field childRequests = nodeManager.getDeclaredField("childRequests");
            Field watcher = nodeManager.getDeclaredField("watcher");
            Field activeNodeRequestCount = nodeManager.getDeclaredField("activeNodeRequestCount");
            for (Field field : new Field[]{
                    activeSectionMap,
                    nodeData,
                    childRequests,
                    watcher,
                    activeNodeRequestCount
            }) field.setAccessible(true);

            Class<?> nodeStore = nodeData.getType();
            Class<?> allocationList = childRequests.getType();
            Class<?> map = activeSectionMap.getType();
            Class<?> watcherType = watcher.getType();
            Method makeChildPos = nodeManager.getDeclaredMethod("makeChildPos", long.class, int.class);
            makeChildPos.setAccessible(true);
            Method invalidateNode = nodeManager.getDeclaredMethod("invalidateNode", int.class);
            invalidateNode.setAccessible(true);

            return new NodeAccess(
                    activeSectionMap,
                    nodeData,
                    childRequests,
                    watcher,
                    activeNodeRequestCount,
                    accessible(map.getMethod("get", long.class)),
                    accessible(map.getMethod("remove", long.class)),
                    accessible(nodeStore.getMethod("getNodeRequest", int.class)),
                    accessible(nodeStore.getMethod("setNodeRequest", int.class, int.class)),
                    accessible(nodeStore.getMethod("isNodeRequestInFlight", int.class)),
                    accessible(nodeStore.getMethod("nodeExists", int.class)),
                    accessible(nodeStore.getMethod("nodePosition", int.class)),
                    accessible(nodeStore.getMethod("getEndNodeId")),
                    accessible(allocationList.getMethod("getOrNull", int.class)),
                    accessible(allocationList.getMethod("release", int.class)),
                    accessible(watcherType.getMethod("unwatch", long.class, int.class)),
                    makeChildPos,
                    invalidateNode,
                    accessible(nodeManager.getMethod("processRequest", long.class))
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
