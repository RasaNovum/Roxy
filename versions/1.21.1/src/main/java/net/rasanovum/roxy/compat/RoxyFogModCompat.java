package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.LoggerFactory;

public final class RoxyFogModCompat {
    private static Accessors accessors;
    private static boolean attempted;
    private static WeatherAccessors weather;
    private static boolean weatherAttempted;

    private RoxyFogModCompat() {}

    public static boolean supportedModPresent() {
        return loaded("fog") || loaded("nomansland");
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
        if (!attempted) {
            attempted = true;
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class<?> config = Class.forName("dev.imb11.fog.config.FogConfig", false, loader);
                Class<?> manager = Class.forName("dev.imb11.fog.client.FogManager", false, loader);
                accessors = new Accessors(config.getMethod("getInstance"), config.getField("enableMod"),
                        manager.getMethod("isInDisabledBiome"));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                LoggerFactory.getLogger("Roxy").warn("Fog compatibility API is unavailable", exception);
            }
        }
        if (accessors == null) return false;
        try {
            return accessors.enabled.getBoolean(accessors.config.invoke(null))
                    && !Boolean.TRUE.equals(accessors.disabledBiome.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LoggerFactory.getLogger("Roxy").warn("Fog compatibility API failed; retaining event-based fog", exception);
            accessors = null;
            return false;
        }
    }

    private record Accessors(Method config, Field enabled, Method disabledBiome) {}

    public static float weatherProgress(float partialTick, float progress) {
        if (accessors == null) return progress;
        if (!weatherAttempted) {
            weatherAttempted = true;
            try {
                ClassLoader loader = accessors.config.getDeclaringClass().getClassLoader();
                Class<?> manager = Class.forName("dev.imb11.fog.client.FogManager", false, loader);
                Field underground = manager.getField("undergroundness");
                weather = new WeatherAccessors(manager.getMethod("getInstance"), underground,
                        underground.getType().getMethod("get", float.class),
                        accessors.config.getDeclaringClass().getField("rainFogMultiplier"));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                LoggerFactory.getLogger("Roxy").warn("Fog weather API unavailable; using Minecraft weather", exception);
            }
        }
        if (weather == null) return progress;
        try {
            Object manager = weather.manager.invoke(null);
            float underground = (float) weather.value.invoke(weather.undergroundness.get(manager), partialTick);
            float strength = weather.multiplier.getFloat(accessors.config.invoke(null));
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
