package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import net.rasanovum.roxy.core.RoxyPlatform;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;
import org.slf4j.LoggerFactory;

public final class RoxyFogModCompat {
    private static volatile Accessors accessors;
    private static volatile boolean attempted;
    private static volatile WeatherAccessors weather;
    private static volatile boolean weatherAttempted;
    private static volatile boolean apiWarningLogged;
    private static volatile boolean weatherWarningLogged;

    private RoxyFogModCompat() {}

    static void resetForTest() {
        accessors = null;
        attempted = false;
        weather = null;
        weatherAttempted = false;
        apiWarningLogged = false;
        weatherWarningLogged = false;
    }

    public static boolean supportedModPresent() {
        return loaded("fog") || loaded("nomansland");
    }

    public static boolean nativeTerrainFogAllowed() {
        return !loaded("fog") && !loaded("nomansland") && !loaded("betterfog") && !loaded("distantfog");
    }

    public static boolean betterFogPresent() {
        return loaded("betterfog");
    }

    public static boolean betterFogUniformsEnabled() {
        return betterFogPresent() && !loaded("distantfog");
    }

    public static boolean fogHorizonActive() {
        return !loaded("nomansland") && fogActive();
    }

    private static boolean loaded(String id) {
        return RoxyPlatform.isModLoaded(id);
    }

    public static boolean fogActive() {
        if (!loaded("fog")) return false;
        Accessors current = accessors;
        if (current == null && !attempted) {
            synchronized (RoxyFogModCompat.class) {
                current = accessors;
                if (current == null && !attempted) {
                    attempted = true;
                    try {
                        ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor("dev.imb11.fog.config.FogConfig");
                        if (loader == null) loader = Thread.currentThread().getContextClassLoader();
                        if (loader == null) loader = RoxyFogModCompat.class.getClassLoader();
                        current = resolveAccessors(loader);
                        accessors = current;
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                        LoggerFactory.getLogger("Roxy").warn("Fog compatibility API is unavailable", exception);
                    }
                }
            }
        }
        if (current == null) return false;
        try {
            Object cfg = current.config.invoke(null);
            if (cfg == null) return false;
            boolean enabled = current.invertedToggle
                    ? !current.toggle.getBoolean(cfg)
                    : current.toggle.getBoolean(cfg);
            if (!enabled) return false;
            if (current.disabledBiome != null && Boolean.TRUE.equals(current.disabledBiome.invoke(null))) {
                return false;
            }
            if (current.disabledDimensions != null) {
                Object mc = current.minecraft.invoke(null);
                Object level = mc == null ? null : current.level.get(mc);
                if (level == null) return false;
                Object key = current.dimension.invoke(level);
                Object location = key == null ? null : current.location.invoke(key);
                Object disabledDims = current.disabledDimensions.get(cfg);
                if (location != null && disabledDims instanceof Collection<?> collection
                        && (collection.contains(location.toString()) || collection.contains(location))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException exception) {
            if (!apiWarningLogged) {
                apiWarningLogged = true;
                LoggerFactory.getLogger("Roxy").warn("Fog compatibility API failed; retaining event-based fog", exception);
            }
            accessors = null;
            return false;
        } catch (RuntimeException exception) {
            if (!apiWarningLogged) {
                apiWarningLogged = true;
                LoggerFactory.getLogger("Roxy").warn("Fog compatibility API threw a runtime error", exception);
            }
            return false;
        }
    }

    static Accessors resolveAccessors(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> config = Class.forName("dev.imb11.fog.config.FogConfig", false, loader);
        Class<?> manager = Class.forName("dev.imb11.fog.client.FogManager", false, loader);
        Method configInstance = config.getMethod("getInstance");
        Method disabledBiome = tryStaticPublicMethod(manager, "isInDisabledBiome");
        Field disabledDimensions = tryPublicField(config, "disabledDimensions");
        Field disableRaininess = tryPublicField(config, "disableRaininessEffect");
        Field enableMod = tryPublicField(config, "enableMod");
        Field toggle = enableMod != null ? enableMod : config.getField("disableMod");
        boolean inverted = enableMod == null;

        Method mcInstance = null;
        Field mcLevel = null;
        Method levelDimension = null;
        Method keyLocation = null;
        if (disabledDimensions != null) {
            ClassLoader gameLoader = RoxyClassLoaders.findGameLoader(loader);
            Class<?> minecraft = RoxyClassLoaders.loadMinecraftClass("net.minecraft.client.Minecraft", gameLoader);
            Class<?> level = RoxyClassLoaders.loadMinecraftClass("net.minecraft.world.level.Level", gameLoader);
            Class<?> resourceKey = RoxyClassLoaders.loadMinecraftClass("net.minecraft.resources.ResourceKey", gameLoader);
            mcInstance = RoxyReflection.findMethod(minecraft, "getInstance");
            mcLevel = RoxyReflection.findField(minecraft, "level");
            levelDimension = RoxyReflection.findMethod(level, "dimension");
            keyLocation = RoxyReflection.findMethod(resourceKey, "location");
        }
        return new Accessors(
                configInstance,
                toggle,
                inverted,
                disabledBiome,
                disabledDimensions,
                disableRaininess,
                mcInstance,
                mcLevel,
                levelDimension,
                keyLocation
        );
    }

    private static Field tryPublicField(Class<?> type, String name) {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Method tryPublicMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method tryStaticPublicMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Method method = tryPublicMethod(type, name, parameterTypes);
        return method != null && Modifier.isStatic(method.getModifiers()) ? method : null;
    }

    public static float weatherProgress(float partialTick, float progress) {
        Accessors currentAccessors = accessors;
        if (currentAccessors == null) return progress;
        if (currentAccessors.disableRaininess != null) {
            try {
                Object cfg = currentAccessors.config.invoke(null);
                if (cfg != null && currentAccessors.disableRaininess.getBoolean(cfg)) return 0;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                if (!weatherWarningLogged) {
                    weatherWarningLogged = true;
                    LoggerFactory.getLogger("Roxy").warn("Fog weather API failed; using Minecraft weather", exception);
                }
                return progress;
            }
        }
        WeatherAccessors currentWeather = weather;
        if (currentWeather == null && !weatherAttempted) {
            synchronized (RoxyFogModCompat.class) {
                currentWeather = weather;
                if (currentWeather == null && !weatherAttempted) {
                    weatherAttempted = true;
                    try {
                        ClassLoader loader = currentAccessors.config.getDeclaringClass().getClassLoader();
                        Class<?> manager = Class.forName("dev.imb11.fog.client.FogManager", false, loader);
                        Field underground = tryPublicField(manager, "undergroundness");
                        Method managerInstance = underground == null ? null : tryStaticPublicMethod(manager, "getInstance");
                        Method valueMethod = underground == null ? null : tryPublicMethod(underground.getType(), "get", float.class);
                        Field multiplier = tryPublicField(currentAccessors.config.getDeclaringClass(), "rainFogMultiplier");
                        if (multiplier != null || (underground != null && managerInstance != null && valueMethod != null)) {
                            currentWeather = new WeatherAccessors(managerInstance, underground, valueMethod, multiplier);
                            weather = currentWeather;
                        }
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                        LoggerFactory.getLogger("Roxy").warn("Fog weather API unavailable; using Minecraft weather", exception);
                    }
                }
            }
        }
        if (currentWeather == null) return progress;
        try {
            if (currentWeather.multiplier != null) {
                Object cfg = currentAccessors.config.invoke(null);
                if (cfg == null) return progress;
                float strength = currentWeather.multiplier.getFloat(cfg);
                if (!Float.isFinite(strength)) return progress;
                if (strength <= 0) return 0;
            }
            if (currentWeather.manager == null || currentWeather.undergroundness == null || currentWeather.value == null) {
                return progress;
            }
            Object manager = currentWeather.manager.invoke(null);
            if (manager == null) return progress;
            Object undergroundObj = currentWeather.undergroundness.get(manager);
            if (undergroundObj == null) return progress;
            float underground = (float) currentWeather.value.invoke(undergroundObj, partialTick);
            if (!Float.isFinite(underground)) return progress;
            return progress * (1 - Math.max(0, Math.min(1, underground)));
        } catch (ReflectiveOperationException exception) {
            if (!weatherWarningLogged) {
                weatherWarningLogged = true;
                LoggerFactory.getLogger("Roxy").warn("Fog weather API failed; using Minecraft weather", exception);
            }
            weather = null;
            return progress;
        } catch (RuntimeException exception) {
            if (!weatherWarningLogged) {
                weatherWarningLogged = true;
                LoggerFactory.getLogger("Roxy").warn("Fog weather API threw a runtime error", exception);
            }
            return progress;
        }
    }

    record Accessors(
            Method config,
            Field toggle,
            boolean invertedToggle,
            Method disabledBiome,
            Field disabledDimensions,
            Field disableRaininess,
            Method minecraft,
            Field level,
            Method dimension,
            Method location
    ) {}

    private record WeatherAccessors(Method manager, Field undergroundness, Method value, Field multiplier) {}
}
