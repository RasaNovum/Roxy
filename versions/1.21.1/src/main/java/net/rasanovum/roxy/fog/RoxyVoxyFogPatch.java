package net.rasanovum.roxy.fog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.rasanovum.roxy.compat.RoxyFogModCompat;
import java.lang.ref.WeakReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoxyVoxyFogPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String VOXY_RENDER_BRIDGE = "me.cortex.voxy.client.core.IGetVoxyRenderSystem";
    private static final String VOXY_CONFIG = "me.cortex.voxy.client.config.VoxyConfig";
    private static final String MINECRAFT_RENDER_SYSTEM = "com.mojang.blaze3d.systems.RenderSystem";
    private static final float NO_FOG = 1.0E9F;
    private static int extendedFogMode;
    private static final RoxyWeatherFog WEATHER = new RoxyWeatherFog();
    private static WeakReference<Object> diagnosticRenderer = new WeakReference<>(null);
    private static int diagnosticCount;
    private static long nextDiagnostic;
    private static boolean failureReported;

    private static volatile Accessors accessors;
    private static volatile boolean lookupAttempted;
    private static volatile FogSetter fogSetter;
    private static volatile boolean fogSetterLookupAttempted;

    private RoxyVoxyFogPatch() {
    }

    public static void apply(Object fogMode, boolean noFluid, float viewDistance,
                             float originalStart, float originalEnd, int originalShape,
                             float start, float end, int shape) {
        apply(fogMode, noFluid, viewDistance, originalStart, originalEnd, originalShape, start, end, shape,
                null, 0, 0, 0, 0);
    }

    public static void apply(Object fogMode, boolean noFluid, float viewDistance,
                             float originalStart, float originalEnd, int originalShape,
                             float start, float end, int shape, Object level, double tick,
                             float rain, float thunder, float partialTick) {
        // Avoid exposing Minecraft's nested FogMode type through this helper's descriptor.
        if (!"FOG_TERRAIN".equals(String.valueOf(fogMode))) return;
        extendedFogMode = 0;
        if (!noFluid) return;

        Accessors current = getAccessors();
        if (current == null) return;

        try {
            Object renderer = current.getNullable.invoke(null);
            if (renderer == null) return;
            if (diagnosticRenderer.get() != renderer) {
                diagnosticRenderer = new WeakReference<>(renderer);
                diagnosticCount = 0;
                nextDiagnostic = 0;
            }
            Object config = current.config.get(null);
            if (config == null
                    || !(Boolean) current.renderingEnabled.invoke(config)
                    || current.environmentalFog.getBoolean(config)) {
                return;
            }
            Object pipeline = current.pipeline.get(renderer);
            if (pipeline != null && pipeline.getClass().getName().equals("me.cortex.voxy.client.core.NormalRenderPipeline")) {
                float lodDistance = current.renderDistance.getFloat(config) * 512.0F;
                boolean fogMod = RoxyFogModCompat.fogActive();
                if (!fogMod && (originalStart < 0 || originalEnd < viewDistance - 0.01F)) {
                    trace("preserved environmental baseline", originalStart, originalEnd, start, end, start, end, lodDistance);
                    return;
                }
                RoxyFogRange range = fogMod ? RoxyFogRange.extendModified(start, end, viewDistance, lodDistance)
                        : RoxyFogRange.extend(originalStart, originalEnd, originalShape, start, end, shape, viewDistance, lodDistance);
                if (range != null && RoxyFogModCompat.supportedModPresent()) {
                    float voxyDistance = Math.round(lodDistance / 32.0F) * 32.0F;
                    var settings = RoxyFogConfig.get();
                    range = settings.apply(range, voxyDistance);
                    float progress = RoxyWeatherFog.fallbackProgress(rain, thunder);
                    if (fogMod) progress = RoxyFogModCompat.weatherProgress(partialTick, progress);
                    range = WEATHER.apply(range, level, tick, settings.automatic ? progress : 0, settings.weatherRollIn, viewDistance);
                }
                if (range != null) {
                    FogSetter setter = getFogSetter();
                    if (setter == null) return;
                    setter.start.invoke(null, range.start());
                    setter.end.invoke(null, range.end());
                    extendedFogMode = shape == 1 ? 2 : 1;
                    trace("extended", originalStart, originalEnd, start, end, range.start(), range.end(), lodDistance);
                    return;
                }
                trace("no qualifying event change", originalStart, originalEnd, start, end, NO_FOG, NO_FOG, lodDistance);
            }
            setNoFog();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!failureReported) {
                failureReported = true;
                LOGGER.warn("Unable to apply the Roxy terrain fog range", exception);
            }
        }
    }

    private static void trace(String result, float baselineStart, float baselineEnd, float eventStart,
                              float eventEnd, float outputStart, float outputEnd, float lodDistance) {
        if (!LOGGER.isDebugEnabled() || diagnosticCount >= 8) return;
        long now = System.nanoTime();
        if (now < nextDiagnostic) return;
        nextDiagnostic = now + 1_000_000_000L;
        diagnosticCount++;
        LOGGER.debug("Terrain fog {}: baseline {}..{}, event {}..{}, output {}..{}, LoD distance {}",
                result, baselineStart, baselineEnd, eventStart, eventEnd, outputStart, outputEnd, lodDistance);
    }

    public static boolean hasExtendedFog() {
        return extendedFogMode != 0;
    }

    public static boolean optionsEnabled() {
        Accessors current = getAccessors();
        if (current == null) return false;
        try {
            Object config = current.config.get(null);
            return config != null && (Boolean) current.renderingEnabled.invoke(config)
                    && !current.environmentalFog.getBoolean(config);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    public static boolean synchronizeWeatherColor() {
        if (!RoxyFogModCompat.fogActive() || !optionsEnabled() || !RoxyFogConfig.get().automatic) return false;
        return normalPipelineActive();
    }

    public static boolean fadeFogHorizon() {
        return RoxyFogModCompat.fogHorizonActive() && optionsEnabled() && normalPipelineActive();
    }

    private static boolean normalPipelineActive() {
        try {
            Accessors current = getAccessors();
            Object renderer = current.getNullable.invoke(null);
            Object pipeline = renderer == null ? null : current.pipeline.get(renderer);
            return pipeline != null && pipeline.getClass().getName().equals("me.cortex.voxy.client.core.NormalRenderPipeline");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    public static float opacityLimit(float original) {
        return hasExtendedFog() ? 1.0F : original;
    }

    public static float shaderMode(float original) {
        return hasExtendedFog() ? extendedFogMode : original;
    }

    private static void setNoFog() throws ReflectiveOperationException {
        FogSetter current = getFogSetter();
        if (current == null) return;
        current.start.invoke(null, NO_FOG);
        current.end.invoke(null, NO_FOG);
    }

    private static FogSetter getFogSetter() {
        FogSetter current = fogSetter;
        if (current != null || fogSetterLookupAttempted) return current;
        synchronized (RoxyVoxyFogPatch.class) {
            current = fogSetter;
            if (current != null || fogSetterLookupAttempted) return current;
            fogSetterLookupAttempted = true;
            try {
                Class<?> renderSystem = Class.forName(
                        MINECRAFT_RENDER_SYSTEM,
                        false,
                        findMinecraftLoader()
                );
                current = new FogSetter(
                        renderSystem.getMethod("setShaderFogStart", float.class),
                        renderSystem.getMethod("setShaderFogEnd", float.class)
                );
                fogSetter = current;
                return current;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                LOGGER.debug("Roxy terrain fog setter lookup failed", ignored);
                return null;
            }
        }
    }

    private static Accessors getAccessors() {
        Accessors current = accessors;
        if (current != null || lookupAttempted) return current;
        synchronized (RoxyVoxyFogPatch.class) {
            current = accessors;
            if (current != null || lookupAttempted) return current;
            lookupAttempted = true;
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) loader = RoxyVoxyFogPatch.class.getClassLoader();
                Class<?> bridge = Class.forName(VOXY_RENDER_BRIDGE, false, loader);
                Class<?> configClass = Class.forName(VOXY_CONFIG, false, loader);
                Field pipeline = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader).getDeclaredField("pipeline");
                pipeline.setAccessible(true);
                current = new Accessors(
                        bridge.getMethod("getNullable"),
                        configClass.getField("CONFIG"),
                        configClass.getMethod("isRenderingEnabled"),
                        configClass.getField("useEnvironmentalFog"),
                        configClass.getField("sectionRenderDistance"), pipeline
                );
                accessors = current;
                return current;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                LOGGER.debug("Roxy terrain fog accessor lookup failed", ignored);
                return null;
            }
        }
    }

    private record Accessors(Method getNullable, Field config, Method renderingEnabled, Field environmentalFog,
                             Field renderDistance, Field pipeline) {
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyVoxyFogPatch.class.getClassLoader(),
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

    private record FogSetter(Method start, Method end) {
    }
}
