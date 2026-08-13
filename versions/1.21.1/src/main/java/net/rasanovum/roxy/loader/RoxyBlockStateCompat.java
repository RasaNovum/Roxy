package net.rasanovum.roxy.loader;

import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

public final class RoxyBlockStateCompat {
    private RoxyBlockStateCompat() {
    }

    public static int getLightBlock(BlockState state) {

        try {
            ClassLoader minecraftLoader = state.getClass().getClassLoader();
            Method lightBlock = findLightBlockMethod(state.getClass());
            Object zeroPosition = findZeroPosition(minecraftLoader);
            Object emptyLevel = findEmptyLevel(minecraftLoader);
            return (Integer) lightBlock.invoke(state, new Object[]{emptyLevel, zeroPosition});
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge BlockState.getLightBlock", exception);
        }
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

    // EmptyBlockGetter answers "air, no block entity" for any position: blocks whose shape is
    // driven by a block entity fall back to their default shape instead of throwing.
    private static Object findEmptyLevel(ClassLoader minecraftLoader) throws ReflectiveOperationException {
        Class<?> emptyBlockGetter = Class.forName("net.minecraft.world.level.EmptyBlockGetter", false, minecraftLoader);
        return emptyBlockGetter.getField("INSTANCE").get(null);
    }
}
