package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.LoggerFactory;

public final class RoxyFogModCompat {
    private static Accessors accessors;
    private static boolean attempted;

    private RoxyFogModCompat() {}

    public static boolean supportedModPresent() {
        return loaded("fog") || loaded("nomansland");
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
}
