package net.rasanovum.roxy.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Optional;

public final class RoxyCompoundTagBridge {
    private static final ClassValue<TagAccess> ACCESS = new ClassValue<>() {
        @Override
        protected TagAccess computeValue(Class<?> type) {
            return TagAccess.resolve(type);
        }
    };

    private RoxyCompoundTagBridge() {
    }

    public static int getInt(Object compoundTag, String key, int fallback) {
        if (compoundTag == null) return fallback;
        try {
            TagAccess access = ACCESS.get(compoundTag.getClass());
            if (!(boolean) access.contains.invokeExact(compoundTag, key, 99)) return fallback;
            return (int) access.getInt.invokeExact(compoundTag, key);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getInt", exception);
        }
    }

    public static Optional<?> getCompound(Object compoundTag, String key) {
        if (compoundTag == null) return Optional.empty();
        try {
            TagAccess access = ACCESS.get(compoundTag.getClass());
            if (!(boolean) access.contains.invokeExact(compoundTag, key, 10)) return Optional.empty();
            return Optional.ofNullable(access.getCompound.invokeExact(compoundTag, key));
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getCompound", exception);
        }
    }

    public static Optional<?> getList(Object compoundTag, String key) {
        if (compoundTag == null) return Optional.empty();
        try {
            TagAccess access = ACCESS.get(compoundTag.getClass());
            if (!(boolean) access.contains.invokeExact(compoundTag, key, 9)) return Optional.empty();
            return Optional.ofNullable(access.get.invokeExact(compoundTag, key));
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.get", exception);
        }
    }

    public static Optional<?> getByteArray(Object compoundTag, String key) {
        if (compoundTag == null) return Optional.empty();
        try {
            TagAccess access = ACCESS.get(compoundTag.getClass());
            if (!(boolean) access.contains.invokeExact(compoundTag, key, 7)) return Optional.empty();
            return Optional.ofNullable(access.getByteArray.invokeExact(compoundTag, key));
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getByteArray", exception);
        }
    }

    public static String getString(Object compoundTag, String key, String fallback) {
        if (compoundTag == null) return fallback;
        try {
            TagAccess access = ACCESS.get(compoundTag.getClass());
            if (!(boolean) access.contains.invokeExact(compoundTag, key, 8)) return fallback;
            return (String) access.getString.invokeExact(compoundTag, key);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to bridge CompoundTag.getString", exception);
        }
    }

    private record TagAccess(
            MethodHandle contains,
            MethodHandle getInt,
            MethodHandle getCompound,
            MethodHandle get,
            MethodHandle getByteArray,
            MethodHandle getString
    ) {
        private static TagAccess resolve(Class<?> type) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return new TagAccess(
                        unreflect(lookup, type.getMethod("contains", String.class, int.class),
                                MethodType.methodType(boolean.class, Object.class, String.class, int.class)),
                        unreflect(lookup, type.getMethod("getInt", String.class),
                                MethodType.methodType(int.class, Object.class, String.class)),
                        unreflect(lookup, type.getMethod("getCompound", String.class),
                                MethodType.methodType(Object.class, Object.class, String.class)),
                        unreflect(lookup, type.getMethod("get", String.class),
                                MethodType.methodType(Object.class, Object.class, String.class)),
                        unreflect(lookup, type.getMethod("getByteArray", String.class),
                                MethodType.methodType(Object.class, Object.class, String.class)),
                        unreflect(lookup, type.getMethod("getString", String.class),
                                MethodType.methodType(String.class, Object.class, String.class))
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("No compatible CompoundTag methods on " + type.getName(), exception);
            }
        }

        private static MethodHandle unreflect(MethodHandles.Lookup lookup, Method method, MethodType target)
                throws IllegalAccessException {
            method.trySetAccessible();
            return lookup.unreflect(method).asType(target);
        }
    }
}
