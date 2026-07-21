package net.rasanovum.roxy.loader;

import java.lang.reflect.Method;


public final class RoxyTextureAtlasCompat {
    private RoxyTextureAtlasCompat() {
    }

    public static int getMaxSupportedTextureSize(Object textureAtlas) {
        try {
            Method accessor = findAccessor(textureAtlas.getClass());
            return (Integer) accessor.invoke(textureAtlas);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to bridge TextureAtlas.maxSupportedTextureSize", exception);
        }
    }

    private static Method findAccessor(Class<?> textureAtlasClass) {
        for (Method method : textureAtlasClass.getMethods()) {
            if (method.getName().equals("maxSupportedTextureSize")
                    && method.getParameterCount() == 0
                    && method.getReturnType() == int.class) {
                return method;
            }
        }
        throw new IllegalStateException("No maxSupportedTextureSize accessor on " + textureAtlasClass.getName());
    }
}
