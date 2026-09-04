package net.rasanovum.roxy.compat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class RoxyVoxyImportCompat {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String PALETTED_CONTAINER_PROVIDER =
            "net.minecraft.world.level.chunk.PalettedContainerRO";

    private RoxyVoxyImportCompat() {
    }

    public static Object createDefaultBiomeProvider(Object defaultBiome) {
        try {
            ClassLoader loader = findMinecraftLoader();
            Class<?> provider = Class.forName(PALETTED_CONTAINER_PROVIDER, false, loader);
            InvocationHandler handler = (proxy, method, arguments) -> invoke(
                    method,
                    proxy,
                    arguments,
                    defaultBiome
            );
            return Proxy.newProxyInstance(loader, new Class<?>[]{provider}, handler);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw new IllegalStateException("Unable to create Voxy's default biome provider", exception);
        }
    }

    private static Object invoke(Method method, Object proxy, Object[] arguments, Object defaultBiome) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "RoxyDefaultBiomeProvider";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
                default -> null;
            };
        }
        if (method.getName().equals("get")) return defaultBiome;
        return defaultValue(method.getReturnType());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static ClassLoader findMinecraftLoader() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                RoxyVoxyImportCompat.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName(MINECRAFT, false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }
}
