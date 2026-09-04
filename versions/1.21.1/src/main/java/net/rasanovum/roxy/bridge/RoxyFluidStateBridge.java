package net.rasanovum.roxy.bridge;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

public final class RoxyFluidStateBridge {
    private static final String LIQUID_BLOCK = "net.minecraft.world.level.block.LiquidBlock";
    private static final ConcurrentHashMap<Class<?>, StateMethods> STATE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, FluidMethods> FLUID_METHODS = new ConcurrentHashMap<>();

    private RoxyFluidStateBridge() {
    }

    public static boolean isFluidBlockState(Object state) {
        if (state == null) return false;

        try {
            StateMethods stateMethods = STATE_METHODS.computeIfAbsent(
                    state.getClass(),
                    RoxyFluidStateBridge::resolveStateMethods
            );
            Object block = stateMethods.getBlock.invoke(state);
            if (isLiquidBlock(block)) return true;

            Object fluidState = stateMethods.getFluidState.invoke(state);
            FluidMethods fluidMethods = FLUID_METHODS.computeIfAbsent(
                    fluidState.getClass(),
                    RoxyFluidStateBridge::resolveFluidMethods
            );
            if ((Boolean) fluidMethods.isEmpty.invoke(fluidState)) return false;

            Object legacyState = fluidMethods.createLegacyBlock.invoke(fluidState);
            StateMethods legacyStateMethods = STATE_METHODS.computeIfAbsent(
                    legacyState.getClass(),
                    RoxyFluidStateBridge::resolveStateMethods
            );
            return legacyStateMethods.getBlock.invoke(legacyState) == block;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isLiquidBlock(Object block) {
        if (block == null) return false;
        for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(LIQUID_BLOCK)) return true;
        }
        return false;
    }

    private static StateMethods resolveStateMethods(Class<?> stateClass) {
        try {
            return new StateMethods(
                    stateClass.getMethod("getBlock"),
                    stateClass.getMethod("getFluidState")
            );
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Unable to bridge BlockState methods", exception);
        }
    }

    private static FluidMethods resolveFluidMethods(Class<?> fluidStateClass) {
        try {
            return new FluidMethods(
                    fluidStateClass.getMethod("isEmpty"),
                    fluidStateClass.getMethod("createLegacyBlock")
            );
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Unable to bridge FluidState methods", exception);
        }
    }

    private record StateMethods(Method getBlock, Method getFluidState) {
    }

    private record FluidMethods(Method isEmpty, Method createLegacyBlock) {
    }
}
