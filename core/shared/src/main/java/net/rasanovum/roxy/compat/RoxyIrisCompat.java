package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.rasanovum.roxy.util.RoxyClassLoaders;

public final class RoxyIrisCompat {
    private static final String SAMPLER_HOLDER = "net.irisshaders.iris.gl.sampler.SamplerHolder";
    private static final String TEXTURE_TYPE = "net.irisshaders.iris.gl.texture.TextureType";
    private static final String GL_SAMPLER = "net.irisshaders.iris.gl.sampler.GlSampler";
    private static final String VALUE_UPDATE_NOTIFIER = "net.irisshaders.iris.gl.state.ValueUpdateNotifier";

    private static volatile Object mippedNearest;
    private static volatile Object nearest;

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
                    return new SamplerInvoker(newer, Mode.SUPPLIER);
                } catch (NoSuchMethodException ignored) {
                }
                try {
                    Class<?> samplerType = Class.forName(GL_SAMPLER, false, loader);
                    Method older = holderType.getMethod(
                            "addDynamicSampler",
                            textureTypeType,
                            IntSupplier.class,
                            samplerType,
                            String[].class
                    );
                    return new SamplerInvoker(older, Mode.SAMPLER);
                } catch (NoSuchMethodException | ClassNotFoundException ignored) {
                }
                try {
                    Class<?> notifierType = Class.forName(VALUE_UPDATE_NOTIFIER, false, loader);
                    Method withNotifier = holderType.getMethod(
                            "addDynamicSampler",
                            textureTypeType,
                            IntSupplier.class,
                            notifierType,
                            String[].class
                    );
                    return new SamplerInvoker(withNotifier, Mode.NOTIFIER);
                } catch (NoSuchMethodException | ClassNotFoundException ignored) {
                }
                Method minimal = holderType.getMethod(
                        "addDynamicSampler",
                        IntSupplier.class,
                        String[].class
                );
                return new SamplerInvoker(minimal, Mode.MINIMAL);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Roxy could not bridge Iris's dynamic sampler API", exception);
            }
        }
    };

    private RoxyIrisCompat() {
    }

    public static Object mippedNearest() {
        Object cached = mippedNearest;
        if (cached != null) return cached;
        synchronized (RoxyIrisCompat.class) {
            cached = mippedNearest;
            if (cached == null) {
                ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor(GL_SAMPLER);
                if (loader == null) loader = RoxyIrisCompat.class.getClassLoader();
                cached = resolveSampler(loader, true, "MIPPED_NEAREST_NEAREST", "MIPPED_NEAREST");
                mippedNearest = cached;
            }
            return cached;
        }
    }

    public static Object nearest(ClassLoader loader) {
        Object cached = nearest;
        if (cached != null) return cached;
        synchronized (RoxyIrisCompat.class) {
            cached = nearest;
            if (cached == null) {
                ClassLoader resolved = loader == null ? RoxyClassLoaders.tryFindLoaderFor(GL_SAMPLER) : loader;
                if (resolved == null) resolved = RoxyIrisCompat.class.getClassLoader();
                cached = resolveSampler(resolved, false, "NEAREST");
                nearest = cached;
            }
            return cached;
        }
    }

    private static Object resolveSampler(ClassLoader loader, boolean mipmapped, String... fieldNames) {
        try {
            Class<?> sampler = Class.forName(GL_SAMPLER, true, loader);
            for (String fieldName : fieldNames) {
                try {
                    Field field = sampler.getField(fieldName);
                    Object value = field.get(null);
                    if (value != null) return value;
                } catch (NoSuchFieldException ignored) {
                }
            }
            return sampler.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(false, mipmapped, false, false);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Roxy could not create the Iris sampler", exception);
        }
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
            Object[] args = switch (invoker.mode) {
                case SUPPLIER -> new Object[]{textureType, textureId, samplerFactory, names};
                case SAMPLER -> new Object[]{textureType, textureId, samplerFactory == null ? null : samplerFactory.get(), names};
                case NOTIFIER -> new Object[]{textureType, textureId, null, names};
                case MINIMAL -> new Object[]{textureId, names};
            };
            return Boolean.TRUE.equals(invoker.method.invoke(holder, args));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Roxy could not bridge Iris's dynamic sampler API", exception);
        }
    }

    private enum Mode {
        SUPPLIER,
        SAMPLER,
        NOTIFIER,
        MINIMAL
    }

    private record SamplerInvoker(Method method, Mode mode) {
    }
}
