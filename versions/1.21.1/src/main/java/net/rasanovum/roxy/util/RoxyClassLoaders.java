package net.rasanovum.roxy.util;

public final class RoxyClassLoaders {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static volatile ClassLoader cachedGameLoader;

    private RoxyClassLoaders() {
    }

    public static ClassLoader findGameLoader(ClassLoader... hints) {
        ClassLoader cached = cachedGameLoader;
        if (cached != null) return cached;
        ClassLoader resolved = findLoaderFor(MINECRAFT, hints);
        cachedGameLoader = resolved;
        return resolved;
    }

    public static ClassLoader findLoaderFor(String className, ClassLoader... hints) {
        if (hints != null) {
            for (ClassLoader hint : hints) {
                if (canLoad(hint, className)) return hint;
            }
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (canLoad(context, className)) return context;
        ClassLoader own = RoxyClassLoaders.class.getClassLoader();
        if (canLoad(own, className)) return own;
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (canLoad(system, className)) return system;
        throw new IllegalStateException("Unable to locate class loader for " + className);
    }

    public static ClassLoader tryFindLoaderFor(String className, ClassLoader... hints) {
        if (hints != null) {
            for (ClassLoader hint : hints) {
                if (canLoad(hint, className)) return hint;
            }
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (canLoad(context, className)) return context;
        ClassLoader own = RoxyClassLoaders.class.getClassLoader();
        if (canLoad(own, className)) return own;
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (canLoad(system, className)) return system;
        return null;
    }

    public static boolean canLoad(ClassLoader loader, String className) {
        if (loader == null || className == null) return false;
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
