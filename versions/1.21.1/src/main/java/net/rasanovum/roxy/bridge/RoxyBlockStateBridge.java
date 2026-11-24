package net.rasanovum.roxy.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public final class RoxyBlockStateBridge {
    private static final ClassValue<StateAccess> STATES = new ClassValue<>() {
        @Override
        protected StateAccess computeValue(Class<?> type) {
            return StateAccess.resolve(type);
        }
    };
    private static final ClassValue<MethodHandle> DYNAMIC_SHAPE = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("hasDynamicShape");
                method.trySetAccessible();
                return MethodHandles.lookup().unreflect(method)
                        .asType(MethodType.methodType(boolean.class, Object.class));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };

    private RoxyBlockStateBridge() {
    }

    public static int getLightBlock(Object state) {
        if (state == null) return 0;
        StateAccess access = STATES.get(state.getClass());
        try {
            if ((boolean) access.hasBlockEntity.invokeExact(state)) {
                Object block = access.getBlock.invokeExact(state);
                if (block != null && (boolean) DYNAMIC_SHAPE.get(block.getClass()).invokeExact(block)) {
                    return 0;
                }
            }
            return (int) access.getLightBlock.invokeExact(state, access.emptyLevel, access.zeroPosition);
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private record StateAccess(
            MethodHandle hasBlockEntity,
            MethodHandle getBlock,
            MethodHandle getLightBlock,
            Object emptyLevel,
            Object zeroPosition
    ) {
        private static StateAccess resolve(Class<?> stateClass) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Method hasBlockEntity = stateClass.getMethod("hasBlockEntity");
                Method getBlock = stateClass.getMethod("getBlock");
                Method lightBlock = findLightBlockMethod(stateClass);
                hasBlockEntity.trySetAccessible();
                getBlock.trySetAccessible();
                lightBlock.trySetAccessible();
                ClassLoader loader = stateClass.getClassLoader();
                Object emptyLevel = Class.forName("net.minecraft.world.level.EmptyBlockGetter", false, loader)
                        .getField("INSTANCE").get(null);
                Object zeroPosition = Class.forName("net.minecraft.core.BlockPos", false, loader)
                        .getField("ZERO").get(null);
                return new StateAccess(
                        lookup.unreflect(hasBlockEntity).asType(MethodType.methodType(boolean.class, Object.class)),
                        lookup.unreflect(getBlock).asType(MethodType.methodType(Object.class, Object.class)),
                        lookup.unreflect(lightBlock).asType(MethodType.methodType(int.class, Object.class, Object.class, Object.class)),
                        emptyLevel,
                        zeroPosition
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to bridge BlockState.getLightBlock", exception);
            }
        }

        private static Method findLightBlockMethod(Class<?> stateClass) {
            for (Method method : stateClass.getMethods()) {
                if (method.getName().equals("getLightBlock")
                        && method.getParameterCount() == 2
                        && method.getReturnType() == int.class) {
                    return method;
                }
            }
            throw new IllegalStateException("No two-argument getLightBlock method on " + stateClass.getName());
        }
    }
}
