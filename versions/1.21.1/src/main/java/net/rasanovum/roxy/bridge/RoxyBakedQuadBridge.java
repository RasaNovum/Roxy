package net.rasanovum.roxy.bridge;

import net.rasanovum.roxy.util.RoxyReflection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyBakedQuadBridge {
    private static final ClassValue<ConsumerAccess> CONSUMERS = new ClassValue<>() {
        @Override
        protected ConsumerAccess computeValue(Class<?> type) {
            return ConsumerAccess.resolve(type);
        }
    };
    private static final ClassValue<QuadAccess> QUADS = new ClassValue<>() {
        @Override
        protected QuadAccess computeValue(Class<?> type) {
            return QuadAccess.resolve(type);
        }
    };
    private static final ClassValue<MethodHandle> SPRITE_CONTENTS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                Method method = RoxyReflection.findMethod(type, "contents");
                return MethodHandles.lookup().unreflect(method)
                        .asType(MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };
    private static final ClassValue<MethodHandle> ANIMATED_TEXTURE = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                Field field = RoxyReflection.findField(type, "animatedTexture");
                return MethodHandles.lookup().unreflectGetter(field)
                        .asType(MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };

    private RoxyBakedQuadBridge() {
    }

    public static Object writeQuad(Object consumer, Object quad, int metadata) {
        try {
            ConsumerAccess consumerAccess = CONSUMERS.get(consumer.getClass());
            QuadAccess quadAccess = QUADS.get(quad.getClass());

            if ((boolean) quadAccess.isShade.invokeExact(quad)) {
                consumerAccess.setAnyShaded.invokeExact(consumer, true);
            }

            if (hasAnimatedTexture(quad, quadAccess)) {
                consumerAccess.setAnyDarkendTex.invokeExact(consumer, true);
            }

            consumerAccess.ensureCanPut.invokeExact(consumer);
            int[] vertices = (int[]) quadAccess.getVertices.invokeExact(quad);
            int stride = vertices.length / 4;
            if (stride < 6 || stride * 4 != vertices.length) {
                throw new IllegalStateException("Unexpected BakedQuad vertex stride: " + vertices.length);
            }

            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * stride;
                consumerAccess.addVertex.invokeExact(
                        consumer,
                        Float.intBitsToFloat(vertices[offset]),
                        Float.intBitsToFloat(vertices[offset + 1]),
                        Float.intBitsToFloat(vertices[offset + 2])
                );
                consumerAccess.setUv.invokeExact(
                        consumer,
                        Float.intBitsToFloat(vertices[offset + 4]),
                        Float.intBitsToFloat(vertices[offset + 5])
                );
                consumerAccess.meta.invokeExact(consumer, metadata);
            }
            return consumer;
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to write a 1.21.1 BakedQuad", exception);
        }
    }

    private static boolean hasAnimatedTexture(Object quad, QuadAccess quadAccess) throws Throwable {
        Object sprite = quadAccess.getSprite.invokeExact(quad);
        if (sprite == null) return false;
        Object contents = SPRITE_CONTENTS.get(sprite.getClass()).invokeExact(sprite);
        if (contents == null) return false;
        return ANIMATED_TEXTURE.get(contents.getClass()).invokeExact(contents) != null;
    }

    private record ConsumerAccess(
            MethodHandle setAnyShaded,
            MethodHandle setAnyDarkendTex,
            MethodHandle ensureCanPut,
            MethodHandle addVertex,
            MethodHandle setUv,
            MethodHandle meta
    ) {
        private static ConsumerAccess resolve(Class<?> type) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return new ConsumerAccess(
                        lookup.unreflectSetter(RoxyReflection.findField(type, "anyShaded"))
                                .asType(MethodType.methodType(void.class, Object.class, boolean.class)),
                        lookup.unreflectSetter(RoxyReflection.findField(type, "anyDarkendTex"))
                                .asType(MethodType.methodType(void.class, Object.class, boolean.class)),
                        lookup.unreflect(RoxyReflection.findMethod(type, "ensureCanPut"))
                                .asType(MethodType.methodType(void.class, Object.class)),
                        lookup.unreflect(RoxyReflection.findMethod(type, "addVertex", float.class, float.class, float.class))
                                .asType(MethodType.methodType(void.class, Object.class, float.class, float.class, float.class)),
                        lookup.unreflect(RoxyReflection.findMethod(type, "setUv", float.class, float.class))
                                .asType(MethodType.methodType(void.class, Object.class, float.class, float.class)),
                        lookup.unreflect(RoxyReflection.findMethod(type, "meta", int.class))
                                .asType(MethodType.methodType(void.class, Object.class, int.class))
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private record QuadAccess(
            MethodHandle isShade,
            MethodHandle getVertices,
            MethodHandle getSprite
    ) {
        private static QuadAccess resolve(Class<?> type) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return new QuadAccess(
                        lookup.unreflect(RoxyReflection.findMethod(type, "isShade"))
                                .asType(MethodType.methodType(boolean.class, Object.class)),
                        lookup.unreflect(RoxyReflection.findMethod(type, "getVertices"))
                                .asType(MethodType.methodType(int[].class, Object.class)),
                        lookup.unreflect(RoxyReflection.findMethod(type, "getSprite"))
                                .asType(MethodType.methodType(Object.class, Object.class))
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
