package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rasanovum.roxy.patch.RoxyVoxyRenderPatch;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RoxyVoxyRendererReloadCompat {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final ThreadLocal<IrisReloadState> IRIS_RELOAD = new ThreadLocal<>();
    private static final AtomicReference<PendingReload> PENDING = new AtomicReference<>();
    private static final AtomicReference<Quarantine> QUARANTINE = new AtomicReference<>();
    private static final AtomicLong RELOAD_GENERATION = new AtomicLong();
    private static final long QUIET_INTERVAL_NANOS = 500_000_000L;
    private static final long MAX_DEFER_NANOS = 5_000_000_000L;
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 5000L;
    private static volatile Accessors accessors;
    private static volatile boolean accessorsFailureLogged;
    private static long lastFailureAt;

    private RoxyVoxyRendererReloadCompat() {
    }

    public static void beginIrisReload() {
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
            RoxyVoxyRenderPatch.prepareIrisRendererReload();
            shutdownRenderer.invoke(levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            IRIS_RELOAD.remove();
            Throwable cause = rootCause(exception);
            if (isFatal(exception)) quarantine(world, levelRenderer, generation);
            logFailure("Unable to retire Voxy before an Iris pipeline reload", cause);
        }
    }

    public static void finishIrisReload() {
        IrisReloadState state = IRIS_RELOAD.get();
        if (state == null) return;
        state.completed = true;

        PendingReload pending = PENDING.get();
        if (pending != null
                && pending.matches(state.levelRenderer, state.world)) {
            IRIS_RELOAD.remove();
            return;
        }

        try {
            deferReload(state.levelRenderer);
            IRIS_RELOAD.remove();
        } catch (RuntimeException | LinkageError exception) {
            IRIS_RELOAD.remove();
            logFailure("Unable to recreate Voxy after an Iris pipeline reload", exception);
        }
    }

    public static void invokeImmediateVoxyReload(Object levelRenderer) {
        beginExplicitReloadGeneration();
        try {
            levelRenderer.getClass().getMethod("allChanged").invoke(levelRenderer);
        } catch (InvocationTargetException exception) {
            Throwable cause = rootCause(exception);
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
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                Throwable cause = rootCause(exception);
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
                    now
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
                            now
                    );
                }
                if (PENDING.compareAndSet(pending, next)) {
                    if (pending == null || pending.generation != generation || !pending.matches(levelRenderer, world)) {
                        LOGGER.info("Deferred Voxy renderer reload until the client render state is stable");
                    }
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Throwable cause = rootCause(exception);
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
            if (quietNanos < QUIET_INTERVAL_NANOS && deferredNanos < MAX_DEFER_NANOS) return;

            if (!PENDING.compareAndSet(pending, null)) return;
            try {
                pending.shutdownRenderer.invoke(expectedLevelRenderer);
                pending.createRenderer.invoke(expectedLevelRenderer);
                if (quietNanos >= QUIET_INTERVAL_NANOS) {
                    LOGGER.info("Applied deferred Voxy renderer reload after the client render state stabilized");
                } else {
                    LOGGER.warn("Applied deferred Voxy renderer reload after the maximum stabilization wait elapsed");
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                Throwable cause = rootCause(exception);
                if (isFatal(exception)) {
                    quarantine(pending, cause);
                    return;
                }
                try {
                    Object renderer = pending.getRenderSystem.invoke(expectedLevelRenderer);
                    if (renderer == null || renderer == pending.renderer.get()) restorePending(pending);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError inspectionFailure) {
                    exception.addSuppressed(inspectionFailure);
                    Throwable inspectionCause = rootCause(inspectionFailure);
                    if (isFatal(inspectionFailure)) quarantine(pending, inspectionCause);
                    else restorePending(pending);
                }
                long now = System.currentTimeMillis();
                if (now - lastFailureAt >= FAILURE_LOG_INTERVAL_MILLIS) {
                    lastFailureAt = now;
                    LOGGER.warn("Unable to apply deferred Voxy renderer reload", cause);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Throwable cause = rootCause(exception);
            if (isFatal(exception)) {
                quarantine(pending, cause);
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFailureAt >= FAILURE_LOG_INTERVAL_MILLIS) {
                lastFailureAt = now;
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
                PENDING.compareAndSet(null, new PendingReload(
                        expectedLevelRenderer,
                        world,
                        pending.renderer.get(),
                        pending.shutdownRenderer,
                        pending.createRenderer,
                        pending.getRenderSystem,
                        pending.generation,
                        pending.firstRequestedAtNanos,
                        System.nanoTime()
                ));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("Unable to restore a deferred Voxy renderer reload", rootCause(exception));
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
        logFailure("Quarantined Voxy renderer reload after a fatal compatibility failure", cause);
    }

    private static void quarantine(Object world, Object levelRenderer, long generation) {
        if (generation != RELOAD_GENERATION.get() || world == null || levelRenderer == null) return;
        QUARANTINE.set(new Quarantine(world, levelRenderer, generation));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            Throwable next = current instanceof InvocationTargetException invocation
                    ? invocation.getTargetException()
                    : current.getCause();
            if (next == null || next == current || seen.contains(next)) return current;
            current = next;
        }
        return failure;
    }

    private static boolean isFatal(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof LinkageError) return true;
            String name = current.getClass().getName();
            if (name.equals("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError")
                    || name.equals("org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError")
                    || name.equals("org.spongepowered.asm.mixin.injection.throwables.InjectionError")) return true;
        }
        return false;
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null) return current;
        try {
            ClassLoader loader = findMinecraftLoader();
            Class<?> minecraft = Class.forName(MINECRAFT, false, loader);
            current = new Accessors(
                    minecraft.getMethod("getInstance"),
                    minecraft.getField("level"),
                    minecraft.getField("levelRenderer")
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

    private static void logFailure(String message, Throwable exception) {
        long now = System.currentTimeMillis();
        if (now - lastFailureAt >= FAILURE_LOG_INTERVAL_MILLIS) {
            lastFailureAt = now;
            LOGGER.warn(message, exception);
        }
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyVoxyRendererReloadCompat.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName(MINECRAFT, false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }

    private record Accessors(Method minecraftInstance, Field level, Field levelRenderer) {
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

        private PendingReload(
                Object levelRenderer,
                Object world,
                Object renderer,
                Method shutdownRenderer,
                Method createRenderer,
                Method getRenderSystem,
                long generation,
                long firstRequestedAtNanos,
                long lastRequestedAtNanos
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
        }

        private boolean matches(Object levelRenderer, Object world) {
            return this.levelRenderer.get() == levelRenderer && this.world.get() == world;
        }
    }

    private static final class Quarantine {
        private final WeakReference<Object> world;
        private final WeakReference<Object> levelRenderer;
        private final long generation;

        private Quarantine(
                Object world,
                Object levelRenderer,
                long generation
        ) {
            this.world = new WeakReference<>(world);
            this.levelRenderer = new WeakReference<>(levelRenderer);
            this.generation = generation;
        }
    }

    private static final class IrisReloadState {
        private final Object levelRenderer;
        private final Object world;
        private boolean completed;

        private IrisReloadState(Object levelRenderer, Object world) {
            this.levelRenderer = levelRenderer;
            this.world = world;
        }
    }
}
