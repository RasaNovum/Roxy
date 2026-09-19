package net.rasanovum.roxy.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyDynamicTreesBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final AtomicBoolean LOGGED = new AtomicBoolean();
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> stateClass) {
            try {
                ClassLoader loader = stateClass.getClassLoader();
                Class<?> branchBlock = Class.forName(
                        "com.dtteam.dynamictrees.block.branch.BranchBlock",
                        false,
                        loader
                );
                Class<?> block = Class.forName("net.minecraft.world.level.block.Block", false, loader);
                return new Access(
                        branchBlock,
                        stateClass.getMethod("getBlock"),
                        branchBlock.getMethod("getPrimitiveLog"),
                        block.getMethod("defaultBlockState")
                );
            } catch (ClassNotFoundException exception) {
                return Access.UNAVAILABLE;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                warnOnce(exception);
                return Access.UNAVAILABLE;
            }
        }
    };

    private RoxyDynamicTreesBridge() {
    }

    public static Object usePrimitiveLog(Object state) {
        if (state == null) return null;

        Access access = ACCESS.get(state.getClass());
        if (access == Access.UNAVAILABLE) return state;

        try {
            Object block = access.getBlock.invoke(state);
            if (!access.branchBlock.isInstance(block)) return state;

            Optional<?> primitiveLog = (Optional<?>) access.getPrimitiveLog.invoke(block);
            if (primitiveLog.isEmpty()) return state;

            Object lodState = access.defaultBlockState.invoke(primitiveLog.get());
            if (LOGGED.compareAndSet(false, true)) {
                LOGGER.info("Dynamic Trees branches will use their primitive logs in Voxy LODs");
            }
            return lodState;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warnOnce(exception);
            return state;
        }
    }

    private static void warnOnce(Throwable exception) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Unable to apply Dynamic Trees Voxy model compatibility", exception);
        }
    }

    private record Access(
            Class<?> branchBlock,
            Method getBlock,
            Method getPrimitiveLog,
            Method defaultBlockState
    ) {
        private static final Access UNAVAILABLE = new Access(null, null, null, null);
    }
}
