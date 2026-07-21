package net.rasanovum.roxy.loader;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RoxyBakedQuadCompat {
    private RoxyBakedQuadCompat() {
    }

    public static Object writeQuad(Object consumer, Object quad, int metadata) {
        try {
            Class<?> consumerType = consumer.getClass();
            Class<?> quadType = quad.getClass();

            boolean shaded = (Boolean) findMethod(quadType, "isShade").invoke(quad);
            if (shaded) {
                Field anyShaded = findField(consumerType, "anyShaded");
                anyShaded.setBoolean(consumer, true);
            }

            if (hasAnimatedTexture(quad, quadType)) {
                Field anyDarkenedTexture = findField(consumerType, "anyDarkendTex");
                anyDarkenedTexture.setBoolean(consumer, true);
            }

            findDeclaredMethod(consumerType, "ensureCanPut").invoke(consumer);
            int[] vertices = (int[]) findMethod(quadType, "getVertices").invoke(quad);
            int stride = vertices.length / 4;
            if (stride < 6 || stride * 4 != vertices.length) {
                throw new IllegalStateException("Unexpected BakedQuad vertex stride: " + vertices.length);
            }

            Method addVertex = findMethod(consumerType, "addVertex", float.class, float.class, float.class);
            Method setUv = findMethod(consumerType, "setUv", float.class, float.class);
            Method meta = findMethod(consumerType, "meta", int.class);
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * stride;
                addVertex.invoke(
                        consumer,
                        Float.intBitsToFloat(vertices[offset]),
                        Float.intBitsToFloat(vertices[offset + 1]),
                        Float.intBitsToFloat(vertices[offset + 2])
                );
                setUv.invoke(
                        consumer,
                        Float.intBitsToFloat(vertices[offset + 4]),
                        Float.intBitsToFloat(vertices[offset + 5])
                );
                meta.invoke(consumer, metadata);
            }
            return consumer;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unable to write a 1.21.1 BakedQuad", cause);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unable to write a 1.21.1 BakedQuad", exception);
        }
    }

    private static boolean hasAnimatedTexture(Object quad, Class<?> quadType)
            throws ReflectiveOperationException {
        Object sprite = findMethod(quadType, "getSprite").invoke(quad);
        if (sprite == null) return false;
        Object contents = findMethod(sprite.getClass(), "contents").invoke(sprite);
        if (contents == null) return false;
        Field animatedTexture = findField(contents.getClass(), "animatedTexture");
        return animatedTexture.get(contents) != null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        method.trySetAccessible();
        return method;
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.trySetAccessible();
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the Voxy class hierarchy.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.trySetAccessible();
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through the Voxy/Minecraft class hierarchy.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
