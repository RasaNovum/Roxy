package net.rasanovum.roxy.patch;

import net.rasanovum.roxy.compat.RoxyVoxyWorkDrainCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RoxyVoxyRenderPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final AtomicLong WORLD_GENERATION = new AtomicLong();
    private static final AtomicLong REPAIR_SEQUENCE = new AtomicLong();
    private static final AtomicLong STALE_RESULT_COUNT = new AtomicLong();
    private static final AtomicLong FAILED_TASK_COUNT = new AtomicLong();
    private static final AtomicLong DEFERRED_TASK_COUNT = new AtomicLong();
    private static final AtomicLong DIRTY_TASK_REQUEUE_COUNT = new AtomicLong();
    private static final AtomicLong REPLACED_TASK_REQUEUE_COUNT = new AtomicLong();
    private static final AtomicLong RESULT_EPOCH = new AtomicLong();
    private static final ConcurrentMap<Class<?>, Field> TASK_POSITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Field> RESULT_POSITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Field> RENDER_GENERATION_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Field> NODE_MANAGER_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Field> SERVICE_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> LIVE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> ENQUEUE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<TaskKey, TaskState> IN_FLIGHT_TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<TaskPositionKey, Boolean> QUEUED_TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<TaskPositionKey, Boolean> DIRTY_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<Object, Long> SERVICE_SEQUENCES = new WeakHashMap<>();
    private static final Map<Object, Long> SERVICE_WORLDS = new WeakHashMap<>();
    private static final Map<Object, Long> TASK_EPOCHS = new WeakHashMap<>();
    private static final Map<TaskPositionKey, Long> LATEST_EPOCHS = new HashMap<>();
    private static final ConcurrentMap<PendingTaskKey, Boolean> PENDING_RENDER_TASKS = new ConcurrentHashMap<>();
    private static final Object PUBLICATION_LOCK = new Object();
    private static final Object RENDER_LOCK = new Object();
    private static final ThreadLocal<TaskStamp> TASK_STAMP = new ThreadLocal<>();
    private static volatile boolean taskPositionFailure;
    private static volatile boolean rendererLookupFailure;
    private static volatile boolean serviceLookupFailure;
    private static volatile boolean enqueueFailure;
    private static volatile Object activeRenderGenerationService;
    private static Object worldToken;
    private static Object worldEngineToken;
    private static boolean worldObserved;

    private RoxyVoxyRenderPatch() {
    }

    public static void reset() {
        resetState(true);
    }

    public static void prepareFullInstanceReload() {
        resetState(true);
        LOGGER.info("Reset Voxy render task state before a full Voxy instance reload");
    }

    public static void prepareIrisRendererReload() {
        resetState(true);
        LOGGER.info("Reset Voxy render task state before an Iris renderer reload");
    }

    public static void observeWorld(Object world) {
        if (world == null) return;
        synchronized (PUBLICATION_LOCK) {
            boolean changed;
            synchronized (RENDER_LOCK) {
                if (worldObserved && worldToken == world) return;
                changed = worldObserved;
                worldToken = world;
                worldEngineToken = null;
                worldObserved = true;
            }
            if (changed) resetState(true);
        }
    }

    public static void observeWorld(Object world, Object worldEngine) {
        if (world == null) return;
        synchronized (PUBLICATION_LOCK) {
            boolean changed;
            synchronized (RENDER_LOCK) {
                if (worldObserved && worldToken == world) {
                    worldEngineToken = worldEngine;
                    return;
                }
                changed = worldObserved;
                worldToken = world;
                worldEngineToken = worldEngine;
                worldObserved = true;
            }
            if (changed) resetState(true);
        }
    }

    public static void observeEngine(Object worldEngine) {
        if (worldEngine == null) return;
        synchronized (PUBLICATION_LOCK) {
            synchronized (RENDER_LOCK) {
                worldEngineToken = worldEngine;
            }
        }
    }

    public static void retireRenderer(Object renderer) {
        if (renderer == null) return;
        try {
            Field renderGeneration = RENDER_GENERATION_FIELDS.computeIfAbsent(
                    renderer.getClass(),
                    RoxyVoxyRenderPatch::resolveRenderGeneration
            );
            Object renderGenerationService = renderGeneration.get(renderer);
            if (renderGenerationService == null) {
                if (!rendererLookupFailure) {
                    rendererLookupFailure = true;
                    LOGGER.warn("Unable to retire Voxy render-generation state because its service is unavailable");
                }
                return;
            }
            synchronized (PUBLICATION_LOCK) {
                synchronized (RENDER_LOCK) {
                    if (activeRenderGenerationService != renderGenerationService) return;
                }
                resetState(false);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!rendererLookupFailure) {
                rendererLookupFailure = true;
                LOGGER.warn("Unable to retire Voxy render-generation state", exception);
            }
        }
    }

    private static void resetState(boolean clearPendingRenderTasks) {
        RoxyVoxyWorkDrainCompat.reset();
        synchronized (PUBLICATION_LOCK) {
            synchronized (RENDER_LOCK) {
                Object retiredService = activeRenderGenerationService;
                if (!clearPendingRenderTasks) rememberRendererTasksLocked(retiredService);
                if (clearPendingRenderTasks) WORLD_GENERATION.incrementAndGet();
                REPAIR_SEQUENCE.incrementAndGet();
                STALE_RESULT_COUNT.set(0L);
                FAILED_TASK_COUNT.set(0L);
                DEFERRED_TASK_COUNT.set(0L);
                DIRTY_TASK_REQUEUE_COUNT.set(0L);
                REPLACED_TASK_REQUEUE_COUNT.set(0L);
                IN_FLIGHT_TASKS.clear();
                TASK_EPOCHS.clear();
                LATEST_EPOCHS.clear();
                if (clearPendingRenderTasks) {
                    QUEUED_TASKS.clear();
                } else {
                    if (retiredService == null) QUEUED_TASKS.clear();
                    else removeQueuedTasksLocked(retiredService);
                }
                DIRTY_POSITIONS.clear();
                if (clearPendingRenderTasks) PENDING_RENDER_TASKS.clear();
                activeRenderGenerationService = null;
                TASK_STAMP.remove();
                taskPositionFailure = false;
                rendererLookupFailure = false;
                serviceLookupFailure = false;
                enqueueFailure = false;
            }
        }
        RoxyVoxyRequestPatch.reset();
    }

    public static void registerRenderer(Object renderer) {
        if (renderer == null) return;
        try {
            synchronized (PUBLICATION_LOCK) {
                Field renderGeneration = RENDER_GENERATION_FIELDS.computeIfAbsent(
                        renderer.getClass(),
                        RoxyVoxyRenderPatch::resolveRenderGeneration
                );
                Object renderGenerationService = renderGeneration.get(renderer);
                Field nodeManagerField = NODE_MANAGER_FIELDS.computeIfAbsent(
                        renderer.getClass(),
                        RoxyVoxyRenderPatch::resolveNodeManager
                );
                Object nodeManager = nodeManagerField.get(renderer);
                if (renderGenerationService == null || nodeManager == null) return;

                boolean rendererReplaced;
                synchronized (RENDER_LOCK) {
                    rendererReplaced = activeRenderGenerationService != null
                            && activeRenderGenerationService != renderGenerationService;
                }
                if (rendererReplaced) resetState(false);

                RoxyVoxyRequestPatch.registerAsyncNodeManager(nodeManager);
                RoxyVoxyWorkDrainCompat.bind(nodeManager);
                synchronized (RENDER_LOCK) {
                    SERVICE_SEQUENCES.put(renderGenerationService, REPAIR_SEQUENCE.get());
                    SERVICE_WORLDS.put(renderGenerationService, WORLD_GENERATION.get());
                    activeRenderGenerationService = renderGenerationService;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!rendererLookupFailure) {
                rendererLookupFailure = true;
                LOGGER.warn("Unable to bind Voxy render-generation state", exception);
            }
        }
    }

    public static void recordRenderTaskCreation(
            Object renderGenerationService,
            long position,
            Object task,
            boolean created
    ) {
        if (renderGenerationService == null || task == null || !created) return;
        synchronized (RENDER_LOCK) {
            SERVICE_SEQUENCES.putIfAbsent(renderGenerationService, REPAIR_SEQUENCE.get());
            SERVICE_WORLDS.putIfAbsent(renderGenerationService, WORLD_GENERATION.get());
            long epoch = RESULT_EPOCH.incrementAndGet();
            TASK_EPOCHS.put(task, epoch);
            LATEST_EPOCHS.put(new TaskPositionKey(renderGenerationService, position), epoch);
            QUEUED_TASKS.put(new TaskPositionKey(renderGenerationService, position), Boolean.TRUE);
        }
    }

    public static boolean deferRenderTaskIfInFlight(Object renderGenerationService, long position) {
        if (renderGenerationService == null) return false;
        synchronized (RENDER_LOCK) {
            if (activeRenderGenerationService != renderGenerationService) return false;
            Long sequence = SERVICE_SEQUENCES.get(renderGenerationService);
            Long world = SERVICE_WORLDS.get(renderGenerationService);
            if (sequence == null
                    || world == null
                    || sequence != REPAIR_SEQUENCE.get()
                    || world != WORLD_GENERATION.get()) return false;

            TaskPositionKey positionKey = new TaskPositionKey(renderGenerationService, position);
            if (!hasInFlightTask(positionKey)) return false;
            if (DIRTY_POSITIONS.putIfAbsent(positionKey, Boolean.TRUE) == null) {
                LATEST_EPOCHS.put(positionKey, RESULT_EPOCH.incrementAndGet());
                long count = DEFERRED_TASK_COUNT.incrementAndGet();
                if (count <= 8 || count % 100 == 0) {
                    LOGGER.debug("Deferred a Voxy replacement while its render task was in flight at {} ({} total)", position, count);
                }
            }
            return true;
        }
    }

    public static void recordRenderTaskRetry(Object renderGenerationService, Object task) {
        if (renderGenerationService == null || task == null) return;
        try {
            long position = taskPosition(task);
            synchronized (RENDER_LOCK) {
                long world = SERVICE_WORLDS.computeIfAbsent(
                        renderGenerationService,
                        ignored -> WORLD_GENERATION.get()
                );
                if (world != WORLD_GENERATION.get()) return;
                if (activeRenderGenerationService != null
                        && activeRenderGenerationService != renderGenerationService) {
                    rememberPendingPositionLocked(position);
                    return;
                }

                long sequence = SERVICE_SEQUENCES.computeIfAbsent(
                        renderGenerationService,
                        ignored -> REPAIR_SEQUENCE.get()
                );
                if (sequence != REPAIR_SEQUENCE.get()) {
                    rememberPendingPositionLocked(position);
                    return;
                }

                TaskPositionKey positionKey = new TaskPositionKey(renderGenerationService, position);
                Long epoch = TASK_EPOCHS.get(task);
                if (epoch == null) {
                    epoch = RESULT_EPOCH.incrementAndGet();
                    TASK_EPOCHS.put(task, epoch);
                }
                Long latest = LATEST_EPOCHS.get(positionKey);
                if (latest == null || epoch > latest) LATEST_EPOCHS.put(positionKey, epoch);
                QUEUED_TASKS.put(positionKey, Boolean.TRUE);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!taskPositionFailure) {
                taskPositionFailure = true;
                LOGGER.warn("Unable to track a directly requeued Voxy render task", exception);
            }
        }
    }

    public static void beginRenderTask(Object renderGenerationService, Object task) {
        long sequence = REPAIR_SEQUENCE.get();
        long world = WORLD_GENERATION.get();
        long epoch = Long.MIN_VALUE;
        try {
            synchronized (RENDER_LOCK) {
                sequence = SERVICE_SEQUENCES.computeIfAbsent(
                        renderGenerationService,
                        ignored -> REPAIR_SEQUENCE.get()
                );
                world = SERVICE_WORLDS.computeIfAbsent(
                        renderGenerationService,
                        ignored -> WORLD_GENERATION.get()
                );
                long position = taskPosition(task);
                QUEUED_TASKS.remove(new TaskPositionKey(renderGenerationService, position));
                TaskKey key = new TaskKey(renderGenerationService, position, task);
                Long knownEpoch = TASK_EPOCHS.get(task);
                if (knownEpoch != null) epoch = knownEpoch;
                TaskState state = new TaskState(world);
                IN_FLIGHT_TASKS.put(key, state);
                TASK_STAMP.set(new TaskStamp(renderGenerationService, position, sequence, world, task, state, epoch));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            TASK_STAMP.set(new TaskStamp(renderGenerationService, Long.MIN_VALUE, sequence, world, null, null, epoch));
            if (!taskPositionFailure) {
                taskPositionFailure = true;
                LOGGER.warn("Unable to track Voxy render task position", exception);
            }
        }
    }

    public static void flushPendingRenderTasks() {
        synchronized (PUBLICATION_LOCK) {
            int flushed = 0;
            while (flushed < 32) {
                Object renderGenerationService;
                long world;
                long position;
                synchronized (RENDER_LOCK) {
                    renderGenerationService = activeRenderGenerationService;
                    if (renderGenerationService == null) return;
                    PendingTaskKey pending = null;
                    world = WORLD_GENERATION.get();
                    for (PendingTaskKey candidate : PENDING_RENDER_TASKS.keySet()) {
                        if (candidate.world == world) {
                            pending = candidate;
                            break;
                        }
                    }
                    if (pending == null || !PENDING_RENDER_TASKS.remove(pending)) return;
                    position = pending.position;
                    TaskPositionKey positionKey = new TaskPositionKey(renderGenerationService, position);
                    if (hasInFlightTask(positionKey)) {
                        DIRTY_POSITIONS.put(positionKey, Boolean.TRUE);
                        flushed++;
                        continue;
                    }
                    if (QUEUED_TASKS.containsKey(positionKey)) {
                        flushed++;
                        continue;
                    }
                }

                boolean enqueued = enqueueCurrentMesh(renderGenerationService, position);
                synchronized (RENDER_LOCK) {
                    if (world == WORLD_GENERATION.get()
                            && (activeRenderGenerationService != renderGenerationService || !enqueued)) {
                        if (!enqueued) {
                            QUEUED_TASKS.remove(new TaskPositionKey(renderGenerationService, position));
                        }
                        rememberPendingPositionLocked(position);
                    } else if (enqueued) {
                        long count = REPLACED_TASK_REQUEUE_COUNT.incrementAndGet();
                        if (count <= 8 || count % 100 == 0) {
                            LOGGER.debug(
                                    "Requeued render task on the active Voxy renderer at {} ({} total)",
                                    position,
                                    count
                            );
                        }
                    }
                }
                if (!enqueued) return;
                flushed++;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void acceptIfCurrent(Consumer<?> consumer, Object result) {
        TaskStamp taskStamp = TASK_STAMP.get();
        synchronized (PUBLICATION_LOCK) {
            boolean discard;
            boolean requeue = false;
            synchronized (RENDER_LOCK) {
                discard = !isCurrentTaskLocked(taskStamp) || !resultMatchesTask(taskStamp, result);
                if (discard) {
                    long count = STALE_RESULT_COUNT.incrementAndGet();
                    if (count <= 8 || count % 100 == 0) {
                        if (taskStamp == null) {
                            LOGGER.debug("Discarded an untracked Voxy render result ({} total)", count);
                        } else {
                            LOGGER.debug(
                                    "Discarded stale Voxy render result at {} (task epoch {}, latest epoch {}, {} total)",
                                    taskStamp.position,
                                    taskStamp.epoch,
                                    latestEpochLocked(taskStamp),
                                    count
                            );
                        }
                    }
                    if (taskStamp != null) {
                        requeue = finishRenderTaskLocked(taskStamp);
                        if (!requeue) rememberStaleTaskLocked(taskStamp);
                    } else {
                        clearTaskLocals();
                    }
                }
            }

            if (discard) {
                free(result);
                if (requeue) requeue(taskStamp);
                return;
            }

            try {
                ((Consumer<Object>) consumer).accept(result);
            } catch (RuntimeException | Error exception) {
                synchronized (RENDER_LOCK) {
                    retainFailedTaskLocked(taskStamp);
                }
                throw exception;
            } finally {
                boolean completionRequeue;
                synchronized (RENDER_LOCK) {
                    completionRequeue = finishRenderTaskLocked(taskStamp);
                }
                if (completionRequeue) requeue(taskStamp);
            }
        }
    }

    private static void clearTaskLocals() {
        TASK_STAMP.remove();
    }

    private static boolean finishRenderTaskLocked(TaskStamp taskStamp) {
        if (taskStamp == null) return false;
        boolean dirty = finishTask(taskStamp);
        clearTaskLocals();
        return dirty;
    }

    public static void finishRenderTask(boolean failed) {
        TaskStamp taskStamp = TASK_STAMP.get();
        if (taskStamp == null) return;
        synchronized (PUBLICATION_LOCK) {
            if (failed) {
                synchronized (RENDER_LOCK) {
                    retainFailedTaskLocked(taskStamp);
                    clearTaskLocals();
                }
                return;
            }
            boolean requeue;
            synchronized (RENDER_LOCK) {
                requeue = finishRenderTaskLocked(taskStamp);
            }
            if (requeue) requeue(taskStamp);
        }
    }

    private static void retainFailedTaskLocked(TaskStamp taskStamp) {
        removeTask(taskStamp);
        if (taskStamp.position == Long.MIN_VALUE || taskStamp.world != WORLD_GENERATION.get()) return;
        TaskPositionKey positionKey = new TaskPositionKey(taskStamp.renderGenerationService, taskStamp.position);
        if (!hasInFlightTask(positionKey) && !QUEUED_TASKS.containsKey(positionKey)) {
            rememberPendingPositionLocked(taskStamp.position);
        }
        long count = FAILED_TASK_COUNT.incrementAndGet();
        if (count <= 8 || count % 100 == 0) {
            LOGGER.warn("Retained failed Voxy render task at {} for retry ({} total)", taskStamp.position, count);
        }
    }

    private static boolean isCurrentTaskLocked(TaskStamp taskStamp) {
        if (taskStamp == null
                || taskStamp.state == null
                || taskStamp.position == Long.MIN_VALUE
                || taskStamp.epoch == Long.MIN_VALUE) return false;
        if (taskStamp.sequence != REPAIR_SEQUENCE.get()
                || taskStamp.world != WORLD_GENERATION.get()
                || activeRenderGenerationService != taskStamp.renderGenerationService
                || !isRendererLive(taskStamp.renderGenerationService)) return false;
        long latest = latestEpochLocked(taskStamp);
        return latest == Long.MIN_VALUE || taskStamp.epoch >= latest;
    }

    private static long latestEpochLocked(TaskStamp taskStamp) {
        if (taskStamp == null || taskStamp.position == Long.MIN_VALUE) return Long.MIN_VALUE;
        Long latest = LATEST_EPOCHS.get(new TaskPositionKey(taskStamp.renderGenerationService, taskStamp.position));
        return latest == null ? Long.MIN_VALUE : latest;
    }

    private static boolean finishTask(TaskStamp taskStamp) {
        if (taskStamp.state == null || taskStamp.position == Long.MIN_VALUE) return false;
        TaskKey key = new TaskKey(taskStamp.renderGenerationService, taskStamp.position, taskStamp.task);
        if (!IN_FLIGHT_TASKS.remove(key, taskStamp.state)) return false;
        TaskPositionKey positionKey = key.positionKey();
        if (hasInFlightTask(positionKey)) return false;
        boolean queued = QUEUED_TASKS.containsKey(positionKey);
        boolean dirty = DIRTY_POSITIONS.remove(positionKey) != null;
        if (!queued && !dirty && !hasPendingTaskLocked(positionKey)) {
            LATEST_EPOCHS.remove(positionKey, taskStamp.epoch);
        }
        return dirty && !queued;
    }

    private static void removeTask(TaskStamp taskStamp) {
        if (taskStamp.state == null || taskStamp.position == Long.MIN_VALUE) return;
        TaskPositionKey positionKey = new TaskPositionKey(taskStamp.renderGenerationService, taskStamp.position);
        IN_FLIGHT_TASKS.remove(
                new TaskKey(taskStamp.renderGenerationService, taskStamp.position, taskStamp.task),
                taskStamp.state
        );
        if (!hasInFlightTask(positionKey)) DIRTY_POSITIONS.remove(positionKey);
    }

    private static boolean hasInFlightTask(TaskPositionKey positionKey) {
        for (TaskKey taskKey : IN_FLIGHT_TASKS.keySet()) {
            if (taskKey.positionKey().equals(positionKey)) return true;
        }
        return false;
    }

    private static boolean hasPendingTaskLocked(TaskPositionKey positionKey) {
        long world = WORLD_GENERATION.get();
        for (PendingTaskKey pending : PENDING_RENDER_TASKS.keySet()) {
            if (pending.world == world && pending.position == positionKey.position) return true;
        }
        return false;
    }

    private static long taskPosition(Object task) throws ReflectiveOperationException {
        if (task == null) return Long.MIN_VALUE;
        Field position = TASK_POSITIONS.computeIfAbsent(task.getClass(), RoxyVoxyRenderPatch::resolveTaskPosition);
        return position.getLong(task);
    }

    private static Field resolveTaskPosition(Class<?> type) {
        try {
            Field position = type.getDeclaredField("position");
            position.setAccessible(true);
            return position;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean resultMatchesTask(TaskStamp taskStamp, Object result) {
        if (taskStamp == null || result == null) return false;
        try {
            Field position = RESULT_POSITIONS.computeIfAbsent(
                    result.getClass(),
                    RoxyVoxyRenderPatch::resolveResultPosition
            );
            return position.getLong(result) == taskStamp.position;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!taskPositionFailure) {
                taskPositionFailure = true;
                LOGGER.warn("Unable to inspect a Voxy render result position", exception);
            }
            return false;
        }
    }

    private static Field resolveResultPosition(Class<?> type) {
        try {
            Field position = type.getField("position");
            position.setAccessible(true);
            return position;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Field resolveRenderGeneration(Class<?> type) {
        try {
            Field renderGeneration = type.getDeclaredField("renderGen");
            renderGeneration.setAccessible(true);
            return renderGeneration;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean enqueueCurrentMesh(Object renderGenerationService, long position) {
        if (renderGenerationService == null || position == Long.MIN_VALUE) return false;
        if (!isRendererLive(renderGenerationService)) return false;
        try {
            Method enqueueTask = ENQUEUE_METHODS.computeIfAbsent(
                    renderGenerationService.getClass(),
                    RoxyVoxyRenderPatch::resolveEnqueueTask
            );
            enqueueTask.invoke(renderGenerationService, position);
            return isRendererLive(renderGenerationService);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!enqueueFailure) {
                enqueueFailure = true;
                LOGGER.warn("Unable to requeue a Voxy render task after an in-flight update", exception);
            }
            return false;
        }
    }

    private static Method resolveEnqueueTask(Class<?> type) {
        try {
            return type.getMethod("enqueueTask", long.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isRendererLive(Object renderGenerationService) {
        if (renderGenerationService == null) return false;
        try {
            Field serviceField = SERVICE_FIELDS.computeIfAbsent(
                    renderGenerationService.getClass(),
                    RoxyVoxyRenderPatch::resolveService
            );
            Object service = serviceField.get(renderGenerationService);
            if (service == null) return false;
            Method live = LIVE_METHODS.computeIfAbsent(service.getClass(), RoxyVoxyRenderPatch::resolveLive);
            return (boolean) live.invoke(service);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!serviceLookupFailure) {
                serviceLookupFailure = true;
                LOGGER.warn("Unable to inspect Voxy render-generation service liveness", exception);
            }
            return false;
        }
    }

    private static Field resolveService(Class<?> type) {
        try {
            Field service = type.getDeclaredField("service");
            service.setAccessible(true);
            return service;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method resolveLive(Class<?> type) {
        try {
            Method live = type.getMethod("isLive");
            live.setAccessible(true);
            return live;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void rememberRendererTasksLocked(Object renderGenerationService) {
        if (renderGenerationService != null) {
            rememberServiceTasksLocked(renderGenerationService);
            return;
        }
        for (Map.Entry<TaskKey, TaskState> entry : IN_FLIGHT_TASKS.entrySet()) {
            if (entry.getValue().world == WORLD_GENERATION.get()) {
                rememberPendingPositionLocked(entry.getKey().position);
            }
        }
        for (TaskPositionKey key : QUEUED_TASKS.keySet()) {
            if (serviceBelongsToCurrentWorldLocked(key.renderGenerationService)) {
                rememberPendingPositionLocked(key.position);
            }
        }
        for (TaskPositionKey key : DIRTY_POSITIONS.keySet()) {
            if (serviceBelongsToCurrentWorldLocked(key.renderGenerationService)) {
                rememberPendingPositionLocked(key.position);
            }
        }
    }

    private static void rememberServiceTasksLocked(Object renderGenerationService) {
        if (renderGenerationService == null) return;
        long world = SERVICE_WORLDS.getOrDefault(renderGenerationService, WORLD_GENERATION.get());
        if (world != WORLD_GENERATION.get()) return;

        for (TaskKey key : IN_FLIGHT_TASKS.keySet()) {
            if (key.renderGenerationService == renderGenerationService) {
                rememberPendingPositionLocked(key.position);
            }
        }
        for (TaskPositionKey key : QUEUED_TASKS.keySet()) {
            if (key.renderGenerationService == renderGenerationService) {
                rememberPendingPositionLocked(key.position);
            }
        }
        for (TaskPositionKey key : DIRTY_POSITIONS.keySet()) {
            if (key.renderGenerationService == renderGenerationService) {
                rememberPendingPositionLocked(key.position);
            }
        }
    }

    private static boolean serviceBelongsToCurrentWorldLocked(Object renderGenerationService) {
        return SERVICE_WORLDS.getOrDefault(renderGenerationService, WORLD_GENERATION.get()) == WORLD_GENERATION.get();
    }

    private static void removeQueuedTasksLocked(Object renderGenerationService) {
        if (renderGenerationService == null) return;
        for (TaskPositionKey key : QUEUED_TASKS.keySet()) {
            if (key.renderGenerationService == renderGenerationService) {
                QUEUED_TASKS.remove(key);
            }
        }
    }

    private static Field resolveNodeManager(Class<?> type) {
        try {
            Field nodeManager = type.getDeclaredField("nodeManager");
            nodeManager.setAccessible(true);
            return nodeManager;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requeue(TaskStamp taskStamp) {
        if (taskStamp.position == Long.MIN_VALUE) return;
        synchronized (RENDER_LOCK) {
            TaskPositionKey positionKey = new TaskPositionKey(taskStamp.renderGenerationService, taskStamp.position);
            if (taskStamp.sequence != REPAIR_SEQUENCE.get()
                    || taskStamp.world != WORLD_GENERATION.get()
                    || activeRenderGenerationService != taskStamp.renderGenerationService
                    || hasInFlightTask(positionKey)
                    || QUEUED_TASKS.containsKey(positionKey)) {
                rememberStaleTaskLocked(taskStamp);
                return;
            }
        }

        if (!enqueueCurrentMesh(taskStamp.renderGenerationService, taskStamp.position)) {
            synchronized (RENDER_LOCK) {
                QUEUED_TASKS.remove(new TaskPositionKey(taskStamp.renderGenerationService, taskStamp.position));
                if (taskStamp.world == WORLD_GENERATION.get()) rememberStaleTaskLocked(taskStamp);
            }
            return;
        }

        synchronized (RENDER_LOCK) {
            if (taskStamp.world != WORLD_GENERATION.get()) return;
            if (activeRenderGenerationService != taskStamp.renderGenerationService) {
                rememberStaleTaskLocked(taskStamp);
                return;
            }
            long count = DIRTY_TASK_REQUEUE_COUNT.incrementAndGet();
            if (count <= 8 || count % 100 == 0) {
                LOGGER.debug(
                        "Requeued Voxy render task after an in-flight update at {} ({} total)",
                        taskStamp.position,
                        count
                );
            }
        }
    }

    private static void rememberStaleTaskLocked(TaskStamp taskStamp) {
        if (taskStamp.position == Long.MIN_VALUE || taskStamp.world != WORLD_GENERATION.get()) return;
        TaskPositionKey positionKey = new TaskPositionKey(taskStamp.renderGenerationService, taskStamp.position);
        if (taskStamp.renderGenerationService == activeRenderGenerationService
                && (hasInFlightTask(positionKey) || QUEUED_TASKS.containsKey(positionKey))) {
            return;
        }
        rememberPendingPositionLocked(taskStamp.position);
    }

    private static void rememberPendingPositionLocked(long position) {
        if (position == Long.MIN_VALUE) return;
        PENDING_RENDER_TASKS.putIfAbsent(new PendingTaskKey(WORLD_GENERATION.get(), position), Boolean.TRUE);
    }

    private static void free(Object result) {
        if (result == null) return;
        try {
            Method method = result.getClass().getMethod("free");
            method.setAccessible(true);
            method.invoke(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Unable to free a discarded Voxy render result", exception);
        }
    }

    private record TaskStamp(
            Object renderGenerationService,
            long position,
            long sequence,
            long world,
            Object task,
            TaskState state,
            long epoch
    ) {
    }

    private static final class TaskState {
        private final long world;

        private TaskState(long world) {
            this.world = world;
        }
    }

    private record TaskKey(Object renderGenerationService, long position, Object task) {
        private TaskPositionKey positionKey() {
            return new TaskPositionKey(renderGenerationService, position);
        }
    }

    private record TaskPositionKey(Object renderGenerationService, long position) {
    }

    private record PendingTaskKey(long world, long position) {
    }
}
