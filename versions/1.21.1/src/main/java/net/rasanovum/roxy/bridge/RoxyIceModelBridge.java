package net.rasanovum.roxy.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyIceModelBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final int TRANSLUCENT_FLAG = 4;
    private static final int ICE_BACKFACE_FLAG = 0x10;
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> stateClass) {
            try {
                ClassLoader loader = stateClass.getClassLoader();
                Class<?> blocksClass = Class.forName(
                        "net.minecraft.world.level.block.Blocks",
                        false,
                        loader
                );
                return new Access(
                        stateClass.getMethod("getBlock"),
                        blocksClass.getField("ICE"),
                        blocksClass.getField("FROSTED_ICE")
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to resolve vanilla ice blocks", exception);
            }
        }
    };

    private RoxyIceModelBridge() {
    }

    public static int addIceBackfaceFlag(Object state, int flags) {
        if (state == null || (flags & TRANSLUCENT_FLAG) == 0) return flags;

        try {
            Access access = ACCESS.get(state.getClass());
            Object block = access.getBlock.invoke(state);
            if (block == access.ice.get(null) || block == access.frostedIce.get(null)) {
                return flags | ICE_BACKFACE_FLAG;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
        }
        return flags;
    }

    private static void warnOnce(Throwable exception) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Unable to detect vanilla ice blocks for Voxy translucent backface handling", exception);
        }
    }

    private record Access(Method getBlock, Field ice, Field frostedIce) {
    }
}
