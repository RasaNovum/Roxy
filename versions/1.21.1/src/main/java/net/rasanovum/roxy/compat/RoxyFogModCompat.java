package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.LoggerFactory;

public final class RoxyFogModCompat {
    private static volatile Accessors accessors;
    private static volatile boolean attempted;
    private static volatile WeatherAccessors weather;
    private static volatile boolean weatherAttempted;

    private RoxyFogModCompat() {}

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
        var mods = LoadingModList.get();
        return mods != null && mods.getModFileById(id) != null;
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
                        ClassLoader loader = Thread.currentThread().getContextClassLoader();
                        if (loader == null) loader = RoxyFogModCompat.class.getClassLoader();
                        Class<?> config = Class.forName("dev.imb11.fog.config.FogConfig", false, loader);
                        Class<?> manager = Class.forName("dev.imb11.fog.client.FogManager", false, loader);
                        current = new Accessors(config.getMethod("getInstance"), config.getField("enableMod"),
                                manager.getMethod("isInDisabledBiome"));
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
            return current.enabled.getBoolean(cfg)
                    && !Boolean.TRUE.equals(current.disabledBiome.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LoggerFactory.getLogger("Roxy").warn("Fog compatibility API failed; retaining event-based fog", exception);
            accessors = null;
            return false;
        }
    }

    private record Accessors(Method config, Field enabled, Method disabledBiome) {}

    public static float weatherProgress(float partialTick, float progress) {
        Accessors currentAccessors = accessors;
        if (currentAccessors == null) return progress;
        WeatherAccessors currentWeather = weather;
        if (currentWeather == null && !weatherAttempted) {
            synchronized (RoxyFogModCompat.class) {
                currentWeather = weather;
                if (currentWeather == null && !weatherAttempted) {
                    weatherAttempted = true;
                    try {
                        ClassLoader loader = currentAccessors.config.getDeclaringClass().getClassLoader();
                        Class<?> manager = Class.forName("dev.imb11.fog.client.FogManager", false, loader);
                        Field underground = manager.getField("undergroundness");
                        currentWeather = new WeatherAccessors(manager.getMethod("getInstance"), underground,
                                underground.getType().getMethod("get", float.class),
                                currentAccessors.config.getDeclaringClass().getField("rainFogMultiplier"));
                        weather = currentWeather;
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                        LoggerFactory.getLogger("Roxy").warn("Fog weather API unavailable; using Minecraft weather", exception);
                    }
                }
            }
        }
        if (currentWeather == null) return progress;
        try {
            Object manager = currentWeather.manager.invoke(null);
            if (manager == null) return progress;
            Object undergroundObj = currentWeather.undergroundness.get(manager);
            if (undergroundObj == null) return progress;
            Object cfg = currentAccessors.config.invoke(null);
            if (cfg == null) return progress;
            float underground = (float) currentWeather.value.invoke(undergroundObj, partialTick);
            float strength = currentWeather.multiplier.getFloat(cfg);
            if (!Float.isFinite(strength) || !Float.isFinite(underground)) return progress;
            return strength <= 0 ? 0 : progress * (1 - Math.max(0, Math.min(1, underground)));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LoggerFactory.getLogger("Roxy").warn("Fog weather API failed; using Minecraft weather", exception);
            weather = null;
            return progress;
        }
    }

    private record WeatherAccessors(Method manager, Field undergroundness, Method value, Field multiplier) {}

}
