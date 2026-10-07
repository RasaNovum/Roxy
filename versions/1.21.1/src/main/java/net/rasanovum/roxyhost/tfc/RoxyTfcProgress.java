package net.rasanovum.roxyhost.tfc;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import net.rasanovum.roxy.compat.RoxyVoxyRendererReloadCompat;
import net.rasanovum.roxy.tfc.TfcCompatConfig;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;

public final class RoxyTfcProgress {
    private static final UUID ID = UUID.fromString("eab352d0-4f29-4ade-9832-3a0fa9ee208b");
    private static Object world;
    private static boolean visible;
    private static int ticks;
    private static final Visibility VISIBILITY = new Visibility();
    private static boolean reloadQueued;
    private static Object reloadWorld;
    private static Object reloadLevelRenderer;
    private static Object rendererBeforeReload;
    private static boolean queuedEnabled;
    private static final Object RENDERER_UNAVAILABLE = new Object();

    private RoxyTfcProgress() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        pollRendererReload(minecraft);
        if (!TfcCompatConfig.enabled()) {
            RoxyTfcBackfill.disable();
            hide(minecraft);
            VISIBILITY.reset(minecraft.level);
            world = minecraft.level;
            return;
        }
        RoxyTfcBackfill.resetWorld(minecraft.level);
        if (!TfcCompatConfig.showProgress()) {
            hide(minecraft);
            VISIBILITY.reset(minecraft.level);
        }
        if (world == minecraft.level && ++ticks % 5 != 0) return;
        update(minecraft.level, minecraft.gui.getBossOverlay(), TfcVoxyBridge.refreshProgress(), System.currentTimeMillis());
    }

    private static void pollRendererReload(Minecraft minecraft) {
        boolean requested = TfcVoxyBridge.updateEnabledState(minecraft.level);
        if (reloadQueued) {
            if (minecraft.level != reloadWorld || minecraft.levelRenderer != reloadLevelRenderer) {
                clearReloadState(true);
                return;
            }
            boolean enabledNow = TfcCompatConfig.enabled();
            if (enabledNow != queuedEnabled) {
                Object before = voxyRenderer(reloadLevelRenderer);
                if (before == RENDERER_UNAVAILABLE) return;
                if (RoxyVoxyRendererReloadCompat.deferVoxyReload(reloadLevelRenderer)) {
                    queuedEnabled = enabledNow;
                    rendererBeforeReload = before;
                }
                return;
            }
            Object current = voxyRenderer(reloadLevelRenderer);
            if (current != RENDERER_UNAVAILABLE && current != rendererBeforeReload) clearReloadState(true);
            return;
        }
        if (!requested) return;
        if (minecraft.level == null) {
            TfcVoxyBridge.rendererReloaded();
            return;
        }
        if (minecraft.levelRenderer == null) return;
        Object before = voxyRenderer(minecraft.levelRenderer);
        if (before == RENDERER_UNAVAILABLE) return;
        if (!RoxyVoxyRendererReloadCompat.deferVoxyReload(minecraft.levelRenderer)) return;
        reloadQueued = true;
        reloadWorld = minecraft.level;
        reloadLevelRenderer = minecraft.levelRenderer;
        rendererBeforeReload = before;
        queuedEnabled = TfcCompatConfig.enabled();
    }

    private static Object voxyRenderer(Object levelRenderer) {
        try {
            return levelRenderer.getClass().getMethod("voxy$getRenderSystem").invoke(levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return RENDERER_UNAVAILABLE;
        }
    }

    private static void clearReloadState(boolean completed) {
        reloadQueued = false;
        reloadWorld = null;
        reloadLevelRenderer = null;
        rendererBeforeReload = null;
        queuedEnabled = false;
        if (completed) TfcVoxyBridge.rendererReloaded();
    }

    public static void update(Object currentWorld, net.minecraft.client.gui.components.BossHealthOverlay overlay,
                              long[] progress, long now) {
        int backfillPending = RoxyTfcBackfill.pendingCount();
        if (!TfcCompatConfig.enabled() || !TfcCompatConfig.showProgress()) {
            remove(overlay);
            VISIBILITY.reset(currentWorld);
            world = currentWorld;
            return;
        }
        boolean shouldShow = VISIBILITY.update(currentWorld, true, true, progress, backfillPending, now);
        if (world != currentWorld) {
            remove(overlay);
            world = currentWorld;
        }
        if (!shouldShow || currentWorld == null) {
            remove(overlay);
            return;
        }
        long done = progress.length > 1 ? progress[1] : 0;
        long sampled = progress.length > 2 ? progress[2] : 0;
        long unknown = progress.length > 3 ? progress[3] : 0;
        long total = sampled;
        Component title = Component.translatable("roxy.tfc.progress", done, total);
        if (backfillPending > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.reads", backfillPending));
        long meshPending = progress.length > 6 ? progress[6] : 0;
        if (meshPending > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.meshes", meshPending));
        if (unknown > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.missing", unknown));
        float fraction = total <= 0 ? 0 : Math.min(1, done / (float) total);
        var event = new LerpingBossEvent(ID, title, fraction,
                unknown > 0 ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        // Update only the local HUD; no boss event is sent to the server or other players.
        overlay.update(ClientboundBossEventPacket.createAddPacket(event));
        visible = true;
    }

    private static void hide(Minecraft minecraft) {
        remove(minecraft.gui.getBossOverlay());
    }

    private static void remove(net.minecraft.client.gui.components.BossHealthOverlay overlay) {
        if (visible) overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
        visible = false;
    }

    static final class Visibility {
        private Object world;
        private boolean visible;
        private long hideAt = Long.MAX_VALUE;
        private long revision = Long.MIN_VALUE;
        private long done = -1, sampled = -1, unknown = -1, meshPending = -1, backfillPending = -1;
        private boolean scanCompleteSeen;

        boolean update(Object currentWorld, boolean enabled, boolean show, long[] progress,
                       long pendingClimate, long now) {
            if (world != currentWorld) reset(currentWorld);
            if (currentWorld == null || !enabled || !show) {
                reset(currentWorld);
                return false;
            }
            long currentRevision = progress.length > 0 ? progress[0] : Long.MIN_VALUE;
            long currentDone = progress.length > 1 ? progress[1] : 0;
            long currentSampled = progress.length > 2 ? progress[2] : 0;
            long currentUnknown = progress.length > 3 ? progress[3] : 0;
            long currentMeshPending = progress.length > 6 ? progress[6] : 0;
            boolean currentWorking = progress.length > 4 && progress[4] != 0;
            boolean scanComplete = progress.length > 7 && progress[7] != 0;
            boolean workChanged = currentRevision != revision
                    || currentSampled != sampled || currentUnknown != unknown
                    || currentDone < done && currentSampled >= sampled
                    || currentMeshPending > meshPending || pendingClimate > backfillPending;
            if (workChanged) {
                hideAt = Long.MAX_VALUE;
                scanCompleteSeen = false;
            }
            scanCompleteSeen |= scanComplete;
            revision = currentRevision;
            done = currentDone;
            sampled = currentSampled;
            unknown = currentUnknown;
            meshPending = currentMeshPending;
            backfillPending = pendingClimate;

            boolean complete = scanCompleteSeen && currentDone >= currentSampled
                    && currentMeshPending == 0 && pendingClimate == 0;
            boolean hasWork = currentWorking || currentDone > 0
                    || currentSampled > 0 || currentUnknown > 0 || currentMeshPending > 0 || pendingClimate > 0;
            if (!hasWork && !visible) return false;
            if (!complete) {
                hideAt = Long.MAX_VALUE;
                visible = true;
                return true;
            }
            if (!visible) {
                if (!workChanged) return false;
                visible = true;
            }
            if (hideAt == Long.MAX_VALUE) hideAt = now + 5_000;
            if (now >= hideAt) {
                visible = false;
                return false;
            }
            return true;
        }

        void reset(Object currentWorld) {
            world = currentWorld;
            visible = false;
            hideAt = Long.MAX_VALUE;
            revision = Long.MIN_VALUE;
            done = sampled = unknown = meshPending = backfillPending = -1;
            scanCompleteSeen = false;
        }
    }
}
