package net.rasanovum.roxyhost;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.rasanovum.roxy.compat.RoxyBetterFogCompat;
import net.rasanovum.roxy.compat.RoxyCompatConfig;
import net.rasanovum.roxy.compat.RoxyFogModCompat;
import net.rasanovum.roxy.fog.RoxyFogConfig;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import net.rasanovum.roxy.tfc.TfcCompatConfig;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RoxySodiumOptions {
    private static final ResourceLocation VOXY_ENABLED = ResourceLocation.fromNamespaceAndPath("voxy", "enabled");
    private static final ResourceLocation VOXY_RENDERING = ResourceLocation.fromNamespaceAndPath("voxy", "rendering");
    private static final ResourceLocation VOXY_ENVIRONMENTAL_FOG =
            ResourceLocation.fromNamespaceAndPath("voxy", "eviromental_fog");
    private static final ResourceLocation TFC_DAY_CYCLE =
            ResourceLocation.fromNamespaceAndPath("roxy", "tfc_day_cycle_lod_updates");
    private static volatile IrisAccess irisAccess;
    private static volatile boolean irisChecked;
    private static volatile boolean irisPresent;

    private RoxySodiumOptions() {}

    public static void register(Object value) {
        ConfigBuilder builder = (ConfigBuilder) value;
        var modOptions = builder.registerModOptions("roxy")
                .setNonTintedIcon(ResourceLocation.fromNamespaceAndPath("roxy", "icon.png"));
        modOptions.addPage(createFogPage(builder));
        modOptions.addPage(createCompatibilityPage(builder));
        modOptions.addPage(createLodUpdatesPage(builder));
    }

    private static OptionPageBuilder createFogPage(ConfigBuilder builder) {
        var settings = RoxyFogConfig.get();
        StorageEventHandler storage = RoxyFogConfig::save;
        int maxChunks = 512;
        var page = builder.createOptionPage().setName(text("page"));
        ResourceLocation automaticId = ResourceLocation.fromNamespaceAndPath("roxy", "fog_automatic");
        boolean voxyOptionsAvailable = voxyOptionsAvailable();
        ResourceLocation[] enabledDependencies = voxyOptionsAvailable
                ? new ResourceLocation[]{VOXY_ENABLED, VOXY_RENDERING, VOXY_ENVIRONMENTAL_FOG,
                        ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD}
                : new ResourceLocation[]{ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD};
        ResourceLocation[] rollInDependencies = new ResourceLocation[enabledDependencies.length + 1];
        rollInDependencies[0] = automaticId;
        System.arraycopy(enabledDependencies, 0, rollInDependencies, 1, enabledDependencies.length);
        ResourceLocation terrainEnabledId = ResourceLocation.fromNamespaceAndPath("roxy", "terrain_fog_enabled");
        ResourceLocation[] sharedStartDependencies = new ResourceLocation[enabledDependencies.length + 1];
        sharedStartDependencies[0] = terrainEnabledId;
        System.arraycopy(enabledDependencies, 0, sharedStartDependencies, 1, enabledDependencies.length);
        var automatic = builder.createBooleanOption(automaticId)
                .setName(text("automatic"))
                .setTooltip(tooltip("automatic.tooltip"))
                .setDefaultValue(true).setStorageHandler(storage)
                .setBinding(v -> settings.automatic = v, () -> settings.automatic)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled(state, voxyOptionsAvailable), enabledDependencies);
        var terrainEnabled = builder.createBooleanOption(terrainEnabledId)
                .setName(text("terrain_enabled"))
                .setTooltip(text("terrain_enabled.tooltip"))
                .setDefaultValue(true).setStorageHandler(storage)
                .setBinding(v -> settings.terrainEnabled = v, () -> settings.terrainEnabled)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> terrainFogEnabled(state, voxyOptionsAvailable), enabledDependencies);
        var start = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "fog_start"))
                .setName(text("start"))
                .setTooltip(startTooltip())
                .setDefaultValue(RoxyFogConfig.Settings.DEFAULT_START_CHUNKS).setRange(0, maxChunks - 1, 1)
                .setValueFormatter(v -> Component.translatable("roxy.fog.chunks", v))
                .setStorageHandler(storage)
                .setBinding(
                        v -> settings.start = v * 16,
                        () -> Math.max(0, Math.min(maxChunks - 1, settings.start / 16))
                )
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled(state, voxyOptionsAvailable)
                        || (terrainFogEnabled(state, voxyOptionsAvailable)
                        && state.readBooleanOption(terrainEnabledId)), sharedStartDependencies);
        var rollIn = builder.createIntegerOption(ResourceLocation.fromNamespaceAndPath("roxy", "weather_fog_roll_in"))
                .setName(text("weather_roll_in"))
                .setTooltip(tooltip("weather_roll_in.tooltip"))
                .setDefaultValue(RoxyFogConfig.Settings.DEFAULT_WEATHER_ROLL_IN).setRange(0, 100, 1)
                .setValueFormatter(v -> Component.translatable("roxy.fog.percent", v))
                .setStorageHandler(storage).setBinding(v -> settings.weatherRollIn = v, () -> settings.weatherRollIn)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> enabled(state, voxyOptionsAvailable) && state.readBooleanOption(automaticId),
                        rollInDependencies);
        page.addOption(automatic).addOption(start).addOption(rollIn).addOption(terrainEnabled);
        return page;
    }

    private static OptionPageBuilder createCompatibilityPage(ConfigBuilder builder) {
        var compatibility = RoxyCompatConfig.optionsForRestart();
        var compatibilityPage = builder.createOptionPage().setName(compatText("page"));
        compatibilityPage.addOption(compatOption(builder, "terrain_slabs",
                value -> compatibility.terrainSlabs = value, () -> compatibility.terrainSlabs));
        compatibilityPage.addOption(compatOption(builder, "tfc",
                value -> compatibility.tfc = value, () -> compatibility.tfc));
        compatibilityPage.addOption(compatOption(builder, "copycats",
                value -> compatibility.copycats = value, () -> compatibility.copycats));
        compatibilityPage.addOption(compatOption(builder, "power_grid",
                value -> compatibility.powerGrid = value, () -> compatibility.powerGrid));
        return compatibilityPage;
    }

    private static OptionPageBuilder createLodUpdatesPage(ConfigBuilder builder) {
        var lodUpdatesPage = builder.createOptionPage().setName(tfcText("page"));
        var tfc = builder.createBooleanOption(TFC_DAY_CYCLE)
                .setName(tfcText("day_cycle"))
                .setTooltip(tfcTooltip())
                .setDefaultValue(false).setStorageHandler(TfcCompatConfig::save)
                .setBinding(TfcCompatConfig::setEnabled, TfcCompatConfig::enabled)
                .setControlHiddenWhenDisabled(false)
                .setEnabledProvider(state -> TfcCompatConfig.installed() && RoxyCompatConfig.tfcEnabled(),
                        ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD);
        var showProgress = builder.createBooleanOption(
                        ResourceLocation.fromNamespaceAndPath("roxy", "tfc_show_progress"))
                .setName(tfcText("show_progress"))
                .setTooltip(tfcText("show_progress.tooltip"))
                .setDefaultValue(true).setStorageHandler(TfcCompatConfig::save)
                .setBinding(TfcCompatConfig::setShowProgress, TfcCompatConfig::showProgress)
                .setControlHiddenWhenDisabled(true)
                .setEnabledProvider(state -> TfcCompatConfig.installed() && RoxyCompatConfig.tfcEnabled()
                                && state.readBooleanOption(TFC_DAY_CYCLE),
                        TFC_DAY_CYCLE, ConfigState.UPDATE_ON_APPLY, ConfigState.UPDATE_ON_REBUILD);
        lodUpdatesPage.addOption(tfc).addOption(showProgress);
        return lodUpdatesPage;
    }

    private static BooleanOptionBuilder compatOption(ConfigBuilder builder, String key,
                                                     Consumer<Boolean> setter, Supplier<Boolean> getter) {
        return builder.createBooleanOption(ResourceLocation.fromNamespaceAndPath("roxy", "compat_" + key))
                .setName(compatText(key))
                .setTooltip(compatText(key + ".tooltip").copy().append("\n\n").append(compatText("restart")))
                .setDefaultValue(true)
                .setStorageHandler(RoxyCompatConfig::save)
                .setBinding(setter, getter)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART);
    }

    private static Component compatText(String key) {
        return Component.translatable("roxy.compat." + key);
    }

    private static Component text(String key) {
        return Component.translatable("roxy.fog." + key);
    }

    private static Component tooltip(String key) {
        Component description = text(key);
        if (RoxyBetterFogCompat.available()) {
            return text("betterfog_settings").copy().append("\n\n").append(description);
        }
        return RoxyFogModCompat.supportedModPresent() ? description
                : text("requires_mod").copy().append("\n\n").append(description);
    }

    private static Component startTooltip() {
        Component description = text("start.tooltip");
        if (RoxyBetterFogCompat.available()) {
            return text("betterfog_settings").copy().append("\n\n").append(description);
        }
        return RoxyFogModCompat.supportedModPresent()
                || (RoxyFogModCompat.nativeTerrainFogAllowed() && !shadersActive())
                ? description
                : text("requires_mod").copy().append("\n\n").append(description);
    }

    private static Component tfcText(String key) {
        return Component.translatable("roxy.tfc." + key);
    }

    private static Component tfcTooltip() {
        Component description = tfcText("day_cycle.tooltip");
        if (!TfcCompatConfig.installed()) {
            return tfcText("requires_mod").copy().append("\n\n").append(description);
        }
        return RoxyCompatConfig.tfcEnabled() ? description
                : tfcText("requires_compat").copy().append("\n\n").append(description);
    }

    private static boolean enabled(ConfigState state, boolean voxyOptionsAvailable) {
        if (RoxyBetterFogCompat.available() || !RoxyFogModCompat.supportedModPresent() || shadersActive()) return false;
        if (!voxyOptionsAvailable) return RoxyVoxyFogPatch.optionsEnabled();
        return state.readBooleanOption(VOXY_ENABLED)
                && state.readBooleanOption(VOXY_RENDERING)
                && !state.readBooleanOption(VOXY_ENVIRONMENTAL_FOG);
    }

    private static boolean terrainFogEnabled(ConfigState state, boolean voxyOptionsAvailable) {
        if (!RoxyFogModCompat.nativeTerrainFogAllowed() || shadersActive()) return false;
        if (!voxyOptionsAvailable) return RoxyVoxyFogPatch.optionsEnabled();
        return state.readBooleanOption(VOXY_ENABLED)
                && state.readBooleanOption(VOXY_RENDERING)
                && !state.readBooleanOption(VOXY_ENVIRONMENTAL_FOG);
    }

    private static boolean voxyOptionsAvailable() {
        try {
            Class<?> voxy = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false,
                    RoxySodiumOptions.class.getClassLoader());
            return Boolean.TRUE.equals(voxy.getMethod("isAvailable").invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean shadersActive() {
        IrisAccess access = resolveIrisAccess();
        if (!irisPresent) return false;
        if (access == null) return true;
        try {
            Object instance = access.getInstance.invoke(null);
            return instance != null && Boolean.TRUE.equals(access.isShaderPackInUse.invoke(instance));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return true;
        }
    }

    private static IrisAccess resolveIrisAccess() {
        IrisAccess current = irisAccess;
        if (current != null || irisChecked) return current;
        synchronized (RoxySodiumOptions.class) {
            current = irisAccess;
            if (current != null || irisChecked) return current;
            irisChecked = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisPresent = true;
                current = new IrisAccess(api.getMethod("getInstance"), api.getMethod("isShaderPackInUse"));
                irisAccess = current;
                return current;
            } catch (ClassNotFoundException ignored) {
                irisPresent = false;
                return null;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                irisPresent = true;
                return null;
            }
        }
    }

    private record IrisAccess(Method getInstance, Method isShaderPackInUse) {
    }
}
