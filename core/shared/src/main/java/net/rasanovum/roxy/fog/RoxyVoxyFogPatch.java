package net.rasanovum.roxy.fog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import net.rasanovum.roxy.compat.RoxyBetterFogCompat;
import net.rasanovum.roxy.compat.RoxyFogModCompat;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoxyVoxyFogPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String VOXY_RENDER_BRIDGE = "me.cortex.voxy.client.core.IGetVoxyRenderSystem";
    private static final String VOXY_CONFIG = "me.cortex.voxy.client.config.VoxyConfig";
    private static final String MINECRAFT_RENDER_SYSTEM = "com.mojang.blaze3d.systems.RenderSystem";
    private static final float NO_FOG = 1.0E9F;
    private static volatile int extendedFogMode;
    private static final ConcurrentHashMap<Integer, ProgramUniforms> PROGRAM_UNIFORMS = new ConcurrentHashMap<>();
    private static final float[] terrainFogEnd = new float[4];
    private static final float[] terrainFogColour = new float[4];
    private static final float[] nativeTerrainFogColour = new float[4];
    private static volatile float nativeTerrainFogStart = Float.NaN;
    private static volatile float nativeTerrainFogEnd = Float.NaN;
    private static volatile int nativeTerrainFogShape;
    private static volatile boolean terrainFogPrepared;
    private static volatile boolean normalFogShaderSupport;
    private static volatile boolean terrainFogRequested;
    private static final ClassValue<Field> VIEWPORT_FOG = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (!RoxyFogParameters.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    return field;
                }
            }
            return null;
        }
    };
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
                             float start, float end, int shape, Object level, double tick,
                             float rain, float thunder, float partialTick) {
        apply(fogMode, noFluid, false, viewDistance, originalStart, originalEnd, originalShape,
                start, end, shape, level, tick, rain, thunder, partialTick);
    }

    public static void apply(Object fogMode, boolean noFluid, boolean thickDimensionFog, float viewDistance,
                             float originalStart, float originalEnd, int originalShape,
                             float start, float end, int shape, Object level, double tick,
                             float rain, float thunder, float partialTick) {
        if (!"FOG_TERRAIN".equals(String.valueOf(fogMode))) return;
        terrainFogRequested = false;
        extendedFogMode = 0;
        if (!noFluid) return;
        if (RoxyBetterFogCompat.available()) return;
        boolean fogMod = RoxyFogModCompat.fogActive();
        if (shouldPreserveEnvironmentalBaseline(fogMod, thickDimensionFog, originalStart, originalEnd, viewDistance)) {
            trace("preserved environmental baseline", originalStart, originalEnd, start, end, start, end, 0.0F);
            return;
        }

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
                boolean modModifiedFog = Math.abs(start - originalStart) > 0.01F
                        || Math.abs(end - originalEnd) > 0.01F
                        || shape != originalShape;
                if (!modModifiedFog && builtinTerrainFogAllowed() && Float.isFinite(lodDistance) && lodDistance > 16.0F) {
                    RoxyFogConfig.Settings terrainFog = RoxyFogConfig.get();
                    RoxyFogRange thickRange = thickDimensionFog
                            ? computeThickDimensionFog(start, end, viewDistance, lodDistance, terrainFog)
                            : null;
                    float terrainStart = thickRange != null ? thickRange.start() : terrainFog.computeStart(lodDistance);
                    float terrainEnd = thickRange != null ? thickRange.end() : lodDistance;
                    FogSetter setter = getFogSetter();
                    if (setter != null && Float.isFinite(terrainStart) && Float.isFinite(terrainEnd)
                            && terrainStart >= 0.0F && terrainEnd > terrainStart) {
                        setter.start.invoke(null, terrainStart);
                        setter.end.invoke(null, terrainEnd);
                        RoxyFogParameters nativeFog = RoxyFogParameters.current();
                        nativeTerrainFogColour[0] = nativeFog.red();
                        nativeTerrainFogColour[1] = nativeFog.green();
                        nativeTerrainFogColour[2] = nativeFog.blue();
                        nativeTerrainFogColour[3] = nativeFog.alpha();
                        nativeTerrainFogStart = terrainStart;
                        nativeTerrainFogEnd = terrainEnd;
                        nativeTerrainFogShape = shape;
                        terrainFogRequested = true;
                        extendedFogMode = shape == 1 ? 2 : 1;
                        return;
                    }
                }
                RoxyFogRange range;
                if (thickDimensionFog) {
                    range = (!RoxyFogModCompat.nativeTerrainFogAllowed() || RoxyFogConfig.get().terrainEnabled)
                            ? computeThickDimensionFog(start, end, viewDistance, lodDistance, RoxyFogConfig.get())
                            : null;
                } else {
                    range = fogMod ? RoxyFogRange.extendModified(start, end, viewDistance, lodDistance)
                            : RoxyFogRange.extend(originalStart, originalEnd, originalShape, start, end, shape, viewDistance, lodDistance);
                    if (range != null && RoxyFogModCompat.supportedModPresent()) {
                        float voxyDistance = Math.round(lodDistance / 32.0F) * 32.0F;
                        var settings = RoxyFogConfig.get();
                        range = settings.apply(range, voxyDistance);
                        float progress = RoxyWeatherFog.fallbackProgress(rain, thunder);
                        if (fogMod) progress = RoxyFogModCompat.weatherProgress(partialTick, progress);
                        range = WEATHER.apply(range, level, tick, settings.automatic ? progress : 0, settings.weatherRollIn, viewDistance);
                    }
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
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!failureReported) {
                failureReported = true;
                LOGGER.warn("Unable to apply the Roxy terrain fog range", exception);
            }
        }
    }

    static boolean shouldPreserveEnvironmentalBaseline(
            boolean fogModActive,
            float originalStart,
            float originalEnd,
            float viewDistance
    ) {
        return shouldPreserveEnvironmentalBaseline(fogModActive, false, originalStart, originalEnd, viewDistance);
    }

    static boolean shouldPreserveEnvironmentalBaseline(
            boolean fogModActive,
            boolean thickDimensionFog,
            float originalStart,
            float originalEnd,
            float viewDistance
    ) {
        if (fogModActive) return false;
        if (thickDimensionFog) return originalStart < 0.0F || originalEnd < 10.0F;
        return originalStart < 0.0F || originalEnd < viewDistance - 0.01F;
    }

    static RoxyFogRange computeThickDimensionFog(
            float start,
            float end,
            float viewDistance,
            float lodDistance,
            RoxyFogConfig.Settings settings
    ) {
        if (!Float.isFinite(start) || !Float.isFinite(end)
                || !Float.isFinite(viewDistance) || !Float.isFinite(lodDistance)
                || viewDistance <= 0.0F || lodDistance <= 16.0F || end <= start) {
            return null;
        }
        double scale = Math.sqrt(Math.max(1.0, (double) lodDistance / viewDistance));
        float scaledEnd = (float) (end * scale * 1.5);
        float netherEnd = Math.min(lodDistance, Math.max(viewDistance * 2.0F, scaledEnd));
        float configuredStart = settings == null ? 0.0F : settings.computeStart(netherEnd) * 0.35F;
        float netherStart = Math.max(Math.max(0.0F, start), Math.min(netherEnd * 0.2F, configuredStart));
        if (!Float.isFinite(netherStart) || !Float.isFinite(netherEnd) || netherEnd - netherStart <= 1.0F) {
            return null;
        }
        return new RoxyFogRange(netherStart, netherEnd);
    }

    private static void trace(String result, float baselineStart, float baselineEnd, float eventStart,
                              float eventEnd, float outputStart, float outputEnd, float lodDistance) {
        if (!LOGGER.isDebugEnabled() || diagnosticCount >= 8) return;
        long now = System.nanoTime();
        if (diagnosticCount > 0 && now - nextDiagnostic < 0L) return;
        nextDiagnostic = now + 1_000_000_000L;
        diagnosticCount++;
        LOGGER.debug("Terrain fog {}: baseline {}..{}, event {}..{}, output {}..{}, LoD distance {}",
                result, baselineStart, baselineEnd, eventStart, eventEnd, outputStart, outputEnd, lodDistance);
    }

    public static boolean hasExtendedFog() {
        return extendedFogMode != 0;
    }

    private static boolean builtinTerrainFogAllowed() {
        return RoxyFogModCompat.nativeTerrainFogAllowed()
                && !shaderPackEnabled()
                && RoxyFogConfig.get().terrainEnabled;
    }

    private static boolean shaderPackEnabled() {
        try {
            Accessors current = getAccessors();
            return current == null || Boolean.TRUE.equals(current.shaderPackEnabled.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return true;
        }
    }

    public static void beginFrameTerrainFog() {
        clearTerrainFog();
        normalFogShaderSupport = false;
    }

    public static void resetTerrainFog() {
        clearTerrainFog();
        normalFogShaderSupport = false;
        PROGRAM_UNIFORMS.clear();
    }

    public static void resetTerrainFogLifecycle() {
        resetTerrainFog();
        WEATHER.reset();
        terrainFogRequested = false;
        extendedFogMode = 0;
        for (int i = 0; i < 4; i++) nativeTerrainFogColour[i] = 0.0F;
        nativeTerrainFogStart = Float.NaN;
        nativeTerrainFogEnd = Float.NaN;
        nativeTerrainFogShape = 0;
    }

    public static void prepareTerrainFog(Object viewport) {
        clearTerrainFog();
        normalFogShaderSupport = false;
        Accessors current = getAccessors();
        if (current == null) return;
        try {
            Object renderer = current.getNullable.invoke(null);
            if (renderer == null) return;
            Object pipeline = current.pipeline.get(renderer);
            if (pipeline == null || !pipeline.getClass().getName().equals("me.cortex.voxy.client.core.NormalRenderPipeline")) return;
            normalFogShaderSupport = true;
            if (!terrainFogRequested || !builtinTerrainFogAllowed() || viewport == null) return;
            Object voxyConfig = current.config.get(null);
            if (voxyConfig == null || !(Boolean) current.renderingEnabled.invoke(voxyConfig)
                    || current.environmentalFog.getBoolean(voxyConfig)) return;
            float lodEnd = current.renderDistance.getFloat(voxyConfig) * 512.0F;
            if (!Float.isFinite(lodEnd) || lodEnd <= 0.0F) return;
            float start = Float.isFinite(nativeTerrainFogStart) ? nativeTerrainFogStart : RoxyFogConfig.get().computeStart(lodEnd);
            float end = Float.isFinite(nativeTerrainFogEnd) ? nativeTerrainFogEnd : lodEnd;
            if (!Float.isFinite(start) || !Float.isFinite(end) || end <= start) return;
            RoxyFogParameters fog = viewportFogParameters(viewport);
            float red = fog == null ? nativeTerrainFogColour[0] : fog.red();
            float green = fog == null ? nativeTerrainFogColour[1] : fog.green();
            float blue = fog == null ? nativeTerrainFogColour[2] : fog.blue();
            float alpha = fog == null ? nativeTerrainFogColour[3] : fog.alpha();
            if (!Float.isFinite(red) || !Float.isFinite(green)
                    || !Float.isFinite(blue) || !Float.isFinite(alpha)) return;
            float inverseRange = 1.0F / (end - start);
            terrainFogEnd[0] = inverseRange;
            terrainFogEnd[1] = -start * inverseRange;
            terrainFogEnd[2] = 1.0F;
            terrainFogEnd[3] = nativeTerrainFogShape == 1 ? 2.0F : 1.0F;
            terrainFogColour[0] = red;
            terrainFogColour[1] = green;
            terrainFogColour[2] = blue;
            terrainFogColour[3] = alpha;
            terrainFogPrepared = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            clearTerrainFog();
            if (!failureReported) {
                failureReported = true;
                LOGGER.warn("Unable to prepare Roxy terrain fog shader values", exception);
            }
        }
    }

    public static void uploadTerrainFog() {
        if (!normalFogShaderSupport) return;
        ProgramUniforms uniforms = currentProgramUniforms();
        if (uniforms == null) return;
        if (uniforms.endLocation >= 0) {
            GL33C.glUniform4f(uniforms.endLocation, terrainFogEnd[0], terrainFogEnd[1], terrainFogEnd[2], terrainFogEnd[3]);
        }
        if (uniforms.colourLocation >= 0) {
            GL33C.glUniform4f(uniforms.colourLocation, terrainFogColour[0], terrainFogColour[1], terrainFogColour[2], terrainFogColour[3]);
        }
    }

    public static boolean terrainFogActive() {
        return normalFogShaderSupport && terrainFogPrepared;
    }

    public static void uploadTerrainFogBlit() {
        ProgramUniforms uniforms = currentProgramUniforms();
        if (uniforms == null || uniforms.activeLocation < 0) return;
        GL33C.glUniform1f(uniforms.activeLocation, terrainFogActive() ? 1.0F : 0.0F);
    }

    private static ProgramUniforms currentProgramUniforms() {
        int program = GL11.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program <= 0) return null;
        return PROGRAM_UNIFORMS.computeIfAbsent(program, id -> new ProgramUniforms(
                GL20C.glGetUniformLocation(id, "roxyTerrainFogEnd"),
                GL20C.glGetUniformLocation(id, "roxyTerrainFogColour"),
                GL20C.glGetUniformLocation(id, "roxyTerrainFogActive")
        ));
    }

    private record ProgramUniforms(int endLocation, int colourLocation, int activeLocation) {
    }

    private static void clearTerrainFog() {
        terrainFogPrepared = false;
        for (int i = 0; i < 4; i++) {
            terrainFogEnd[i] = 0.0F;
            terrainFogColour[i] = 0.0F;
        }
    }

    private static RoxyFogParameters viewportFogParameters(Object viewport) throws ReflectiveOperationException {
        Field field = VIEWPORT_FOG.get(viewport.getClass());
        return field == null ? null : (RoxyFogParameters) field.get(viewport);
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

    public static float lodDistanceBlocks() {
        Accessors current = getAccessors();
        if (current == null) return 0.0F;
        try {
            Object config = current.config.get(null);
            return config == null ? 0.0F : current.renderDistance.getFloat(config) * 512.0F;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return 0.0F;
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
            if (current == null) return false;
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
                Class<?> renderSystem = RoxyClassLoaders.loadMinecraftClass(
                        MINECRAFT_RENDER_SYSTEM,
                        RoxyClassLoaders.findGameLoader()
                );
                current = new FogSetter(
                        RoxyReflection.findMethod(renderSystem, "setShaderFogStart", float.class),
                        RoxyReflection.findMethod(renderSystem, "setShaderFogEnd", float.class)
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
            ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor(VOXY_RENDER_BRIDGE);
            if (loader == null) return null;
            try {
                Class<?> bridge = Class.forName(VOXY_RENDER_BRIDGE, false, loader);
                Class<?> configClass = Class.forName(VOXY_CONFIG, false, loader);
                Field pipeline = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader).getDeclaredField("pipeline");
                pipeline.setAccessible(true);
                current = new Accessors(
                        bridge.getMethod("getNullable"),
                        configClass.getField("CONFIG"),
                        configClass.getMethod("isRenderingEnabled"),
                        configClass.getField("useEnvironmentalFog"),
                        configClass.getField("sectionRenderDistance"),
                        pipeline,
                        Class.forName("me.cortex.voxy.client.core.util.IrisUtil", false, loader)
                                .getMethod("irisShaderPackEnabled")
                );
                accessors = current;
                lookupAttempted = true;
                return current;
            } catch (NoSuchFieldException | NoSuchMethodException | LinkageError ignored) {
                lookupAttempted = true;
                LOGGER.debug("Roxy terrain fog accessor lookup failed", ignored);
                return null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                LOGGER.debug("Roxy terrain fog accessor lookup failed", ignored);
                return null;
            }
        }
    }

    private record Accessors(Method getNullable, Field config, Method renderingEnabled, Field environmentalFog,
                             Field renderDistance, Field pipeline, Method shaderPackEnabled) {
    }

    private record FogSetter(Method start, Method end) {
    }
}
