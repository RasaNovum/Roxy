package net.rasanovum.roxy.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxyChunksmithCompat {
    private static final int QUEUE_LIMIT = 512;
    private static final long RENDERER_READY_TIMEOUT_MILLIS = 60_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final AtomicBoolean AVAILABILITY_FAILURE_LOGGED = new AtomicBoolean();
    private static final Object RENDERER_READY_LOCK = new Object();
    private static volatile Methods methods;
    private static volatile Object readyRenderer;

    private RoxyChunksmithCompat() {
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
        try {
            Class<?> configClass = Class.forName(
                    "me.cortex.voxy.client.config.VoxyConfig",
                    true,
                    Thread.currentThread().getContextClassLoader()
            );
            Object config = configClass.getField("CONFIG").get(null);
            if (config == null
                    || !configClass.getField("enabled").getBoolean(config)
                    || !configClass.getField("enableRendering").getBoolean(config)) {
                return 0;
            }
            Number sections = (Number) configClass.getField("sectionRenderDistance").get(config);
            double blocks = sections.doubleValue() * 512.0;
            return blocks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(blocks);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return 0;
        }
    }

    public static int inject(Object level, Object record) {
        try {
            Methods resolved = methods(record.getClass().getClassLoader());
            Object world = resolved.worldIdentifierOf.invoke(null, level);
            List<?> sections = (List<?>) resolved.recordSections.invoke(record);
            int chunkX = (int) resolved.recordChunkX.invoke(record);
            int chunkZ = (int) resolved.recordChunkZ.invoke(record);
            int minSectionY = (int) resolved.recordMinSectionY.invoke(record);
            int ingested = 0;

            awaitRendererReady(resolved);
            for (int index = 0; index < sections.size(); index++) {
                awaitCapacity(resolved);
                Object section = sections.get(index);
                Object rebuilt = resolved.rebuild.invoke(null, level, record, section);
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
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Roxy could not feed Chunksmith LoD data into Voxy", unwrap(exception));
        }
    }

    public static void ingestChunkyChunk(Object chunkyWorld, int chunkX, int chunkZ) {
        try {
            Field worldField = chunkyWorld.getClass().getDeclaredField("world");
            worldField.setAccessible(true);
            Object level = worldField.get(chunkyWorld);
            Method getServer = level.getClass().getMethod("getServer");
            Object server = getServer.invoke(level);
            Method execute = server.getClass().getMethod("execute", Runnable.class);
            execute.invoke(server, (Runnable) () -> {
                try {
                    Method getChunk = level.getClass().getMethod("getChunk", int.class, int.class);
                    Object chunk = getChunk.invoke(level, chunkX, chunkZ);
                    ClassLoader loader = chunkyWorld.getClass().getClassLoader();
                    Class<?> ingestService = Class.forName(
                            "me.cortex.voxy.common.world.service.VoxelIngestService",
                            false,
                            loader
                    );
                    Class<?> levelChunk = Class.forName(
                            "net.minecraft.world.level.chunk.LevelChunk",
                            false,
                            loader
                    );
                    ingestService.getMethod("tryAutoIngestChunk", levelChunk).invoke(null, chunk);
                } catch (ReflectiveOperationException | LinkageError exception) {
                    throw new IllegalStateException("Roxy could not feed a Chunky chunk into Voxy", unwrap(exception));
                }
            });
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Roxy could not access Chunky's NeoForge world", unwrap(exception));
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
        while (true) {
            Object instance = resolved.voxyInstance.invoke(null);
            if (instance == null) {
                return;
            }
            Object ingestService = resolved.ingestService.invoke(instance);
            Queue<?> ingestQueue = (Queue<?>) resolved.ingestQueue.get(ingestService);
            if (ingestQueue.size() <= QUEUE_LIMIT
                    && (int) resolved.taskCount.invoke(ingestService) <= QUEUE_LIMIT) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    private static void awaitRendererReady(Methods resolved) throws ReflectiveOperationException, InterruptedException {
        Object minecraft = resolved.minecraftInstance.invoke(null);
        Object levelRenderer = resolved.levelRenderer.get(minecraft);
        Object renderer = resolved.getRenderSystem.invoke(levelRenderer);
        if (renderer == null || renderer == readyRenderer) {
            return;
        }

        synchronized (RENDERER_READY_LOCK) {
            if (renderer == readyRenderer) {
                return;
            }

            long deadline = System.currentTimeMillis() + RENDERER_READY_TIMEOUT_MILLIS;
            long earliestReady = System.currentTimeMillis() + 2_000L;
            int idlePolls = 0;
            while (System.currentTimeMillis() < deadline) {
                Object current = resolved.getRenderSystem.invoke(levelRenderer);
                if (current != renderer) {
                    renderer = current;
                    idlePolls = 0;
                    earliestReady = System.currentTimeMillis() + 2_000L;
                    if (renderer == null) {
                        Thread.sleep(100L);
                        continue;
                    }
                }

                boolean busy = (boolean) resolved.rendererHasWork.invoke(renderer);
                if (!busy && System.currentTimeMillis() >= earliestReady) {
                    if (++idlePolls >= 10) {
                        readyRenderer = renderer;
                        return;
                    }
                } else {
                    idlePolls = 0;
                }
                Thread.sleep(100L);
            }

            LOGGER.warn("Roxy timed out waiting for Voxy's initial renderer work before Chunksmith injection");
            readyRenderer = renderer;
        }
    }

    private static Methods methods(ClassLoader loader) throws ReflectiveOperationException {
        Methods cached = methods;
        if (cached != null) {
            return cached;
        }
        synchronized (RoxyChunksmithCompat.class) {
            if (methods == null) {
                methods = Methods.resolve(loader);
            }
            return methods;
        }
    }

    private static Throwable unwrap(Throwable exception) {
        if (exception instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return exception;
    }

    private record Methods(
            Method voxyInstance,
            Method ingestService,
            Method taskCount,
            Field ingestQueue,
            Method minecraftInstance,
            Field levelRenderer,
            Method getRenderSystem,
            Method rendererHasWork,
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
            Class<?> ingestService = Class.forName(
                    "me.cortex.voxy.common.world.service.VoxelIngestService",
                    false,
                    loader
            );
            Class<?> worldIdentifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            Class<?> record = Class.forName("com.kishku7.chunksmith.lod.CsLodChunk", false, loader);
            Class<?> section = Class.forName("com.kishku7.chunksmith.lod.CsLodChunk$Section", false, loader);
            Class<?> builder = Class.forName("com.kishku7.chunksmith.lod.CsLodSectionBuilder", false, loader);
            Class<?> level = Class.forName("net.minecraft.world.level.Level", false, loader);
            Class<?> levelChunkSection = Class.forName(
                    "net.minecraft.world.level.chunk.LevelChunkSection",
                    false,
                    loader
            );
            Class<?> dataLayer = Class.forName("net.minecraft.world.level.chunk.DataLayer", false, loader);
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> levelRendererClass = Class.forName("net.minecraft.client.renderer.LevelRenderer", false, loader);
            Class<?> renderSystemBridge = Class.forName(
                    "me.cortex.voxy.client.core.IGetVoxyRenderSystem",
                    false,
                    loader
            );
            Class<?> renderSystem = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader);
            Field ingestQueue = ingestService.getDeclaredField("ingestQueue");
            ingestQueue.setAccessible(true);
            Method rendererHasWork = renderSystem.getDeclaredMethod("frexStillHasWork");
            rendererHasWork.setAccessible(true);

            return new Methods(
                    voxyCommon.getMethod("getInstance"),
                    voxyInstance.getMethod("getIngestService"),
                    ingestService.getMethod("getTaskCount"),
                    ingestQueue,
                    minecraft.getMethod("getInstance"),
                    minecraft.getField("levelRenderer"),
                    renderSystemBridge.getMethod("voxy$getRenderSystem"),
                    rendererHasWork,
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
