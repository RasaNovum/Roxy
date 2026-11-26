package net.rasanovum.roxy.util;

import net.rasanovum.roxy.bridge.RoxyFramebufferBridge;
import net.rasanovum.roxy.compat.RoxyChunksmithCompat;
import net.rasanovum.roxy.compat.RoxyCopycatsCompat;
import net.rasanovum.roxy.compat.RoxyCreateStaticModelCompat;
import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.compat.RoxyVoxyWorldgenCompat;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RoxyCompatLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final List<Runnable> WORLD_UNLOAD_HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> WORLD_CHANGE_HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> RENDERER_SHUTDOWN_HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> RESOURCE_RELOAD_HOOKS = new CopyOnWriteArrayList<>();

    private RoxyCompatLifecycle() {
    }

    public static void registerWorldUnloadHook(Runnable hook) {
        if (hook != null) WORLD_UNLOAD_HOOKS.add(hook);
    }

    public static void registerWorldChangeHook(Runnable hook) {
        if (hook != null) WORLD_CHANGE_HOOKS.add(hook);
    }

    public static void registerRendererShutdownHook(Runnable hook) {
        if (hook != null) RENDERER_SHUTDOWN_HOOKS.add(hook);
    }

    public static void registerResourceReloadHook(Runnable hook) {
        if (hook != null) RESOURCE_RELOAD_HOOKS.add(hook);
    }

    public static void onWorldUnload() {
        runGuarded(RoxyLodEntityOcclusion::reset);
        runGuarded(RoxyVoxyFogPatch::resetTerrainFogLifecycle);
        runGuarded(RoxyChunksmithCompat::resetRendererState);
        runGuarded(RoxyVoxyWorldgenCompat::clearQueuedPayloads);
        runGuarded(RoxyCreateStaticModelCompat::clear);
        runGuarded(RoxyCopycatsCompat::reset);
        runGuarded(RoxyFramebufferBridge::reset);
        runAll(WORLD_UNLOAD_HOOKS);
    }

    public static void onWorldChange() {
        runGuarded(RoxyLodEntityOcclusion::reset);
        runGuarded(RoxyChunksmithCompat::resetRendererState);
        runGuarded(RoxyVoxyWorldgenCompat::clearQueuedPayloads);
        runGuarded(RoxyCopycatsCompat::reset);
        runAll(WORLD_CHANGE_HOOKS);
    }

    public static void onRendererShutdown() {
        runGuarded(RoxyLodEntityOcclusion::reset);
        runGuarded(RoxyVoxyFogPatch::resetTerrainFog);
        runGuarded(RoxyChunksmithCompat::resetRendererState);
        runGuarded(RoxyFramebufferBridge::reset);
        runAll(RENDERER_SHUTDOWN_HOOKS);
    }

    public static void onResourceReload() {
        runGuarded(RoxyCreateStaticModelCompat::clear);
        runAll(RESOURCE_RELOAD_HOOKS);
    }

    private static void runAll(List<Runnable> hooks) {
        for (Runnable hook : hooks) {
            runGuarded(hook);
        }
    }

    private static void runGuarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.debug("Roxy lifecycle hook failed", exception);
        }
    }
}
