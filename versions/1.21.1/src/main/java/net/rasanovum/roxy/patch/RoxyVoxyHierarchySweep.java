package net.rasanovum.roxy.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class RoxyVoxyHierarchySweep {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final long SWEEP_DELAY_MILLIS = 10_000L;
    private static final long WAKE_DELAY_MILLIS = 50L;
    private static final int BATCH_SIZE = 32;
    private static final int MAX_POSITIONS = 65_536;
    private static final int NODE_ID_MASK = (1 << 24) - 1;
    private static final int NODE_TYPE_MASK = 0b11 << 30;
    private static final int NODE_TYPE_REQUEST = 0b10 << 30;
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new SweepThreadFactory()
    );
    private static final AtomicBoolean WAKE_SCHEDULED = new AtomicBoolean();
    private static volatile ScheduledFuture<?> scheduledWake;
    private static volatile Context context;
    private static volatile Access access;
    private static volatile boolean accessFailed;

    private RoxyVoxyHierarchySweep() {
    }

    public static void setContext(Object engine, Object renderer, Object nodeManager) {
        Context current = context;
        if (current != null
                && current.engine == engine
                && current.renderer == renderer
                && current.nodeManager == nodeManager) {
            return;
        }
        Plan plan = null;
        if (engine != null && renderer != null && nodeManager != null) {
            try {
                plan = Plan.capture(renderer);
                LOGGER.info("Prepared background Voxy hierarchy sweep for {} top-level positions", plan.positions.length);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                logAccessFailure("Unable to prepare the background Voxy hierarchy sweep", exception);
            }
        }
        Context next = new Context(engine, renderer, nodeManager, plan);
        ScheduledFuture<?> previousWake = scheduledWake;
        if (previousWake != null) previousWake.cancel(false);
        scheduledWake = null;
        WAKE_SCHEDULED.set(false);
        context = next;
        if (plan != null) scheduleWake(next, SWEEP_DELAY_MILLIS);
    }

    public static boolean requestManualSweep() {
        Context current = context;
        if (current == null || current.renderer == null || current.nodeManager == null) return false;

        Plan plan;
        try {
            plan = Plan.capture(current.renderer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Unable to prepare the manually requested Voxy hierarchy sweep", exception);
            return false;
        }

        long now = System.currentTimeMillis();
        plan.dueAt = now;
        plan.nextAllowedAt = now;
        Context next = new Context(current.engine, current.renderer, current.nodeManager, plan);
        ScheduledFuture<?> previousWake = scheduledWake;
        if (previousWake != null) previousWake.cancel(false);
        scheduledWake = null;
        WAKE_SCHEDULED.set(false);
        context = next;
        scheduleWake(next, 0L);
        LOGGER.info("Manually requested background Voxy hierarchy sweep");
        return true;
    }

    public static void processAsync(Object asyncNodeManager) {
        Context current = context;
        Plan plan = current == null ? null : current.plan;
        long now = System.currentTimeMillis();
        if (plan == null
                || current.nodeManager != asyncNodeManager
                || now < plan.dueAt
                || now < plan.nextAllowedAt) return;

        try {
            Access resolved = access(asyncNodeManager.getClass().getClassLoader(), asyncNodeManager.getClass());
            int processed = 0;
            while (processed++ < BATCH_SIZE && plan.next < plan.positions.length) {
                if (context != current) return;
                long position = plan.positions[plan.next++];
                Object manager = resolved.manager.get(asyncNodeManager);
                Object activeSections = resolved.activeSectionMap.get(manager);
                Object topLevelNodes = resolved.topLevelNodes.get(manager);
                boolean active = (boolean) resolved.activeContains.invoke(activeSections, position);
                boolean topLevel = (boolean) resolved.topLevelContains.invoke(topLevelNodes, position);
                if (active) {
                    int nodeId = (int) resolved.activeGet.invoke(activeSections, position);
                    if ((nodeId & NODE_TYPE_MASK) != NODE_TYPE_REQUEST) {
                        resolved.invalidateNode.invoke(manager, nodeId & NODE_ID_MASK);
                        plan.nodeInvalidations++;
                    }
                }
                if (active && topLevel) {
                    Object section = resolved.acquireIfExists.invoke(current.engine, position);
                    if (section != null) {
                        try {
                            resolved.submitChildChange.invoke(asyncNodeManager, section);
                        } finally {
                            resolved.release.invoke(section);
                        }
                    }
                }
                if (RoxyVoxyRenderPatch.requestMesh(plan.renderGenerationService, position)) {
                    plan.meshRequests++;
                }
            }

            if (plan.next < plan.positions.length) {
                plan.nextAllowedAt = System.currentTimeMillis() + WAKE_DELAY_MILLIS;
                scheduleWake(current, WAKE_DELAY_MILLIS);
            } else if (!plan.completed) {
                plan.completed = true;
                LOGGER.info(
                        "Completed background Voxy hierarchy sweep; invalidated {} active nodes and requested {} render refreshes",
                        plan.nodeInvalidations,
                        plan.meshRequests
                );
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logAccessFailure("Background Voxy hierarchy sweep stopped for this Voxy ABI", exception);
            plan.next = plan.positions.length;
        }
    }

    private static void scheduleWake(Context expected, long delayMillis) {
        if (!WAKE_SCHEDULED.compareAndSet(false, true)) return;
        scheduledWake = EXECUTOR.schedule(() -> {
            WAKE_SCHEDULED.set(false);
            if (context != expected || expected.plan == null || expected.plan.next >= expected.plan.positions.length) return;
            try {
                Access resolved = access(expected.nodeManager.getClass().getClassLoader(), expected.nodeManager.getClass());
                LockSupport.unpark((Thread) resolved.thread.get(expected.nodeManager));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                logAccessFailure("Unable to wake Voxy's hierarchy worker for a background sweep", exception);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static Access access(ClassLoader loader, Class<?> asyncNodeManager) throws ReflectiveOperationException {
        Access cached = access;
        if (cached != null && cached.asyncNodeManager == asyncNodeManager) return cached;
        synchronized (RoxyVoxyHierarchySweep.class) {
            if (access == null || access.asyncNodeManager != asyncNodeManager) {
                access = Access.resolve(loader, asyncNodeManager);
            }
            return access;
        }
    }

    private static void logAccessFailure(String message, Throwable exception) {
        if (!accessFailed) {
            accessFailed = true;
            LOGGER.warn(message, exception);
        }
    }

    private record Context(Object engine, Object renderer, Object nodeManager, Plan plan) {
    }

    private static final class Plan {
        private long dueAt;
        private final Object renderGenerationService;
        private final long[] positions;
        private long nextAllowedAt;
        private int next;
        private int meshRequests;
        private int nodeInvalidations;
        private boolean completed;

        private Plan(Object renderGenerationService, long[] positions) {
            this.dueAt = System.currentTimeMillis() + SWEEP_DELAY_MILLIS;
            this.renderGenerationService = renderGenerationService;
            this.nextAllowedAt = this.dueAt;
            this.positions = positions;
        }

        private static Plan capture(Object renderer) throws ReflectiveOperationException {
            Field distanceTracker = renderer.getClass().getDeclaredField("renderDistanceTracker");
            distanceTracker.setAccessible(true);
            Object trackerObject = distanceTracker.get(renderer);
            if (trackerObject == null) throw new IllegalStateException("Voxy render-distance tracker was null");
            Class<?> trackerClass = trackerObject.getClass();
            Field tracker = trackerClass.getDeclaredField("tracker");
            Field renderDistance = trackerClass.getDeclaredField("renderDistance");
            Field minSection = trackerClass.getDeclaredField("minSec");
            Field maxSection = trackerClass.getDeclaredField("maxSec");
            tracker.setAccessible(true);
            renderDistance.setAccessible(true);
            minSection.setAccessible(true);
            maxSection.setAccessible(true);
            Object ring = tracker.get(trackerObject);
            Class<?> ringClass = ring.getClass();
            Field centerX = ringClass.getDeclaredField("centerX");
            Field centerZ = ringClass.getDeclaredField("centerZ");
            Field boundDistance = ringClass.getDeclaredField("boundDist");
            centerX.setAccessible(true);
            centerZ.setAccessible(true);
            boundDistance.setAccessible(true);
            int radius = renderDistance.getInt(trackerObject);
            int minY = minSection.getInt(trackerObject);
            int maxY = maxSection.getInt(trackerObject);
            Field renderGeneration = renderer.getClass().getDeclaredField("renderGen");
            int centerXValue = centerX.getInt(ring);
            int centerZValue = centerZ.getInt(ring);
            renderGeneration.setAccessible(true);
            Object renderGenerationService = renderGeneration.get(renderer);
            int[] bounds = ((int[]) boundDistance.get(ring)).clone();
            int width = radius * 2 + 1;
            int yCount = Math.max(0, maxY - minY + 1);
            long count = 0L;
            for (int i = 0; i < width && i < bounds.length; i++) {
                count += (long) (bounds[i] * 2 + 1) * yCount;
            }
            int capacity = (int) Math.min(Math.max(0L, count), MAX_POSITIONS);
            long[] positions = new long[capacity];
            int next = 0;
            outer:
            for (int i = 0; i < width && i < bounds.length; i++) {
                int x = centerXValue + i - radius;
                for (int z = centerZValue - bounds[i]; z <= centerZValue + bounds[i]; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        if (next >= positions.length) break outer;
                        positions[next++] = worldSectionId(4, x, y, z);
                    }
                }
            }
            return new Plan(renderGenerationService, positions);
        }
    }

    private record Access(
            Class<?> asyncNodeManager,
            Field thread,
            Field manager,
            Field activeSectionMap,
            Field topLevelNodes,
            Method activeContains,
            Method activeGet,
            Method topLevelContains,
            Method acquireIfExists,
            Method submitChildChange,
            Method invalidateNode,
            Method release
    ) {
        private static Access resolve(ClassLoader loader, Class<?> asyncNodeManager) throws ReflectiveOperationException {
            Class<?> nodeManager = asyncNodeManager.getDeclaredField("manager").getType();
            Field thread = asyncNodeManager.getDeclaredField("thread");
            Field manager = asyncNodeManager.getDeclaredField("manager");
            Field activeSectionMap = nodeManager.getDeclaredField("activeSectionMap");
            Field topLevelNodes = nodeManager.getDeclaredField("topLevelNodes");
            thread.setAccessible(true);
            manager.setAccessible(true);
            activeSectionMap.setAccessible(true);
            topLevelNodes.setAccessible(true);
            Class<?> worldEngine = Class.forName("me.cortex.voxy.common.world.WorldEngine", false, loader);
            Class<?> worldSection = Class.forName("me.cortex.voxy.common.world.WorldSection", false, loader);
            Method submitChildChange = asyncNodeManager.getDeclaredMethod("submitChildChange", worldSection);
            submitChildChange.setAccessible(true);
            Method invalidateNode = nodeManager.getDeclaredMethod("invalidateNode", int.class);
            invalidateNode.setAccessible(true);
            return new Access(
                    asyncNodeManager,
                    thread,
                    manager,
                    activeSectionMap,
                    topLevelNodes,
                    activeSectionMap.getType().getMethod("containsKey", long.class),
                    activeSectionMap.getType().getMethod("get", long.class),
                    topLevelNodes.getType().getMethod("contains", long.class),
                    worldEngine.getMethod("acquireIfExists", long.class),
                    submitChildChange,
                    invalidateNode,
                    worldSection.getMethod("release")
            );
        }
    }

    private static long worldSectionId(int level, int x, int y, int z) {
        return ((long) level << 60)
                | ((long) (y & 0xFF) << 52)
                | ((long) (z & ((1 << 24) - 1)) << 28)
                | ((long) (x & ((1 << 24) - 1)) << 4);
    }

    private static final class SweepThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Roxy Voxy Hierarchy Sweep");
            thread.setDaemon(true);
            return thread;
        }
    }
}
