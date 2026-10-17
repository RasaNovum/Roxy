package net.rasanovum.roxy.blend;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;

public final class RoxyIrisDepthHandoff {
    private static final int LOD_TEXTURE_UNIT = 3;
    private static final int REAL_DEPTH_TEXTURE_UNIT = 4;
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
            uniform sampler2D roxyLodDepth;
            uniform sampler2D roxyRealDepth;
            uniform float roxyFadeStart;
            uniform mat4 roxyInvVanillaMvp;
            void main() {
                float lodDepth = texture(roxyLodDepth, roxyUv).r;
                float realDepth = texture(roxyRealDepth, roxyUv).r;
                if (lodDepth <= 0.0 || lodDepth >= 1.0 || realDepth <= 0.0 || realDepth >= 1.0) discard;

                vec4 realView = roxyInvVanillaMvp * vec4(roxyUv * 2.0 - 1.0, realDepth * 2.0 - 1.0, 1.0);
                if (abs(realView.w) < 1e-7) discard;
                vec3 realPosition = realView.xyz / realView.w;
                if (length(realPosition.xz) <= roxyFadeStart) discard;
                gl_FragDepth = 1.0;
            }
            """;

    private int program;
    private int vao;
    private int sampler;
    private int lodDepthLocation;
    private int realDepthLocation;
    private int fadeStartLocation;
    private int invVanillaMvpLocation;

    public void apply(int outputFramebuffer, int lodDepthTexture, int realDepthSnapshot,
                      int outputWidth, int outputHeight, float fadeStart,
                      float[] invVanillaMvp, boolean excludeLodsFromVanillaDepth) {
        // Voxy already performs the shaderpack-requested depth conversion in this mode.
        if (!excludeLodsFromVanillaDepth) return;
        if (outputFramebuffer == 0 || lodDepthTexture == 0 || realDepthSnapshot == 0
                || outputWidth < 1 || outputHeight < 1 || invVanillaMvp == null || invVanillaMvp.length != 16
        ) {
            return;
        }
        int depthAttachmentType = glGetNamedFramebufferAttachmentParameteri(outputFramebuffer,
                GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (depthAttachmentType != GL_TEXTURE && depthAttachmentType != GL_RENDERBUFFER) return;
        if (depthAttachmentType == GL_TEXTURE) {
            int depthAttachment = glGetNamedFramebufferAttachmentParameteri(outputFramebuffer,
                    GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (depthAttachment == 0 || depthAttachment == lodDepthTexture || depthAttachment == realDepthSnapshot) return;
        }

        ensureProgram();
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
        int oldLodTexture = glGetIntegeri(GL_TEXTURE_BINDING_2D, LOD_TEXTURE_UNIT);
        int oldRealTexture = glGetIntegeri(GL_TEXTURE_BINDING_2D, REAL_DEPTH_TEXTURE_UNIT);
        int oldLodSampler = glGetIntegeri(GL_SAMPLER_BINDING, LOD_TEXTURE_UNIT);
        int oldRealSampler = glGetIntegeri(GL_SAMPLER_BINDING, REAL_DEPTH_TEXTURE_UNIT);
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, outputFramebuffer);
            glViewport(0, 0, outputWidth, outputHeight);
            glColorMask(false, false, false, false);
            glEnable(GL_DEPTH_TEST);
            glDepthMask(true);
            glDepthFunc(GL_ALWAYS);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_RASTERIZER_DISCARD);
            glBindTextureUnit(LOD_TEXTURE_UNIT, lodDepthTexture);
            glBindTextureUnit(REAL_DEPTH_TEXTURE_UNIT, realDepthSnapshot);
            glBindSampler(LOD_TEXTURE_UNIT, sampler);
            glBindSampler(REAL_DEPTH_TEXTURE_UNIT, sampler);
            glUseProgram(program);
            glUniform1i(lodDepthLocation, LOD_TEXTURE_UNIT);
            glUniform1i(realDepthLocation, REAL_DEPTH_TEXTURE_UNIT);
            glUniform1f(fadeStartLocation, fadeStart);
            glUniformMatrix4fv(invVanillaMvpLocation, false, invVanillaMvp);
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
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
            glBindTextureUnit(LOD_TEXTURE_UNIT, oldLodTexture);
            glBindTextureUnit(REAL_DEPTH_TEXTURE_UNIT, oldRealTexture);
            glBindSampler(LOD_TEXTURE_UNIT, oldLodSampler);
            glBindSampler(REAL_DEPTH_TEXTURE_UNIT, oldRealSampler);
        }
    }

    public void free() {
        if (program != 0) glDeleteProgram(program);
        if (vao != 0) glDeleteVertexArrays(vao);
        if (sampler != 0) glDeleteSamplers(sampler);
        program = 0;
        vao = 0;
        sampler = 0;
        lodDepthLocation = realDepthLocation = fadeStartLocation = invVanillaMvpLocation = -1;
    }

    private void ensureProgram() {
        if (program != 0 && glIsProgram(program) && vao != 0 && glIsVertexArray(vao)
                && sampler != 0 && glIsSampler(sampler)) return;
        if (program != 0 && !glIsProgram(program)) program = 0;
        if (vao != 0 && !glIsVertexArray(vao)) vao = 0;
        if (sampler != 0 && !glIsSampler(sampler)) sampler = 0;
        if (program != 0) {
            if (vao == 0) vao = glCreateVertexArrays();
            if (sampler == 0) sampler = createSampler();
            return;
        }
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
                throw new IllegalStateException("Unable to link Iris depth handoff shader: " + glGetProgramInfoLog(candidate));
            }
            lodDepthLocation = glGetUniformLocation(candidate, "roxyLodDepth");
            realDepthLocation = glGetUniformLocation(candidate, "roxyRealDepth");
            fadeStartLocation = glGetUniformLocation(candidate, "roxyFadeStart");
            invVanillaMvpLocation = glGetUniformLocation(candidate, "roxyInvVanillaMvp");
            if (lodDepthLocation < 0 || realDepthLocation < 0 || fadeStartLocation < 0
                    || invVanillaMvpLocation < 0) {
                throw new IllegalStateException("Iris depth handoff shader is missing an active uniform");
            }
            program = candidate;
            candidate = 0;
            if (vao == 0 || !glIsVertexArray(vao)) vao = glCreateVertexArrays();
            if (sampler == 0 || !glIsSampler(sampler)) sampler = createSampler();
        } finally {
            glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            if (candidate != 0) glDeleteProgram(candidate);
        }
    }

    private int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Unable to compile Iris depth handoff shader: " + log);
        }
        return shader;
    }

    private int createSampler() {
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
