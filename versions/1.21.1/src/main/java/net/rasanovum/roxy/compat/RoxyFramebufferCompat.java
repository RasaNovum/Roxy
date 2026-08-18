package net.rasanovum.roxy.compat;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

public final class RoxyFramebufferCompat {
    private static final int DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final ThreadLocal<FramebufferState> SAVED_STATE = new ThreadLocal<>();
    private static int adapterFramebuffer;

    private RoxyFramebufferCompat() {
    }

    public static long prepareVoxySource() {
        int drawFramebuffer = GL11.glGetInteger(DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(READ_FRAMEBUFFER_BINDING);
        int maxDrawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
        int[] drawBuffers = new int[maxDrawBuffers];
        for (int index = 0; index < maxDrawBuffers; index++) {
            drawBuffers[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
        }
        int[] viewport = new int[4];
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        SAVED_STATE.set(new FramebufferState(drawFramebuffer, drawBuffers, viewport, scissorBox,
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)));
        if (drawFramebuffer != 0 && bindAdapter(drawBuffers)) {
            GL11.glViewport(0, 0, textureWidth(), textureHeight());
        }
        return (Integer.toUnsignedLong(readFramebuffer) << 32) | Integer.toUnsignedLong(drawFramebuffer);
    }

    private static boolean bindAdapter(int[] drawBuffers) {
        int colorAttachment = GL11.GL_NONE;
        for (int drawBuffer : drawBuffers) {
            if (drawBuffer >= GL30.GL_COLOR_ATTACHMENT0 && drawBuffer < GL30.GL_COLOR_ATTACHMENT0 + 32) {
                colorAttachment = drawBuffer;
                break;
            }
        }
        if (colorAttachment == GL11.GL_NONE) return false;

        int depthTexture = attachmentTexture(GL30.GL_DEPTH_ATTACHMENT);
        int colorTexture = attachmentTexture(colorAttachment);
        if (depthTexture == 0 || colorTexture == 0) return false;

        if (adapterFramebuffer == 0) adapterFramebuffer = GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTexture(adapterFramebuffer, GL30.GL_DEPTH_ATTACHMENT, depthTexture, 0);
        GL45.glNamedFramebufferTexture(adapterFramebuffer, GL30.GL_COLOR_ATTACHMENT0, colorTexture, 0);
        GL45.glNamedFramebufferDrawBuffer(adapterFramebuffer, GL30.GL_COLOR_ATTACHMENT0);
        if (GL45.glCheckNamedFramebufferStatus(adapterFramebuffer, GL30.GL_DRAW_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) return false;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, adapterFramebuffer);
        GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        return true;
    }

    private static int attachmentTexture(int attachment) {
        int type = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL11.GL_TEXTURE) return 0;
        return GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    private static int textureWidth() {
        int texture = attachmentTexture(GL30.GL_DEPTH_ATTACHMENT);
        return Math.max(1, GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_WIDTH));
    }

    private static int textureHeight() {
        int texture = attachmentTexture(GL30.GL_DEPTH_ATTACHMENT);
        return Math.max(1, GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_HEIGHT));
    }

    public static void useMainColorAttachment() {
        if (GL11.glGetInteger(DRAW_FRAMEBUFFER_BINDING) != 0) {
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        }
    }

    public static void restoreFramebuffer(long framebuffers) {
        int drawFramebuffer = (int) framebuffers;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        FramebufferState state = SAVED_STATE.get();
        SAVED_STATE.remove();
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

    private record FramebufferState(
            int drawFramebuffer,
            int[] drawBuffers,
            int[] viewport,
            int[] scissorBox,
            boolean scissorEnabled
    ) {
    }
}
