package net.rasanovum.roxy.bridge;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public final class RoxyMissingStateBridge {
    private RoxyMissingStateBridge() {}

    public static void retainMissingIds(List<?> missing, List<Object> entries) {
        if (missing == null || entries == null || missing.isEmpty()) return;
        try {
            Object first = null;
            for (Object item : missing) {
                if (item != null) {
                    first = item;
                    break;
                }
            }
            if (first == null) return;

            ClassLoader loader = findVoxyLoader(first.getClass().getClassLoader());
            BlockState air = Blocks.AIR.defaultBlockState();
            var constructor = Class.forName("me.cortex.voxy.common.world.other.Mapper$StateEntry", false, loader)
                    .getConstructor(int.class, BlockState.class);
            Method interfaceRight = resolvePairRight(first.getClass());
            for (Object entry : missing) {
                if (entry == null) continue;
                Method right = interfaceRight != null && interfaceRight.getDeclaringClass().isInstance(entry)
                        ? interfaceRight
                        : resolvePairRight(entry.getClass());
                if (right == null) throw new NoSuchMethodException("right");
                entries.add(constructor.newInstance(((Number) right.invoke(entry)).intValue(), air));
            }
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Unable to retain missing Voxy state IDs", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to retain missing Voxy state IDs", exception);
        }
    }

    private static ClassLoader findVoxyLoader(ClassLoader hint) {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                BlockState.class.getClassLoader(),
                RoxyMissingStateBridge.class.getClassLoader(),
                hint
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("me.cortex.voxy.common.world.other.Mapper$StateEntry", false, candidate);
                return candidate;
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return Thread.currentThread().getContextClassLoader();
    }

    private static Method resolvePairRight(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                try {
                    Method method = iface.getMethod("right");
                    method.trySetAccessible();
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        try {
            Method method = type.getMethod("right");
            method.trySetAccessible();
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
