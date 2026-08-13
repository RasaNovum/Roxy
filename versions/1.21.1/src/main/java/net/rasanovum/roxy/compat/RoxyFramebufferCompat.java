package net.rasanovum.roxy.compat;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class RoxyFramebufferCompat {
    private static final int DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int READ_FRAMEBUFFER_BINDING = 0x8CAA;

    private RoxyFramebufferCompat() {
    }

    public static long prepareVoxySource() {
        int drawFramebuffer = GL11.glGetInteger(DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(READ_FRAMEBUFFER_BINDING);
        return (Integer.toUnsignedLong(readFramebuffer) << 32) | Integer.toUnsignedLong(drawFramebuffer);
    }

    public static void useMainColorAttachment() {
        if (GL11.glGetInteger(DRAW_FRAMEBUFFER_BINDING) != 0) {
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        }
    }

    public static void restoreFramebuffer(long framebuffers) {
        int drawFramebuffer = (int) framebuffers;
        if (drawFramebuffer != 0) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, (int) (framebuffers >>> 32));
    }
}
