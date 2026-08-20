package net.rasanovum.roxy.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RoxyBlockStateCompat {
    private RoxyBlockStateCompat() {
    }

    public static int getLightBlock(Object state) {
        try {
            if (hasDynamicBlockEntityShape(state)) return 0;
            Method lightBlock = findLightBlockMethod(state.getClass());
            ClassLoader minecraftLoader = state.getClass().getClassLoader();
            Object emptyLevel = findEmptyLevel(minecraftLoader);
            Object zeroPosition = findZeroPosition(minecraftLoader);
            return (Integer) lightBlock.invoke(state, emptyLevel, zeroPosition);
        } catch (InvocationTargetException exception) {
            return 0;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge BlockState.getLightBlock", exception);
        }
    }

    private static boolean hasDynamicBlockEntityShape(Object state) throws ReflectiveOperationException {
        Method hasBlockEntity = state.getClass().getMethod("hasBlockEntity");
        if (!(Boolean) hasBlockEntity.invoke(state)) return false;
        Object block = state.getClass().getMethod("getBlock").invoke(state);
        return (Boolean) block.getClass().getMethod("hasDynamicShape").invoke(block);
    }

    private static Method findLightBlockMethod(Class<?> stateClass) {
        for (Method method : stateClass.getMethods()) {
            if (method.getName().equals("getLightBlock") && method.getParameterCount() == 2
                    && method.getReturnType() == int.class) {
                return method;
            }
        }
        throw new IllegalStateException("No two-argument getLightBlock method on " + stateClass.getName());
    }

    private static Object findZeroPosition(ClassLoader minecraftLoader) throws ReflectiveOperationException {
        Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos", false, minecraftLoader);
        return blockPos.getField("ZERO").get(null);
    }

    private static Object findEmptyLevel(ClassLoader minecraftLoader) throws ReflectiveOperationException {
        Class<?> emptyBlockGetter = Class.forName("net.minecraft.world.level.EmptyBlockGetter", false, minecraftLoader);
        return emptyBlockGetter.getField("INSTANCE").get(null);
    }
}
