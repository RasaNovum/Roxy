package net.rasanovum.roxy.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public final class RoxyRenderTypeBridge {
    private static final ClassValue<Accessors> ACCESSORS = new ClassValue<>() {
        @Override
        protected Accessors computeValue(Class<?> stateClass) {
            return Accessors.resolve(stateClass);
        }
    };

    private RoxyRenderTypeBridge() {
    }

    public static Object getChunkRenderType(Object state, Object fallback) {
        if (state == null) return fallback;
        try {
            Accessors access = ACCESSORS.get(state.getClass());
            Object minecraft = access.minecraftInstance.invokeExact();
            if (minecraft == null) return fallback;
            Object blockRenderer = access.getBlockRenderer.invokeExact(minecraft);
            if (blockRenderer == null) return fallback;
            Object model = access.getBlockModel.invokeExact(blockRenderer, state);
            if (model == null) return fallback;
            Object random = access.createRandom.invokeExact(42L);
            Object types = access.getRenderTypes.invokeExact(model, state, random, access.emptyModelData);
            if (types == null) return fallback;
            for (Object renderType : access.orderedRenderTypes) {
                if ((boolean) access.containsRenderType.invokeExact(types, renderType)) {
                    return renderType;
                }
            }
            return fallback;
        } catch (Error error) {
            if (error instanceof LinkageError) return fallback;
            throw error;
        } catch (Throwable exception) {
            return fallback;
        }
    }

    private record Accessors(
            MethodHandle minecraftInstance,
            MethodHandle getBlockRenderer,
            MethodHandle getBlockModel,
            MethodHandle createRandom,
            Object emptyModelData,
            MethodHandle getRenderTypes,
            MethodHandle containsRenderType,
            Object[] orderedRenderTypes
    ) {
        private static Accessors resolve(Class<?> stateClass) {
            try {
                ClassLoader loader = stateClass.getClassLoader();
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, loader);
                Method getInstance = minecraftClass.getMethod("getInstance");
                Method getBlockRenderer = minecraftClass.getMethod("getBlockRenderer");
                Class<?> blockRendererClass = getBlockRenderer.getReturnType();
                Method getBlockModel = findMethod(blockRendererClass, "getBlockModel", stateClass);
                Class<?> randomSourceClass = Class.forName("net.minecraft.util.RandomSource", false, loader);
                Method createRandom = randomSourceClass.getMethod("create", long.class);
                Class<?> modelDataClass = Class.forName(
                        "net.neoforged.neoforge.client.model.data.ModelData",
                        false,
                        loader
                );
                Object emptyModelData = modelDataClass.getField("EMPTY").get(null);
                Class<?> bakedModelClass = Class.forName(
                        "net.minecraft.client.resources.model.BakedModel",
                        false,
                        loader
                );
                Method getRenderTypes = findMethod(bakedModelClass, "getRenderTypes", 3);
                Class<?> renderTypeClass = Class.forName("net.minecraft.client.renderer.RenderType", false, loader);
                Class<?> renderTypeSetClass = Class.forName(
                        "net.neoforged.neoforge.client.ChunkRenderTypeSet",
                        false,
                        loader
                );
                Method contains = renderTypeSetClass.getMethod("contains", renderTypeClass);
                Object[] orderedRenderTypes = new Object[]{
                        renderTypeClass.getMethod("translucent").invoke(null),
                        renderTypeClass.getMethod("cutoutMipped").invoke(null),
                        renderTypeClass.getMethod("cutout").invoke(null),
                        renderTypeClass.getMethod("tripwire").invoke(null),
                        renderTypeClass.getMethod("solid").invoke(null)
                };
                return new Accessors(
                        unreflect(lookup, getInstance, MethodType.methodType(Object.class)),
                        unreflect(lookup, getBlockRenderer, MethodType.methodType(Object.class, Object.class)),
                        unreflect(lookup, getBlockModel, MethodType.methodType(Object.class, Object.class, Object.class)),
                        unreflect(lookup, createRandom, MethodType.methodType(Object.class, long.class)),
                        emptyModelData,
                        unreflect(lookup, getRenderTypes, MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class)),
                        unreflect(lookup, contains, MethodType.methodType(boolean.class, Object.class, Object.class)),
                        orderedRenderTypes
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to resolve chunk render type accessors", exception);
            }
        }

        private static MethodHandle unreflect(MethodHandles.Lookup lookup, Method method, MethodType target)
                throws IllegalAccessException {
            method.trySetAccessible();
            return lookup.unreflect(method).asType(target);
        }

        private static Method findMethod(Class<?> owner, String name, Class<?> argumentType) throws NoSuchMethodException {
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(argumentType)) {
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
}
