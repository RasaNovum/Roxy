package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyChunksmithCompat {
    private static final int QUEUE_LIMIT = 512;
    private static final long CAPACITY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final long RENDERER_READY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long RENDERER_SETTLE_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final AtomicBoolean AVAILABILITY_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CHUNKY_FAILURE_LOGGED = new AtomicBoolean();
    private static final Object RENDERER_READY_LOCK = new Object();
    private static volatile Methods methods;
    private static volatile ChunkyAccess chunkyAccess;
    private static volatile ConfigAccess configAccess;
    private static volatile boolean configLookupFailed;
    private static volatile WeakReference<Object> readyRenderer = new WeakReference<>(null);

    private RoxyChunksmithCompat() {
    }

    public static void resetRendererState() {
        readyRenderer = new WeakReference<>(null);
    }

    public static boolean available(ClassLoader loader) {
        try {
            methods(loader);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (AVAILABILITY_FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Roxy could not resolve Voxy's Chunksmith ingestion API", exception);
            }
            return false;
        }
    }

    public static int configuredRadiusBlocks() {
        ConfigAccess access = resolveConfigAccess();
        if (access == null) return 0;
        try {
            Object config = access.config.get(null);
            if (config == null
                    || !access.enabled.getBoolean(config)
                    || !access.enableRendering.getBoolean(config)) {
                return 0;
            }
            Number sections = (Number) access.sectionRenderDistance.get(config);
            if (sections == null) return 0;
            double blocks = sections.doubleValue() * 512.0;
            if (!Double.isFinite(blocks) || blocks <= 0.0) return 0;
            return blocks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(blocks);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return 0;
        }
    }

    private static ConfigAccess resolveConfigAccess() {
        ConfigAccess current = configAccess;
        if (current != null || configLookupFailed) return current;
        synchronized (RoxyChunksmithCompat.class) {
            current = configAccess;
            if (current != null || configLookupFailed) return current;
            try {
                ClassLoader loader = RoxyClassLoaders.tryFindLoaderFor("me.cortex.voxy.client.config.VoxyConfig");
                if (loader == null) return null;
                Class<?> configClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig", false, loader);
                current = new ConfigAccess(
                        configClass.getField("CONFIG"),
                        configClass.getField("enabled"),
                        configClass.getField("enableRendering"),
                        configClass.getField("sectionRenderDistance")
                );
                configAccess = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                configLookupFailed = true;
                return null;
            }
        }
    }

    public static int inject(Object level, Object record) {
        if (level == null || record == null) return 0;
        try {
            Methods resolved = methods(record.getClass().getClassLoader());
            Object world = resolved.worldIdentifierOf.invoke(null, level);
            if (world == null) return 0;
            List<?> sections = (List<?>) resolved.recordSections.invoke(record);
            if (sections == null || sections.isEmpty()) return 0;
            int chunkX = (int) resolved.recordChunkX.invoke(record);
            int chunkZ = (int) resolved.recordChunkZ.invoke(record);
            int minSectionY = (int) resolved.recordMinSectionY.invoke(record);
            int ingested = 0;

            awaitRendererReady(resolved);
            for (int index = 0; index < sections.size(); index++) {
                awaitCapacity(resolved);
                Object section = sections.get(index);
                if (section == null) continue;
                Object rebuilt = resolved.rebuild.invoke(null, level, record, section);
                if (rebuilt == null) continue;
                Object sky = light(
                        resolved,
                        (byte[]) resolved.sectionSkyLight.invoke(section),
                        (int) resolved.sectionUniformSky.invoke(section)
                );
                Object block = light(
                        resolved,
                        (byte[]) resolved.sectionBlockLight.invoke(section),
                        (int) resolved.sectionUniformBlockLight.invoke(section)
                );
                boolean accepted = (boolean) resolved.rawIngest.invoke(
                        null,
                        world,
                        rebuilt,
                        chunkX,
                        minSectionY + index,
                        chunkZ,
                        block,
                        sky
                );
                if (accepted) {
                    ingested++;
                }
            }
            return ingested;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw new IllegalStateException("Roxy could not feed Chunksmith LoD data into Voxy", RoxyReflection.rootCause(exception));
        }
    }

    public static void ingestChunkyChunk(Object chunkyWorld, int chunkX, int chunkZ) {
        if (chunkyWorld == null) return;
        try {
            ChunkyAccess access = resolveChunkyAccess(chunkyWorld);
            Object level = access.worldField.get(chunkyWorld);
            if (level == null) return;
            Object server = access.getServer.invoke(level);
            if (server == null) return;
            access.execute.invoke(server, (Runnable) () -> {
                try {
                    Object chunkSource = access.getChunkSource.invoke(level);
                    Object chunk = chunkSource == null ? null : access.getChunkNow.invoke(chunkSource, chunkX, chunkZ);
                    if (chunk == null) return;
                    access.tryAutoIngestChunk.invoke(null, chunk);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                    if (CHUNKY_FAILURE_LOGGED.compareAndSet(false, true)) {
                        LOGGER.warn("Roxy could not feed a Chunky chunk into Voxy", RoxyReflection.unwrap(exception));
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (CHUNKY_FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Roxy could not access Chunky's world", RoxyReflection.unwrap(exception));
            }
        }
    }

    private static ChunkyAccess resolveChunkyAccess(Object chunkyWorld) throws ReflectiveOperationException {
        ChunkyAccess current = chunkyAccess;
        if (current != null && current.worldClass == chunkyWorld.getClass()) return current;
        synchronized (RoxyChunksmithCompat.class) {
            current = chunkyAccess;
            if (current != null && current.worldClass == chunkyWorld.getClass()) return current;
            Field worldField = RoxyReflection.findField(chunkyWorld.getClass(), "world");
            Object level = worldField.get(chunkyWorld);
            if (level == null) {
                throw new IllegalStateException("Chunky world has no active level");
            }
            Method getServer = RoxyReflection.findMethod(level.getClass(), "getServer");
            Object server = getServer.invoke(level);
            if (server == null) {
                throw new IllegalStateException("Chunky level has no active server");
            }
            Method execute = RoxyReflection.findMethod(server.getClass(), "execute", Runnable.class);
            Method getChunkSource = RoxyReflection.findMethod(level.getClass(), "getChunkSource");
            Object chunkSource = getChunkSource.invoke(level);
            Method getChunkNow = RoxyReflection.findMethod(chunkSource.getClass(), "getChunkNow", int.class, int.class);
            ClassLoader loader = RoxyClassLoaders.findLoaderFor(
                    "me.cortex.voxy.common.world.service.VoxelIngestService",
                    chunkyWorld.getClass().getClassLoader(),
                    level.getClass().getClassLoader()
            );
            Class<?> ingestService = Class.forName(
                    "me.cortex.voxy.common.world.service.VoxelIngestService",
                    false,
                    loader
            );
            Class<?> levelChunk = RoxyClassLoaders.loadMinecraftClass(
                    "net.minecraft.world.level.chunk.LevelChunk",
                    loader
            );
            Method tryAutoIngestChunk = ingestService.getMethod("tryAutoIngestChunk", levelChunk);
            current = new ChunkyAccess(
                    chunkyWorld.getClass(),
                    worldField,
                    getServer,
                    execute,
                    getChunkSource,
                    getChunkNow,
                    tryAutoIngestChunk
            );
            chunkyAccess = current;
            return current;
        }
    }

    private static Object light(Methods resolved, byte[] packed, int uniform) throws ReflectiveOperationException {
        if (packed != null) {
            return resolved.dataLayerBytes.newInstance((Object) packed.clone());
        }
        return uniform > 0
                ? resolved.dataLayerUniform.newInstance(uniform)
                : resolved.dataLayerEmpty.newInstance();
    }

    private static void awaitCapacity(Methods resolved) throws ReflectiveOperationException, InterruptedException {
        long deadline = System.nanoTime() + CAPACITY_TIMEOUT_NANOS;
        while (System.nanoTime() - deadline < 0L) {
            Object minecraft = resolved.minecraftInstance.invoke(null);
            if (minecraft == null || resolved.level.get(minecraft) == null) return;
            Object instance = resolved.voxyInstance.invoke(null);
            if (instance == null) return;
            Object ingestService = resolved.ingestService.invoke(instance);
            if (ingestService == null) return;
            Queue<?> ingestQueue = (Queue<?>) resolved.ingestQueue.get(ingestService);
            if (ingestQueue == null) return;
            if (ingestQueue.size() <= QUEUE_LIMIT
                    && (int) resolved.taskCount.invoke(ingestService) <= QUEUE_LIMIT) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    private static void awaitRendererReady(Methods resolved) throws ReflectiveOperationException, InterruptedException {
        Object minecraft = resolved.minecraftInstance.invoke(null);
        if (minecraft == null || resolved.level.get(minecraft) == null) return;
        Object levelRenderer = resolved.levelRenderer.get(minecraft);
        if (levelRenderer == null) return;
        Object initial = resolved.getRenderSystem.invoke(levelRenderer);
        if (initial != null && initial == readyRenderer.get()) return;

        synchronized (RENDERER_READY_LOCK) {
            Object renderer = resolved.getRenderSystem.invoke(levelRenderer);
            if (renderer != null && renderer == readyRenderer.get()) return;

            long now = System.nanoTime();
            long deadline = now + RENDERER_READY_TIMEOUT_NANOS;
            long earliestReady = now + RENDERER_SETTLE_NANOS;
            int idlePolls = 0;
            while (System.nanoTime() - deadline < 0L) {
                if (resolved.level.get(minecraft) == null) return;
                Object current = resolved.getRenderSystem.invoke(levelRenderer);
                if (current != renderer) {
                    renderer = current;
                    idlePolls = 0;
                    earliestReady = System.nanoTime() + RENDERER_SETTLE_NANOS;
                }
                if (renderer == null) {
                    Thread.sleep(100L);
                    continue;
                }

                Object nodeManager = resolved.renderNodeManager.get(renderer);
                Object renderGeneration = resolved.renderGeneration.get(renderer);
                Object modelService = resolved.modelService.get(renderer);
                boolean busy = nodeManager == null
                        || renderGeneration == null
                        || modelService == null
                        || (boolean) resolved.nodeManagerHasWork.invoke(nodeManager)
                        || (int) resolved.renderTaskCount.invoke(renderGeneration) != 0
                        || !(boolean) resolved.modelQueuesEmpty.invoke(modelService);
                if (!busy && System.nanoTime() - earliestReady >= 0L) {
                    if (++idlePolls >= 10) {
                        readyRenderer = new WeakReference<>(renderer);
                        return;
                    }
                } else {
                    idlePolls = 0;
                }
                Thread.sleep(100L);
            }

            LOGGER.warn("Roxy timed out waiting for Voxy's initial renderer work before Chunksmith injection");
            readyRenderer = new WeakReference<>(renderer);
        }
    }

    private static Methods methods(ClassLoader loader) throws ReflectiveOperationException {
        Methods cached = methods;
        if (cached != null) return cached;
        synchronized (RoxyChunksmithCompat.class) {
            if (methods == null) {
                methods = Methods.resolve(RoxyClassLoaders.findGameLoader(loader));
            }
            return methods;
        }
    }

    private record ConfigAccess(Field config, Field enabled, Field enableRendering, Field sectionRenderDistance) {
    }

    private record ChunkyAccess(
            Class<?> worldClass,
            Field worldField,
            Method getServer,
            Method execute,
            Method getChunkSource,
            Method getChunkNow,
            Method tryAutoIngestChunk
    ) {
    }

    private record Methods(
            Method voxyInstance,
            Method ingestService,
            Method taskCount,
            Field ingestQueue,
            Method minecraftInstance,
            Field level,
            Field levelRenderer,
            Method getRenderSystem,
            Field renderNodeManager,
            Field renderGeneration,
            Field modelService,
            Method nodeManagerHasWork,
            Method renderTaskCount,
            Method modelQueuesEmpty,
            Method worldIdentifierOf,
            Method recordSections,
            Method recordChunkX,
            Method recordChunkZ,
            Method recordMinSectionY,
            Method sectionSkyLight,
            Method sectionUniformSky,
            Method sectionBlockLight,
            Method sectionUniformBlockLight,
            Method rebuild,
            Method rawIngest,
            Constructor<?> dataLayerEmpty,
            Constructor<?> dataLayerBytes,
            Constructor<?> dataLayerUniform
    ) {
        private static Methods resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> voxyCommon = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            Class<?> voxyInstance = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance", false, loader);
            Class<?> ingestService = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", false, loader);
            Class<?> worldIdentifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            Class<?> record = Class.forName("com.kishku7.chunksmith.lod.CsLodChunk", false, loader);
            Class<?> section = Class.forName("com.kishku7.chunksmith.lod.CsLodChunk$Section", false, loader);
            Class<?> builder = Class.forName("com.kishku7.chunksmith.lod.CsLodSectionBuilder", false, loader);
            Class<?> level = RoxyClassLoaders.loadMinecraftClass("net.minecraft.world.level.Level", loader);
            Class<?> levelChunkSection = RoxyClassLoaders.loadMinecraftClass("net.minecraft.world.level.chunk.LevelChunkSection", loader);
            Class<?> dataLayer = RoxyClassLoaders.loadMinecraftClass("net.minecraft.world.level.chunk.DataLayer", loader);
            Class<?> minecraft = RoxyClassLoaders.loadMinecraftClass("net.minecraft.client.Minecraft", loader);
            Class<?> renderSystemBridge = Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem", false, loader);
            Class<?> renderSystem = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader);
            Class<?> asyncNodeManager = Class.forName("me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager", false, loader);
            Class<?> renderGeneration = Class.forName("me.cortex.voxy.client.core.rendering.building.RenderGenerationService", false, loader);
            Class<?> modelService = Class.forName("me.cortex.voxy.client.core.model.ModelBakerySubsystem", false, loader);

            return new Methods(
                    voxyCommon.getMethod("getInstance"),
                    voxyInstance.getMethod("getIngestService"),
                    ingestService.getMethod("getTaskCount"),
                    RoxyReflection.findField(ingestService, "ingestQueue"),
                    RoxyReflection.findMethod(minecraft, "getInstance"),
                    RoxyReflection.findField(minecraft, "level"),
                    RoxyReflection.findField(minecraft, "levelRenderer"),
                    renderSystemBridge.getMethod("voxy$getRenderSystem"),
                    RoxyReflection.findField(renderSystem, "nodeManager"),
                    RoxyReflection.findField(renderSystem, "renderGen"),
                    RoxyReflection.findField(renderSystem, "modelService"),
                    asyncNodeManager.getMethod("hasWork"),
                    renderGeneration.getMethod("getTaskCount"),
                    modelService.getMethod("areQueuesEmpty"),
                    worldIdentifier.getMethod("of", level),
                    record.getMethod("getSections"),
                    record.getMethod("getChunkX"),
                    record.getMethod("getChunkZ"),
                    record.getMethod("getMinSectionY"),
                    section.getMethod("getSkyLight"),
                    section.getMethod("getUniformSky"),
                    section.getMethod("getBlockLight"),
                    section.getMethod("getUniformBlockLight"),
                    builder.getMethod("rebuild", level, record, section),
                    ingestService.getMethod(
                            "rawIngest",
                            worldIdentifier,
                            levelChunkSection,
                            int.class,
                            int.class,
                            int.class,
                            dataLayer,
                            dataLayer
                    ),
                    dataLayer.getConstructor(),
                    dataLayer.getConstructor(byte[].class),
                    dataLayer.getConstructor(int.class)
            );
        }
    }
}
