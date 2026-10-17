package net.rasanovum.roxy.compat;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL45C.*;

public final class RoxyIrisNativeDepthMerge {
    private static final String VERTEX_SHADER = """
            #version 330 core
            out vec2 roxyUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                roxyUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec2 roxyUv;
            uniform sampler2D roxyNativeCurrentDepth;
            void main() {
                gl_FragDepth = texture(roxyNativeCurrentDepth, roxyUv).r;
            }
            """;

    private int framebuffer;
    private int attachedDepth;
    private int program;
    private int vao;
    private int sampler;
    private int depthLocation;

    public boolean merge(int destinationDepth, int nativeDepth, int width, int height) {
        if (destinationDepth == 0 || nativeDepth == 0 || destinationDepth == nativeDepth || width < 1 || height < 1) {
            return false;
        }
        ensureResources(destinationDepth);
        int unit = Math.max(0, glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS) - 1);
        int oldDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int oldReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int[] oldViewport = ints(4, GL_VIEWPORT);
        boolean[] oldColorMask = booleans(4, GL_COLOR_WRITEMASK);
        int oldDepthFunction = glGetInteger(GL_DEPTH_FUNC);
        boolean oldDepthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean oldDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean oldStencilTest = glIsEnabled(GL_STENCIL_TEST);
        boolean oldScissorTest = glIsEnabled(GL_SCISSOR_TEST);
        boolean oldCullFace = glIsEnabled(GL_CULL_FACE);
        boolean oldRasterizerDiscard = glIsEnabled(GL_RASTERIZER_DISCARD);
        int[] oldScissorBox = oldScissorTest ? ints(4, GL_SCISSOR_BOX) : null;
        int oldTexture = glGetIntegeri(GL_TEXTURE_BINDING_2D, unit);
        int oldSampler = glGetIntegeri(GL_SAMPLER_BINDING, unit);
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, width, height);
            glColorMask(false, false, false, false);
            glEnable(GL_DEPTH_TEST);
            glDepthMask(true);
            glDepthFunc(GL_LESS);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_RASTERIZER_DISCARD);
            glBindTextureUnit(unit, nativeDepth);
            glBindSampler(unit, sampler);
            glUseProgram(program);
            glUniform1i(depthLocation, unit);
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            return true;
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldDrawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, oldReadFramebuffer);
            glUseProgram(oldProgram);
            glBindVertexArray(oldVao);
            glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            glColorMask(oldColorMask[0], oldColorMask[1], oldColorMask[2], oldColorMask[3]);
            glDepthFunc(oldDepthFunction);
            glDepthMask(oldDepthWrite);
            setEnabled(GL_DEPTH_TEST, oldDepthTest);
            setEnabled(GL_STENCIL_TEST, oldStencilTest);
            setEnabled(GL_SCISSOR_TEST, oldScissorTest);
            if (oldScissorBox != null) glScissor(oldScissorBox[0], oldScissorBox[1], oldScissorBox[2], oldScissorBox[3]);
            setEnabled(GL_CULL_FACE, oldCullFace);
            setEnabled(GL_RASTERIZER_DISCARD, oldRasterizerDiscard);
            glBindTextureUnit(unit, oldTexture);
            glBindSampler(unit, oldSampler);
        }
    }

    public void free() {
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (program != 0) glDeleteProgram(program);
        if (vao != 0) glDeleteVertexArrays(vao);
        if (sampler != 0) glDeleteSamplers(sampler);
        framebuffer = attachedDepth = program = vao = sampler = 0;
        depthLocation = -1;
    }

    private void ensureResources(int targetDepth) {
        if (framebuffer != 0 && !glIsFramebuffer(framebuffer)) framebuffer = 0;
        if (program != 0 && !glIsProgram(program)) program = 0;
        if (vao != 0 && !glIsVertexArray(vao)) vao = 0;
        if (sampler != 0 && !glIsSampler(sampler)) sampler = 0;
        if (program == 0) createProgram();
        if (vao == 0) vao = glCreateVertexArrays();
        if (sampler == 0) sampler = createSampler();
        if (framebuffer == 0) framebuffer = glCreateFramebuffers();
        if (attachedDepth != targetDepth) {
            glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, targetDepth, 0);
            glNamedFramebufferDrawBuffer(framebuffer, GL_NONE);
            glNamedFramebufferReadBuffer(framebuffer, GL_NONE);
            int status = glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete Iris native-depth merge framebuffer: 0x" + Integer.toHexString(status));
            }
            attachedDepth = targetDepth;
        }
    }

    private void createProgram() {
        int vertex = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = 0;
        int candidate = 0;
        try {
            fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            candidate = glCreateProgram();
            glAttachShader(candidate, vertex);
            glAttachShader(candidate, fragment);
            glLinkProgram(candidate);
            if (glGetProgrami(candidate, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("Unable to link Iris native-depth merge shader: " + glGetProgramInfoLog(candidate));
            }
            depthLocation = glGetUniformLocation(candidate, "roxyNativeCurrentDepth");
            if (depthLocation < 0) throw new IllegalStateException("Native-depth merge shader has no active sampler");
            program = candidate;
            candidate = 0;
        } finally {
            glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            if (candidate != 0) glDeleteProgram(candidate);
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Unable to compile Iris native-depth merge shader: " + log);
        }
        return shader;
    }

    private static int createSampler() {
        int result = glCreateSamplers();
        glSamplerParameteri(result, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(result, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(result, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(result, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return result;
    }

    private static int[] ints(int count, int pname) {
        int[] values = new int[count];
        glGetIntegerv(pname, values);
        return values;
    }

    private static boolean[] booleans(int count, int pname) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count);
        glGetBooleanv(pname, buffer);
        boolean[] values = new boolean[count];
        for (int index = 0; index < count; index++) values[index] = buffer.get(index) != 0;
        return values;
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) glEnable(capability);
        else glDisable(capability);
    }
}
