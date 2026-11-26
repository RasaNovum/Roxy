package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.core.RoxyPlatform;
import net.rasanovum.roxy.util.RoxyClassLoaders;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class RoxyVoxyImportCompat {
    private static final String PALETTED_CONTAINER_PROVIDER =
            "net.minecraft.world.level.chunk.PalettedContainerRO";
    private static volatile ProviderAccess cachedAccess;

    private RoxyVoxyImportCompat() {
    }

    public static Object createDefaultBiomeProvider(Object defaultBiome) {
        try {
            ProviderAccess access = resolveAccess(defaultBiome == null ? null : defaultBiome.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, arguments) -> invoke(
                    method,
                    proxy,
                    arguments,
                    defaultBiome,
                    access.getByCoords
            );
            return Proxy.newProxyInstance(access.provider.getClassLoader(), new Class<?>[]{access.provider}, handler);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw new IllegalStateException("Unable to create Voxy's default biome provider", exception);
        }
    }

    private static ProviderAccess resolveAccess(ClassLoader hint) throws ClassNotFoundException {
        ProviderAccess current = cachedAccess;
        if (current != null) return current;
        synchronized (RoxyVoxyImportCompat.class) {
            current = cachedAccess;
            if (current != null) return current;
            ClassLoader loader = RoxyClassLoaders.findLoaderFor(PALETTED_CONTAINER_PROVIDER, hint);
            Class<?> provider = RoxyClassLoaders.loadMinecraftClass(PALETTED_CONTAINER_PROVIDER, loader);
            String getByCoords = RoxyPlatform.resolveRuntimeMethodName(provider, "get", int.class, int.class, int.class);
            current = new ProviderAccess(provider, getByCoords);
            cachedAccess = current;
            return current;
        }
    }

    private static Object invoke(
            Method method,
            Object proxy,
            Object[] arguments,
            Object defaultBiome,
            String getByCoords
    ) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "RoxyDefaultBiomeProvider";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
                default -> null;
            };
        }
        String name = method.getName();
        if (name.equals("get") || name.equals(getByCoords)) return defaultBiome;
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

    private record ProviderAccess(Class<?> provider, String getByCoords) {
    }
}
