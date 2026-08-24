package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class RoxyVoxyMaskSweepCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final long SWEEP_DELAY_MILLIS = 10_000L;
    private static final int MAX_PENDING_POSITIONS = 16_384;
    private static final int BATCH_SIZE = 32;
    private static final int MAX_REPAIRS_PER_BATCH = 8;
    private static final long BATCH_NANOS = 2_000_000L;
    private static final int CHILD_EXISTENCE_UPDATE = 2;
    private static final ConcurrentLinkedQueue<Long> PENDING_QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentMap<Long, Boolean> PENDING_POSITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Object, ConcurrentLinkedQueue<Long>> EARLY_EVENTS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new SweepThreadFactory()
    );
    private static volatile Object engine;
    private static volatile Object nodeManager;
    private static volatile long contextGeneration;
    private static volatile long dueAt;
    private static volatile Access access;
    private static volatile boolean accessFailed;
    private static long lastLogAt;

    static {
        EXECUTOR.scheduleWithFixedDelay(
                RoxyVoxyMaskSweepCompat::processBatch,
                250L,
                250L,
                TimeUnit.MILLISECONDS
        );
    }

    private RoxyVoxyMaskSweepCompat() {
    }

    public static void setContext(Object currentEngine, Object currentNodeManager) {
        if (engine == currentEngine && nodeManager == currentNodeManager) return;
        engine = currentEngine;
        nodeManager = currentNodeManager;
        contextGeneration++;
        dueAt = currentEngine == null || currentNodeManager == null
                ? 0L
                : System.currentTimeMillis() + SWEEP_DELAY_MILLIS;
        PENDING_QUEUE.clear();
        PENDING_POSITIONS.clear();
        if (currentNodeManager == null) {
            EARLY_EVENTS.clear();
        } else {
            ConcurrentLinkedQueue<Long> early = EARLY_EVENTS.remove(currentNodeManager);
            if (early != null) {
                Long position;
                while ((position = early.poll()) != null) queue(position);
            }
            EARLY_EVENTS.clear();
        }
    }

    public static void recordWorldEvent(Object eventNodeManager, Object section) {
        if (eventNodeManager == null || section == null) return;
        try {
            Access resolved = access(section.getClass().getClassLoader());
            long position = resolved.sectionKey.getLong(section);
            long now = System.currentTimeMillis();
            if (nodeManager == null || eventNodeManager != nodeManager) {
                bufferEarlyEvent(eventNodeManager, position);
                return;
            }
            if (PENDING_POSITIONS.size() >= MAX_PENDING_POSITIONS) return;
            boolean wasEmpty = PENDING_POSITIONS.isEmpty();
            if (PENDING_POSITIONS.putIfAbsent(position, Boolean.TRUE) == null) {
                PENDING_QUEUE.offer(position);
            }
            if (dueAt == 0L || (wasEmpty && now >= dueAt)) dueAt = now + SWEEP_DELAY_MILLIS;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Unable to queue a Voxy section for background LoD verification", exception);
        }
    }

    private static void bufferEarlyEvent(Object eventNodeManager, long position) {
        ConcurrentLinkedQueue<Long> early = EARLY_EVENTS.computeIfAbsent(
                eventNodeManager,
                ignored -> new ConcurrentLinkedQueue<>()
        );
        if (early.size() < MAX_PENDING_POSITIONS) early.offer(position);
    }

    private static void processBatch() {
        long now = System.currentTimeMillis();
        if (now < dueAt) return;
        Object currentEngine = engine;
        Object currentNodeManager = nodeManager;
        if (currentEngine == null || currentNodeManager == null) return;

        try {
            Access resolved = access(currentEngine.getClass().getClassLoader());
            long generation = contextGeneration;
            long deadline = System.nanoTime() + BATCH_NANOS;
            int processed = 0;
            int repaired = 0;
            while (processed++ < BATCH_SIZE && repaired < MAX_REPAIRS_PER_BATCH && System.nanoTime() < deadline) {
                Long position = PENDING_QUEUE.poll();
                if (position == null) break;
                PENDING_POSITIONS.remove(position);
                if (generation != contextGeneration || currentEngine != engine || currentNodeManager != nodeManager) return;
                if (verifySection(currentEngine, position, resolved)) repaired++;
            }
            if (repaired != 0 && now - lastLogAt >= 1000L) {
                lastLogAt = now;
                LOGGER.info("Background Voxy LoD verifier repaired {} section states; {} candidates remain", repaired, PENDING_QUEUE.size());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Background Voxy LoD verification stopped for this Voxy ABI", exception);
        }
    }

    private static boolean verifySection(Object currentEngine, long position, Access resolved)
            throws ReflectiveOperationException {
        Object section = resolved.acquireIfExists.invoke(currentEngine, position);
        if (section == null) return false;
        try {
            int level = resolved.level.getInt(section);
            int x = resolved.x.getInt(section);
            int y = resolved.y.getInt(section);
            int z = resolved.z.getInt(section);
            int expected;
            if (level == 0) {
                expected = ((Number) resolved.nonEmptyBlockCount.invoke(section)).intValue() == 0 ? 0 : 0xFF;
                int actual = Byte.toUnsignedInt((Byte) resolved.nonEmptyChildren.invoke(section));
                if (actual == expected) return false;
                if (!(boolean) resolved.updateLvl0State.invoke(section)) return false;
            } else {
                expected = 0;
                for (int child = 0; child < 8; child++) {
                    int childX = (x << 1) + (child & 1);
                    int childY = (y << 1) + ((child >> 2) & 1);
                    int childZ = (z << 1) + ((child >> 1) & 1);
                    Object childSection = resolved.acquireIfExists.invoke(
                            currentEngine,
                            worldSectionId(level - 1, childX, childY, childZ)
                    );
                    if (childSection == null) continue;
                    try {
                        if ((Byte) resolved.nonEmptyChildren.invoke(childSection) != 0) expected |= 1 << child;
                    } finally {
                        resolved.release.invoke(childSection);
                    }
                }
                int actual = Byte.toUnsignedInt((Byte) resolved.nonEmptyChildren.invoke(section));
                if (actual == expected) return false;
                resolved.unsafeSetNonEmptyChildren.invoke(section, (byte) expected);
            }

            resolved.markDirty.invoke(currentEngine, section, CHILD_EXISTENCE_UPDATE, 0);
            if (level < 4) {
                queue(worldSectionId(level + 1, x >> 1, y >> 1, z >> 1));
            }
            return true;
        } finally {
            resolved.release.invoke(section);
        }
    }

    private static void queue(long position) {
        if (PENDING_POSITIONS.size() >= MAX_PENDING_POSITIONS) return;
        if (PENDING_POSITIONS.putIfAbsent(position, Boolean.TRUE) == null) {
            PENDING_QUEUE.offer(position);
        }
    }

    private static long worldSectionId(int level, int x, int y, int z) {
        return ((long) level << 60)
                | ((long) (y & 0xFF) << 52)
                | ((long) (z & ((1 << 24) - 1)) << 28)
                | ((long) (x & ((1 << 24) - 1)) << 4);
    }

    private static Access access(ClassLoader loader) throws ReflectiveOperationException {
        Access cached = access;
        if (cached != null) return cached;
        synchronized (RoxyVoxyMaskSweepCompat.class) {
            if (access == null) access = Access.resolve(loader);
            return access;
        }
    }

    private static void logAccessFailure(String message, Throwable exception) {
        if (!accessFailed) {
            accessFailed = true;
            LOGGER.warn(message, exception);
        }
    }

    private record Access(
            Field sectionKey,
            Field level,
            Field x,
            Field y,
            Field z,
            Method acquireIfExists,
            Method release,
            Method nonEmptyChildren,
            Method nonEmptyBlockCount,
            Method updateLvl0State,
            Method unsafeSetNonEmptyChildren,
            Method markDirty
    ) {
        private static Access resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> worldSection = Class.forName("me.cortex.voxy.common.world.WorldSection", false, loader);
            Class<?> worldEngine = Class.forName("me.cortex.voxy.common.world.WorldEngine", false, loader);
            Field sectionKey = worldSection.getField("key");
            Field level = worldSection.getField("lvl");
            Field x = worldSection.getField("x");
            Field y = worldSection.getField("y");
            Field z = worldSection.getField("z");
            for (Field field : new Field[]{sectionKey, level, x, y, z}) field.setAccessible(true);
            Method markDirty = worldEngine.getMethod("markDirty", worldSection, int.class, int.class);
            return new Access(
                    sectionKey,
                    level,
                    x,
                    y,
                    z,
                    worldEngine.getMethod("acquireIfExists", long.class),
                    worldSection.getMethod("release"),
                    worldSection.getMethod("getNonEmptyChildren"),
                    worldSection.getMethod("getNonEmptyBlockCount"),
                    worldSection.getMethod("updateLvl0State"),
                    worldSection.getMethod("_unsafeSetNonEmptyChildren", byte.class),
                    markDirty
            );
        }
    }

    private static final class SweepThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Roxy Voxy Mask Sweep");
            thread.setDaemon(true);
            return thread;
        }
    }
}
