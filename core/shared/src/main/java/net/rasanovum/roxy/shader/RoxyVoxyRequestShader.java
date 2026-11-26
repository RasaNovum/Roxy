package net.rasanovum.roxy.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoxyVoxyRequestShader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String REQUEST_GATE = "if (!hasRequested(node)) {";
    private static final String PATCHED_GATE =
            "if (!hasRequested(node) || (((frameId + getId(node) * 2654435761u) & 255u) == 0u"
                    + " && requestQueueIndex.x < 32u)) {";
    private static volatile boolean retriesEnabled;

    private RoxyVoxyRequestShader() {
    }

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (!path.endsWith("/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp")) return source;
        if (!Boolean.parseBoolean(System.getProperty("roxy.voxyRequestRetries", "true"))) {
            retriesEnabled = false;
            return source;
        }
        if (source.contains(PATCHED_GATE)) {
            retriesEnabled = true;
            return source;
        }
        int index = source.indexOf(REQUEST_GATE);
        if (index < 0 || source.indexOf(REQUEST_GATE, index + REQUEST_GATE.length()) >= 0) {
            retriesEnabled = false;
            throw new IllegalStateException("Unsupported Voxy traversal request gate");
        }
        LOGGER.info("Voxy traversal request recovery enabled: staggered 256-traversal retries, 32-request soft limit");
        retriesEnabled = true;
        return source.replace(REQUEST_GATE, PATCHED_GATE);
    }

    public static boolean retriesEnabled() {
        return retriesEnabled;
    }
}
