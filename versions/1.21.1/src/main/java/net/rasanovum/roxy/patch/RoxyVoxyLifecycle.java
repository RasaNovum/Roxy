package net.rasanovum.roxy.patch;

import net.rasanovum.roxy.compat.RoxyVoxyRendererReloadCompat;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

public final class RoxyVoxyLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final long TICK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long ERROR_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static volatile Methods methods;
    private static volatile boolean resolutionFailed;
    private static long lastTickAtNanos;
    private static boolean hasTicked;
    private static long lastErrorLogAtNanos;
    private static boolean hasLoggedError;
    private static WeakReference<Object> instance = new WeakReference<>(null);
    private static WeakReference<Object> engine = new WeakReference<>(null);
    private static WeakReference<Object> renderer = new WeakReference<>(null);
    private static WeakReference<Object> level = new WeakReference<>(null);
    private static boolean worldActive;
    private static boolean rendererReady;

    private RoxyVoxyLifecycle() {
    }

    public static void tick() {
        if (resolutionFailed) return;
        long now = System.nanoTime();
        if (hasTicked && now - lastTickAtNanos < TICK_INTERVAL_NANOS) return;
        hasTicked = true;
        lastTickAtNanos = now;

        Methods resolved = resolveMethods();
        if (resolved == null) return;

        try {
            Object currentInstance = resolved.voxyInstance.invoke(null);
            Object minecraft = resolved.minecraftInstance.invoke(null);
            if (minecraft == null) return;
            Object currentLevel = resolved.level.get(minecraft);
            if (currentInstance != null) RoxyVoxyRendererReloadCompat.processPending();
            Object currentEngine = currentLevel == null ? null : resolved.worldEngine.invoke(null, currentLevel);
            if (currentLevel == null) {
                if (worldActive) {
                    worldActive = false;
                    RoxyVoxyRenderPatch.retireWorld();
                }
            } else if (!worldActive || currentLevel != level.get()) {
                worldActive = true;
                RoxyVoxyRenderPatch.activateWorld();
            }
            level = new WeakReference<>(currentLevel);
            Object trackedInstance = instance.get();
            Object trackedEngine = engine.get();
            Object trackedRenderer = renderer.get();
            if (currentInstance == null || currentEngine == null) {
                if (trackedInstance != currentInstance || trackedEngine != currentEngine || trackedRenderer != null) {
                    instance = new WeakReference<>(currentInstance);
                    engine = new WeakReference<>(currentEngine);
                    renderer = new WeakReference<>(null);
                    rendererReady = false;
                }
                return;
            }

            Object levelRenderer = resolved.levelRenderer.get(minecraft);
            if (levelRenderer == null) return;
            Object currentRenderer = resolved.getRenderSystem.invoke(levelRenderer);
            boolean worldChanged = currentInstance != trackedInstance || currentEngine != trackedEngine;
            boolean rendererChanged = currentRenderer != trackedRenderer;
            if (worldChanged || rendererChanged) {
                instance = new WeakReference<>(currentInstance);
                engine = new WeakReference<>(currentEngine);
                renderer = new WeakReference<>(currentRenderer);
                rendererReady = false;
                if (worldChanged) RoxyVoxyRenderPatch.observeWorld(currentLevel, currentEngine);
                if (currentRenderer != null) {
                    RoxyVoxyRenderPatch.registerRenderer(currentRenderer);
                    LOGGER.info("Registered Voxy render task tracking for a world or renderer change");
                }
            }
            if (currentRenderer != null) {
                if (!rendererReady) {
                    rendererReady = true;
                    return;
                }
                RoxyVoxyRenderPatch.flushPendingRenderTasks();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!hasLoggedError || now - lastErrorLogAtNanos >= ERROR_LOG_INTERVAL_NANOS) {
                hasLoggedError = true;
                lastErrorLogAtNanos = now;
                LOGGER.warn("Voxy render task lifecycle tick failed", exception);
            }
        }
    }

    private static Methods resolveMethods() {
        Methods cached = methods;
        if (cached != null || resolutionFailed) return cached;
        synchronized (RoxyVoxyLifecycle.class) {
            cached = methods;
            if (cached != null || resolutionFailed) return cached;
            try {
                ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor("net.minecraft.client.Minecraft");
                if (loader == null) {
                    resolutionFailed = true;
                    return null;
                }
                cached = Methods.resolve(loader);
                methods = cached;
                return cached;
            } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | LinkageError exception) {
                resolutionFailed = true;
                LOGGER.warn("Voxy render task lifecycle tracking is unavailable", exception);
                return null;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("Voxy render task lifecycle resolution failed", exception);
                return null;
            }
        }
    }

    private record Methods(
            Method voxyInstance,
            Method minecraftInstance,
            Field level,
            Field levelRenderer,
            Method worldEngine,
            Method getRenderSystem
    ) {
        private static Methods resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> voxyCommon = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level", false, loader);
            Class<?> worldIdentifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            Class<?> renderSystemBridge = Class.forName(
                    "me.cortex.voxy.client.core.IGetVoxyRenderSystem",
                    false,
                    loader
            );
            return new Methods(
                    voxyCommon.getMethod("getInstance"),
                    minecraft.getMethod("getInstance"),
                    minecraft.getField("level"),
                    minecraft.getField("levelRenderer"),
                    worldIdentifier.getMethod("ofEngineNullable", levelClass),
                    renderSystemBridge.getMethod("voxy$getRenderSystem")
            );
        }
    }
}
