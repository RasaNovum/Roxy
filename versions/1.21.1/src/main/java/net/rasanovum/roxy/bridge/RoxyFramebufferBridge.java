package net.rasanovum.roxy.bridge;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.util.ArrayDeque;

public final class RoxyFramebufferBridge {
    private static final ThreadLocal<ArrayDeque<FramebufferState>> SAVED_STATES = ThreadLocal.withInitial(ArrayDeque::new);
    private static int cachedMaxDrawBuffers;
    private static int adapterFramebuffer;

    private RoxyFramebufferBridge() {
    }

    public static long prepareVoxySource() {
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int maxDrawBuffers = cachedMaxDrawBuffers;
        if (maxDrawBuffers <= 0) {
            maxDrawBuffers = Math.max(1, GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS));
            cachedMaxDrawBuffers = maxDrawBuffers;
        }
        int[] drawBuffers = new int[maxDrawBuffers];
        for (int index = 0; index < maxDrawBuffers; index++) {
            drawBuffers[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
        }
        int[] viewport = new int[4];
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        AdapterBinding binding = drawFramebuffer != 0 ? bindAdapter(drawBuffers) : null;
        boolean adapterBound = binding != null;
        SAVED_STATES.get().push(new FramebufferState(
                drawFramebuffer,
                drawBuffers,
                viewport,
                scissorBox,
                scissorEnabled,
                adapterBound
        ));
        if (adapterBound) {
            if (scissorEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            int width = Math.max(1, GL45.glGetTextureLevelParameteri(binding.depthTexture, binding.depthLevel, GL11.GL_TEXTURE_WIDTH));
            int height = Math.max(1, GL45.glGetTextureLevelParameteri(binding.depthTexture, binding.depthLevel, GL11.GL_TEXTURE_HEIGHT));
            GL11.glViewport(0, 0, width, height);
        }
        return (Integer.toUnsignedLong(readFramebuffer) << 32) | Integer.toUnsignedLong(drawFramebuffer);
    }

    private static AdapterBinding bindAdapter(int[] drawBuffers) {
        int colorAttachment = firstColorAttachment(drawBuffers);
        if (colorAttachment == GL11.GL_NONE) return null;

        int depthTexture = attachmentTexture(GL30.GL_DEPTH_ATTACHMENT);
        int colorTexture = attachmentTexture(colorAttachment);
        if (depthTexture == 0 || colorTexture == 0) return null;

        int depthLevel = attachmentLevel(GL30.GL_DEPTH_ATTACHMENT);
        int colorLevel = attachmentLevel(colorAttachment);
        int stencilTexture = attachmentTexture(GL30.GL_STENCIL_ATTACHMENT);
        int stencilLevel = stencilTexture != 0 ? attachmentLevel(GL30.GL_STENCIL_ATTACHMENT) : 0;

        if (adapterFramebuffer == 0 || !GL30.glIsFramebuffer(adapterFramebuffer)) {
            adapterFramebuffer = GL45.glCreateFramebuffers();
        }
        if (stencilTexture == depthTexture && stencilLevel == depthLevel) {
            GL45.glNamedFramebufferTexture(adapterFramebuffer, GL30.GL_DEPTH_STENCIL_ATTACHMENT, depthTexture, depthLevel);
        } else {
            GL45.glNamedFramebufferTexture(adapterFramebuffer, GL30.GL_STENCIL_ATTACHMENT, stencilTexture, stencilLevel);
            GL45.glNamedFramebufferTexture(adapterFramebuffer, GL30.GL_DEPTH_ATTACHMENT, depthTexture, depthLevel);
        }
        GL45.glNamedFramebufferTexture(adapterFramebuffer, GL30.GL_COLOR_ATTACHMENT0, colorTexture, colorLevel);
        GL45.glNamedFramebufferDrawBuffer(adapterFramebuffer, GL30.GL_COLOR_ATTACHMENT0);
        if (GL45.glCheckNamedFramebufferStatus(adapterFramebuffer, GL30.GL_DRAW_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) return null;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, adapterFramebuffer);
        GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        return new AdapterBinding(depthTexture, depthLevel);
    }

    private static int firstColorAttachment(int[] drawBuffers) {
        if (drawBuffers == null) return GL11.GL_NONE;
        for (int drawBuffer : drawBuffers) {
            if (drawBuffer >= GL30.GL_COLOR_ATTACHMENT0 && drawBuffer < GL30.GL_COLOR_ATTACHMENT0 + 32) {
                return drawBuffer;
            }
        }
        return GL11.GL_NONE;
    }

    private static int attachmentTexture(int attachment) {
        int type = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL11.GL_TEXTURE) return 0;
        return GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    private static int attachmentLevel(int attachment) {
        return GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
    }

    public static void useMainColorAttachment() {
        ArrayDeque<FramebufferState> stack = SAVED_STATES.get();
        FramebufferState top = stack.peek();
        if (top != null && top.adapterBound) return;
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != 0) {
            int colorAttachment = top == null ? GL11.GL_NONE : firstColorAttachment(top.drawBuffers);
            GL20.glDrawBuffers(colorAttachment != GL11.GL_NONE ? colorAttachment : GL30.GL_COLOR_ATTACHMENT0);
        }
    }

    public static void restoreFramebuffer(long framebuffers) {
        int drawFramebuffer = (int) framebuffers;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        ArrayDeque<FramebufferState> stack = SAVED_STATES.get();
        FramebufferState state = stack.poll();
        if (stack.isEmpty()) SAVED_STATES.remove();
        if (state != null && state.drawFramebuffer == drawFramebuffer && drawFramebuffer != 0) {
            GL20.glDrawBuffers(state.drawBuffers);
        }
        if (state != null) {
            GL11.glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
            GL11.glScissor(state.scissorBox[0], state.scissorBox[1], state.scissorBox[2], state.scissorBox[3]);
            if (state.scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, (int) (framebuffers >>> 32));
    }

    private record AdapterBinding(int depthTexture, int depthLevel) {
    }

    private record FramebufferState(
            int drawFramebuffer,
            int[] drawBuffers,
            int[] viewport,
            int[] scissorBox,
            boolean scissorEnabled,
            boolean adapterBound
    ) {
    }
}
