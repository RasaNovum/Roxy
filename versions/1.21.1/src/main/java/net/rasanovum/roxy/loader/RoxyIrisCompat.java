package net.rasanovum.roxy.loader;

import java.lang.reflect.Method;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class RoxyIrisCompat {
    private static final String SAMPLER_HOLDER = "net.irisshaders.iris.gl.sampler.SamplerHolder";
    private static final String TEXTURE_TYPE = "net.irisshaders.iris.gl.texture.TextureType";
    private static final String GL_SAMPLER = "net.irisshaders.iris.gl.sampler.GlSampler";

    private RoxyIrisCompat() {
    }

    public static Supplier<?> samplerSupplier(Object sampler) {
        return () -> sampler;
    }

    public static boolean addDynamicSampler(
            Object holder,
            Object textureType,
            IntSupplier textureId,
            Supplier<?> samplerFactory,
            String[] names
    ) {
        ClassLoader loader = holder.getClass().getClassLoader();
        try {
            Class<?> holderType = Class.forName(SAMPLER_HOLDER, false, loader);
            Class<?> textureTypeType = Class.forName(TEXTURE_TYPE, false, loader);
            try {
                Method newer = holderType.getMethod(
                        "addDynamicSampler",
                        textureTypeType,
                        IntSupplier.class,
                        Supplier.class,
                        String[].class
                );
                return (Boolean) newer.invoke(holder, new Object[]{textureType, textureId, samplerFactory, names});
            } catch (NoSuchMethodException ignored) {
                Class<?> samplerType = Class.forName(GL_SAMPLER, false, loader);
                Method older = holderType.getMethod(
                        "addDynamicSampler",
                        textureTypeType,
                        IntSupplier.class,
                        samplerType,
                        String[].class
                );
                return (Boolean) older.invoke(
                        holder,
                        new Object[]{textureType, textureId, samplerFactory.get(), names}
                );
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Roxy could not bridge Iris's dynamic sampler API", exception);
        }
    }
}
