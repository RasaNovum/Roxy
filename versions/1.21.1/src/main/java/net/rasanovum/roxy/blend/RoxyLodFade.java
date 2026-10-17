package net.rasanovum.roxy.blend;

import net.rasanovum.roxy.compat.RoxyIrisNativeTransFade;
import net.rasanovum.roxy.compat.RoxyIrisEffectsDepth;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL45C.*;

public final class RoxyLodFade {
    private static final ThreadLocal<RoxyLodFade> CURRENT = new ThreadLocal<>();
    private final Map<Integer, Locations> locations = new HashMap<>();
    private final Map<Integer, IrisLocations> irisLocations = new HashMap<>();
    private final boolean iris;
    private final RoxyIrisDepthHandoff depthHandoff;
    private final RoxyIrisFullDepth fullDepth;
    private final RoxyTranslucentBoundary translucentBoundary = new RoxyTranslucentBoundary();
    private final float[] inverse = new float[16];
    private final float[] inverseNativeProjection = new float[16];
    private final float[] inverseLodProjection = new float[16];
    private Field projectionField, modelViewField, cameraXField, cameraZField;
    private Field lodProjectionField, viewportWidth, viewportHeight;
    private Field optionsField, levelField;
    private Method minecraftInstance, renderDistance;
    private Field opaqueFramebufferField, translucentFramebufferField, depthIdField, dataField, mergeDepthField;
    private Field framebufferField, framebufferIdField;
    private Method framebufferDepth, shadowActive;
    private float start, end;
    private double cameraX, cameraZ;
    private boolean enabled, warned, logged;
    private boolean excludesLodDepth;
    private int opaqueLodDepth;
    private boolean translucentPass;
    private int fullDepthTextureUnit;
    private long irisDepthGeneration = Long.MIN_VALUE;
    private int renderWidth, renderHeight;
    private int realDepth, depthFramebuffer, width, height, format;

    public RoxyLodFade() {
        this(false);
    }

    public RoxyLodFade(boolean iris) {
        this.iris = iris;
        this.depthHandoff = iris ? new RoxyIrisDepthHandoff() : null;
        this.fullDepth = iris ? new RoxyIrisFullDepth() : null;
    }

    public static void prepare(Object pipeline, Object viewport) {
        CURRENT.remove();
        if (!(pipeline instanceof RoxyLodFadeAccess access)) return;
        RoxyLodFade fade = access.roxy$getLodFade();
        fade.enabled = false;
        CURRENT.set(fade);
        if (fade.warned || !RoxyBlendConfig.enabled()) return;
        try {
            if (fade.minecraftInstance == null) {
                ClassLoader loader = viewport.getClass().getClassLoader();
                Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
                fade.minecraftInstance = minecraft.getMethod("getInstance");
                fade.optionsField = minecraft.getField("options");
                fade.levelField = minecraft.getField("level");
                fade.renderDistance = Class.forName("net.minecraft.client.Options", false, loader)
                        .getMethod("getEffectiveRenderDistance");
            }
            Object minecraft = fade.minecraftInstance.invoke(null);
            if (minecraft == null || fade.levelField.get(minecraft) == null) return;
            if (fade.iris && fade.shadowActive != null && (boolean) fade.shadowActive.invoke(null)) return;
            float distance = ((Number) fade.renderDistance.invoke(fade.optionsField.get(minecraft))).floatValue() * 16.0F;
            fade.start = RoxyBlendConfig.transitionStartBlocks(distance);
            fade.end = RoxyBlendConfig.transitionEndBlocks(distance);
            if (fade.end <= fade.start) return;
            if (fade.projectionField == null) {
                Class<?> type = viewport.getClass();
                fade.projectionField = type.getField("vanillaProjection");
                fade.modelViewField = type.getField("modelView");
                fade.cameraXField = type.getField("cameraX");
                fade.cameraZField = type.getField("cameraZ");
                fade.lodProjectionField = type.getField("projection");
                fade.viewportWidth = type.getField("width");
                fade.viewportHeight = type.getField("height");
            }
            Matrix4f transform = new Matrix4f((Matrix4f) fade.projectionField.get(viewport))
                    .mul((Matrix4f) fade.modelViewField.get(viewport));
            transform.invert();
            if (!transform.isFinite()) return;
            transform.get(fade.inverse);
            fade.cameraX = fade.cameraXField.getDouble(viewport);
            fade.cameraZ = fade.cameraZField.getDouble(viewport);
            fade.renderWidth = fade.viewportWidth.getInt(viewport);
            fade.renderHeight = fade.viewportHeight.getInt(viewport);
            if (fade.iris) {
                if (fade.opaqueFramebufferField == null) {
                    Class<?> type = pipeline.getClass();
                    fade.opaqueFramebufferField = type.getField("fb");
                    fade.translucentFramebufferField = type.getField("fbTranslucent");
                    fade.dataField = type.getDeclaredField("data");
                    fade.dataField.setAccessible(true);
                    fade.mergeDepthField = fade.dataField.getType().getField("renderToVanillaDepth");
                    fade.framebufferDepth = fade.opaqueFramebufferField.getType().getMethod("getDepthTex");
                    fade.framebufferField = fade.opaqueFramebufferField.getType().getField("framebuffer");
                    fade.framebufferIdField = fade.framebufferField.getType().getField("id");
                    fade.depthIdField = fade.framebufferDepth.getReturnType().getField("id");
                    fade.shadowActive = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState", false,
                            viewport.getClass().getClassLoader()).getMethod("areShadowsCurrentlyBeingRendered");
                }
                if ((boolean) fade.shadowActive.invoke(null)) return;
                fade.excludesLodDepth = !fade.mergeDepthField.getBoolean(fade.dataField.get(pipeline));
                Matrix4f lodProjection = new Matrix4f((Matrix4f) fade.lodProjectionField.get(viewport)).invert();
                if (!lodProjection.isFinite()) return;
                lodProjection.get(fade.inverseLodProjection);
                Matrix4f nativeProjection = new Matrix4f((Matrix4f) fade.projectionField.get(viewport)).invert();
                if (!nativeProjection.isFinite()) return;
                nativeProjection.get(fade.inverseNativeProjection);
            }
            fade.enabled = true;
            CURRENT.set(fade);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            fade.disable(exception);
        }
    }

    public static boolean keepChunkBoundary(int chunkX, int chunkZ) {
        RoxyLodFade fade = CURRENT.get();
        if (fade == null || !fade.enabled) return true;
        double farX = Math.abs(chunkX * 16.0 + 8.0 - fade.cameraX) + 8.0;
        double farZ = Math.abs(chunkZ * 16.0 + 8.0 - fade.cameraZ) + 8.0;
        return farX * farX + farZ * farZ <= fade.start * fade.start;
    }

    public static boolean active() {
        RoxyLodFade fade = CURRENT.get();
        return fade != null && fade.enabled;
    }

    public static void beginTranslucentBoundary() {
        RoxyLodFade fade = CURRENT.get();
        if (fade != null) fade.translucentBoundary.clear();
    }

    public static void addTranslucentSection(int x, int y, int z) {
        RoxyLodFade fade = CURRENT.get();
        if (fade != null && fade.enabled) fade.translucentBoundary.add(x, y, z);
    }

    public static void uploadTranslucentBoundary() {
        RoxyLodFade fade = CURRENT.get();
        if (fade == null) return;
        try {
            fade.translucentBoundary.upload();
        } catch (RuntimeException | LinkageError exception) {
            fade.translucentBoundary.clear();
            fade.disable(exception);
        }
    }

    public void activate() {
        CURRENT.set(this);
    }

    public void activateIris(Object pipeline, boolean translucent) {
        activate();
        translucentPass = translucent;
        RoxyIrisEffectsDepth.activatePipeline(pipeline.getClass().getClassLoader());
        if (!enabled) return;
        try {
            opaqueLodDepth = depthIdField.getInt(framebufferDepth.invoke(opaqueFramebufferField.get(pipeline)));
            int transDepth = depthIdField.getInt(framebufferDepth.invoke(translucentFramebufferField.get(pipeline)));
            RoxyIrisNativeTransFade.publish(true, start, end, inverse, inverseNativeProjection, inverseLodProjection,
                    opaqueLodDepth, transDepth, renderWidth, renderHeight);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            disable(exception);
        }
    }

    public boolean beginFullDepth(Object pipeline, boolean translucent) {
        if (!iris || !enabled || realDepth == 0) return false;
        try {
            Object framebuffer = (translucent ? translucentFramebufferField : opaqueFramebufferField).get(pipeline);
            int depth = depthIdField.getInt(framebufferDepth.invoke(framebuffer));
            int id = framebufferIdField.getInt(framebufferField.get(framebuffer));
            fullDepth.begin(id, depth, translucent, renderWidth, renderHeight);
            translucentPass = translucent;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            fullDepth.end(false);
            disable(exception);
            return false;
        }
    }

    public void endFullDepth(boolean completed) {
        fullDepth.end(completed);
    }

    public static void bindIrisShader() {
        bindTerrainShader();
    }

    public static void bindTerrainShader() {
        RoxyLodFade fade = CURRENT.get();
        if (fade == null) return;
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        if (program == 0) return;
        fade.translucentBoundary.bind(program);
        if (!fade.iris) return;
        IrisLocations uniforms = fade.irisLocations.computeIfAbsent(program, IrisLocations::resolve);
        if (uniforms.enabled < 0) return;
        boolean active = fade.enabled && fade.realDepth != 0;
        glUniform1i(uniforms.enabled, active ? 1 : 0);
        glUniform1i(uniforms.depthPass, active && fade.fullDepth.rendering() ? 1 : 0);
        if (fade.fullDepth.rendering()) glColorMask(false, false, false, false);
        glUniform1i(uniforms.translucentEnabled, active && RoxyIrisNativeTransFade.canFadeNativeTranslucency() ? 1 : 0);
        if (!active) {
            RoxyIrisEffectsDepth.bindVoxyUniforms();
            return;
        }
        glUniform1f(uniforms.start, fade.start);
        glUniform1f(uniforms.end, fade.end);
        glUniformMatrix4fv(uniforms.inverse, false, fade.inverse);
        glUniform2f(uniforms.viewport, fade.renderWidth, fade.renderHeight);
        glBindTextureUnit(3, fade.realDepth);
        glBindSampler(3, 0);
        if (uniforms.opaqueDepth >= 0) {
            glBindTextureUnit(5, fade.opaqueLodDepth);
            glBindSampler(5, 0);
        }
        if (uniforms.fullDepth >= 0) {
            if (fade.fullDepthTextureUnit == 0) fade.fullDepthTextureUnit = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) - 1;
            glUniform1i(uniforms.fullDepth, fade.fullDepthTextureUnit);
            glBindTextureUnit(fade.fullDepthTextureUnit, fade.translucentPass ? fade.fullDepth.translucent() : fade.fullDepth.opaque());
            glBindSampler(fade.fullDepthTextureUnit, 0);
        }
        RoxyIrisEffectsDepth.bindVoxyUniforms();
        if (!fade.logged) {
            fade.logged = true;
            LoggerFactory.getLogger("Roxy").info("Blending Iris terrain into LODs between {} and {} blocks", fade.start, fade.end);
        }
    }

    public void reconcileIrisDepth(Object pipeline, int sourceFramebuffer) {
        if (!enabled || !iris || realDepth == 0) return;
        try {
            Object framebuffer = opaqueFramebufferField.get(pipeline);
            Object depth = framebufferDepth.invoke(framebuffer);
            int lodDepth = depthIdField.getInt(depth);
            depthHandoff.apply(sourceFramebuffer, lodDepth, realDepth, width, height,
                    start, inverse, excludesLodDepth);
            int transDepth = depthIdField.getInt(framebufferDepth.invoke(translucentFramebufferField.get(pipeline)));
            RoxyIrisNativeTransFade.publish(true, start, end, inverse, inverseNativeProjection, inverseLodProjection,
                    lodDepth, transDepth, width, height);
            if (width == renderWidth && height == renderHeight
                    && fullDepth.readyForFrame(irisDepthGeneration, renderWidth, renderHeight)) {
                RoxyIrisEffectsDepth.publish(irisDepthGeneration, true, realDepth, fullDepth.opaque(),
                        fullDepth.effectsTranslucent(irisDepthGeneration, renderWidth, renderHeight),
                        renderWidth, renderHeight);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            disable(exception);
        }
    }

    public void captureDepth(int sourceFramebuffer, int sourceWidth, int sourceHeight) {
        if (iris) {
            fullDepth.invalidate();
            irisDepthGeneration = RoxyIrisEffectsDepth.beginFrame();
        }
        if (!enabled) return;
        try {
            int type = glGetNamedFramebufferAttachmentParameteri(sourceFramebuffer, GL_DEPTH_ATTACHMENT,
                    GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (type != GL_TEXTURE) {
                enabled = false;
                return;
            }
            int source = glGetNamedFramebufferAttachmentParameteri(sourceFramebuffer, GL_DEPTH_ATTACHMENT,
                    GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (source == 0) {
                enabled = false;
                return;
            }
            sourceWidth = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_WIDTH);
            sourceHeight = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_HEIGHT);
            int sourceFormat = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_INTERNAL_FORMAT);
            if (sourceFormat == GL_DEPTH_COMPONENT) {
                int bits = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_DEPTH_SIZE);
                int depthType = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_DEPTH_TYPE);
                sourceFormat = bits <= 16 ? GL_DEPTH_COMPONENT16 : bits <= 24 ? GL_DEPTH_COMPONENT24
                        : depthType == GL_FLOAT ? GL_DEPTH_COMPONENT32F : GL_DEPTH_COMPONENT32;
            }
            if (sourceWidth < 1 || sourceHeight < 1) {
                enabled = false;
                return;
            }
            if (iris) fullDepth.beginFrame(irisDepthGeneration, renderWidth, renderHeight);
            if (realDepth == 0 || width != sourceWidth || height != sourceHeight || format != sourceFormat) {
                releaseTexture();
                realDepth = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorage2D(realDepth, 1, sourceFormat, sourceWidth, sourceHeight);
                glTextureParameteri(realDepth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTextureParameteri(realDepth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTextureParameteri(realDepth, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(realDepth, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                if (sourceFormat == GL_DEPTH24_STENCIL8 || sourceFormat == GL_DEPTH32F_STENCIL8) {
                    glTextureParameteri(realDepth, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
                }
                depthFramebuffer = glCreateFramebuffers();
                glNamedFramebufferTexture(depthFramebuffer, GL_DEPTH_ATTACHMENT, realDepth, 0);
                glNamedFramebufferDrawBuffer(depthFramebuffer, GL_NONE);
                glNamedFramebufferReadBuffer(depthFramebuffer, GL_NONE);
                if (glCheckNamedFramebufferStatus(depthFramebuffer, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Incomplete LOD fade depth framebuffer");
                }
                width = sourceWidth;
                height = sourceHeight;
                format = sourceFormat;
            }
            // A separate texture avoids sampling the depth attachment while the final blend writes to it.
            boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
            if (scissor) glDisable(GL_SCISSOR_TEST);
            try {
                glBlitNamedFramebuffer(sourceFramebuffer, depthFramebuffer, 0, 0, width, height,
                        0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
            } finally {
                if (scissor) glEnable(GL_SCISSOR_TEST);
            }
        } catch (RuntimeException | LinkageError exception) {
            releaseTexture();
            disable(exception);
        }
    }

    public static void bindStencil(Object pipeline) {
        if (pipeline instanceof RoxyLodFadeAccess access) access.roxy$getLodFade().bind(false);
        else {
            int program = glGetInteger(GL_CURRENT_PROGRAM);
            if (program != 0) glUniform1i(glGetUniformLocation(program, "roxyFadeEnabled"), 0);
        }
    }

    public void bindComposite() {
        bind(true);
    }

    private void bind(boolean composite) {
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        if (program == 0) return;
        Locations uniforms = locations.computeIfAbsent(program, Locations::resolve);
        boolean active = enabled && realDepth != 0 && uniforms.available();
        glUniform1i(uniforms.enabled, active ? 1 : 0);
        if (!active) return;
        glUniform1f(uniforms.start, start);
        glUniform1f(uniforms.end, end);
        glUniformMatrix4fv(uniforms.inverse, false, inverse);
        if (composite) {
            glBindTextureUnit(6, realDepth);
            glBindSampler(6, 0);
            if (!logged) {
                logged = true;
                LoggerFactory.getLogger("Roxy").info("Blending real terrain into LODs between {} and {} blocks", start, end);
            }
        }
    }

    public static void finish(Object pipeline) {
        CURRENT.remove();
    }

    public void free() {
        if (CURRENT.get() == this) CURRENT.remove();
        enabled = false;
        locations.clear();
        irisLocations.clear();
        translucentBoundary.free();
        if (depthHandoff != null) depthHandoff.free();
        if (fullDepth != null) fullDepth.free();
        if (iris) RoxyIrisNativeTransFade.invalidateFrame();
        if (iris) RoxyIrisEffectsDepth.releaseDepthResources();
        releaseTexture();
    }

    private void releaseTexture() {
        if (depthFramebuffer != 0) glDeleteFramebuffers(depthFramebuffer);
        depthFramebuffer = 0;
        if (realDepth != 0) glDeleteTextures(realDepth);
        realDepth = 0;
    }

    private void disable(Throwable exception) {
        enabled = false;
        if (iris) RoxyIrisNativeTransFade.invalidateFrame();
        if (iris) RoxyIrisEffectsDepth.beginFrame();
        if (!warned) {
            warned = true;
            LoggerFactory.getLogger("Roxy").warn("Unable to blend the LOD transition; retaining real terrain", exception);
        }
    }

    private record Locations(int enabled, int start, int end, int inverse) {
        static Locations resolve(int program) {
            return new Locations(glGetUniformLocation(program, "roxyFadeEnabled"),
                    glGetUniformLocation(program, "roxyFadeStart"),
                    glGetUniformLocation(program, "roxyFadeEnd"),
                    glGetUniformLocation(program, "roxyFadeInvVanillaMvp"));
        }

        boolean available() {
            return enabled >= 0 && start >= 0 && inverse >= 0;
        }
    }

    private record IrisLocations(int enabled, int start, int end, int inverse, int viewport, int opaqueDepth, int translucentEnabled,
                                 int depthPass, int fullDepth) {
        static IrisLocations resolve(int program) {
            return new IrisLocations(glGetUniformLocation(program, "roxyIrisFadeEnabled"),
                    glGetUniformLocation(program, "roxyIrisFadeStart"),
                    glGetUniformLocation(program, "roxyIrisFadeEnd"),
                    glGetUniformLocation(program, "roxyIrisFadeInvVanillaMvp"),
                    glGetUniformLocation(program, "roxyIrisFadeViewport"),
                    glGetUniformLocation(program, "roxyIrisFadeOpaqueDepth"),
                    glGetUniformLocation(program, "roxyIrisFadeTranslucentEnabled"),
                    glGetUniformLocation(program, "roxyIrisFadeDepthPass"),
                    glGetUniformLocation(program, "roxyIrisFadeFullDepth"));
        }
    }
}
