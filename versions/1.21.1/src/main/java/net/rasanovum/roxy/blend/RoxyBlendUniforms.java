package net.rasanovum.roxy.blend;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RoxyBlendUniforms {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final int SECTION_COUNT = 256;
    private static final float[] SECTION_BLEND = new float[SECTION_COUNT];
    private static final Map<Integer, Locations> LOCATIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> TRANSLUCENT_PASS = ThreadLocal.withInitial(() -> false);
    private static volatile Method irisShadowActive;
    private static volatile boolean irisShadowResolved;
    private static volatile RenderDistanceAccess renderDistanceAccess;
    private static volatile SectionAccess sectionAccess;
    private static volatile boolean failed;
    private static volatile boolean ready;

    private RoxyBlendUniforms() {}

    public static void beginPass(boolean translucent) {
        TRANSLUCENT_PASS.set(translucent);
    }

    public static void endPass() {
        TRANSLUCENT_PASS.remove();
    }

    public static void applyRegion(Object region, Object camera) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        Locations locations = LOCATIONS.computeIfAbsent(program, Locations::resolve);
        if (!locations.available()) return;

        try {
            float renderDistance = renderDistanceBlocks();
            float blendStart = RoxyBlendConfig.transitionStartBlocks(renderDistance);
            float blendEnd = RoxyBlendConfig.transitionEndBlocks(renderDistance);
            boolean enabled = RoxyBlendConfig.enabled()
                    && !TRANSLUCENT_PASS.get()
                    && !irisShadowActive()
                    && blendEnd > blendStart;
            Arrays.fill(SECTION_BLEND, 0.0F);
            if (enabled) {
                SectionAccess sections = sectionAccess();
                for (int index = 0; index < SECTION_COUNT; index++) {
                    Object section = sections.regionGetSection().invoke(region, index);
                    if (section != null
                            && Boolean.TRUE.equals(sections.sectionIsBuilt().invoke(section))
                            && !sections.hasTranslucentGeometry(section)) {
                        SECTION_BLEND[index] = 1.0F;
                    }
                }
            }
            GL20.glUniform1f(locations.start(), blendStart);
            GL20.glUniform1f(locations.end(), blendEnd);
            GL20.glUniform1i(locations.enabled(), enabled ? 1 : 0);
            GL20.glUniform1fv(locations.sections(), SECTION_BLEND);
            if (enabled) ready = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Arrays.fill(SECTION_BLEND, 0.0F);
            GL20.glUniform1i(locations.enabled(), 0);
            GL20.glUniform1fv(locations.sections(), SECTION_BLEND);
            ready = false;
            if (!failed) {
                failed = true;
                LOGGER.warn("Render-distance smoothing uniforms are unavailable", exception);
            }
        }
    }

    public static void invalidate() {
        LOCATIONS.clear();
        ready = false;
    }

    public static boolean ready() {
        return ready;
    }

    private static boolean irisShadowActive() {
        try {
            Method method = irisShadowActive;
            if (!irisShadowResolved) {
                synchronized (RoxyBlendUniforms.class) {
                    if (!irisShadowResolved) {
                        try {
                            Class<?> type = Class.forName(
                                    "net.irisshaders.iris.shadows.ShadowRenderingState",
                                    false,
                                    contextLoader()
                            );
                            irisShadowActive = type.getMethod("areShadowsCurrentlyBeingRendered");
                        } catch (ClassNotFoundException ignored) {
                            irisShadowActive = null;
                        }
                        irisShadowResolved = true;
                    }
                    method = irisShadowActive;
                }
            }
            return method != null && Boolean.TRUE.equals(method.invoke(null));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return true;
        }
    }

    private static ClassLoader contextLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? RoxyBlendUniforms.class.getClassLoader() : loader;
    }

    private static float renderDistanceBlocks() throws ReflectiveOperationException {
        RenderDistanceAccess runtime = renderDistanceAccess;
        if (runtime == null) {
            synchronized (RoxyBlendUniforms.class) {
                if (renderDistanceAccess == null) renderDistanceAccess = RenderDistanceAccess.resolve();
                runtime = renderDistanceAccess;
            }
        }
        Object minecraft = runtime.minecraftInstance().invoke(null);
        Object options = runtime.minecraftOptions().get(minecraft);
        return ((Number) runtime.effectiveRenderDistance().invoke(options)).floatValue() * 16.0F;
    }

    private static SectionAccess sectionAccess() throws ReflectiveOperationException {
        SectionAccess runtime = sectionAccess;
        if (runtime == null) {
            synchronized (RoxyBlendUniforms.class) {
                if (sectionAccess == null) sectionAccess = SectionAccess.resolve();
                runtime = sectionAccess;
            }
        }
        return runtime;
    }

    private record RenderDistanceAccess(
            Method minecraftInstance,
            Field minecraftOptions,
            Method effectiveRenderDistance
    ) {
        private static RenderDistanceAccess resolve() throws ReflectiveOperationException {
            ClassLoader loader = contextLoader();
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> options = Class.forName("net.minecraft.client.Options", false, loader);
            return new RenderDistanceAccess(
                    minecraft.getMethod("getInstance"),
                    minecraft.getField("options"),
                    options.getMethod("getEffectiveRenderDistance")
            );
        }
    }

    private record SectionAccess(
            Method regionGetSection,
            Method regionGetStorage,
            Method sectionIsBuilt,
            Method sectionIndex,
            Method sectionRegion,
            Method storageGetDataPointer,
            Method vertexCount,
            Object translucentPass,
            int facingCount
    ) {
        private static SectionAccess resolve() throws ReflectiveOperationException {
            ClassLoader loader = contextLoader();
            Class<?> region = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion", false, loader);
            Class<?> section = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", false, loader);
            Class<?> pass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass", false, loader);
            Class<?> passes = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses", false, loader);
            Class<?> storage = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage", false, loader);
            Class<?> unsafe = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe", false, loader);
            Class<?> facing = Class.forName(
                    "net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing", false, loader);
            return new SectionAccess(
                    region.getMethod("getSection", int.class),
                    region.getMethod("getStorage", pass),
                    section.getMethod("isBuilt"),
                    section.getMethod("getSectionIndex"),
                    section.getMethod("getRegion"),
                    storage.getMethod("getDataPointer", int.class),
                    unsafe.getMethod("getVertexCount", long.class, int.class),
                    passes.getField("TRANSLUCENT").get(null),
                    facing.getField("COUNT").getInt(null)
            );
        }

        private boolean hasTranslucentGeometry(Object section) throws ReflectiveOperationException {
            Object region = sectionRegion.invoke(section);
            Object storage = regionGetStorage.invoke(region, translucentPass);
            if (storage == null) return false;
            int index = ((Number) sectionIndex.invoke(section)).intValue();
            long pointer = ((Number) storageGetDataPointer.invoke(storage, index)).longValue();
            for (int facing = 0; facing < facingCount; facing++) {
                if (((Number) vertexCount.invoke(null, pointer, facing)).longValue() != 0L) return true;
            }
            return false;
        }
    }

    private record Locations(int start, int end, int enabled, int sections) {
        private static Locations resolve(int program) {
            return new Locations(
                    GL20.glGetUniformLocation(program, "roxy_BlendStart"),
                    GL20.glGetUniformLocation(program, "roxy_BlendEnd"),
                    GL20.glGetUniformLocation(program, "roxy_BlendEnabled"),
                    GL20.glGetUniformLocation(program, "roxy_SectionBlend[0]")
            );
        }

        private boolean available() {
            return start >= 0 && end >= 0 && enabled >= 0 && sections >= 0;
        }
    }
}
