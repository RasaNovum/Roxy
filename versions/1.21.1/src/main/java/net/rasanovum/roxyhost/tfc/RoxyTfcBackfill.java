package net.rasanovum.roxyhost.tfc;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;
import org.slf4j.LoggerFactory;

/** Reads saved climate attachments without loading or generating Minecraft chunks. */
public final class RoxyTfcBackfill {
    private static final int MAX_QUEUED = 8192, MAX_IN_FLIGHT = 32, MAX_MISSING = 131072;
    private static final LinkedHashSet<Long> near = new LinkedHashSet<>(), far = new LinkedHashSet<>();
    private static final LinkedHashSet<Long> missing = new LinkedHashSet<>();
    private static final Set<Long> inFlight = new HashSet<>();
    private static ClientLevel world;
    private static long epoch;
    private static int cameraX, cameraZ;
    private static boolean warned, enabled;

    private RoxyTfcBackfill() {}

    public static synchronized void request(Object level, int x, int z) {
        if (!enabled || level != world || world == null || distance(x, z) > 512L * 512) return;
        long key = ChunkPos.asLong(x, z);
        if (missing.contains(key) || inFlight.contains(key) || near.contains(key) || far.contains(key)) return;
        boolean close = distance(x, z) <= 128L * 128;
        if (near.size() + far.size() >= MAX_QUEUED) {
            if (!close || far.isEmpty()) return;
            poll(far);
        }
        (close ? near : far).add(key);
    }

    public static synchronized void retryMissing() { missing.clear(); }

    public static synchronized int pendingCount() { return enabled ? near.size() + far.size() + inFlight.size() : 0; }

    public static void tick() { tick(Minecraft.getInstance().level); }

    public static synchronized void resetWorld(Object level) {
        if (world == level) return;
        world = level instanceof ClientLevel client ? client : null;
        epoch++;
        near.clear(); far.clear(); missing.clear(); inFlight.clear(); warned = false; enabled = false;
    }

    public static synchronized void tick(Object level) {
        Minecraft minecraft = Minecraft.getInstance();
        resetWorld(level);
        var server = minecraft.getSingleplayerServer();
        enabled = world != null && minecraft.level == world && server != null;
        if (!enabled) return;
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        cameraX = net.minecraft.util.Mth.floor(camera.x) >> 4;
        cameraZ = net.minecraft.util.Mth.floor(camera.z) >> 4;
        ClientLevel expected = world;
        long generation = epoch;
        for (int started = 0, inspected = 0; started < 8 && inspected < 128 && inFlight.size() < MAX_IN_FLIGHT; inspected++) {
            Long key = poll(near);
            if (key == null) key = poll(far);
            if (key == null) break;
            ChunkPos pos = new ChunkPos(key);
            if (distance(pos.x, pos.z) > 512L * 512) continue;
            inFlight.add(key);
            started++;
            server.execute(() -> {
                if (!current(expected, generation)) return;
                var savedLevel = server.getLevel(expected.dimension());
                if (savedLevel == null) {
                    minecraft.execute(() -> complete(expected, generation, pos, Optional.empty(), null));
                    return;
                }
                try {
                    savedLevel.getChunkSource().chunkMap.read(pos).whenComplete((data, failure) ->
                            minecraft.execute(() -> complete(expected, generation, pos, data, failure)));
                } catch (RuntimeException failure) {
                    minecraft.execute(() -> complete(expected, generation, pos, Optional.empty(), failure));
                }
            });
        }
    }

    private static synchronized boolean current(ClientLevel expected, long generation) {
        return world == expected && epoch == generation;
    }

    private static void complete(ClientLevel expected, long generation, ChunkPos pos,
                                 Optional<CompoundTag> data, Throwable failure) {
        float[] snapshot = null;
        synchronized (RoxyTfcBackfill.class) {
            if (!current(expected, generation) || Minecraft.getInstance().level != expected) return;
            inFlight.remove(pos.toLong());
            if (failure == null && data != null && data.isPresent()) {
                CompoundTag root = data.get();
                if (root.getInt("xPos") == pos.x && root.getInt("zPos") == pos.z) snapshot = RoxyTfcImport.decode(root);
            }
            if (snapshot == null) {
                if (missing.size() >= MAX_MISSING) poll(missing);
                missing.add(pos.toLong());
            }
            if (failure != null && !warned) {
                warned = true;
                LoggerFactory.getLogger("Roxy").debug("Could not read saved TFC climate; missing chunks will be skipped", failure);
            }
        }
        if (snapshot != null) TfcVoxyBridge.capture(expected, pos.x, pos.z, snapshot);
    }

    private static long distance(int x, int z) {
        long dx = (long)x - cameraX, dz = (long)z - cameraZ;
        return dx * dx + dz * dz;
    }

    private static Long poll(LinkedHashSet<Long> queue) {
        Iterator<Long> iterator = queue.iterator();
        if (!iterator.hasNext()) return null;
        Long key = iterator.next(); iterator.remove(); return key;
    }
}
