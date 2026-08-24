package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collection;

public final class RoxyVoxyWorldgenCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static volatile boolean queueLookupFailed;

    private RoxyVoxyWorldgenCompat() {
    }

    public static void clearQueuedPayloads() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = RoxyVoxyWorldgenCompat.class.getClassLoader();
            Class<?> handler;
            try {
                handler = Class.forName(
                        "com.ethan.voxyworldgenv2.network.NetworkClientHandler",
                        false,
                        loader
                );
            } catch (ClassNotFoundException ignored) {
                return;
            }
            Field queueField = handler.getDeclaredField("INGEST_QUEUE");
            Field lockField = handler.getDeclaredField("QUEUE_LOCK");
            queueField.setAccessible(true);
            lockField.setAccessible(true);
            Object lock = lockField.get(null);
            int cleared;
            synchronized (lock) {
                Object queue = queueField.get(null);
                cleared = queue instanceof Collection<?> collection ? collection.size() : 0;
                if (queue instanceof Collection<?> collection) collection.clear();
            }
            if (cleared > 0) {
                LOGGER.info("Cleared {} queued Voxy World Gen payloads after Minecraft client world change", cleared);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!queueLookupFailed) {
                queueLookupFailed = true;
                LOGGER.warn("Unable to clear queued Voxy World Gen payloads after Minecraft client world change", exception);
            }
        }
    }
}
