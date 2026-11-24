package net.rasanovum.roxy.compat;

import java.lang.reflect.Method;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class RoxyIrisCompat {
    private static final String SAMPLER_HOLDER = "net.irisshaders.iris.gl.sampler.SamplerHolder";
    private static final String TEXTURE_TYPE = "net.irisshaders.iris.gl.texture.TextureType";
    private static final String GL_SAMPLER = "net.irisshaders.iris.gl.sampler.GlSampler";

    private static final ClassValue<SamplerInvoker> INVOKERS = new ClassValue<>() {
        @Override
        protected SamplerInvoker computeValue(Class<?> holderClass) {
            ClassLoader loader = holderClass.getClassLoader();
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
                    return new SamplerInvoker(newer, false);
                } catch (NoSuchMethodException ignored) {
                    Class<?> samplerType = Class.forName(GL_SAMPLER, false, loader);
                    Method older = holderType.getMethod(
                            "addDynamicSampler",
                            textureTypeType,
                            IntSupplier.class,
                            samplerType,
                            String[].class
                    );
                    return new SamplerInvoker(older, true);
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Roxy could not bridge Iris's dynamic sampler API", exception);
            }
        }
    };

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
        if (holder == null) return false;
        try {
            SamplerInvoker invoker = INVOKERS.get(holder.getClass());
            Object samplerArg = invoker.unwrapSupplier
                    ? (samplerFactory == null ? null : samplerFactory.get())
                    : samplerFactory;
            return Boolean.TRUE.equals(invoker.method.invoke(
                    holder,
                    new Object[]{textureType, textureId, samplerArg, names}
            ));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Roxy could not bridge Iris's dynamic sampler API", exception);
        }
    }

    private record SamplerInvoker(Method method, boolean unwrapSupplier) {
    }
}
