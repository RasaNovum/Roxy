package net.rasanovum.roxy.util;

import net.rasanovum.roxy.core.RoxyPlatform;

public final class RoxyClassLoaders {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String LEVEL = "net.minecraft.world.level.Level";
    private static volatile ClassLoader cachedGameLoader;

    private RoxyClassLoaders() {
    }

    public static ClassLoader findGameLoader(ClassLoader... hints) {
        ClassLoader cached = cachedGameLoader;
        if (cached != null) return cached;
        ClassLoader resolved = tryFindLoaderFor(MINECRAFT, hints);
        if (resolved == null) {
            resolved = tryFindLoaderFor(LEVEL, hints);
        }
        if (resolved == null) {
            throw new IllegalStateException("Unable to locate game class loader");
        }
        cachedGameLoader = resolved;
        return resolved;
    }

    public static ClassLoader findLoaderFor(String className, ClassLoader... hints) {
        ClassLoader found = tryFindLoaderFor(className, hints);
        if (found != null) return found;
        throw new IllegalStateException("Unable to locate class loader for " + className);
    }

    public static ClassLoader tryFindLoaderFor(String className, ClassLoader... hints) {
        if (className == null) return null;
        String resolvedName = RoxyPlatform.resolveRuntimeClassName(className);
        ClassLoader found = tryFindLoaderRaw(resolvedName, hints);
        if (found == null && !resolvedName.equals(className)) {
            found = tryFindLoaderRaw(className, hints);
        }
        return found;
    }

    private static ClassLoader tryFindLoaderRaw(String className, ClassLoader... hints) {
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

    public static Class<?> loadMinecraftClass(String mojmapBinaryName, ClassLoader loader) throws ClassNotFoundException {
        String runtimeName = RoxyPlatform.resolveRuntimeClassName(mojmapBinaryName);
        try {
            return Class.forName(runtimeName, false, loader);
        } catch (ClassNotFoundException first) {
            if (!runtimeName.equals(mojmapBinaryName)) {
                return Class.forName(mojmapBinaryName, false, loader);
            }
            throw first;
        }
    }

    public static boolean canLoad(ClassLoader loader, String className) {
        if (loader == null || className == null) return false;
        String resource = className.replace('.', '/') + ".class";
        if (loader.getResource(resource) != null) return true;
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
