package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.util.RoxyClassLoaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collection;

public final class RoxyVoxyWorldgenCompat {
    private static final String NETWORK_CLIENT_HANDLER = "com.ethan.voxyworldgenv2.network.NetworkClientHandler";
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static volatile QueueFields cachedFields;
    private static volatile boolean lookupAttempted;
    private static volatile boolean queueLookupFailed;

    private RoxyVoxyWorldgenCompat() {
    }

    public static void clearQueuedPayloads() {
        QueueFields fields = resolveFields();
        if (fields == null) return;
        try {
            Object lock = fields.lockField.get(null);
            if (lock == null) return;
            int cleared;
            synchronized (lock) {
                Object queue = fields.queueField.get(null);
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

    private static QueueFields resolveFields() {
        QueueFields current = cachedFields;
        if (current != null || lookupAttempted) return current;
        synchronized (RoxyVoxyWorldgenCompat.class) {
            current = cachedFields;
            if (current != null || lookupAttempted) return current;
            lookupAttempted = true;
            try {
                ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor(NETWORK_CLIENT_HANDLER);
                if (loader == null) return null;
                Class<?> handler = Class.forName(NETWORK_CLIENT_HANDLER, false, loader);
                Field queueField = handler.getDeclaredField("INGEST_QUEUE");
                Field lockField = handler.getDeclaredField("QUEUE_LOCK");
                queueField.setAccessible(true);
                lockField.setAccessible(true);
                current = new QueueFields(queueField, lockField);
                cachedFields = current;
                return current;
            } catch (ClassNotFoundException ignored) {
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (!queueLookupFailed) {
                    queueLookupFailed = true;
                    LOGGER.warn("Unable to resolve Voxy World Gen queue fields", exception);
                }
                return null;
            }
        }
    }

    private record QueueFields(Field queueField, Field lockField) {
    }
}
