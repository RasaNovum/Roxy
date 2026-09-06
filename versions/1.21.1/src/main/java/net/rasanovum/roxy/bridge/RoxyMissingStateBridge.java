package net.rasanovum.roxy.bridge;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

public final class RoxyMissingStateBridge {
    private RoxyMissingStateBridge() {}

    public static void retainMissingIds(List<?> missing, List<Object> entries) {
        if (missing.isEmpty()) return;
        try {
            ClassLoader loader = missing.get(0).getClass().getClassLoader();
            Class<?> stateType = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
            Object airBlock = Class.forName("net.minecraft.world.level.block.Blocks", false, loader).getField("AIR").get(null);
            Object air = airBlock.getClass().getMethod("defaultBlockState").invoke(airBlock);
            var constructor = Class.forName("me.cortex.voxy.common.world.other.Mapper$StateEntry", false, loader)
                    .getConstructor(int.class, stateType);
            var id = missing.get(0).getClass().getMethod("right");
            for (Object entry : missing) entries.add(constructor.newInstance(((Number) id.invoke(entry)).intValue(), air));
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Unable to retain missing Voxy state IDs", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to retain missing Voxy state IDs", exception);
        }
    }
}
