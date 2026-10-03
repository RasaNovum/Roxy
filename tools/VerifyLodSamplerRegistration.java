import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import com.mojang.blaze3d.systems.RenderSystem;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.shader.RoxyLodEntityShader;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
import java.util.Set;

public final class VerifyLodSamplerRegistration {
    private VerifyLodSamplerRegistration() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        installCreateMarker();
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialize");
        long window = 0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(16, 16, "Roxy sampler registration", 0, 0);
            if (window == 0) throw new IllegalStateException("GLFW could not create context");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();

            int program = program((String) patch().invoke(null, "#version 330 core\n"
                    + "layout(location=0) out vec4 roxyColor;\n"
                    + "void main(){ roxyColor=vec4(1.0); }\n"));
            ProgramSamplers.Builder builder = ProgramSamplers.builder(program, Set.of(0, 1, 2));
            Key key = new Key(program);
            require(RoxyLodEntityOcclusion.registerSamplers(key, builder), "real Iris builder rejected both LOD samplers");
            ProgramSamplers samplers = builder.build();
            List<?> bindings = (List<?>) field(samplers, "samplerBindings");
            require(bindings.size() == 2, "expected two dynamic LOD sampler bindings, found " + bindings.size());
            for (Object binding : bindings) {
                int unit = ((Number) field(binding, "textureUnit")).intValue();
                require(unit >= 3, "LOD sampler stole a reserved texture unit: " + unit);
            }
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "sampler registration generated an OpenGL error");
            require(RoxyLodEntityOcclusion.registerSamplers(key, builder), "same-program registration was not idempotent");
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "idempotent registration generated an OpenGL error");
            GL20C.glDeleteProgram(program);
            System.out.println("PASS: real Iris ProgramSamplers.Builder registered two LOD samplers at non-reserved units and remained idempotent");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static Method patch() throws Exception {
        Method method = RoxyLodEntityShader.class.getDeclaredMethod("patch", String.class);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private static void installCreateMarker() throws Exception {
        Field filesField = LoadingModList.class.getDeclaredField("fileById");
        filesField.setAccessible(true);
        Map<String, ModFileInfo> files = (Map<String, ModFileInfo>) filesField.get(LoadingModList.get());
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        files.put("create", (ModFileInfo) allocate.invoke(unsafe, ModFileInfo.class));
    }

    private static int program(String fragment) {
        int vertex = shader(GL20C.GL_VERTEX_SHADER,
                "#version 330 core\nvoid main(){ vec2 p=gl_VertexID==0?vec2(-1):gl_VertexID==1?vec2(3,-1):vec2(-1,3); gl_Position=vec4(p,0,1); }\n");
        int pixel = shader(GL20C.GL_FRAGMENT_SHADER, fragment);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, pixel);
        GL20C.glLinkProgram(program);
        require(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(pixel);
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        require(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0, GL20C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    public static final class Key {
        private final int id;
        Key(int id) { this.id = id; }
        public int handle() { return id; }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
