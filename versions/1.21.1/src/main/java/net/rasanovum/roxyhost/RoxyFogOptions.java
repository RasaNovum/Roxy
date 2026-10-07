package net.rasanovum.roxyhost;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.rasanovum.roxy.fog.RoxyFogConfig;
import net.rasanovum.roxy.compat.RoxyFogModCompat;
import net.rasanovum.roxy.tfc.TfcCompatConfig;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;

public final class RoxyFogOptions {
    private static final ResourceLocation VOXY_ENABLED = ResourceLocation.fromNamespaceAndPath("voxy", "enabled");
    private static final ResourceLocation VOXY_RENDERING = ResourceLocation.fromNamespaceAndPath("voxy", "rendering");
    private static final ResourceLocation VOXY_ENVIRONMENTAL_FOG =
            ResourceLocation.fromNamespaceAndPath("voxy", "eviromental_fog");
    private static final ResourceLocation TFC_DAY_CYCLE =
            ResourceLocation.fromNamespaceAndPath("roxy", "tfc_day_cycle_lod_updates");

    private RoxyFogOptions() {}

    public static void register(Object value) {
        ConfigBuilder builder = (ConfigBuilder) value;
        addOptions(builder, RoxyFogConfig.get(), RoxyFogConfig::save, !shadersActive());
    }

    private static void addOptions(ConfigBuilder builder, RoxyFogConfig.Settings settings,
                                   StorageEventHandler storage, boolean includeFogOptions) {
        int maxChunks = 512;
        var page = builder.createOptionPage().setName(text("page"));
        if (includeFogOptions) {
            ResourceLocation automaticId = ResourceLocation.fromNamespaceAndPath("roxy", "fog_automatic");
            boolean voxyOptionsAvailable = voxyOptionsAvailable();
            ResourceLocation[] enabledDependencies = voxyOptionsAvailable
                    ? new ResourceLocation[]{VOXY_ENABLED, VOXY_RENDERING, VOXY_ENVIRONMENTAL_FOG,
                            ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD}
                    : new ResourceLocation[]{ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD};
            ResourceLocation[] rollInDependencies = new ResourceLocation[enabledDependencies.length + 1];
            rollInDependencies[0] = automaticId;
            System.arraycopy(enabledDependencies, 0, rollInDependencies, 1, enabledDependencies.length);
            var automatic = builder.createBooleanOption(automaticId)
                    .setName(text("automatic"))
                    .setTooltip(tooltip("automatic.tooltip"))
                    .setDefaultValue(true).setStorageHandler(storage)
                    .setBinding(v -> settings.automatic = v, () -> settings.automatic)
                    .setControlHiddenWhenDisabled(false)
                    .setEnabledProvider(state -> enabled(state, voxyOptionsAvailable), enabledDependencies);
            var start = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "fog_start"))
                    .setName(text("start"))
                    .setTooltip(tooltip("start.tooltip"))
                    .setDefaultValue(RoxyFogConfig.Settings.DEFAULT_START_CHUNKS).setRange(0, maxChunks - 1, 1)
                    .setValueFormatter(v -> Component.translatable("roxy.fog.chunks", v))
                    .setStorageHandler(storage).setBinding(v -> settings.start = v * 16, () -> settings.start / 16)
                    .setControlHiddenWhenDisabled(false)
                    .setEnabledProvider(state -> enabled(state, voxyOptionsAvailable), enabledDependencies);
            var rollIn = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "weather_fog_roll_in"))
                    .setName(text("weather_roll_in"))
                    .setTooltip(tooltip("weather_roll_in.tooltip"))
                    .setDefaultValue(RoxyFogConfig.Settings.DEFAULT_WEATHER_ROLL_IN).setRange(0, 100, 1)
                    .setValueFormatter(v -> Component.translatable("roxy.fog.percent", v))
                    .setStorageHandler(storage).setBinding(v -> settings.weatherRollIn = v, () -> settings.weatherRollIn)
                    .setControlHiddenWhenDisabled(false)
                    .setEnabledProvider(state -> enabled(state, voxyOptionsAvailable) && state.readBooleanOption(automaticId),
                            rollInDependencies);
            page.addOption(automatic).addOption(start).addOption(rollIn);
        }
        var tfc = builder.createBooleanOption(TFC_DAY_CYCLE)
                .setName(tfcText("day_cycle"))
                .setTooltip(tfcTooltip())
                .setDefaultValue(false).setStorageHandler(TfcCompatConfig::save)
                .setBinding(TfcCompatConfig::setEnabled, TfcCompatConfig::enabled)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> TfcCompatConfig.installed(),
                        ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD);
        page.addOption(tfc);
        builder.registerModOptions("roxy")
                .setNonTintedIcon(ResourceLocation.fromNamespaceAndPath("roxy", "icon.png"))
                .addPage(page);
    }

    private static Component text(String key) {
        return Component.translatable("roxy.fog." + key);
    }

    private static Component tooltip(String key) {
        Component description = text(key);
        return RoxyFogModCompat.supportedModPresent() ? description
                : text("requires_mod").copy().append("\n\n").append(description);
    }

    private static Component tfcText(String key) {
        return Component.translatable("roxy.tfc." + key);
    }

    private static Component tfcTooltip() {
        Component description = tfcText("day_cycle.tooltip");
        return TfcCompatConfig.installed() ? description
                : tfcText("requires_mod").copy().append("\n\n").append(description);
    }

    private static boolean enabled(ConfigState state, boolean voxyOptionsAvailable) {
        if (!RoxyFogModCompat.supportedModPresent() || shadersActive()) return false;
        if (!voxyOptionsAvailable) return RoxyVoxyFogPatch.optionsEnabled();
        return state.readBooleanOption(VOXY_ENABLED)
                && state.readBooleanOption(VOXY_RENDERING)
                && !state.readBooleanOption(VOXY_ENVIRONMENTAL_FOG);
    }

    private static boolean voxyOptionsAvailable() {
        try {
            Class<?> voxy = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false,
                    RoxyFogOptions.class.getClassLoader());
            return Boolean.TRUE.equals(voxy.getMethod("isAvailable").invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
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
