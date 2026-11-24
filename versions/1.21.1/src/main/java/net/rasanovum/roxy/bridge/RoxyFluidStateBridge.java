package net.rasanovum.roxy.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyFluidStateBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String LIQUID_BLOCK = "net.minecraft.world.level.block.LiquidBlock";
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static final ClassValue<StateMethods> STATE_METHODS = new ClassValue<>() {
        @Override
        protected StateMethods computeValue(Class<?> stateClass) {
            try {
                Class<?> liquidBlockClass = null;
                try {
                    liquidBlockClass = Class.forName(LIQUID_BLOCK, false, stateClass.getClassLoader());
                } catch (ClassNotFoundException ignored) {
                }
                return new StateMethods(
                        stateClass.getMethod("getBlock"),
                        stateClass.getMethod("getFluidState"),
                        liquidBlockClass
                );
            } catch (NoSuchMethodException exception) {
                warnOnce(exception);
                return StateMethods.UNAVAILABLE;
            }
        }
    };
    private static final ClassValue<FluidMethods> FLUID_METHODS = new ClassValue<>() {
        @Override
        protected FluidMethods computeValue(Class<?> fluidStateClass) {
            try {
                return new FluidMethods(
                        fluidStateClass.getMethod("isEmpty"),
                        fluidStateClass.getMethod("createLegacyBlock")
                );
            } catch (NoSuchMethodException exception) {
                warnOnce(exception);
                return FluidMethods.UNAVAILABLE;
            }
        }
    };

    private RoxyFluidStateBridge() {
    }

    public static boolean isFluidBlockState(Object state) {
        if (state == null) return false;

        try {
            StateMethods stateMethods = STATE_METHODS.get(state.getClass());
            if (stateMethods == StateMethods.UNAVAILABLE) return false;
            Object block = stateMethods.getBlock.invoke(state);
            if (block == null) return false;
            if (stateMethods.liquidBlockClass != null) {
                if (stateMethods.liquidBlockClass.isInstance(block)) return true;
            } else if (isLiquidBlock(block)) {
                return true;
            }

            Object fluidState = stateMethods.getFluidState.invoke(state);
            if (fluidState == null) return false;
            FluidMethods fluidMethods = FLUID_METHODS.get(fluidState.getClass());
            if (fluidMethods == FluidMethods.UNAVAILABLE) return false;
            if (Boolean.TRUE.equals(fluidMethods.isEmpty.invoke(fluidState))) return false;

            Object legacyState = fluidMethods.createLegacyBlock.invoke(fluidState);
            if (legacyState == null) return false;
            StateMethods legacyStateMethods = STATE_METHODS.get(legacyState.getClass());
            if (legacyStateMethods == StateMethods.UNAVAILABLE) return false;
            return legacyStateMethods.getBlock.invoke(legacyState) == block;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return false;
        }
    }

    private static boolean isLiquidBlock(Object block) {
        for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(LIQUID_BLOCK)) return true;
        }
        return false;
    }

    private static void warnOnce(Throwable exception) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Unable to inspect fluid block state for Voxy", exception);
        }
    }

    private record StateMethods(Method getBlock, Method getFluidState, Class<?> liquidBlockClass) {
        private static final StateMethods UNAVAILABLE = new StateMethods(null, null, null);
    }

    private record FluidMethods(Method isEmpty, Method createLegacyBlock) {
        private static final FluidMethods UNAVAILABLE = new FluidMethods(null, null);
    }
}
