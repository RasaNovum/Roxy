package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoxyVoxyRequestShader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String REQUEST_GATE = "if (!hasRequested(node)) {";
    private static volatile boolean retriesEnabled;

    private RoxyVoxyRequestShader() {
    }

    public static String patch(String path, String source) {
        if (!path.endsWith("/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp")) return source;
        retriesEnabled = false;
        if (!Boolean.parseBoolean(System.getProperty("roxy.voxyRequestRetries", "true"))) return source;
        try {
            Class<?> iris = Class.forName("me.cortex.voxy.client.core.util.IrisUtil", false,
                    Thread.currentThread().getContextClassLoader());
            if (!Boolean.TRUE.equals(iris.getMethod("irisShaderPackEnabled").invoke(null))) return source;
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("Unable to enable Voxy shader request recovery", exception);
            return source;
        }
        if (source.indexOf(REQUEST_GATE) < 0
                || source.indexOf(REQUEST_GATE) != source.lastIndexOf(REQUEST_GATE)) {
            throw new IllegalStateException("Unsupported Voxy traversal request gate");
        }
        LOGGER.info("Voxy shader request recovery enabled: staggered 256-traversal retries, 32-request soft limit");
        retriesEnabled = true;
        return source.replace(REQUEST_GATE,
                "if (!hasRequested(node) || (((frameId + getId(node) * 2654435761u) & 255u) == 0u"
                        + " && requestQueueIndex.x < 32u)) {");
    }

    public static boolean retriesEnabled() {
        return retriesEnabled;
    }
}
