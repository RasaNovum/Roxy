package net.rasanovum.roxyhost;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.rasanovum.roxy.compat.RoxyFogConfig;
import net.rasanovum.roxy.compat.RoxyFogModCompat;
import net.rasanovum.roxy.patch.RoxyVoxyFogPatch;

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
                .setName(text("automatic", "Automatic fog range"))
                .setTooltip(tooltip("automatic.tooltip", "Automatically extend the range of fog over distant LoD terrain."))
                .setDefaultValue(true).setStorageHandler(storage)
                .setBinding(v -> settings.automatic = v, () -> settings.automatic)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled(), ConfigState.UPDATE_ON_APPLY);
        var start = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "fog_start"))
                .setName(text("start", "Fog start"))
                .setTooltip(tooltip("start.tooltip", "Distance in chunks where fog begins. The current voxy render distance as the fog end point."))
                .setDefaultValue(0).setRange(0, maxChunks - 1, 1)
                .setValueFormatter(v -> Component.translatableWithFallback("roxy.fog.chunks", "%s chunks", v))
                .setStorageHandler(storage).setBinding(v -> settings.start = v * 16, () -> settings.start / 16)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled() && !state.readBooleanOption(automaticId), automaticId, ConfigState.UPDATE_ON_APPLY);
        builder.registerModOptions("roxy")
                .setNonTintedIcon(ResourceLocation.fromNamespaceAndPath("roxy", "icon.png"))
                .addPage(builder.createOptionPage()
                .setName(text("page", "LoD Fog"))
                .addOption(automatic).addOption(start));
    }

    private static Component text(String key, String fallback) {
        return Component.translatableWithFallback("roxy.fog." + key, fallback);
    }

    private static Component tooltip(String key, String fallback) {
        Component description = text(key, fallback);
        return RoxyFogModCompat.supportedModPresent() ? description
                : text("requires_mod", "Requires a supported fog mod: Fog or No Man's Land.").copy().append("\n\n").append(description);
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
