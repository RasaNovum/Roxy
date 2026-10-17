package net.rasanovum.roxy.loader;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public final class RoxyMacOSGraphics {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static volatile StartupStatus startupStatus = new StartupStatus(
            StartupState.NOT_CHECKED, System.getProperty("os.name", ""),
            System.getProperty("os.arch", ""), "", "");

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

        boolean supported = caps.glDispatchComputeIndirect != 0
                && caps.glMultiDrawElementsIndirectCountARB != 0
                && caps.glCreateBuffers != 0 && caps.glNamedBufferStorage != 0
                && caps.glCreateTextures != 0 && caps.glTextureView != 0;
        startupStatus = new StartupStatus(
                supported ? StartupState.CHECKING_VOXY : StartupState.UNSUPPORTED_GRAPHICS,
                os, System.getProperty("os.arch", ""), renderer, version);
        if (!supported) {
            LOGGER.warn("Voxy rendering disabled: this macOS OpenGL context lacks compute, "
                    + "GPU-counted indexed draws, or modern buffer/texture operations. "
                    + "Set up the macOS compatibility bundle using Roxy-macOS-compat/setup-guide.md. "
                    + "Minecraft and Sodium can continue using this context.");
        }
        return supported;
    }

    public static void onVoxyInitialized() {
        StartupStatus previous = startupStatus;
        if (previous.state() != StartupState.CHECKING_VOXY) return;
        StartupState result;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = RoxyMacOSGraphics.class.getClassLoader();
            Class<?> common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false,
                    loader);
            boolean available = Boolean.TRUE.equals(common.getMethod("isAvailable").invoke(null));
            result = available ? StartupState.AVAILABLE : StartupState.VOXY_UNAVAILABLE;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            result = StartupState.UNVERIFIED;
            LOGGER.warn("Could not read Voxy's completed macOS startup status", exception);
        }
        startupStatus = new StartupStatus(result, previous.osName(), previous.architecture(),
                previous.renderer(), previous.openGlVersion());
        LOGGER.info("macOS Voxy startup status: {}", result);
    }

    public static StartupStatus startupStatus() {
        return startupStatus;
    }

    public enum StartupState {
        NOT_CHECKED, CHECKING_VOXY, UNSUPPORTED_GRAPHICS, VOXY_UNAVAILABLE, UNVERIFIED, AVAILABLE
    }

    public record StartupStatus(StartupState state, String osName, String architecture,
                                String renderer, String openGlVersion) {
        public boolean nativeArm64() {
            return architecture.equalsIgnoreCase("aarch64") || architecture.equalsIgnoreCase("arm64");
        }

        public boolean appleSilicon() {
            String os = osName.toLowerCase(Locale.ROOT);
            if (!os.contains("mac") && !os.contains("darwin")) return false;
            // Apple's renderer identifies the physical GPU even under Rosetta.
            return nativeArm64() || (renderer != null
                    && renderer.toLowerCase(Locale.ROOT).matches(".*apple m[0-9]+.*"));
        }

        public String warningId() {
            if (!appleSilicon() || state == StartupState.NOT_CHECKED || state == StartupState.CHECKING_VOXY) {
                return null;
            }
            if (!nativeArm64()) return "macos-java-architecture";
            return switch (state) {
                case UNSUPPORTED_GRAPHICS -> "macos-unsupported-graphics";
                case VOXY_UNAVAILABLE -> "macos-voxy-unavailable";
                case UNVERIFIED -> "macos-startup-unverified";
                default -> null;
            };
        }
    }
}
