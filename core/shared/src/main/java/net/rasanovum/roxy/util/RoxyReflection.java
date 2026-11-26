package net.rasanovum.roxy.util;

import net.rasanovum.roxy.core.RoxyPlatform;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class RoxyReflection {
    private RoxyReflection() {
    }

    public static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Field resolved = tryFindField(type, name);
        if (resolved != null) return resolved;
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    public static Field tryFindField(Class<?> type, String name) {
        Field direct = tryFindFieldRaw(type, name);
        if (direct != null) return direct;
        String mapped = RoxyPlatform.resolveRuntimeFieldName(type, name);
        if (mapped != null && !mapped.equals(name)) {
            return tryFindFieldRaw(type, mapped);
        }
        return null;
    }

    private static Field tryFindFieldRaw(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                makeAccessible(field);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method resolved = tryFindMethod(type, name, parameterTypes);
        if (resolved != null) return resolved;
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    public static Method tryFindMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Method direct = tryFindMethodRaw(type, name, parameterTypes);
        if (direct != null) return direct;
        String mapped = RoxyPlatform.resolveRuntimeMethodName(type, name, parameterTypes);
        if (mapped != null && !mapped.equals(name)) {
            return tryFindMethodRaw(type, mapped, parameterTypes);
        }
        return null;
    }

    private static Method tryFindMethodRaw(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                makeAccessible(method);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            Method method = type.getMethod(name, parameterTypes);
            makeAccessible(method);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    public static Method findMethodByArity(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        return findFirstMethod(type, name, parameterCount);
    }

    public static Method findFirstMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Method direct = tryFindFirstMethodRaw(type, name, parameterCount);
        if (direct != null) return direct;
        for (String candidate : RoxyPlatform.resolveRuntimeMethodNamesByArity(type, name, parameterCount)) {
            if (candidate.equals(name)) continue;
            Method resolved = tryFindFirstMethodRaw(type, candidate, parameterCount);
            if (resolved != null) return resolved;
        }
        String mapped = RoxyPlatform.resolveRuntimeMethodNameAnyParams(type, name);
        if (mapped != null && !mapped.equals(name)) {
            Method resolved = tryFindFirstMethodRaw(type, mapped, parameterCount);
            if (resolved != null) return resolved;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "(" + parameterCount + ")");
    }

    private static Method tryFindFirstMethodRaw(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    makeAccessible(method);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    private static void makeAccessible(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (RuntimeException ignored) {
        }
    }

    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    public static void rethrowIfFatal(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof Error error) throw error;
    }

    public static RuntimeException rethrowCause(InvocationTargetException exception, String message) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(message, cause == null ? exception : cause);
    }

    public static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            Throwable next = current.getCause();
            if (next == null) break;
            current = next;
        }
        return current;
    }
}
