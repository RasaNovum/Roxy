package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RoxyVoxyRenderCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final AtomicLong REPAIR_SEQUENCE = new AtomicLong();
    private static final AtomicLong STALE_RESULT_COUNT = new AtomicLong();
    private static final AtomicLong DIRTY_TASK_MARK_COUNT = new AtomicLong();
    private static final AtomicLong DIRTY_TASK_REQUEUE_COUNT = new AtomicLong();
    private static final ConcurrentMap<Class<?>, Field> POSITION_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Field> TASK_POSITION_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> ENQUEUE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<TaskKey, Boolean> DIRTY_TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<TaskKey, Boolean> IN_FLIGHT_TASKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> TASK_SEQUENCE = new ThreadLocal<>();
    private static final ThreadLocal<Long> TASK_POSITION = new ThreadLocal<>();
    private static final ThreadLocal<Object> TASK_GENERATION_SERVICE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SUPPRESS_DIRTY_MARK = new ThreadLocal<>();
    private static volatile boolean positionLookupFailed;
    private static volatile boolean taskLookupFailed;
    private static volatile boolean generationLookupFailed;

    private RoxyVoxyRenderCompat() {
    }

    public static void reset() {
        REPAIR_SEQUENCE.incrementAndGet();
        STALE_RESULT_COUNT.set(0L);
        DIRTY_TASK_MARK_COUNT.set(0L);
        DIRTY_TASK_REQUEUE_COUNT.set(0L);
        DIRTY_TASKS.clear();
        IN_FLIGHT_TASKS.clear();
        TASK_SEQUENCE.remove();
        TASK_POSITION.remove();
        TASK_GENERATION_SERVICE.remove();
        SUPPRESS_DIRTY_MARK.remove();
        RoxyVoxyRequestCompat.reset();
    }

    public static void beginRenderTask(Object renderGenerationService, Object task) {
        try {
            Field positionField = TASK_POSITION_FIELDS.computeIfAbsent(
                    task.getClass(),
                    RoxyVoxyRenderCompat::findTaskPositionField
            );
            long position = positionField.getLong(task);
            TASK_GENERATION_SERVICE.set(renderGenerationService);
            TASK_POSITION.set(position);
            TASK_SEQUENCE.set(REPAIR_SEQUENCE.get());
            IN_FLIGHT_TASKS.put(new TaskKey(renderGenerationService, position), Boolean.TRUE);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            TASK_SEQUENCE.remove();
            TASK_POSITION.remove();
            TASK_GENERATION_SERVICE.remove();
            if (!taskLookupFailed) {
                taskLookupFailed = true;
                LOGGER.warn("Unable to track Voxy render task positions", exception);
            }
        }
    }

    public static void markRenderTaskRequested(Object renderGenerationService, long position) {
        if (renderGenerationService == null || SUPPRESS_DIRTY_MARK.get() != null) return;
        TaskKey key = new TaskKey(renderGenerationService, position);
        if (!IN_FLIGHT_TASKS.containsKey(key)) return;
        DIRTY_TASKS.put(key, Boolean.TRUE);
        long count = DIRTY_TASK_MARK_COUNT.incrementAndGet();
        if (count <= 8 || count % 1000 == 0) {
            LOGGER.info("Voxy render task dirtied while in flight ({} total)", count);
        }
    }

    @SuppressWarnings("unchecked")
    public static void acceptIfCurrent(Consumer<?> consumer, Object result) {
        Long taskStamp = TASK_SEQUENCE.get();
        long currentRepairSequence = REPAIR_SEQUENCE.get();
        long taskRepairSequence = taskStamp == null ? currentRepairSequence : taskStamp;
        Object renderGenerationService = TASK_GENERATION_SERVICE.get();
        Long taskPosition = TASK_POSITION.get();
        long position = taskPosition == null ? position(result) : taskPosition;
        TaskKey key = renderGenerationService == null || position == Long.MIN_VALUE
                ? null
                : new TaskKey(renderGenerationService, position);

        if (taskStamp != null && taskRepairSequence != currentRepairSequence) {
            long count = STALE_RESULT_COUNT.incrementAndGet();
            if (count <= 8 || count % 100 == 0) {
                LOGGER.info("Discarded Voxy render result from a replaced renderer ({} total)", count);
            }
            if (key != null) {
                DIRTY_TASKS.remove(key);
                IN_FLIGHT_TASKS.remove(key);
            }
            clearTaskLocals();
            free(result);
            return;
        }

        boolean dirty = false;
        try {
            ((Consumer<Object>) consumer).accept(result);
        } finally {
            if (key != null) {
                dirty = DIRTY_TASKS.remove(key) != null;
                IN_FLIGHT_TASKS.remove(key);
            }
            clearTaskLocals();
        }

        if (dirty) {
            enqueueCurrentMesh(renderGenerationService, position);
            long count = DIRTY_TASK_REQUEUE_COUNT.incrementAndGet();
            if (count <= 8 || count % 100 == 0) {
                LOGGER.info("Requeued Voxy mesh after an in-flight world update ({} total)", count);
            }
        }
    }

    public static boolean requestMesh(Object renderGenerationService, long position) {
        if (renderGenerationService == null || position == Long.MIN_VALUE) return false;
        TaskKey key = new TaskKey(renderGenerationService, position);
        if (IN_FLIGHT_TASKS.containsKey(key)) {
            DIRTY_TASKS.put(key, Boolean.TRUE);
            return true;
        }
        return enqueueCurrentMesh(renderGenerationService, position);
    }

    private static boolean enqueueCurrentMesh(Object renderGenerationService, long position) {
        if (renderGenerationService == null || position == Long.MIN_VALUE) return false;
        try {
            Method enqueueTask = ENQUEUE_METHODS.computeIfAbsent(
                    renderGenerationService.getClass(),
                    RoxyVoxyRenderCompat::findEnqueueMethod
            );
            SUPPRESS_DIRTY_MARK.set(Boolean.TRUE);
            try {
                enqueueTask.invoke(renderGenerationService, position);
            } finally {
                SUPPRESS_DIRTY_MARK.remove();
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!generationLookupFailed) {
                generationLookupFailed = true;
                LOGGER.warn("Unable to requeue a Voxy render task after an in-flight update", exception);
            }
            return false;
        }
    }

    private static void clearTaskLocals() {
        TASK_SEQUENCE.remove();
        TASK_POSITION.remove();
        TASK_GENERATION_SERVICE.remove();
    }

    private static Field findTaskPositionField(Class<?> type) {
        try {
            Field field = type.getDeclaredField("position");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Roxy could not resolve Voxy render task position", exception);
        }
    }

    private static Method findEnqueueMethod(Class<?> type) {
        try {
            return type.getMethod("enqueueTask", long.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Roxy could not resolve Voxy render task enqueue", exception);
        }
    }

    private static long position(Object result) {
        try {
            Field field = POSITION_FIELDS.computeIfAbsent(result.getClass(), RoxyVoxyRenderCompat::findPositionField);
            return field.getLong(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!positionLookupFailed) {
                positionLookupFailed = true;
                LOGGER.warn("Unable to inspect a Voxy render result position", exception);
            }
            return Long.MIN_VALUE;
        }
    }

    private static Field findPositionField(Class<?> type) {
        try {
            Field field = type.getField("position");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Roxy could not resolve Voxy render result position", exception);
        }
    }

    private static void free(Object result) {
        try {
            result.getClass().getMethod("free").invoke(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Unable to free a discarded Voxy render result", exception);
        }
    }

    private record TaskKey(Object renderGenerationService, long position) {
    }
}
