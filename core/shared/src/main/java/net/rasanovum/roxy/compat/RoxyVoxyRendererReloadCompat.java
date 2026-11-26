package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.patch.RoxyVoxyRenderPatch;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyCompatLifecycle;
import net.rasanovum.roxy.util.RoxyReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RoxyVoxyRendererReloadCompat {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String LEVEL_RENDERER = "net.minecraft.client.renderer.LevelRenderer";
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final ThreadLocal<IrisReloadState> IRIS_RELOAD = new ThreadLocal<>();
    private static final AtomicReference<PendingReload> PENDING = new AtomicReference<>();
    private static final AtomicReference<Quarantine> QUARANTINE = new AtomicReference<>();
    private static final AtomicLong RELOAD_GENERATION = new AtomicLong();
    private static final long QUIET_INTERVAL_NANOS = 500_000_000L;
    private static final long MAX_DEFER_NANOS = 5_000_000_000L;
    private static final int MAX_RETRIES = 4;
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 5000L;
    private static volatile long quietIntervalNanos = QUIET_INTERVAL_NANOS;
    private static volatile Accessors accessors;
    private static volatile boolean accessorsLookupAttempted;
    private static volatile boolean accessorsFailureLogged;
    private static final AtomicLong LAST_FAILURE_AT = new AtomicLong();

    private RoxyVoxyRendererReloadCompat() {
    }

    public static void beginIrisReload() {
        IrisReloadState existing = IRIS_RELOAD.get();
        if (existing != null) {
            existing.depth++;
            return;
        }
        long generation = beginExplicitReloadGeneration();
        PENDING.set(null);
        Accessors current = getAccessors();
        if (current == null) return;
        Object world = null;
        Object levelRenderer = null;
        try {
            Object minecraft = current.minecraftInstance.invoke(null);
            world = minecraft == null ? null : current.level.get(minecraft);
            levelRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            if (world == null || levelRenderer == null) return;

            Method shutdownRenderer = levelRenderer.getClass().getMethod("voxy$shutdownRenderer");
            IRIS_RELOAD.set(new IrisReloadState(levelRenderer, world));
            RoxyCompatLifecycle.onRendererShutdown();
            RoxyVoxyRenderPatch.prepareIrisRendererReload();
            shutdownRenderer.invoke(levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError exception) {
            IRIS_RELOAD.remove();
            Throwable cause = RoxyReflection.rootCause(exception);
            if (isFatal(exception)) quarantine(world, levelRenderer, generation);
            logFailure("Unable to retire Voxy before an Iris pipeline reload", cause);
        }
    }

    public static void finishIrisReload() {
        IrisReloadState state = IRIS_RELOAD.get();
        if (state == null) return;
        if (--state.depth > 0) return;
        state.completed = true;

        PendingReload pending = PENDING.get();
        if (pending != null && pending.matches(state.levelRenderer, state.world)) {
            IRIS_RELOAD.remove();
            return;
        }

        try {
            deferReload(state.levelRenderer);
        } catch (RuntimeException | LinkageError | OutOfMemoryError exception) {
            logFailure("Unable to recreate Voxy after an Iris pipeline reload", exception);
        } finally {
            IRIS_RELOAD.remove();
        }
    }

    public static void invokeImmediateVoxyReload(Object levelRenderer) {
        beginExplicitReloadGeneration();
        try {
            Accessors current = getAccessors();
            Method allChanged = current != null && current.allChanged != null
                    ? current.allChanged
                    : RoxyReflection.findMethod(levelRenderer.getClass(), "allChanged");
            allChanged.invoke(levelRenderer);
        } catch (InvocationTargetException exception) {
            Throwable cause = RoxyReflection.rootCause(exception);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unable to invoke Minecraft's renderer reload", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke Minecraft's renderer reload", exception);
        }
    }

    public static boolean deferVoxyReload(Object levelRenderer) {
        IrisReloadState irisReload = IRIS_RELOAD.get();
        boolean deferred = deferReload(levelRenderer);
        if (deferred && irisReload != null && irisReload.levelRenderer == levelRenderer && irisReload.completed) {
            IRIS_RELOAD.remove();
        }
        return deferred;
    }

    public static boolean isRendererReloadBlocked(Object world, Object levelRenderer) {
        return isQuarantined(world, levelRenderer);
    }

    public static boolean shouldCreateVoxyRenderer(Object levelRenderer) {
        Boolean primary = isPrimaryLevelRenderer(levelRenderer);
        return primary == null || primary;
    }

    public static void observeWorldIfPrimary(Object levelRenderer, Object world) {
        if (world != null && Boolean.TRUE.equals(isPrimaryLevelRenderer(levelRenderer))) {
            RoxyVoxyRenderPatch.observeWorld(world);
        }
    }

    private static Boolean isPrimaryLevelRenderer(Object levelRenderer) {
        if (levelRenderer == null) return null;
        Accessors current = getAccessors();
        if (current == null) return null;
        try {
            Object minecraft = current.minecraftInstance.invoke(null);
            Object primaryLevelRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            return primaryLevelRenderer == null ? null : primaryLevelRenderer == levelRenderer;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("Unable to identify the primary Minecraft renderer for Voxy compatibility", RoxyReflection.rootCause(exception));
            return null;
        }
    }

    private static boolean deferReload(Object levelRenderer) {
        Object world = null;
        Object currentLevelRenderer = null;
        Accessors current = getAccessors();
        if (current == null) return false;
        try {
            Object minecraft = current.minecraftInstance.invoke(null);
            world = minecraft == null ? null : current.level.get(minecraft);
            currentLevelRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            if (world == null || currentLevelRenderer != levelRenderer) return false;

            Method shutdownRenderer = levelRenderer.getClass().getMethod("voxy$shutdownRenderer");
            Method createRenderer = levelRenderer.getClass().getMethod("voxy$createRenderer");
            Method getRenderSystem = levelRenderer.getClass().getMethod("voxy$getRenderSystem");
            Object renderer;
            try {
                renderer = getRenderSystem.invoke(levelRenderer);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError exception) {
                Throwable cause = RoxyReflection.rootCause(exception);
                if (!isFatal(exception)) throw exception;
                quarantine(world, levelRenderer, RELOAD_GENERATION.get());
                logFailure("Quarantined automatic Voxy renderer reload after a fatal bridge failure", cause);
                return true;
            }
            if (isQuarantined(world, levelRenderer)) return true;
            long now = System.nanoTime();
            long generation = RELOAD_GENERATION.get();
            PendingReload next = new PendingReload(
                    levelRenderer,
                    world,
                    renderer,
                    shutdownRenderer,
                    createRenderer,
                    getRenderSystem,
                    generation,
                    now,
                    now,
                    0
            );
            for (;;) {
                PendingReload pending = PENDING.get();
                if (pending != null && pending.generation == generation && pending.matches(levelRenderer, world)) {
                    next = new PendingReload(
                            levelRenderer,
                            world,
                            pending.renderer.get(),
                            pending.shutdownRenderer,
                            pending.createRenderer,
                            pending.getRenderSystem,
                            pending.generation,
                            pending.firstRequestedAtNanos,
                            now,
                            pending.attempts
                    );
                }
                if (PENDING.compareAndSet(pending, next)) {
                    if (pending == null || pending.generation != generation || !pending.matches(levelRenderer, world)) {
                        LOGGER.info("Deferred Voxy renderer reload until the client render state is stable");
                    }
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError exception) {
            Throwable cause = RoxyReflection.rootCause(exception);
            if (isFatal(exception)) {
                if (world != null && currentLevelRenderer == levelRenderer) {
                    quarantine(world, levelRenderer, RELOAD_GENERATION.get());
                }
                logFailure("Quarantined automatic Voxy renderer reload after a fatal bridge failure", cause);
                return true;
            }
            return false;
        }
    }

    public static void processPending() {
        PendingReload pending = PENDING.get();
        if (pending == null) return;

        try {
            Accessors current = getAccessors();
            if (current == null) return;
            Object minecraft = current.minecraftInstance.invoke(null);
            Object world = minecraft == null ? null : current.level.get(minecraft);
            Object levelRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            Object expectedWorld = pending.world.get();
            Object expectedLevelRenderer = pending.levelRenderer.get();
            if (world == null || expectedWorld == null || expectedLevelRenderer == null
                    || levelRenderer != expectedLevelRenderer || world != expectedWorld
                    || pending.generation != RELOAD_GENERATION.get()) {
                PENDING.compareAndSet(pending, null);
                clearQuarantineForDifferentWorld(world);
                return;
            }
            if (isQuarantined(world, levelRenderer)) {
                PENDING.compareAndSet(pending, null);
                return;
            }

            long monotonicNow = System.nanoTime();
            long quietNanos = monotonicNow - pending.lastRequestedAtNanos;
            long deferredNanos = monotonicNow - pending.firstRequestedAtNanos;
            long requiredQuietNanos = quietIntervalNanos;
            if (quietNanos < requiredQuietNanos && deferredNanos < MAX_DEFER_NANOS) return;

            if (!PENDING.compareAndSet(pending, null)) return;
            try {
                RoxyCompatLifecycle.onRendererShutdown();
                pending.shutdownRenderer.invoke(expectedLevelRenderer);
                pending.createRenderer.invoke(expectedLevelRenderer);
                if (quietNanos >= requiredQuietNanos) {
                    LOGGER.info("Applied deferred Voxy renderer reload after the client render state stabilized");
                } else {
                    LOGGER.warn("Applied deferred Voxy renderer reload after the maximum stabilization wait elapsed");
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError exception) {
                Throwable cause = RoxyReflection.rootCause(exception);
                if (isFatal(exception) || pending.attempts + 1 >= MAX_RETRIES) {
                    quarantine(pending, cause);
                    return;
                }
                try {
                    Object renderer = pending.getRenderSystem.invoke(expectedLevelRenderer);
                    if (renderer == null || renderer == pending.renderer.get()) restorePending(pending);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError inspectionFailure) {
                    exception.addSuppressed(inspectionFailure);
                    Throwable inspectionCause = RoxyReflection.rootCause(inspectionFailure);
                    if (isFatal(inspectionFailure)) quarantine(pending, inspectionCause);
                    else restorePending(pending);
                }
                if (shouldLogFailure()) {
                    LOGGER.warn("Unable to apply deferred Voxy renderer reload", cause);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError exception) {
            Throwable cause = RoxyReflection.rootCause(exception);
            if (isFatal(exception)) {
                quarantine(pending, cause);
                return;
            }
            if (shouldLogFailure()) {
                LOGGER.warn("Unable to inspect deferred Voxy renderer reload state", cause);
            }
        }
    }

    private static void restorePending(PendingReload pending) {
        Accessors current = getAccessors();
        if (current == null) return;
        try {
            Object minecraft = current.minecraftInstance.invoke(null);
            Object world = minecraft == null ? null : current.level.get(minecraft);
            Object levelRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            Object expectedWorld = pending.world.get();
            Object expectedLevelRenderer = pending.levelRenderer.get();
            if (world != null && world == expectedWorld && levelRenderer == expectedLevelRenderer
                    && pending.generation == RELOAD_GENERATION.get()
                    && !isQuarantined(world, levelRenderer)) {
                long now = System.nanoTime();
                PENDING.compareAndSet(null, new PendingReload(
                        expectedLevelRenderer,
                        world,
                        pending.renderer.get(),
                        pending.shutdownRenderer,
                        pending.createRenderer,
                        pending.getRenderSystem,
                        pending.generation,
                        now,
                        now,
                        pending.attempts + 1
                ));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("Unable to restore a deferred Voxy renderer reload", RoxyReflection.rootCause(exception));
        }
    }

    private static long beginExplicitReloadGeneration() {
        long generation = RELOAD_GENERATION.incrementAndGet();
        QUARANTINE.set(null);
        return generation;
    }

    private static boolean isQuarantined(Object world, Object levelRenderer) {
        Quarantine quarantine = QUARANTINE.get();
        if (quarantine == null) return false;
        if (quarantine.generation != RELOAD_GENERATION.get()) {
            QUARANTINE.compareAndSet(quarantine, null);
            return false;
        }
        Object quarantinedWorld = quarantine.world.get();
        Object quarantinedLevelRenderer = quarantine.levelRenderer.get();
        if (quarantinedWorld == null || quarantinedLevelRenderer == null) {
            QUARANTINE.compareAndSet(quarantine, null);
            return false;
        }
        if (world != quarantinedWorld) {
            QUARANTINE.compareAndSet(quarantine, null);
            return false;
        }
        return levelRenderer == quarantinedLevelRenderer;
    }

    private static void clearQuarantineForDifferentWorld(Object world) {
        Quarantine quarantine = QUARANTINE.get();
        if (quarantine == null) return;
        Object quarantinedWorld = quarantine.world.get();
        if (world == null || quarantinedWorld == null || world != quarantinedWorld) {
            QUARANTINE.compareAndSet(quarantine, null);
        }
    }

    private static void quarantine(PendingReload pending, Throwable cause) {
        if (pending.generation != RELOAD_GENERATION.get()) return;
        Object levelRenderer = pending.levelRenderer.get();
        Object world = pending.world.get();
        if (world == null || levelRenderer == null) return;
        QUARANTINE.set(new Quarantine(world, levelRenderer, pending.generation));
        PENDING.compareAndSet(pending, null);
        logFailure("Quarantined Voxy renderer reload after repeated or fatal compatibility failures", cause);
    }

    private static void quarantine(Object world, Object levelRenderer, long generation) {
        if (generation != RELOAD_GENERATION.get() || world == null || levelRenderer == null) return;
        QUARANTINE.set(new Quarantine(world, levelRenderer, generation));
    }

    static boolean isFatal(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof LinkageError || current instanceof VirtualMachineError) return true;
            String name = current.getClass().getName();
            if (name.equals("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError")
                    || name.equals("org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError")
                    || name.equals("org.spongepowered.asm.mixin.injection.throwables.InjectionError")) return true;
            current = current instanceof InvocationTargetException invocation
                    ? invocation.getTargetException()
                    : current.getCause();
        }
        return false;
    }

    static boolean isQuarantinedForTest(Object world, Object levelRenderer) {
        return isQuarantined(world, levelRenderer);
    }

    static void configureForTest(Method minecraftInstance, Field level, Field levelRenderer, Method allChanged, long quietNanos) {
        resetForTest();
        accessors = new Accessors(minecraftInstance, level, levelRenderer, allChanged);
        accessorsLookupAttempted = true;
        quietIntervalNanos = quietNanos;
    }

    static void resetForTest() {
        IRIS_RELOAD.remove();
        PENDING.set(null);
        QUARANTINE.set(null);
        RELOAD_GENERATION.set(0L);
        LAST_FAILURE_AT.set(0L);
        accessors = null;
        accessorsLookupAttempted = false;
        accessorsFailureLogged = false;
        quietIntervalNanos = QUIET_INTERVAL_NANOS;
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null || accessorsLookupAttempted) return current;
        synchronized (RoxyVoxyRendererReloadCompat.class) {
            current = accessors;
            if (current != null || accessorsLookupAttempted) return current;
            accessorsLookupAttempted = true;
            try {
                ClassLoader loader = RoxyClassLoaders.findGameLoader();
                Class<?> minecraft = RoxyClassLoaders.loadMinecraftClass(MINECRAFT, loader);
                Class<?> levelRenderer = RoxyClassLoaders.loadMinecraftClass(LEVEL_RENDERER, loader);
                current = new Accessors(
                        RoxyReflection.findMethod(minecraft, "getInstance"),
                        RoxyReflection.findField(minecraft, "level"),
                        RoxyReflection.findField(minecraft, "levelRenderer"),
                        RoxyReflection.tryFindMethod(levelRenderer, "allChanged")
                );
                accessors = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (!accessorsFailureLogged) {
                    accessorsFailureLogged = true;
                    LOGGER.warn("Roxy could not resolve Minecraft's renderer reload bridge", exception);
                }
                return null;
            }
        }
    }

    private static boolean shouldLogFailure() {
        long now = System.currentTimeMillis();
        long previous = LAST_FAILURE_AT.get();
        return now - previous >= FAILURE_LOG_INTERVAL_MILLIS
                && LAST_FAILURE_AT.compareAndSet(previous, now);
    }

    private static void logFailure(String message, Throwable exception) {
        if (shouldLogFailure()) {
            LOGGER.warn(message, exception);
        }
    }

    private record Accessors(Method minecraftInstance, Field level, Field levelRenderer, Method allChanged) {
    }

    private static final class PendingReload {
        private final WeakReference<Object> levelRenderer;
        private final WeakReference<Object> world;
        private final WeakReference<Object> renderer;
        private final Method shutdownRenderer;
        private final Method createRenderer;
        private final Method getRenderSystem;
        private final long generation;
        private final long firstRequestedAtNanos;
        private final long lastRequestedAtNanos;
        private final int attempts;

        private PendingReload(
                Object levelRenderer,
                Object world,
                Object renderer,
                Method shutdownRenderer,
                Method createRenderer,
                Method getRenderSystem,
                long generation,
                long firstRequestedAtNanos,
                long lastRequestedAtNanos,
                int attempts
        ) {
            this.levelRenderer = new WeakReference<>(levelRenderer);
            this.world = new WeakReference<>(world);
            this.renderer = new WeakReference<>(renderer);
            this.shutdownRenderer = shutdownRenderer;
            this.createRenderer = createRenderer;
            this.getRenderSystem = getRenderSystem;
            this.generation = generation;
            this.firstRequestedAtNanos = firstRequestedAtNanos;
            this.lastRequestedAtNanos = lastRequestedAtNanos;
            this.attempts = attempts;
        }

        private boolean matches(Object levelRenderer, Object world) {
            return this.levelRenderer.get() == levelRenderer && this.world.get() == world;
        }
    }

    private static final class Quarantine {
        private final WeakReference<Object> world;
        private final WeakReference<Object> levelRenderer;
        private final long generation;

        private Quarantine(Object world, Object levelRenderer, long generation) {
            this.world = new WeakReference<>(world);
            this.levelRenderer = new WeakReference<>(levelRenderer);
            this.generation = generation;
        }
    }

    private static final class IrisReloadState {
        private final Object levelRenderer;
        private final Object world;
        private int depth = 1;
        private boolean completed;

        private IrisReloadState(Object levelRenderer, Object world) {
            this.levelRenderer = levelRenderer;
            this.world = world;
        }
    }
}
