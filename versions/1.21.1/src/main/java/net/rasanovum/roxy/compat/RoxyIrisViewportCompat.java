package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.fog.RoxyFogParameters;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyIrisViewportCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String IRIS_UTIL = "me.cortex.voxy.client.core.util.IrisUtil";
    private static final String CAPTURED_VIEWPORT =
            "me.cortex.voxy.client.core.util.IrisUtil$CapturedViewportParameters";
    private static final String CHUNK_RENDER_MATRICES =
            "net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices";
    private static final String VOXY_RENDER_SYSTEM = "me.cortex.voxy.client.core.VoxyRenderSystem";
    private static final int[] SAVED_VIEWPORT = new int[4];

    private static volatile Accessors accessors;
    private static volatile boolean lookupAttempted;
    private static volatile boolean capturedViewportReady;
    private static volatile boolean captureSuccessLogged;
    private static volatile boolean accessorsFailureLogged;
    private static volatile boolean captureFailureLogged;

    private RoxyIrisViewportCompat() {
    }

    public static void capture(
            Object levelRenderer,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix
    ) {
        capturedViewportReady = false;
        if (levelRenderer == null) return;
        if (!RoxyVoxyRendererReloadCompat.shouldCreateVoxyRenderer(levelRenderer)) return;

        Accessors current = getAccessors(levelRenderer.getClass().getClassLoader());
        if (current == null) {
            logAccessorsFailure();
            return;
        }

        Object captured = null;
        boolean viewportOverridden = false;
        try {
            if (!Boolean.TRUE.equals(current.shaderPackEnabled.invoke(null))) return;
            if (current.shadowActive != null && Boolean.TRUE.equals(current.shadowActive.invoke(null))) return;

            Object matrices = current.matricesConstructor.newInstance(projectionMatrix, positionMatrix);
            captured = current.capturedConstructor.newInstance(
                    matrices,
                    RoxyFogParameters.current(),
                    cameraX,
                    cameraY,
                    cameraZ
            );
            current.capturedViewport.set(null, captured);

            Method getRenderSystem = RoxyReflection.findMethod(levelRenderer.getClass(), "voxy$getRenderSystem");
            Object renderer = getRenderSystem.invoke(levelRenderer);
            if (renderer != null) {
                Object minecraft = current.minecraftInstance.invoke(null);
                Object target = minecraft == null ? null : current.getMainRenderTarget.invoke(minecraft);
                if (target != null) {
                    int width = current.targetWidth.getInt(target);
                    int height = current.targetHeight.getInt(target);
                    GL11.glGetIntegerv(GL11.GL_VIEWPORT, SAVED_VIEWPORT);
                    viewportOverridden = true;
                    GL11.glViewport(0, 0, width, height);
                    Object viewport = current.apply.invoke(captured, renderer);
                    if (viewport != null) capturedViewportReady = true;
                    if (!captureSuccessLogged) {
                        captureSuccessLogged = true;
                        LOGGER.info("Roxy Iris viewport bridge active at {}x{}", width, height);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                LOGGER.warn("Roxy could not apply Voxy's Iris viewport bridge", exception);
            }
        } finally {
            if (viewportOverridden) {
                GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
            }
            if (captured != null) {
                try {
                    current.capturedViewport.set(null, null);
                } catch (IllegalAccessException exception) {
                    if (!captureFailureLogged) {
                        captureFailureLogged = true;
                        LOGGER.warn("Roxy could not clear Voxy's captured Iris viewport", exception);
                    }
                }
            }
        }
    }

    public static boolean consumeCapturedViewport() {
        if (!capturedViewportReady) return false;
        capturedViewportReady = false;
        return true;
    }

    private static Accessors getAccessors(ClassLoader hint) {
        Accessors current = accessors;
        if (current != null || lookupAttempted) return current;

        synchronized (RoxyIrisViewportCompat.class) {
            current = accessors;
            if (current != null || lookupAttempted) return current;
            lookupAttempted = true;
            try {
                ClassLoader loader = RoxyClassLoaders.findGameLoader(hint);
                Class<?> irisUtil = Class.forName(IRIS_UTIL, false, loader);
                Class<?> matrices = Class.forName(CHUNK_RENDER_MATRICES, false, loader);
                Class<?> captured = Class.forName(CAPTURED_VIEWPORT, false, loader);
                Class<?> renderSystem = Class.forName(VOXY_RENDER_SYSTEM, false, loader);
                Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, loader);
                Class<?> renderTargetClass = Class.forName("com.mojang.blaze3d.pipeline.RenderTarget", false, loader);

                Method shadowActive = RoxyReflection.tryFindMethod(irisUtil, "irisShadowActive");
                Constructor<?> matricesConstructor = findMatricesConstructor(matrices);
                Constructor<?> capturedConstructor = findCapturedConstructor(captured, matrices);
                current = new Accessors(
                        irisUtil.getMethod("irisShaderPackEnabled"),
                        shadowActive,
                        irisUtil.getField("CAPTURED_VIEWPORT_PARAMETERS"),
                        matricesConstructor,
                        capturedConstructor,
                        captured.getMethod("apply", renderSystem),
                        minecraftClass.getMethod("getInstance"),
                        minecraftClass.getMethod("getMainRenderTarget"),
                        renderTargetClass.getField("width"),
                        renderTargetClass.getField("height")
                );
                accessors = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (!accessorsFailureLogged) {
                    accessorsFailureLogged = true;
                    LOGGER.warn("Roxy could not resolve Voxy's Iris viewport bridge", exception);
                }
                return null;
            }
        }
    }

    private static void logAccessorsFailure() {
        if (!accessorsFailureLogged) {
            accessorsFailureLogged = true;
            LOGGER.warn("Roxy could not resolve Voxy's Iris viewport bridge");
        }
    }

    private static Constructor<?> findMatricesConstructor(Class<?> matrices) {
        for (Constructor<?> constructor : matrices.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0].isAssignableFrom(Matrix4f.class)
                    && parameters[1].isAssignableFrom(Matrix4f.class)) {
                return constructor;
            }
        }
        throw new IllegalStateException("Sodium ChunkRenderMatrices constructor was not found");
    }

    private static Constructor<?> findCapturedConstructor(Class<?> captured, Class<?> matrices) {
        for (Constructor<?> constructor : captured.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 5
                    && parameters[0].isAssignableFrom(matrices)
                    && parameters[1].isAssignableFrom(RoxyFogParameters.class)
                    && parameters[2] == double.class
                    && parameters[3] == double.class
                    && parameters[4] == double.class) {
                return constructor;
            }
        }
        throw new IllegalStateException("Voxy CapturedViewportParameters constructor was not found");
    }

    private record Accessors(
            Method shaderPackEnabled,
            Method shadowActive,
            Field capturedViewport,
            Constructor<?> matricesConstructor,
            Constructor<?> capturedConstructor,
            Method apply,
            Method minecraftInstance,
            Method getMainRenderTarget,
            Field targetWidth,
            Field targetHeight
    ) {
    }
}
