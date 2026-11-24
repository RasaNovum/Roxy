package net.rasanovum.roxy.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RoxyReflection {
    private static final ClassValue<ConcurrentHashMap<String, Optional<Field>>> FIELDS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Optional<Field>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final ClassValue<ConcurrentHashMap<MethodSignature, Optional<Method>>> METHODS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<MethodSignature, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final ClassValue<ConcurrentHashMap<ArityKey, Optional<Method>>> ARITY_METHODS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<ArityKey, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private RoxyReflection() {
    }

    public static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Optional<Field> resolved = FIELDS.get(type).computeIfAbsent(name, key -> resolveField(type, key));
        return resolved.orElseThrow(() -> new NoSuchFieldException(type.getName() + "#" + name));
    }

    public static Field tryFindField(Class<?> type, String name) {
        return FIELDS.get(type).computeIfAbsent(name, key -> resolveField(type, key)).orElse(null);
    }

    public static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        MethodSignature signature = new MethodSignature(name, parameterTypes.clone());
        Optional<Method> resolved = METHODS.get(type).computeIfAbsent(signature, key -> resolveMethod(type, key.name, key.parameterTypes));
        return resolved.orElseThrow(() -> new NoSuchMethodException(type.getName() + "#" + name));
    }

    public static Method tryFindMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        MethodSignature signature = new MethodSignature(name, parameterTypes.clone());
        return METHODS.get(type).computeIfAbsent(signature, key -> resolveMethod(type, key.name, key.parameterTypes)).orElse(null);
    }

    public static Method findMethodByArity(Class<?> type, String name, int arity) throws NoSuchMethodException {
        ArityKey key = new ArityKey(name, arity);
        Optional<Method> resolved = ARITY_METHODS.get(type).computeIfAbsent(key, k -> resolveMethodByArity(type, k.name, k.arity));
        return resolved.orElseThrow(() -> new NoSuchMethodException(type.getName() + "#" + name + "/" + arity));
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

    private static Optional<Field> resolveField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.trySetAccessible();
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> resolveMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            method.trySetAccessible();
            return Optional.of(method);
        } catch (NoSuchMethodException ignored) {
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.trySetAccessible();
                return Optional.of(method);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> resolveMethodByArity(Class<?> type, String name, int arity) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) {
                method.trySetAccessible();
                return Optional.of(method);
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) {
                    method.trySetAccessible();
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    private record ArityKey(String name, int arity) {
    }

    private static final class MethodSignature {
        private final String name;
        private final Class<?>[] parameterTypes;
        private final int hash;

        private MethodSignature(String name, Class<?>[] parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes;
            this.hash = 31 * name.hashCode() + Arrays.hashCode(parameterTypes);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MethodSignature that)) return false;
            return name.equals(that.name) && Arrays.equals(parameterTypes, that.parameterTypes);
        }
    }
}
