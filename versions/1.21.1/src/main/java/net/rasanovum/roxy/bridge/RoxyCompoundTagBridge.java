package net.rasanovum.roxy.bridge;

import java.lang.reflect.Method;
import java.util.Optional;

public final class RoxyCompoundTagBridge {
    private RoxyCompoundTagBridge() {
    }

    public static int getInt(Object compoundTag, String key, int fallback) {
        try {
            if (!contains(compoundTag, key, 99)) return fallback;
            Method getInt = findGetIntMethod(compoundTag.getClass());
            return (Integer) getInt.invoke(compoundTag, key);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getInt", exception);
        }
    }

    public static Optional<?> getCompound(Object compoundTag, String key) {
        return getOptional(compoundTag, key, 10, "getCompound");
    }

    public static Optional<?> getList(Object compoundTag, String key) {
        return getOptional(compoundTag, key, 9, "get");
    }

    public static Optional<?> getByteArray(Object compoundTag, String key) {
        return getOptional(compoundTag, key, 7, "getByteArray");
    }

    private static Optional<?> getOptional(Object compoundTag, String key, int tagType, String getter) {
        try {
            if (!contains(compoundTag, key, tagType)) return Optional.empty();
            Method method = findMethod(compoundTag.getClass(), getter, 1, CompoundTagReturn.IGNORE);
            return Optional.ofNullable(method.invoke(compoundTag, key));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag." + getter, exception);
        }
    }

    public static String getString(Object compoundTag, String key, String fallback) {
        try {
            if (!contains(compoundTag, key, 8)) return fallback;
            Method getString = findMethod(compoundTag.getClass(), "getString", 1, CompoundTagReturn.IGNORE);
            return (String) getString.invoke(compoundTag, key);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getString", exception);
        }
    }

    private static boolean contains(Object compoundTag, String key, int tagType) throws ReflectiveOperationException {
        Method contains = findMethod(compoundTag.getClass(), "contains", 2, CompoundTagReturn.BOOLEAN);
        return (Boolean) contains.invoke(compoundTag, new Object[]{key, tagType});
    }

    private static Method findGetIntMethod(Class<?> compoundTagClass) {
        for (Method method : compoundTagClass.getMethods()) {
            if (method.getName().equals("getInt")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class
                    && method.getReturnType() == int.class) {
                return method;
            }
        }
        throw new IllegalStateException("No one-argument getInt method on " + compoundTagClass.getName());
    }

    private static Method findMethod(
            Class<?> compoundTagClass,
            String name,
            int parameterCount,
            CompoundTagReturn returnType
    ) {
        for (Method method : compoundTagClass.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && (returnType == CompoundTagReturn.IGNORE
                    || (returnType == CompoundTagReturn.BOOLEAN && method.getReturnType() == boolean.class))) {
                return method;
            }
        }
        throw new IllegalStateException("No compatible " + name + " method on " + compoundTagClass.getName());
    }

    private enum CompoundTagReturn {
        IGNORE,
        BOOLEAN
    }
}
