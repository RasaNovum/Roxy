package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rasanovum.roxy.patch.RoxyVoxyRenderPatch;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

public final class RoxyVoxyRendererReloadCompat {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final ThreadLocal<IrisReloadState> IRIS_RELOAD = new ThreadLocal<>();
    private static final AtomicReference<PendingReload> PENDING = new AtomicReference<>();
    private static final long QUIET_INTERVAL_NANOS = 500_000_000L;
    private static final long MAX_DEFER_NANOS = 5_000_000_000L;
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 5000L;
    private static volatile Accessors accessors;
    private static volatile boolean accessorsFailureLogged;
    private static long lastFailureAt;

    private RoxyVoxyRendererReloadCompat() {
    }

    public static void beginIrisReload() {
        PENDING.set(null);
        Accessors current = getAccessors();
        if (current == null) return;
        try {
            Object minecraft = current.minecraftInstance.invoke(null);
            Object world = minecraft == null ? null : current.level.get(minecraft);
            Object levelRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            if (world == null || levelRenderer == null) return;

            Method shutdownRenderer = levelRenderer.getClass().getMethod("voxy$shutdownRenderer");
            IRIS_RELOAD.set(new IrisReloadState(levelRenderer, world));
            RoxyVoxyRenderPatch.prepareIrisRendererReload();
            shutdownRenderer.invoke(levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            IRIS_RELOAD.remove();
            logFailure("Unable to retire Voxy before an Iris pipeline reload", exception);
        }
    }

    public static void finishIrisReload() {
        IrisReloadState state = IRIS_RELOAD.get();
        if (state == null) return;
        state.completed = true;

        PendingReload pending = PENDING.get();
        if (pending != null
                && pending.levelRenderer == state.levelRenderer
                && pending.world == state.world) {
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
        try {
            levelRenderer.getClass().getMethod("allChanged").invoke(levelRenderer);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
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

    private static boolean deferReload(Object levelRenderer) {

        Accessors current = getAccessors();
        if (current == null) return false;
        try {
            Object minecraft = current.minecraftInstance.invoke(null);
            Object world = minecraft == null ? null : current.level.get(minecraft);
            Object currentRenderer = minecraft == null ? null : current.levelRenderer.get(minecraft);
            if (world == null || currentRenderer != levelRenderer) return false;

            Method shutdownRenderer = levelRenderer.getClass().getMethod("voxy$shutdownRenderer");
            Method createRenderer = levelRenderer.getClass().getMethod("voxy$createRenderer");
            Method getRenderSystem = levelRenderer.getClass().getMethod("voxy$getRenderSystem");
            Object renderer = getRenderSystem.invoke(levelRenderer);
            long now = System.nanoTime();
            PendingReload next = new PendingReload(
                    levelRenderer,
                    world,
                    renderer,
                    shutdownRenderer,
                    createRenderer,
                    getRenderSystem,
                    now,
                    now
            );
            for (;;) {
                PendingReload pending = PENDING.get();
                if (pending != null
                        && pending.levelRenderer == next.levelRenderer
                        && pending.world == next.world) {
                    next = new PendingReload(
                            pending.levelRenderer,
                            pending.world,
                            pending.renderer,
                            pending.shutdownRenderer,
                            pending.createRenderer,
                            pending.getRenderSystem,
                            pending.firstRequestedAtNanos,
                            now
                    );
                }
                if (PENDING.compareAndSet(pending, next)) {
                    if (pending == null || pending.levelRenderer != next.levelRenderer || pending.world != next.world) {
                        LOGGER.info("Deferred Voxy renderer reload until the client render state is stable");
                    }
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
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
            if (world == null || levelRenderer != pending.levelRenderer || world != pending.world) {
                PENDING.compareAndSet(pending, null);
                return;
            }

            long monotonicNow = System.nanoTime();
            long quietNanos = monotonicNow - pending.lastRequestedAtNanos;
            long deferredNanos = monotonicNow - pending.firstRequestedAtNanos;
            if (quietNanos < QUIET_INTERVAL_NANOS && deferredNanos < MAX_DEFER_NANOS) return;

            if (!PENDING.compareAndSet(pending, null)) return;
            try {
                pending.shutdownRenderer.invoke(pending.levelRenderer);
                pending.createRenderer.invoke(pending.levelRenderer);
                if (quietNanos >= QUIET_INTERVAL_NANOS) {
                    LOGGER.info("Applied deferred Voxy renderer reload after the client render state stabilized");
                } else {
                    LOGGER.warn("Applied deferred Voxy renderer reload after the maximum stabilization wait elapsed");
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                try {
                    Object renderer = pending.getRenderSystem.invoke(pending.levelRenderer);
                    if (renderer == null || renderer == pending.renderer) restorePending(pending);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError inspectionFailure) {
                    exception.addSuppressed(inspectionFailure);
                    restorePending(pending);
                }
                long now = System.currentTimeMillis();
                if (now - lastFailureAt >= FAILURE_LOG_INTERVAL_MILLIS) {
                    lastFailureAt = now;
                    LOGGER.warn("Unable to apply deferred Voxy renderer reload", exception);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            long now = System.currentTimeMillis();
            if (now - lastFailureAt >= FAILURE_LOG_INTERVAL_MILLIS) {
                lastFailureAt = now;
                LOGGER.warn("Unable to inspect deferred Voxy renderer reload state", exception);
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
            if (world == pending.world && levelRenderer == pending.levelRenderer) {
                PENDING.compareAndSet(null, new PendingReload(
                        pending.levelRenderer,
                        pending.world,
                        pending.renderer,
                        pending.shutdownRenderer,
                        pending.createRenderer,
                        pending.getRenderSystem,
                        pending.firstRequestedAtNanos,
                        System.nanoTime()
                ));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
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

    private record PendingReload(
            Object levelRenderer,
            Object world,
            Object renderer,
            Method shutdownRenderer,
            Method createRenderer,
            Method getRenderSystem,
            long firstRequestedAtNanos,
            long lastRequestedAtNanos
    ) {
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
