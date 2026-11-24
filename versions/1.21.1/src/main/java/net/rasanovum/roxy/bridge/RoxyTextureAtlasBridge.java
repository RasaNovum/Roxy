package net.rasanovum.roxy.bridge;

import java.lang.reflect.Method;

public final class RoxyTextureAtlasBridge {
    private static final ClassValue<Method> ACCESSOR = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> textureAtlasClass) {
            for (Method method : textureAtlasClass.getMethods()) {
                if (method.getName().equals("maxSupportedTextureSize")
                        && method.getParameterCount() == 0
                        && method.getReturnType() == int.class) {
                    return method;
                }
            }
            throw new IllegalStateException("No maxSupportedTextureSize accessor on " + textureAtlasClass.getName());
        }
    };

    private RoxyTextureAtlasBridge() {
    }

    public static int getMaxSupportedTextureSize(Object textureAtlas) {
        if (textureAtlas == null) {
            throw new IllegalArgumentException("TextureAtlas is null");
        }
        try {
            return (Integer) ACCESSOR.get(textureAtlas.getClass()).invoke(textureAtlas);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge TextureAtlas.maxSupportedTextureSize", exception);
        }
    }
}
