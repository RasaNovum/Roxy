package net.rasanovum.roxyhost.tfc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class RoxyTfcTerrainRefresh {
    private static final long CALENDAR_TICKS_PER_DAY = 24_000L;
    private static final int CHUNK_PROBES_PER_TICK = 32;
    private static final int SECTIONS_PER_TICK = 8;
    private static final int SECTION_CHECKS_PER_TICK = 64;
    private static final int MAX_PENDING_SECTIONS = 512;
    private static final int MAX_RENDER_RADIUS = 64;
    private static ClientLevel world;
    private static boolean initialized;
    private static long lastCalendarTicks;
    private static long lastCalendarDay;
    private static long lastDaysInMonth;
    private static int centerX;
    private static int centerZ;
    private static int radius;
    private static int chunkCursor;
    private static boolean sweeping;
    private static boolean rerunSweep;
    private static List<Integer> chunks = List.of();
    private static final ArrayDeque<Long> pending = new ArrayDeque<>();
    private static int currentChunkX;
    private static int currentChunkZ;
    private static int currentMinSection;
    private static int currentSectionCursor;
    private static boolean[] currentNonEmptySections;

    private RoxyTfcTerrainRefresh() {}

    public static synchronized void tick(Object level, long calendarTicks, long daysInMonth) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return;
        if (!(level instanceof ClientLevel clientLevel) || minecraft.level != clientLevel
                || daysInMonth < 1 || daysInMonth > Integer.MAX_VALUE) {
            reset();
            return;
        }

        int[] camera = camera(minecraft);
        long calendarDay = Math.floorDiv(calendarTicks, CALENDAR_TICKS_PER_DAY);
        if (!initialized || world != clientLevel) {
            bindWorld(clientLevel, camera, calendarTicks, calendarDay, daysInMonth);
            return;
        }

        boolean changed = calendarChanged(true, lastCalendarDay, lastCalendarTicks, lastDaysInMonth,
                calendarDay, calendarTicks, daysInMonth);
        lastCalendarTicks = calendarTicks;
        lastCalendarDay = calendarDay;
        lastDaysInMonth = daysInMonth;
        if (changed) requestSweep(minecraft, camera);

        if (!sweeping || minecraft.level != world || minecraft.levelRenderer == null) return;
        if (cameraMovedTooFar(camera)) rerunSweep = true;

        scanLoadedChunks(minecraft, CHUNK_PROBES_PER_TICK);
        schedulePendingSections(minecraft, camera, SECTIONS_PER_TICK);
        if (chunkCursor >= chunks.size() && currentNonEmptySections == null && pending.isEmpty()) {
            if (rerunSweep) {
                rerunSweep = false;
                startSweep(minecraft, camera);
            } else {
                finishSweep();
            }
        }
    }

    public static synchronized boolean forceRefresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return false;
        ClientLevel clientLevel = minecraft.level;
        if (clientLevel == null || minecraft.levelRenderer == null) return false;
        int[] camera = camera(minecraft);
        if (!initialized || world != clientLevel) {
            bindWorld(clientLevel, camera, lastCalendarTicks, lastCalendarDay, lastDaysInMonth);
        }
        requestSweep(minecraft, camera);
        return true;
    }

    public static synchronized void disable() {
        reset();
    }

    public static synchronized void reset() {
        world = null;
        initialized = false;
        lastCalendarTicks = 0;
        lastCalendarDay = 0;
        lastDaysInMonth = 0;
        centerX = centerZ = radius = chunkCursor = 0;
        sweeping = false;
        rerunSweep = false;
        chunks = List.of();
        pending.clear();
        currentNonEmptySections = null;
        currentSectionCursor = 0;
    }

    private static void bindWorld(ClientLevel clientLevel, int[] camera,
                                  long calendarTicks, long calendarDay, long daysInMonth) {
        world = clientLevel;
        initialized = true;
        lastCalendarTicks = calendarTicks;
        lastCalendarDay = calendarDay;
        lastDaysInMonth = daysInMonth;
        centerX = camera[0];
        centerZ = camera[1];
        sweeping = false;
        rerunSweep = false;
        chunks = List.of();
        chunkCursor = 0;
        pending.clear();
        currentNonEmptySections = null;
        currentSectionCursor = 0;
        rerunSweep = false;
    }

    private static void startSweep(Minecraft minecraft, int[] camera) {
        centerX = camera[0];
        centerZ = camera[1];
        radius = Math.min(MAX_RENDER_RADIUS, Math.max(1, minecraft.options.getEffectiveRenderDistance() + 2));
        chunks = nearestChunks(radius);
        chunkCursor = 0;
        pending.clear();
        currentNonEmptySections = null;
        currentSectionCursor = 0;
        sweeping = true;
    }

    private static void requestSweep(Minecraft minecraft, int[] camera) {
        if (sweeping) rerunSweep = true;
        else startSweep(minecraft, camera);
    }

    private static boolean cameraMovedTooFar(int[] camera) {
        int threshold = Math.max(4, radius / 2);
        long dx = (long) camera[0] - centerX;
        long dz = (long) camera[1] - centerZ;
        return dx * dx + dz * dz > (long) threshold * threshold;
    }

    private static void scanLoadedChunks(Minecraft minecraft, int budget) {
        ClientLevel clientLevel = world;
        if (clientLevel == null || minecraft.level != clientLevel) return;
        ClientChunkCache chunkCache = clientLevel.getChunkSource();
        int scanned = 0;
        while (scanned < budget && (currentNonEmptySections != null || chunkCursor < chunks.size())
                && pending.size() < MAX_PENDING_SECTIONS) {
            if (currentNonEmptySections == null) {
                int offset = chunks.get(chunkCursor++);
                scanned++;
                currentChunkX = centerX + (offset >>> 16) - 128;
                currentChunkZ = centerZ + (offset & 0xffff) - 128;
                LevelChunk chunk = chunkCache.getChunk(currentChunkX, currentChunkZ, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) continue;
                LevelChunkSection[] sections = chunk.getSections();
                currentMinSection = chunk.getMinSection();
                currentNonEmptySections = new boolean[sections.length];
                for (int index = 0; index < sections.length; index++) {
                    currentNonEmptySections[index] = sections[index] != null && !sections[index].hasOnlyAir();
                }
            }

            LevelChunk current = chunkCache.getChunk(currentChunkX, currentChunkZ, ChunkStatus.FULL, false);
            if (current == null || current.isEmpty()) {
                currentNonEmptySections = null;
                currentSectionCursor = 0;
                continue;
            }
            currentSectionCursor = queueNonEmptySections(currentNonEmptySections, currentSectionCursor,
                    currentChunkX, currentMinSection, currentChunkZ, pending, MAX_PENDING_SECTIONS);
            if (currentSectionCursor >= currentNonEmptySections.length) {
                currentNonEmptySections = null;
                currentSectionCursor = 0;
            } else {
                return;
            }
        }
    }

    private static int queueNonEmptySections(boolean[] nonEmpty, int startIndex, int sectionX, int minSection,
                                             int sectionZ, ArrayDeque<Long> output, int capacity) {
        int index = startIndex;
        while (index < nonEmpty.length) {
            if (nonEmpty[index]) {
                if (output.size() >= capacity) break;
                output.addLast(SectionPos.asLong(sectionX, minSection + index, sectionZ));
            }
            index++;
        }
        return index;
    }

    private static void schedulePendingSections(Minecraft minecraft, int[] camera, int budget) {
        ClientLevel clientLevel = world;
        LevelRenderer renderer = minecraft.levelRenderer;
        if (clientLevel == null || renderer == null || minecraft.level != clientLevel) return;
        ClientChunkCache chunkCache = clientLevel.getChunkSource();
        int scheduled = 0;
        int checked = 0;
        while (scheduled < budget && checked < SECTION_CHECKS_PER_TICK && !pending.isEmpty()) {
            long section = pending.removeFirst();
            checked++;
            int sectionX = SectionPos.x(section);
            int sectionY = SectionPos.y(section);
            int sectionZ = SectionPos.z(section);
            if (!withinRadius(sectionX, sectionZ, camera)) continue;
            LevelChunk chunk = chunkCache.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;
            int index = sectionY - chunk.getMinSection();
            LevelChunkSection[] sections = chunk.getSections();
            if (index < 0 || index >= sections.length || sections[index] == null || sections[index].hasOnlyAir()) continue;
            renderer.setSectionDirty(sectionX, sectionY, sectionZ);
            scheduled++;
        }
    }

    private static boolean withinRadius(int x, int z, int[] camera) {
        long dx = (long) x - camera[0];
        long dz = (long) z - camera[1];
        long limit = (long) radius + 2;
        return dx * dx + dz * dz <= limit * limit;
    }

    private static void finishSweep() {
        sweeping = false;
        chunks = List.of();
        chunkCursor = 0;
        currentNonEmptySections = null;
        currentSectionCursor = 0;
    }

    private static List<Integer> nearestChunks(int radius) {
        int side = radius * 2 + 1;
        ArrayList<Integer> result = new ArrayList<>(side * side);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                result.add(((x + 128) << 16) | (z + 128));
            }
        }
        result.sort(Comparator.comparingLong((Integer offset) -> {
            int x = (offset >>> 16) - 128;
            int z = (offset & 0xffff) - 128;
            return (long) x * x + (long) z * z;
        }).thenComparingInt(Integer::intValue));
        return result;
    }

    private static int[] camera(Minecraft minecraft) {
        var position = minecraft.gameRenderer.getMainCamera().getPosition();
        return new int[]{Mth.floor(position.x) >> 4, Mth.floor(position.z) >> 4};
    }

    private static boolean calendarChanged(boolean initialized, long previousDay, long previousTicks,
                                           long previousDaysInMonth, long currentDay, long currentTicks,
                                           long currentDaysInMonth) {
        return initialized && (currentDay > previousDay || currentTicks < previousTicks
                || currentDaysInMonth != previousDaysInMonth);
    }

}
