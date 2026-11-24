package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.util.RoxyReflection;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

public final class RoxyLodEntityOcclusion {
    public static final String DEPTH_SAMPLER = "roxyVoxyLodDepth";
    public static final String STENCIL_SAMPLER = "roxyVoxyLodStencil";
    public static final String ENABLED_UNIFORM = "roxyVoxyLodEnabled";
    public static final String TRANSFORM_UNIFORM = "roxyVoxyLodTransform";
    public static final String VIEWPORT_UNIFORM = "roxyVoxyLodViewport";
    public static final String REVERSE_Z_UNIFORM = "roxyVoxyLodReverseZ";
    public static final String ZERO_ONE_UNIFORM = "roxyVoxyLodZeroOne";

    private static final int GL_DEPTH32F_STENCIL8 = 0x8CAD;
    private static final int GL_DEPTH_STENCIL_TEXTURE_MODE = 0x90EA;
    private static final Object LOCK = new Object();
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final Map<Object, ProgramBindings> PROGRAMS = new WeakHashMap<>();
    private static final int[] VIEWPORT_BUF = new int[4];
    private static final float[] IDENTITY = new Matrix4f().get(new float[16]);
    private record ProgramAccess(Method id) {}
    private static final ClassValue<ProgramAccess> PROGRAM_ACCESS = new ClassValue<>() {
        @Override protected ProgramAccess computeValue(Class<?> type) {
            for (String name : new String[]{"getId", "getProgramId", "handle"}) {
                Method method = RoxyReflection.tryFindMethod(type, name);
                if (method != null) return new ProgramAccess(method);
            }
            return new ProgramAccess(null);
        }
    };

    private static volatile Method shadowActiveMethod;
    private static volatile boolean shadowLookupAttempted;
    private static int viewSource;
    private static WeakReference<Object> viewSourceObject = new WeakReference<>(null);
    private static int stencilView;
    private static Frame frame;
    private static boolean frameOpen;
    private static boolean warned;
    private static boolean captureLogged;
    private static boolean registrationLogged;

    private RoxyLodEntityOcclusion() {
    }

    public static void beginFrame() {
        synchronized (LOCK) {
            frameOpen = true;
            frame = null;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            frame = null;
            frameOpen = false;
            if (stencilView != 0) {
                try {
                    GL43C.glDeleteTextures(stencilView);
                } catch (RuntimeException ignored) {
                }
            }
            stencilView = 0;
            viewSource = 0;
            viewSourceObject = new WeakReference<>(null);
            warned = false;
        }
    }

    public static void capture(Object pipeline, Object viewport) {
        synchronized (LOCK) {
            if (!frameOpen || pipeline == null || viewport == null || shadowPassActive(pipeline.getClass().getClassLoader())) {
                return;
            }
            try {
                Object framebuffer = readField(pipeline, "fb");
                Object depthTexture = invoke(framebuffer, "getDepthTex");
                int depthId = number(readField(depthTexture, "id"));
                if (depthId == 0) {
                    return;
                }

                Boolean reverseZ = property(pipeline, "isReverseZ");
                Boolean zeroOne = property(pipeline, "isZero2One");
                if (reverseZ == null || zeroOne == null) {
                    return;
                }

                Object projectionObject = readField(viewport, "projection");
                Object vanillaProjectionObject = readField(viewport, "vanillaProjection");
                if (!(projectionObject instanceof Matrix4fc voxyProjection)
                        || !(vanillaProjectionObject instanceof Matrix4fc vanillaProjection)) {
                    return;
                }
                int width = number(readField(viewport, "width"));
                int height = number(readField(viewport, "height"));
                if (width <= 0 || height <= 0) {
                    return;
                }
                if (GL45C.glGetTextureLevelParameteri(depthId, 0, GL11C.GL_TEXTURE_WIDTH) != width
                        || GL45C.glGetTextureLevelParameteri(depthId, 0, GL11C.GL_TEXTURE_HEIGHT) != height) return;

                int format = GL45C.glGetTextureLevelParameteri(depthId, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
                if (format != GL30C.GL_DEPTH24_STENCIL8 && format != GL_DEPTH32F_STENCIL8) {
                    return;
                }
                if (GL45C.glGetTextureParameteri(depthId, GL42C.GL_TEXTURE_IMMUTABLE_FORMAT) == GL11C.GL_FALSE) {
                    return;
                }
                int stencil = ensureStencilView(depthId, depthTexture, format);
                if (stencil == 0) {
                    return;
                }

                Matrix4d transform = new Matrix4d(vanillaProjection).mul(new Matrix4d(voxyProjection).invert());
                float[] values = new float[16];
                new Matrix4f(transform).get(values);
                for (float value : values) if (!Float.isFinite(value)) return;
                frame = new Frame(depthId, stencil, width, height, reverseZ, zeroOne, values);
                if (!captureLogged) {
                    captureLogged = true;
                    LOGGER.info("Create LOD occlusion captured Voxy depth at {}x{}", width, height);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                warnOnce("Voxy LOD depth capture is unavailable", ignored);
            }
        }
    }

    public static boolean registerSamplers(Object program, Object holder) {
        if (program == null || holder == null || !declaresSampler(holder, DEPTH_SAMPLER)
                || !declaresSampler(holder, STENCIL_SAMPLER)) {
            return false;
        }
        synchronized (LOCK) {
            ProgramBindings existing = PROGRAMS.get(program);
            if (existing != null) {
                return existing.registered;
            }
            try {
                ClassLoader loader = holder.getClass().getClassLoader();
                Class<?> textureTypeClass = Class.forName(
                        "net.irisshaders.iris.gl.texture.TextureType", false, loader);
                Object texture2d = textureTypeClass.getField("TEXTURE_2D").get(null);
                Class<?> glSamplerClass = Class.forName(
                        "net.irisshaders.iris.gl.sampler.GlSampler", false, loader);
                Object nearest = glSamplerClass.getField("NEAREST").get(null);
                IntSupplier depth = RoxyLodEntityOcclusion::depthTexture;
                IntSupplier stencil = RoxyLodEntityOcclusion::stencilTexture;
                boolean depthAdded = RoxyIrisCompat.addDynamicSampler(
                        holder, texture2d, depth, RoxyIrisCompat.samplerSupplier(nearest),
                        new String[]{DEPTH_SAMPLER});
                boolean stencilAdded = RoxyIrisCompat.addDynamicSampler(
                        holder, texture2d, stencil, RoxyIrisCompat.samplerSupplier(nearest),
                        new String[]{STENCIL_SAMPLER});
                ProgramBindings bindings = new ProgramBindings(depthAdded && stencilAdded);
                PROGRAMS.put(program, bindings);
                if (bindings.registered && !registrationLogged) {
                    registrationLogged = true;
                    LOGGER.info("Create LOD occlusion registered shader samplers");
                }
                return bindings.registered;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                PROGRAMS.put(program, new ProgramBindings(false));
                warnOnce("Roxy could not register LOD entity samplers", ignored);
                return false;
            }
        }
    }

    public static void applyUniforms(Object program) {
        if (program == null) {
            return;
        }
        synchronized (LOCK) {
            ProgramBindings bindings = PROGRAMS.get(program);
            if (bindings == null || !bindings.registered) {
                return;
            }
            int glProgram = programId(program);
            if (glProgram <= 0) {
                return;
            }
            bindings.resolve(glProgram);
            if (bindings.enabled < 0) {
                return;
            }
            Frame current = frame;
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, VIEWPORT_BUF);
            boolean available = current != null && VIEWPORT_BUF[2] > 0 && VIEWPORT_BUF[3] > 0;
            GL41C.glProgramUniform1i(glProgram, bindings.enabled, available ? 1 : 0);
            if (bindings.transform >= 0) {
                GL41C.glProgramUniformMatrix4fv(glProgram, bindings.transform, false,
                        current == null ? IDENTITY : current.transform);
            }
            if (bindings.viewport >= 0) {
                GL41C.glProgramUniform4f(glProgram, bindings.viewport, VIEWPORT_BUF[0], VIEWPORT_BUF[1],
                        Math.max(1, VIEWPORT_BUF[2]), Math.max(1, VIEWPORT_BUF[3]));
            }
            if (bindings.reverseZ >= 0) {
                GL41C.glProgramUniform1i(glProgram, bindings.reverseZ, current != null && current.reverseZ ? 1 : 0);
            }
            if (bindings.zeroOne >= 0) {
                GL41C.glProgramUniform1i(glProgram, bindings.zeroOne, current != null && current.zeroOne ? 1 : 0);
            }
        }
    }

    private static int depthTexture() {
        synchronized (LOCK) {
            return frame == null ? 0 : frame.depthTexture;
        }
    }

    private static int stencilTexture() {
        synchronized (LOCK) {
            return frame == null ? 0 : frame.stencilTexture;
        }
    }

    private static int ensureStencilView(int depthId, Object depthTexture, int sourceFormat) {
        if (depthId == viewSource && depthTexture == viewSourceObject.get() && stencilView != 0) {
            return stencilView;
        }
        if (stencilView != 0) {
            GL43C.glDeleteTextures(stencilView);
            stencilView = 0;
        }
        stencilView = GL43C.glGenTextures();
        GL43C.glTextureView(stencilView, GL11C.GL_TEXTURE_2D, depthId,
                sourceFormat, 0, 1, 0, 1);
        GL45C.glTextureParameteri(stencilView, GL_DEPTH_STENCIL_TEXTURE_MODE, GL30C.GL_STENCIL_INDEX);
        GL45C.glTextureParameteri(stencilView, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(stencilView, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(stencilView, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
        viewSource = depthId;
        viewSourceObject = new WeakReference<>(depthTexture);
        return stencilView;
    }

    private static boolean declaresSampler(Object holder, String name) {
        try {
            Method method = RoxyReflection.tryFindMethod(holder.getClass(), "hasSampler", String.class);
            if (method == null) method = RoxyReflection.tryFindMethod(holder.getClass(), "hasSamplerName", String.class);
            if (method == null) return false;
            Object value = method.invoke(holder, name);
            return value instanceof Boolean present && present;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean shadowPassActive(ClassLoader loader) {
        Method active = shadowActiveMethod;
        if (active == null && !shadowLookupAttempted) {
            shadowLookupAttempted = true;
            try {
                Class<?> irisUtil = Class.forName("me.cortex.voxy.client.core.util.IrisUtil", false, loader);
                active = irisUtil.getMethod("irisShadowActive");
                shadowActiveMethod = active;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        if (active == null) return false;
        try {
            return Boolean.TRUE.equals(active.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void warnOnce(String message, Throwable cause) {
        if (!warned) {
            warned = true;
            LOGGER.warn(message, cause);
        }
    }

    private static Boolean property(Object pipeline, String name) {
        Object properties;
        try {
            properties = readField(pipeline, "properties");
        } catch (ReflectiveOperationException ignored) {
            try {
                properties = readField(pipeline, "renderProperties");
            } catch (ReflectiveOperationException ignoredAgain) {
                return null;
            }
        }
        try {
            Object value = invoke(properties, name);
            return value instanceof Boolean bool ? bool : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static int programId(Object program) {
        try {
            Method id = PROGRAM_ACCESS.get(program.getClass()).id();
            return id == null ? 0 : number(id.invoke(program));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static int number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected numeric Voxy/Iris value");
        }
        return number.intValue();
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        return RoxyReflection.findField(owner.getClass(), name).get(owner);
    }

    private static Object invoke(Object owner, String name, Object... args) throws ReflectiveOperationException {
        return RoxyReflection.findMethodByArity(owner.getClass(), name, args.length).invoke(owner, args);
    }

    private record Frame(
            int depthTexture,
            int stencilTexture,
            int width,
            int height,
            boolean reverseZ,
            boolean zeroOne,
            float[] transform
    ) {
    }

    private static final class ProgramBindings {
        private final boolean registered;
        private int glProgram = -1;
        private int enabled = -1;
        private int transform = -1;
        private int viewport = -1;
        private int reverseZ = -1;
        private int zeroOne = -1;

        private ProgramBindings(boolean registered) {
            this.registered = registered;
        }

        private void resolve(int program) {
            if (program == glProgram) {
                return;
            }
            glProgram = program;
            enabled = GL20C.glGetUniformLocation(program, ENABLED_UNIFORM);
            transform = GL20C.glGetUniformLocation(program, TRANSFORM_UNIFORM);
            viewport = GL20C.glGetUniformLocation(program, VIEWPORT_UNIFORM);
            reverseZ = GL20C.glGetUniformLocation(program, REVERSE_Z_UNIFORM);
            zeroOne = GL20C.glGetUniformLocation(program, ZERO_ONE_UNIFORM);
        }
    }
}
