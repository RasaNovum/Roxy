package net.rasanovum.roxyhost;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.rasanovum.roxy.fog.RoxyFogConfig;
import net.rasanovum.roxy.compat.RoxyFogModCompat;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;

public final class RoxyFogOptions {
    private RoxyFogOptions() {}

    public static void register(Object value) {
        if (shadersActive()) return;
        ConfigBuilder builder = (ConfigBuilder) value;
        addOptions(builder, RoxyFogConfig.get(), RoxyFogConfig::save);
    }

    private static void addOptions(ConfigBuilder builder, RoxyFogConfig.Settings settings, StorageEventHandler storage) {
        int maxChunks = 512;
        ResourceLocation automaticId = ResourceLocation.fromNamespaceAndPath("roxy", "fog_automatic");
        var automatic = builder.createBooleanOption(automaticId)
                .setName(text("automatic"))
                .setTooltip(tooltip("automatic.tooltip"))
                .setDefaultValue(true).setStorageHandler(storage)
                .setBinding(v -> settings.automatic = v, () -> settings.automatic)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled(), ConfigState.UPDATE_ON_APPLY);
        var start = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "fog_start"))
                .setName(text("start"))
                .setTooltip(tooltip("start.tooltip"))
                .setDefaultValue(RoxyFogConfig.Settings.DEFAULT_START_CHUNKS).setRange(0, maxChunks - 1, 1)
                .setValueFormatter(v -> Component.translatable("roxy.fog.chunks", v))
                .setStorageHandler(storage).setBinding(v -> settings.start = v * 16, () -> settings.start / 16)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled(), ConfigState.UPDATE_ON_APPLY);
        var rollIn = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "weather_fog_roll_in"))
                .setName(text("weather_roll_in"))
                .setTooltip(tooltip("weather_roll_in.tooltip"))
                .setDefaultValue(RoxyFogConfig.Settings.DEFAULT_WEATHER_ROLL_IN).setRange(0, 100, 1)
                .setValueFormatter(v -> Component.translatable("roxy.fog.percent", v))
                .setStorageHandler(storage).setBinding(v -> settings.weatherRollIn = v, () -> settings.weatherRollIn)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled() && state.readBooleanOption(automaticId), automaticId, ConfigState.UPDATE_ON_APPLY);
        builder.registerModOptions("roxy")
                .setNonTintedIcon(ResourceLocation.fromNamespaceAndPath("roxy", "icon.png"))
                .addPage(builder.createOptionPage()
                .setName(text("page"))
                .addOption(automatic).addOption(start).addOption(rollIn));
    }

    private static Component text(String key) {
        return Component.translatable("roxy.fog." + key);
    }

    private static Component tooltip(String key) {
        Component description = text(key);
        return RoxyFogModCompat.supportedModPresent() ? description
                : text("requires_mod").copy().append("\n\n").append(description);
    }

    private static boolean enabled() {
        return RoxyFogModCompat.supportedModPresent() && !shadersActive() && RoxyVoxyFogPatch.optionsEnabled();
    }

    private static boolean shadersActive() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null)));
        } catch (ClassNotFoundException exception) {
            return false;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return true;
        }
    }
}
