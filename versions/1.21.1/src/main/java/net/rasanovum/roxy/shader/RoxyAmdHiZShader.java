package net.rasanovum.roxy.shader;

import net.rasanovum.roxy.util.RoxyGpuInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        RoxyGpuInfo.Driver driver = RoxyGpuInfo.current();
        if (driver == null) return source;
        return patchForDriver(source, driver.vendor(), driver.renderer());
    }

    static String patchForDriver(String source, String vendor, String renderer) {
        if (!RoxyGpuInfo.isAmd(vendor, renderer)) return source;

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
            LOGGER.info("Applied AMD HiZ depth-sampling compatibility patch for {} / {}",
                    vendor == null ? "" : vendor,
                    renderer == null ? "" : renderer);
        }
        return normalized.substring(0, first) + PATCH + normalized.substring(first + SAMPLE.length());
    }
}
