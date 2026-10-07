import net.rasanovum.roxy.shader.RoxyLodEntityShader;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Build-only GPU fixture for the LOD fragment predicate. Run with LWJGL and the compiled Roxy
 * classes on the class path. It exercises the real source patch and a D24S8 stencil texture view.
 */
public final class VerifyLodEntityOcclusion {
    private static final int SIZE = 32;

    public static void main(String[] args) throws Exception {
        Method patch = RoxyLodEntityShader.class.getDeclaredMethod("patch", String.class);
        patch.setAccessible(true);
        String fragment = (String) patch.invoke(null, "#version 330 core\n"
                + "layout(location=0) out vec4 roxyColor;\n"
                + "void main() { roxyColor = vec4(1.0, 0.0, 0.0, 1.0); }\n");
        require(fragment.contains("roxy_lod_entity_occlusion"), "fragment patch is a no-op");
        require(fragment.contains("uniform usampler2D roxyVoxyLodStencil"), "stencil declaration missing");

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("GLFW could not create a headless validation context");
        }
        long window = 0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(SIZE, SIZE, "Roxy LOD occlusion validation", 0, 0);
            if (window == 0) {
                throw new IllegalStateException("GLFW could not create a hidden context");
            }
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            String colorwheelSource = (String) patch.invoke(null, "#version 330 core\n"
                    + "#extension GL_ARB_separate_shader_objects : enable\n"
                    + "layout(location=0) out vec4 roxyColor;\n"
                    + "void _clrwl_shader_main() { roxyColor = vec4(1.0); }\n"
                    + "void main() { _clrwl_shader_main(); }\n");
            require(colorwheelSource.contains("roxy_lod_entity_occlusion();"),
                    "Colorwheel's renamed fragment entry point was not patched");
            int colorwheelProgram = program(colorwheelSource);
            GL20C.glDeleteProgram(colorwheelProgram);
            int program = program(fragment);
            int source = depthStencilTexture();
            int stencil = GL43C.glGenTextures();
            GL43C.glTextureView(stencil, GL11C.GL_TEXTURE_2D, source, GL30C.GL_DEPTH24_STENCIL8, 0, 1, 0, 1);
            GL45C.glTextureParameteri(stencil, 0x90EA, GL30C.GL_STENCIL_INDEX);
            GL45C.glTextureParameteri(stencil, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL45C.glTextureParameteri(stencil, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            int sourceFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(sourceFbo, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, source, 0);
            int color = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(color, 1, GL30C.GL_RGBA8, SIZE, SIZE);
            int drawFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(drawFbo, GL30C.GL_COLOR_ATTACHMENT0, color, 0);
            int[] drawBuffers = {GL30C.GL_COLOR_ATTACHMENT0};
            GL45C.glNamedFramebufferDrawBuffers(drawFbo, drawBuffers);
            require(GL45C.glCheckNamedFramebufferStatus(drawFbo, GL30C.GL_FRAMEBUFFER)
                    == GL30C.GL_FRAMEBUFFER_COMPLETE, "color framebuffer incomplete");
            GL30C.glBindVertexArray(GL45C.glCreateVertexArrays());
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, drawFbo);
            GL11C.glViewport(0, 0, SIZE, SIZE);
            GL20C.glUseProgram(program);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodDepth"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodStencil"), 1);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodEnabled"), 1);
            GL20C.glUniform4f(GL20C.glGetUniformLocation(program, "roxyVoxyLodViewport"), 0, 0, SIZE, SIZE);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodReverseZ"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodZeroOne"), 0);
            FloatBuffer identity = org.lwjgl.system.MemoryUtil.memAllocFloat(16);
            identity.put(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}).flip();
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "roxyVoxyLodTransform"), false, identity);
            org.lwjgl.system.MemoryUtil.memFree(identity);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, source);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, stencil);

            clearSource(sourceFbo, 0.5f, 1);
            require(draw(drawFbo, program, 0.3f), "front fragment was discarded");
            require(!draw(drawFbo, program, 0.7f), "behind fragment survived");
            clearSource(sourceFbo, 0.5f, 0);
            require(draw(drawFbo, program, 0.7f), "stencil-zero pixel occluded");
            clearSource(sourceFbo, 1.0f, 1);
            require(draw(drawFbo, program, 0.7f), "no-LOD clear depth occluded");
            clearSource(sourceFbo, 0.5f, 1);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodReverseZ"), 1);
            require(!draw(drawFbo, program, 0.3f), "reverse-Z behind fragment survived");
            require(draw(drawFbo, program, 0.7f), "reverse-Z front fragment was discarded");
            clearSource(sourceFbo, 0.0f, 1);
            require(draw(drawFbo, program, 0.3f), "reverse-Z empty sky occluded a fragment");
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodReverseZ"), 0);
            verifyProjections(drawFbo, sourceFbo, program);
            clearSource(sourceFbo, 0.5f, 1);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "roxyVoxyLodTransform"), false,
                    new Matrix4f().get(new float[16]));
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodEnabled"), 0);
            require(draw(drawFbo, program, 0.7f), "inactive Voxy changed ordinary entity rendering");
            verifyCaptureLifecycle(drawFbo, sourceFbo, source, program);
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "Occlusion test generated an OpenGL error");
            System.out.println("LOD entity occlusion GPU fixture passed");
        } finally {
            if (window != 0) {
                GLFW.glfwDestroyWindow(window);
            }
            GLFW.glfwTerminate();
        }
    }

    public record Properties(boolean isReverseZ, boolean isZero2One) {}
    public static final class DepthTexture {
        public final int id;
        DepthTexture(int id) { this.id = id; }
    }
    public static final class Framebuffer {
        final DepthTexture depth;
        Framebuffer(int id) { depth = new DepthTexture(id); }
        public DepthTexture getDepthTex() { return depth; }
    }
    public static final class Pipeline {
        final Properties properties = new Properties(false, false);
        final Framebuffer fb;
        Pipeline(int id) { fb = new Framebuffer(id); }
    }
    public static final class Viewport {
        public final int width = SIZE, height = SIZE;
        public final Matrix4f MVP = new Matrix4f(), vanillaProjection = new Matrix4f(), modelView = new Matrix4f();
    }
    public static final class ColorwheelProgram {
        private final int id;
        ColorwheelProgram(int id) { this.id = id; }
        public int handle() { return id; }
    }

    @SuppressWarnings("unchecked")
    private static void verifyCaptureLifecycle(int drawFbo, int sourceFbo, int source, int program) throws Exception {
        Class<?> bindingsType = Class.forName(RoxyLodEntityOcclusion.class.getName() + "$ProgramBindings");
        Constructor<?> constructor = bindingsType.getDeclaredConstructor(boolean.class);
        constructor.setAccessible(true);
        Field programs = RoxyLodEntityOcclusion.class.getDeclaredField("PROGRAMS");
        programs.setAccessible(true);
        ColorwheelProgram colorwheel = new ColorwheelProgram(program);
        ((Map<Object, Object>) programs.get(null)).put(colorwheel, constructor.newInstance(true));
        Pipeline pipeline = new Pipeline(source);
        Viewport viewport = new Viewport();
        int sourceMode = GL45C.glGetTextureParameteri(source, 0x90EA);
        for (int cycle = 0; cycle < 2; cycle++) {
            RoxyLodEntityOcclusion.beginFrame();
            clearSource(sourceFbo, .5f, 1);
            RoxyLodEntityOcclusion.capture(pipeline, viewport);
            Field stencilView = RoxyLodEntityOcclusion.class.getDeclaredField("stencilView");
            stencilView.setAccessible(true);
            int view = stencilView.getInt(null);
            require(view != 0 && GL11C.glIsTexture(view), "capture did not create a usable stencil view");
            require(GL45C.glGetTextureParameteri(source, 0x90EA) == sourceMode,
                    "capture changed the original Voxy texture sampling mode");
            GL45C.glBindTextureUnit(5, source);
            GL45C.glBindTextureUnit(6, view);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodDepth"), 5);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "roxyVoxyLodStencil"), 6);
            GL11C.glViewport(0, 0, SIZE, SIZE);
            RoxyLodEntityOcclusion.applyUniforms(colorwheel);
            require(GL20C.glGetUniformi(program, GL20C.glGetUniformLocation(program, "roxyVoxyLodDepth")) == 5
                            && GL20C.glGetUniformi(program, GL20C.glGetUniformLocation(program, "roxyVoxyLodStencil")) == 6,
                    "uniform update overwrote automatic sampler allocation");
            require(!draw(drawFbo, program, .7f), "captured LOD depth did not hide the rear fragment");
            require(draw(drawFbo, program, .3f), "captured LOD depth hid a front fragment");
            RoxyLodEntityOcclusion.reset();
            require(!GL11C.glIsTexture(view), "reset leaked the stencil texture view");
            RoxyLodEntityOcclusion.applyUniforms(colorwheel);
            require(draw(drawFbo, program, .7f), "renderer reset left occlusion enabled without a valid frame");
        }
    }

    private static void verifyProjections(int drawFbo, int sourceFbo, int program) {
        Matrix4f vanilla = new Matrix4f().perspective((float) Math.toRadians(70), 1, .05f, 24000);
        Matrix4f lod = new Matrix4f().perspective((float) Math.toRadians(70), 1, 8, 48000);
        Matrix4f transform = new Matrix4f(lod).mul(new Matrix4f(vanilla).invert());
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "roxyVoxyLodTransform"), false,
                transform.get(new float[16]));
        for (float distance : new float[]{220, 1000, 10000}) {
            clearSource(sourceFbo, projectedDepth(lod, distance), 1);
            require(draw(drawFbo, program, projectedDepth(vanilla, distance * .8f)),
                    "projection conversion hid a front fragment at " + distance);
            require(!draw(drawFbo, program, projectedDepth(vanilla, distance * 1.2f)),
                    "projection conversion left a fragment visible behind terrain at " + distance);
        }
    }

    private static float projectedDepth(Matrix4f projection, float distance) {
        Vector4f clip = projection.transform(new Vector4f(0, 0, -distance, 1));
        return clip.z / clip.w * .5f + .5f;
    }

    private static int program(String fragment) {
        String vertex = "#version 330 core\n"
                + "uniform float roxyDepth;\n"
                + "void main(){ vec2 p = (gl_VertexID == 0) ? vec2(-1,-1) : ((gl_VertexID == 1) ? vec2(3,-1) : vec2(-1,3)); gl_Position=vec4(p,roxyDepth*2.0-1.0,1); }\n";
        int vs = compile(GL20C.GL_VERTEX_SHADER, vertex);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, fragment);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        require(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        require(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0, GL20C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int depthStencilTexture() {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, GL30C.GL_DEPTH24_STENCIL8, SIZE, SIZE);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        return texture;
    }

    private static void clearSource(int fbo, float depth, int stencil) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL30C.glClearBufferfi(GL30C.GL_DEPTH_STENCIL, 0, depth, stencil);
    }

    private static boolean draw(int fbo, int program, float depth) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL11C.glClearColor(0, 0, 0, 1);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "roxyDepth"), depth);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        ByteBuffer pixel = org.lwjgl.system.MemoryUtil.memAlloc(4);
        GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
        boolean survives = (pixel.get(0) & 0xff) != 0;
        org.lwjgl.system.MemoryUtil.memFree(pixel);
        return survives;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
