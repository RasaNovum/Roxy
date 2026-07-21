package net.rasanovum.roxy.loader;

import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class RoxyFabricMetadataCompat {
    private RoxyFabricMetadataCompat() {
    }

    public static Object getCustomValue(Object metadata, String key) {
        if ("commit".equals(key)) {
            String commit = readCommit();
            return stringValue(metadata, commit);
        }
        return invokeCustomValue(metadata, key);
    }

    private static String readCommit() {
        ModList modList = ModList.get();
        String commit = modList == null ? "" : modList.getModContainerById("voxy")
                .map(container -> container.getModInfo().getModProperties().get("commit"))
                .map(Object::toString)
                .filter(candidate -> !candidate.isBlank())
                .orElse("roxycompat0000000000000000000000000000000");
        return commit.isBlank() ? "roxycompat0000000000000000000000000000000" : commit;
    }

    private static Object invokeCustomValue(Object metadata, String key) {
        if (metadata == null) return null;
        try {
            Method method = metadata.getClass().getMethod("getCustomValue", String.class);
            if (!method.trySetAccessible() && !method.canAccess(metadata)) return null;
            return method.invoke(metadata, key);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            return null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object stringValue(Object metadata, String value) {
        Class<?> customValueType = customValueType(metadata);
        if (customValueType == null || !customValueType.isInterface()) return null;

        ClassLoader loader = customValueType.getClassLoader();
        return Proxy.newProxyInstance(
                loader == null ? RoxyFabricMetadataCompat.class.getClassLoader() : loader,
                new Class<?>[]{customValueType},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAsString" -> value;
                    case "getType" -> enumValue(method.getReturnType(), "STRING");
                    case "toString" -> value;
                    case "hashCode" -> value.hashCode();
                    case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                    default -> throw new UnsupportedOperationException(
                            "Roxy's commit value does not support " + method.getName()
                    );
                }
        );
    }

    private static Class<?> customValueType(Object metadata) {
        if (metadata == null) return null;
        try {
            return metadata.getClass().getMethod("getCustomValue", String.class).getReturnType();
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
    }
}
