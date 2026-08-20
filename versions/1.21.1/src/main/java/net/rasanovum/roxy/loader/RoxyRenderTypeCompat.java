package net.rasanovum.roxy.loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyRenderTypeCompat {
    private RoxyRenderTypeCompat() {
    }

    public static Object getChunkRenderType(Object state, Object fallback) {
        try {
            ClassLoader loader = state.getClass().getClassLoader();
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            Object blockRenderer = minecraftClass.getMethod("getBlockRenderer").invoke(minecraft);
            Object model = findMethod(blockRenderer.getClass(), "getBlockModel", state).invoke(blockRenderer, state);

            Class<?> randomSourceClass = Class.forName("net.minecraft.util.RandomSource", false, loader);
            Object random = randomSourceClass.getMethod("create", long.class).invoke(null, 42L);
            Class<?> modelDataClass = Class.forName(
                    "net.neoforged.neoforge.client.model.data.ModelData",
                    false,
                    loader
            );
            Field emptyField = modelDataClass.getField("EMPTY");
            Object modelData = emptyField.get(null);
            Method getRenderTypes = findMethod(model.getClass(), "getRenderTypes", 3);
            Object types = getRenderTypes.invoke(model, state, random, modelData);

            Class<?> renderTypeClass = Class.forName("net.minecraft.client.renderer.RenderType", false, loader);
            Method contains = types.getClass().getMethod("contains", renderTypeClass);
            for (String methodName : new String[]{"translucent", "cutoutMipped", "cutout", "tripwire", "solid"}) {
                Object renderType = renderTypeClass.getMethod(methodName).invoke(null);
                if ((Boolean) contains.invoke(types, renderType)) {
                    return renderType;
                }
            }
            return fallback;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve NeoForge model render types", exception);
        }
    }

    private static Method findMethod(Class<?> owner, String name, Object argument) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(argument)) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Method findMethod(Class<?> owner, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
}
