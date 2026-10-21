package net.rasanovum.roxy.shader;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyAmdHiZShader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String SCREENSPACE_PATH = "/assets/voxy/shaders/lod/hierarchical/screenspace.glsl";
    private static final String SAMPLE = "float sp = texelFetch(hizDepthSampler, ivec2(x, y), ml).r;";
    private static final String PATCH_MARKER = "#define ROXY_AMD_HIZ_COMPAT";
    private static final String PATCH = SAMPLE + """

#ifndef USE_REVERSE_Z
            #define ROXY_AMD_HIZ_COMPAT
            if (sp <= 0.0001f) {
                sp = 1.0f;
            }
#endif""";
    private static final AtomicBoolean LOGGED = new AtomicBoolean();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private RoxyAmdHiZShader() {
    }

    public static String patch(String path, String source) {
        if (path == null || source == null || !path.equals(SCREENSPACE_PATH)) return source;

        GraphicsDriver driver = graphicsDriver();
        if (driver == null) return source;
        return patchForDriver(source, driver.vendor(), driver.renderer());
    }

    static String patchForDriver(String source, String vendor, String renderer) {
        GraphicsDriver driver = new GraphicsDriver(vendor == null ? "" : vendor, renderer == null ? "" : renderer);
        if (!driver.amd()) return source;

        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.contains(PATCH_MARKER)) return normalized;

        int first = normalized.indexOf(SAMPLE);
        if (first < 0 || normalized.indexOf(SAMPLE, first + SAMPLE.length()) >= 0) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("Unable to apply AMD HiZ compatibility because Voxy's screenspace shader layout was not recognized");
            }
            return source;
        }

        if (LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Applied AMD HiZ depth-sampling compatibility patch for {} / {}", driver.vendor(), driver.renderer());
        }
        return normalized.substring(0, first) + PATCH + normalized.substring(first + SAMPLE.length());
    }

    private static GraphicsDriver graphicsDriver() {
        try {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (vendor == null && renderer == null) return null;
            return new GraphicsDriver(vendor == null ? "" : vendor, renderer == null ? "" : renderer);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private record GraphicsDriver(String vendor, String renderer) {
        private boolean amd() {
            String vendorName = vendor.toLowerCase(Locale.ROOT);
            String rendererName = renderer.toLowerCase(Locale.ROOT);
            return vendorName.contains("advanced micro devices")
                    || vendorName.startsWith("amd")
                    || vendorName.contains("ati technologies")
                    || rendererName.contains("radeon")
                    || rendererName.startsWith("amd ");
        }
    }
}
