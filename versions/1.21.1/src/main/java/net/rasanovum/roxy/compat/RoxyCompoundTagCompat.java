package net.rasanovum.roxy.compat;

import java.lang.reflect.Method;
import java.util.Optional;

public final class RoxyCompoundTagCompat {
    private RoxyCompoundTagCompat() {
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
        try {
            if (!contains(compoundTag, key, 10)) return Optional.empty();
            Method getCompound = findMethod(compoundTag.getClass(), "getCompound", 1, CompoundTagReturn.IGNORE);
            return Optional.ofNullable(getCompound.invoke(compoundTag, key));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getCompound", exception);
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
