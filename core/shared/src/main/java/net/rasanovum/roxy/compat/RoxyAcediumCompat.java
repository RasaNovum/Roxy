package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.util.RoxyClassLoaders;

import java.lang.reflect.Method;

public final class RoxyAcediumCompat {
    private static final String SECONDARY_RENDER_CONTEXT = "me.cortex.nvidium.compat.SecondaryRenderContext";
    private static volatile boolean secondaryContextChecked;
    private static volatile Method secondaryContextIsActive;

    private RoxyAcediumCompat() {
    }

    public static boolean isSecondaryRenderContextActive(Object pipeline) {
        Method method = resolveSecondaryContextIsActive(pipeline);
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static Method resolveSecondaryContextIsActive(Object pipeline) {
        if (secondaryContextChecked) return secondaryContextIsActive;
        synchronized (RoxyAcediumCompat.class) {
            if (secondaryContextChecked) return secondaryContextIsActive;
            ClassLoader hint = pipeline != null ? pipeline.getClass().getClassLoader() : null;
            ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor(SECONDARY_RENDER_CONTEXT, hint);
            secondaryContextIsActive = lookupIsActive(loader);
            secondaryContextChecked = true;
            return secondaryContextIsActive;
        }
    }

    private static Method lookupIsActive(ClassLoader loader) {
        if (loader == null) return null;
        try {
            Class<?> type = Class.forName(SECONDARY_RENDER_CONTEXT, false, loader);
            Method method = type.getMethod("isActive");
            method.setAccessible(true);
            return method;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError | SecurityException ignored) {
            return null;
        }
    }
}
