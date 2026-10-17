package net.rasanovum.roxy.blend;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL45C.*;

public final class RoxyIrisFullDepth {
    private final int[] textures = new int[2];
    private final int[] framebuffers = new int[2];
    private final ByteBuffer colorMask = ByteBuffer.allocateDirect(4);
    private final boolean[] ready = new boolean[2];
    private int width, height, format;
    private int swappedFramebuffer, selectedDepth, activePass = -1;
    private long frameGeneration = Long.MIN_VALUE;
    private int frameWidth, frameHeight;
    private boolean translucentAttempted;

    public void beginFrame(long generation, int outputWidth, int outputHeight) {
        end(false);
        ready[0] = ready[1] = false;
        frameGeneration = generation;
        frameWidth = outputWidth;
        frameHeight = outputHeight;
        translucentAttempted = false;
    }

    public void invalidate() {
        end(false);
        ready[0] = ready[1] = false;
        frameGeneration = Long.MIN_VALUE;
        frameWidth = frameHeight = 0;
        translucentAttempted = false;
    }

    public boolean readyForFrame(long generation, int outputWidth, int outputHeight) {
        return generation != Long.MIN_VALUE && frameGeneration == generation
                && frameWidth == outputWidth && frameHeight == outputHeight
                && ready[0] && (!translucentAttempted || ready[1]);
    }

    public int effectsTranslucent(long generation, int outputWidth, int outputHeight) {
        if (!readyForFrame(generation, outputWidth, outputHeight)) return 0;
        return translucentAttempted ? textures[1] : textures[0];
    }

    public void begin(int framebuffer, int selectedTexture, boolean translucent, int outputWidth, int outputHeight) {
        if (activePass != -1) throw new IllegalStateException("Nested Iris effect depth pass");
        if (frameGeneration == Long.MIN_VALUE || frameWidth != outputWidth || frameHeight != outputHeight) {
            throw new IllegalStateException("Iris effect depth pass does not match the current frame");
        }
        int sourceFormat = glGetTextureLevelParameteri(selectedTexture, 0, GL_TEXTURE_INTERNAL_FORMAT);
        if (sourceFormat != GL_DEPTH24_STENCIL8 && sourceFormat != GL_DEPTH32F_STENCIL8) {
            throw new IllegalStateException("Unsupported Iris effect depth format " + sourceFormat);
        }
        ensure(outputWidth, outputHeight, sourceFormat);
        int pass = translucent ? 1 : 0;
        if (!translucent) {
            ready[0] = ready[1] = false;
            translucentAttempted = false;
        } else {
            translucentAttempted = true;
        }
        if (translucent && !ready[0]) throw new IllegalStateException("Missing opaque effect depth");
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        glDepthMask(true);
        try {
            int source = translucent ? framebuffers[0] : framebuffer;
            int mask = translucent ? GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT : GL_STENCIL_BUFFER_BIT;
            glBlitNamedFramebuffer(source, framebuffers[pass], 0, 0, width, height,
                    0, 0, width, height, mask, GL_NEAREST);
            if (!translucent) glClearNamedFramebufferfv(framebuffers[pass], GL_DEPTH, 0, new float[]{1.0f});
        } finally {
            glDepthMask(depthWrite);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        swappedFramebuffer = framebuffer;
        selectedDepth = selectedTexture;
        activePass = pass;
        glNamedFramebufferTexture(framebuffer, GL_DEPTH_STENCIL_ATTACHMENT, textures[pass], 0);
        glColorMask(false, false, false, false);
    }

    public void end(boolean completed) {
        if (activePass == -1) return;
        glNamedFramebufferTexture(swappedFramebuffer, GL_DEPTH_STENCIL_ATTACHMENT, selectedDepth, 0);
        glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                colorMask.get(2) != 0, colorMask.get(3) != 0);
        ready[activePass] = completed;
        activePass = -1;
        swappedFramebuffer = selectedDepth = 0;
    }

    public boolean rendering() { return activePass != -1; }
    public int opaque() { return ready[0] ? textures[0] : 0; }
    public int translucent() { return ready[1] ? textures[1] : 0; }

    private void ensure(int newWidth, int newHeight, int newFormat) {
        if (width == newWidth && height == newHeight && format == newFormat && textures[0] != 0) return;
        long currentGeneration = frameGeneration;
        int currentFrameWidth = frameWidth, currentFrameHeight = frameHeight;
        free();
        frameGeneration = currentGeneration;
        frameWidth = currentFrameWidth;
        frameHeight = currentFrameHeight;
        if (newWidth < 1 || newHeight < 1) throw new IllegalArgumentException("Invalid effect depth dimensions");
        width = newWidth;
        height = newHeight;
        format = newFormat;
        for (int pass = 0; pass < 2; pass++) {
            textures[pass] = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(textures[pass], 1, format, width, height);
            glTextureParameteri(textures[pass], GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(textures[pass], GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameteri(textures[pass], GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(textures[pass], GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTextureParameteri(textures[pass], GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
            framebuffers[pass] = glCreateFramebuffers();
            glNamedFramebufferTexture(framebuffers[pass], GL_DEPTH_STENCIL_ATTACHMENT, textures[pass], 0);
            glNamedFramebufferDrawBuffer(framebuffers[pass], GL_NONE);
            glNamedFramebufferReadBuffer(framebuffers[pass], GL_NONE);
            if (glCheckNamedFramebufferStatus(framebuffers[pass], GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete Iris effect depth framebuffer");
            }
        }
    }

    public void free() {
        end(false);
        for (int pass = 0; pass < 2; pass++) {
            if (framebuffers[pass] != 0) glDeleteFramebuffers(framebuffers[pass]);
            if (textures[pass] != 0) glDeleteTextures(textures[pass]);
            framebuffers[pass] = textures[pass] = 0;
            ready[pass] = false;
        }
        width = height = format = 0;
        frameGeneration = Long.MIN_VALUE;
        frameWidth = frameHeight = 0;
        translucentAttempted = false;
    }
}
