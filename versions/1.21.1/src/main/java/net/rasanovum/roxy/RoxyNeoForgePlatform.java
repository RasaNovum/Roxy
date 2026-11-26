package net.rasanovum.roxy;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.rasanovum.roxy.bridge.RoxyModelTintBridge;
import net.rasanovum.roxy.compat.RoxyCompatConfig;
import net.rasanovum.roxy.compat.RoxyCopycatsCompat;
import net.rasanovum.roxy.compat.RoxyPowerGridCompat;
import net.rasanovum.roxy.core.RoxyPlatform;
import net.rasanovum.roxy.patch.RoxyVoxyRenderPatch;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;
import net.rasanovum.roxy.util.RoxyCompatLifecycle;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyNeoForgePlatform {
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean();

    private RoxyNeoForgePlatform() {
    }

    public static void ensureConfigured() {
        if (!CONFIGURED.compareAndSet(false, true)) return;
        Path configDir = null;
        try {
            configDir = FMLPaths.CONFIGDIR.get();
        } catch (RuntimeException | LinkageError ignored) {
        }
        RoxyPlatform.configure(configDir, RoxyNeoForgePlatform::isModLoaded, null, false);
        RoxyCompatLifecycle.registerWorldUnloadHook(RoxyModelTintBridge::clear);
        RoxyCompatLifecycle.registerWorldUnloadHook(RoxyPowerGridCompat::onWorldUnload);
        RoxyCompatLifecycle.registerResourceReloadHook(RoxyModelTintBridge::clear);
        RoxyCopycatsCompat.setEnabledSupplier(RoxyCompatConfig::copycatsEnabled);
        RoxyVoxyRenderPatch.registerMeshAcceptedListener(position -> {
            if (RoxyCompatConfig.tfcEnabled()) {
                TfcVoxyBridge.meshAccepted(position);
            }
        });
    }

    private static boolean isModLoaded(String modId) {
        try {
            LoadingModList mods = LoadingModList.get();
            return mods != null && mods.getModFileById(modId) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
