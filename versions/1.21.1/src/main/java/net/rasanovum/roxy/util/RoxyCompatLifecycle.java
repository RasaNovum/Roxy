package net.rasanovum.roxy.util;

import net.rasanovum.roxy.bridge.RoxyModelTintBridge;
import net.rasanovum.roxy.compat.RoxyChunksmithCompat;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.compat.RoxyVoxyWorldgenCompat;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;

public final class RoxyCompatLifecycle {
    private RoxyCompatLifecycle() {
    }

    public static void onWorldUnload() {
        RoxyVoxyWorldgenCompat.clearQueuedPayloads();
        RoxyLodEntityOcclusion.reset();
        RoxyModelTintBridge.clear();
        RoxyChunksmithCompat.resetRendererState();
        RoxyVoxyFogPatch.resetTerrainFogLifecycle();
    }

    public static void onWorldChange() {
        RoxyVoxyWorldgenCompat.clearQueuedPayloads();
        RoxyLodEntityOcclusion.reset();
        RoxyChunksmithCompat.resetRendererState();
    }

    public static void onRendererShutdown() {
        RoxyLodEntityOcclusion.reset();
        RoxyVoxyFogPatch.resetTerrainFog();
        RoxyChunksmithCompat.resetRendererState();
    }

    public static void onResourceReload() {
        RoxyModelTintBridge.clear();
    }
}
