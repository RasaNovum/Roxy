package net.rasanovum.roxyhost.tfc;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;

public final class RoxyTfcProgress {
    private static final UUID ID = UUID.fromString("eab352d0-4f29-4ade-9832-3a0fa9ee208b");
    private static Object world;
    private static boolean visible;
    private static int ticks;
    private static long hideAt = Long.MAX_VALUE;

    private RoxyTfcProgress() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        RoxyTfcBackfill.resetWorld(minecraft.level);
        if (world == minecraft.level && ++ticks % 5 != 0) return;
        update(minecraft.level, minecraft.gui.getBossOverlay(), TfcVoxyBridge.refreshProgress(), System.currentTimeMillis());
    }

    public static void update(Object currentWorld, net.minecraft.client.gui.components.BossHealthOverlay overlay,
                              long[] progress, long now) {
        if (world != currentWorld) {
            overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
            world = currentWorld;
            visible = false;
            hideAt = Long.MAX_VALUE;
        }
        if (world == null) return;
        boolean active = progress[4] != 0 || RoxyTfcBackfill.pendingCount() > 0;
        if (!active && !visible) return;
        if (active) hideAt = Long.MAX_VALUE;
        else if (hideAt == Long.MAX_VALUE) hideAt = now + 3000;
        if (now >= hideAt) {
            overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
            visible = false;
            return;
        }
        long total = progress[2] + progress[3];
        Component title = Component.translatable("roxy.tfc.progress", progress[1], total);
        if (RoxyTfcBackfill.pendingCount() > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.reads", RoxyTfcBackfill.pendingCount()));
        if (progress[6] > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.meshes", progress[6]));
        if (progress[3] > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.missing", progress[3]));
        float fraction = total <= 0 ? 0 : Math.min(1, progress[1] / (float) total);
        var event = new LerpingBossEvent(ID, title, fraction,
                progress[3] > 0 ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        // Update only the local HUD; no boss event is sent to the server or other players.
        overlay.update(ClientboundBossEventPacket.createAddPacket(event));
        visible = true;
    }
}
