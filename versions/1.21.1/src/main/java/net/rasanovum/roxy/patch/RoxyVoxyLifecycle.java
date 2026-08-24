package net.rasanovum.roxy.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyVoxyLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final long TICK_INTERVAL_MILLIS = 250L;
    private static volatile Methods methods;
    private static volatile boolean resolutionFailed;
    private static long lastTickAt;
    private static Object instance;
    private static Object engine;
    private static Object renderer;

    private RoxyVoxyLifecycle() {
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MILLIS) return;
        lastTickAt = now;
        try {
            Methods resolved = methods();
            Object currentInstance = resolved.voxyInstance.invoke(null);
            Object minecraft = resolved.minecraftInstance.invoke(null);
            Object level = resolved.level.get(minecraft);
            Object currentEngine = level == null ? null : resolved.worldEngine.invoke(null, level);
            if (currentInstance == null || currentEngine == null) {
                if (instance != currentInstance || engine != currentEngine) {
                    instance = currentInstance;
                    engine = currentEngine;
                    renderer = null;
                    RoxyVoxyRenderPatch.reset();
                }
                RoxyVoxyMaskSweep.setContext(null, null);
                RoxyVoxyHierarchySweep.setContext(null, null, null);
                return;
            }

            Object levelRenderer = resolved.levelRenderer.get(minecraft);
            Object currentRenderer = resolved.getRenderSystem.invoke(levelRenderer);
            Object currentNodeManager = currentRenderer == null
                    ? null
                    : resolved.nodeManager.get(currentRenderer);
            if (currentInstance != instance || currentEngine != engine || currentRenderer != renderer) {
                instance = currentInstance;
                engine = currentEngine;
                renderer = currentRenderer;
                RoxyVoxyRenderPatch.reset();
                if (currentRenderer != null) {
                    LOGGER.info("Prepared background Voxy LoD verification for a world or renderer change");
                }
            }
            RoxyVoxyMaskSweep.setContext(currentEngine, currentNodeManager);
            RoxyVoxyHierarchySweep.setContext(currentEngine, currentRenderer, currentNodeManager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!resolutionFailed) {
                resolutionFailed = true;
                LOGGER.warn("Background Voxy LoD verification is unavailable", exception);
            }
        }
    }

    private static Methods methods() throws ReflectiveOperationException {
        Methods cached = methods;
        if (cached != null) return cached;
        synchronized (RoxyVoxyLifecycle.class) {
            if (methods == null) methods = Methods.resolve(Thread.currentThread().getContextClassLoader());
            return methods;
        }
    }

    private record Methods(
            Method voxyInstance,
            Method minecraftInstance,
            Field level,
            Field levelRenderer,
            Method worldEngine,
            Method getRenderSystem,
            Field nodeManager
    ) {
        private static Methods resolve(ClassLoader loader) throws ReflectiveOperationException {
            if (loader == null) loader = RoxyVoxyLifecycle.class.getClassLoader();
            Class<?> voxyCommon = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level", false, loader);
            Class<?> worldIdentifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            Class<?> renderSystemBridge = Class.forName(
                    "me.cortex.voxy.client.core.IGetVoxyRenderSystem",
                    false,
                    loader
            );
            Class<?> renderSystem = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader);
            Field nodeManager = renderSystem.getDeclaredField("nodeManager");
            nodeManager.setAccessible(true);
            return new Methods(
                    voxyCommon.getMethod("getInstance"),
                    minecraft.getMethod("getInstance"),
                    minecraft.getField("level"),
                    minecraft.getField("levelRenderer"),
                    worldIdentifier.getMethod("ofEngineNullable", levelClass),
                    renderSystemBridge.getMethod("voxy$getRenderSystem"),
                    nodeManager
            );
        }
    }
}
