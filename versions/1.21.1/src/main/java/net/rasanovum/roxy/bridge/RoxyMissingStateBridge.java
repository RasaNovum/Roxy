package net.rasanovum.roxy.bridge;

import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;

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

            ClassLoader loader = RoxyClassLoaders.findLoaderFor(
                    "me.cortex.voxy.common.world.other.Mapper$StateEntry",
                    first.getClass().getClassLoader()
            );
            Class<?> stateType = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
            Object airBlock = Class.forName("net.minecraft.world.level.block.Blocks", false, loader).getField("AIR").get(null);
            Object air = airBlock.getClass().getMethod("defaultBlockState").invoke(airBlock);
            var constructor = Class.forName("me.cortex.voxy.common.world.other.Mapper$StateEntry", false, loader)
                    .getConstructor(int.class, stateType);
            Method interfaceRight = resolvePairRight(first.getClass());
            for (Object entry : missing) {
                if (entry == null) continue;
                Method right = interfaceRight != null && interfaceRight.getDeclaringClass().isInstance(entry)
                        ? interfaceRight
                        : RoxyReflection.findMethod(entry.getClass(), "right");
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

    private static Method resolvePairRight(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                Method method = RoxyReflection.tryFindMethod(iface, "right");
                if (method != null) return method;
            }
        }
        return RoxyReflection.tryFindMethod(type, "right");
    }
}
