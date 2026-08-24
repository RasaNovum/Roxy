package net.rasanovum.roxy.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RoxyVoxyRequestPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final ConcurrentMap<Object, ConcurrentMap<Long, Boolean>> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong DEFERRED = new AtomicLong();
    private static final AtomicLong RETRIED = new AtomicLong();
    private static volatile boolean retryFailed;

    private RoxyVoxyRequestPatch() {
    }

    public static void reset() {
        PENDING.clear();
        DEFERRED.set(0L);
        RETRIED.set(0L);
    }

    public static void defer(Object nodeManager, long position) {
        PENDING.computeIfAbsent(nodeManager, ignored -> new ConcurrentHashMap<>()).put(position, Boolean.TRUE);
        long count = DEFERRED.incrementAndGet();
        if (count <= 8 || count % 100 == 0) {
            LOGGER.info("Deferred Voxy node request at {} while a request was already in flight ({} total)", position, count);
        }
    }

    public static void retry(Object nodeManager, long position) {
        ConcurrentMap<Long, Boolean> pending = PENDING.get(nodeManager);
        if (pending == null || pending.remove(position) == null) return;
        if (pending.isEmpty()) PENDING.remove(nodeManager, pending);
        try {
            Method processRequest = nodeManager.getClass().getMethod("processRequest", long.class);
            long count = RETRIED.incrementAndGet();
            if (count <= 8 || count % 100 == 0) {
                LOGGER.info("Retried deferred Voxy node request at {} ({} total)", position, count);
            }
            processRequest.invoke(nodeManager, position);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!retryFailed) {
                retryFailed = true;
                LOGGER.warn("Unable to retry a deferred Voxy node request", exception);
            }
        }
    }
}
