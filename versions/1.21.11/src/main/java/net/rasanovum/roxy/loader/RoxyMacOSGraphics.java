package net.rasanovum.roxy.loader;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Called on the render thread before Voxy initializes its GL-only services. */
public final class RoxyMacOSGraphics {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");

    private RoxyMacOSGraphics() {
    }

    public static boolean canInitializeVoxy() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac") && !os.contains("darwin")) return true;

        GLCapabilities caps = GL.getCapabilities();
        String renderer = GL11.glGetString(GL11.GL_RENDERER);
        String version = GL11.glGetString(GL11.GL_VERSION);
        LOGGER.info("macOS graphics: renderer={}, OpenGL={}, JVM architecture={}",
                renderer, version, System.getProperty("os.arch"));

        // Voxy's capability constructor itself runs compute and DSA workloads. On
        // Apple's GL 4.1 it crashes before Voxy can report its unsupported status.
        boolean supported = caps.glDispatchComputeIndirect != 0
                && caps.glMultiDrawElementsIndirectCountARB != 0
                && caps.glCreateBuffers != 0 && caps.glNamedBufferStorage != 0
                && caps.glCreateTextures != 0 && caps.glTextureView != 0;
        if (!supported) {
            LOGGER.warn("Voxy rendering disabled: this macOS OpenGL context lacks compute, "
                    + "GPU-counted indexed draws, or modern buffer/texture operations. "
                    + "Prepare the experimental Zink runtime described in docs/macos.md. "
                    + "Minecraft and Sodium can continue using this context.");
        }
        // Passing this preliminary guard still runs all original Voxy checks,
        // including its executable depth-sampler test. No capabilities are forged.
        return supported;
    }
}
