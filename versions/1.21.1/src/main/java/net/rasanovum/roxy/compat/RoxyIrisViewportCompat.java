package net.rasanovum.roxy.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.rasanovum.roxy.loader.RoxyFogParameters;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// Bridges the Iris viewport capture that Voxy expects from newer Sodium.
public final class RoxyIrisViewportCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String IRIS_UTIL = "me.cortex.voxy.client.core.util.IrisUtil";
    private static final String CAPTURED_VIEWPORT =
            "me.cortex.voxy.client.core.util.IrisUtil$CapturedViewportParameters";
    private static final String CHUNK_RENDER_MATRICES =
            "net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices";
    private static final String VOXY_RENDER_SYSTEM = "me.cortex.voxy.client.core.VoxyRenderSystem";

    private static volatile Accessors accessors;
    private static volatile boolean capturedViewportReady;
    private static volatile boolean captureSuccessLogged;
    private static volatile boolean accessorsFailureLogged;
    private static volatile boolean captureFailureLogged;

    private RoxyIrisViewportCompat() {
    }

    public static void capture(
            Object levelRenderer,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix
    ) {
        Accessors current = getAccessors();
        if (current == null) {
            logAccessorsFailure();
            return;
        }

        try {
            if (!Boolean.TRUE.equals(current.shaderPackEnabled.invoke(null))) return;

            Object matrices = current.matricesConstructor.newInstance(projectionMatrix, positionMatrix);
            Object position = camera.getClass().getMethod("getPosition").invoke(camera);
            Object captured = current.capturedConstructor.newInstance(
                    matrices,
                    RoxyFogParameters.current(),
                    coordinate(position, "x"),
                    coordinate(position, "y"),
                    coordinate(position, "z")
            );
            current.capturedViewport.set(null, captured);

            Method getRenderSystem = levelRenderer.getClass().getMethod("voxy$getRenderSystem");
            Object renderer = getRenderSystem.invoke(levelRenderer);
            if (renderer != null) {
                int[] dimensions = getMainTargetDimensions(levelRenderer.getClass().getClassLoader());
                GL11.glViewport(0, 0, dimensions[0], dimensions[1]);
                current.apply.invoke(captured, renderer);
                current.capturedViewport.set(null, null);
                capturedViewportReady = true;
                if (!captureSuccessLogged) {
                    captureSuccessLogged = true;
                    LOGGER.info("Roxy Iris viewport bridge active at {}x{}", dimensions[0], dimensions[1]);
                }
                current.apply.invoke(captured, renderer);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            // Iris and Voxy remain optional at this boundary. If their runtime ABI changes, the normal non-Iris viewport path must remain usable.
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                LOGGER.warn("Roxy could not apply Voxy's Iris viewport bridge", exception);
            }
        }
    }

    private static int[] getMainTargetDimensions(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
        Object target = minecraftClass.getMethod("getMainRenderTarget").invoke(minecraft);
        return new int[]{target.getClass().getField("width").getInt(target), target.getClass().getField("height").getInt(target)};
    }

    public static boolean consumeCapturedViewport() {
        if (!capturedViewportReady) return false;
        capturedViewportReady = false;
        return true;
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null) return current;

        try {
            ClassLoader loader = findMinecraftLoader();
            Class<?> irisUtil = Class.forName(IRIS_UTIL, false, loader);
            Class<?> matrices = Class.forName(CHUNK_RENDER_MATRICES, false, loader);
            Class<?> captured = Class.forName(CAPTURED_VIEWPORT, false, loader);
            Class<?> renderSystem = Class.forName(VOXY_RENDER_SYSTEM, false, loader);

            Constructor<?> matricesConstructor = findMatricesConstructor(matrices);
            Constructor<?> capturedConstructor = findCapturedConstructor(captured, matrices);
            current = new Accessors(
                    irisUtil.getMethod("irisShaderPackEnabled"),
                    irisUtil.getField("CAPTURED_VIEWPORT_PARAMETERS"),
                    matricesConstructor,
                    capturedConstructor,
                    captured.getMethod("apply", renderSystem)
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

    private static double coordinate(Object position, String name) throws ReflectiveOperationException {
        return ((Number) position.getClass().getField(name).get(position)).doubleValue();
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

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyIrisViewportCompat.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("net.minecraft.client.Minecraft", false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
                // Try the next loader.
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }

    private record Accessors(
            Method shaderPackEnabled,
            Field capturedViewport,
            Constructor<?> matricesConstructor,
            Constructor<?> capturedConstructor,
            Method apply
    ) {
    }
}
