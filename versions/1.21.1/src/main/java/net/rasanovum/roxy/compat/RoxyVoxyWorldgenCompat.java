package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Queue;

public final class RoxyVoxyWorldgenCompat {
    private static final int REQUIRED_IDLE_POLLS = 20;
    private static final long STABILIZATION_MILLIS = 2_000L;
    private static final long POST_DRAIN_MILLIS = 500L;
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static volatile Methods methods;
    private static volatile boolean resolutionFailed;
    private static Object renderer;
    private static Object instance;
    private static long rendererSeenAt;
    private static long lastDrainAt;
    private static int idlePolls;

    private RoxyVoxyWorldgenCompat() {
    }

    public static boolean canDrain() {
        try {
            Methods resolved = methods();
            Object minecraft = resolved.minecraftInstance.invoke(null);
            Object levelRenderer = resolved.levelRenderer.get(minecraft);
            Object currentRenderer = resolved.getRenderSystem.invoke(levelRenderer);
            Object currentInstance = resolved.voxyInstance.invoke(null);
            long now = System.currentTimeMillis();

            if (currentRenderer == null || currentInstance == null) {
                reset(currentRenderer, currentInstance, now);
                return false;
            }
            if (currentRenderer != renderer || currentInstance != instance) {
                reset(currentRenderer, currentInstance, now);
                return false;
            }
            Object nodeManager = resolved.nodeManager.get(currentRenderer);
            Object renderGeneration = resolved.renderGeneration.get(currentRenderer);
            Object modelService = resolved.modelService.get(currentRenderer);
            if ((boolean) resolved.nodeManagerHasWork.invoke(nodeManager)
                    || (int) resolved.renderTaskCount.invoke(renderGeneration) != 0
                    || !(boolean) resolved.modelQueuesEmpty.invoke(modelService)) {
                idlePolls = 0;
                return false;
            }
            if (now - rendererSeenAt < STABILIZATION_MILLIS
                    || now - lastDrainAt < POST_DRAIN_MILLIS
                    || ++idlePolls < REQUIRED_IDLE_POLLS) {
                return false;
            }

            Object ingestService = resolved.ingestService.invoke(currentInstance);
            Queue<?> ingestQueue = (Queue<?>) resolved.ingestQueue.get(ingestService);
            return ingestQueue.isEmpty() && (int) resolved.taskCount.invoke(ingestService) == 0;
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (!resolutionFailed) {
                resolutionFailed = true;
                LOGGER.error("Roxy could not resolve Voxy WorldGen ingestion state", exception);
            }
            return true;
        }
    }

    public static void drained() {
        lastDrainAt = System.currentTimeMillis();
        idlePolls = 0;
    }

    private static void reset(Object currentRenderer, Object currentInstance, long now) {
        renderer = currentRenderer;
        instance = currentInstance;
        rendererSeenAt = now;
        idlePolls = 0;
    }

    private static Methods methods() throws ReflectiveOperationException {
        Methods cached = methods;
        if (cached != null) {
            return cached;
        }
        synchronized (RoxyVoxyWorldgenCompat.class) {
            if (methods == null) {
                methods = Methods.resolve(Thread.currentThread().getContextClassLoader());
            }
            return methods;
        }
    }

    private record Methods(
            Method voxyInstance,
            Method ingestService,
            Method taskCount,
            Field ingestQueue,
            Method minecraftInstance,
            Field levelRenderer,
            Method getRenderSystem,
            Field nodeManager,
            Method nodeManagerHasWork,
            Field renderGeneration,
            Method renderTaskCount,
            Field modelService,
            Method modelQueuesEmpty
    ) {
        private static Methods resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> voxyCommon = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            Class<?> voxyInstance = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance", false, loader);
            Class<?> ingestService = Class.forName(
                    "me.cortex.voxy.common.world.service.VoxelIngestService",
                    false,
                    loader
            );
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> levelRenderer = Class.forName("net.minecraft.client.renderer.LevelRenderer", false, loader);
            Class<?> renderSystemBridge = Class.forName(
                    "me.cortex.voxy.client.core.IGetVoxyRenderSystem",
                    false,
                    loader
            );
            Class<?> renderSystem = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader);
            Class<?> nodeManager = Class.forName(
                    "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager",
                    false,
                    loader
            );
            Class<?> renderGeneration = Class.forName(
                    "me.cortex.voxy.client.core.rendering.building.RenderGenerationService",
                    false,
                    loader
            );
            Class<?> modelService = Class.forName(
                    "me.cortex.voxy.client.core.model.ModelBakerySubsystem",
                    false,
                    loader
            );
            Field ingestQueue = ingestService.getDeclaredField("ingestQueue");
            ingestQueue.setAccessible(true);
            Field nodeManagerField = renderSystem.getDeclaredField("nodeManager");
            Field renderGenerationField = renderSystem.getDeclaredField("renderGen");
            Field modelServiceField = renderSystem.getDeclaredField("modelService");
            nodeManagerField.setAccessible(true);
            renderGenerationField.setAccessible(true);
            modelServiceField.setAccessible(true);

            return new Methods(
                    voxyCommon.getMethod("getInstance"),
                    voxyInstance.getMethod("getIngestService"),
                    ingestService.getMethod("getTaskCount"),
                    ingestQueue,
                    minecraft.getMethod("getInstance"),
                    minecraft.getField("levelRenderer"),
                    renderSystemBridge.getMethod("voxy$getRenderSystem"),
                    nodeManagerField,
                    nodeManager.getMethod("hasWork"),
                    renderGenerationField,
                    renderGeneration.getMethod("getTaskCount"),
                    modelServiceField,
                    modelService.getMethod("areQueuesEmpty")
            );
        }
    }
}
