package net.rasanovum.roxy.render;

import java.lang.ref.WeakReference;

public final class RoxyVoxyWorkDrain {
    private static final int MAX_PENDING_SIGNALS = 8;
    private static final int SHADER_TAIL_FRAMES = 4;
    private static WeakReference<Object> activeManager = new WeakReference<>(null);
    private static boolean bootstrapPending;
    private static int continuationsAvailable;
    private static int pendingSignals;
    private static int shaderTailFrames;
    private static boolean shaderMainActive;
    private static boolean tailUsed;
    private static boolean producing;

    private RoxyVoxyWorkDrain() {
    }

    private static boolean isActiveManager(Object manager) {
        return manager != null && manager == activeManager.get();
    }

    public static synchronized void beginRender(Object manager, boolean shaderPackEnabled, boolean shadowActive) {
        if (shadowActive) {
            tailUsed = false;
            continuationsAvailable = 0;
            return;
        }
        boolean active = isActiveManager(manager);
        shaderMainActive = active && shaderPackEnabled;
        tailUsed = false;
        continuationsAvailable = active ? (shaderPackEnabled ? 2 : 1) : 0;
        if (shaderMainActive && bootstrapPending) shaderTailFrames = SHADER_TAIL_FRAMES;
    }

    public static synchronized boolean shouldRun(Object manager, boolean managerHasWork) {
        if (!isActiveManager(manager)) return false;
        if (continuationsAvailable == 0) return false;
        if (managerHasWork || producing) {
            continuationsAvailable--;
            if (pendingSignals > 0) pendingSignals--;
            return true;
        }
        if (bootstrapPending) {
            continuationsAvailable--;
            bootstrapPending = false;
            if (pendingSignals > 0) pendingSignals--;
            return true;
        }
        if (pendingSignals > 0) {
            continuationsAvailable--;
            pendingSignals--;
            return true;
        }
        if (!shaderMainActive || tailUsed || shaderTailFrames == 0) return false;
        continuationsAvailable--;
        shaderTailFrames--;
        tailUsed = true;
        return true;
    }

    public static synchronized void signalRequest(Object manager) {
        if (isActiveManager(manager) && pendingSignals < MAX_PENDING_SIGNALS) pendingSignals++;
    }

    public static synchronized void beginProduction(Object manager) {
        if (isActiveManager(manager)) producing = true;
    }

    public static synchronized void endProduction(Object manager) {
        if (isActiveManager(manager)) producing = false;
    }

    public static synchronized void publishResult(Object manager) {
        if (!isActiveManager(manager)) return;
        producing = false;
        if (pendingSignals < MAX_PENDING_SIGNALS) pendingSignals++;
        if (shaderMainActive) shaderTailFrames = SHADER_TAIL_FRAMES;
    }

    public static synchronized void bind(Object manager) {
        if (manager == null || manager == activeManager.get()) return;
        activeManager = new WeakReference<>(manager);
        bootstrapPending = true;
        continuationsAvailable = 0;
        pendingSignals = 0;
        shaderTailFrames = 0;
        shaderMainActive = false;
        tailUsed = false;
        producing = false;
    }

    public static synchronized void reset() {
        activeManager = new WeakReference<>(null);
        bootstrapPending = false;
        continuationsAvailable = 0;
        pendingSignals = 0;
        shaderTailFrames = 0;
        shaderMainActive = false;
        tailUsed = false;
        producing = false;
    }
}
