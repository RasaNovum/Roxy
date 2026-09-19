package net.rasanovum.roxyhost;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.rasanovum.roxy.blend.RoxyBlendConfig;
import net.rasanovum.roxy.blend.RoxyBlendUniforms;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyChunkBoundaryMask {
    private static Field managerField;
    private static Field cameraXField;
    private static Field cameraZField;
    private final Object renderer;
    private final Long2IntOpenHashMap positions;
    private final LongOpenHashSet additions;
    private final LongOpenHashSet removals;
    private final LongOpenHashSet visible = new LongOpenHashSet();
    private final LongOpenHashSet obsolete = new LongOpenHashSet();
    private final Method addSection;
    private final Method removeSection;

    public RoxyChunkBoundaryMask(Object renderer) {
        this.renderer = renderer;
        try {
            Class<?> type = renderer.getClass();
            positions = (Long2IntOpenHashMap) field(type, "chunk2idx").get(renderer);
            additions = (LongOpenHashSet) field(type, "addQueue").get(renderer);
            removals = (LongOpenHashSet) field(type, "remQueue").get(renderer);
            addSection = type.getMethod("addSection", long.class);
            removeSection = type.getMethod("removeSection", long.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to initialize Voxy's visible chunk boundary", exception);
        }
    }

    public void sync(Object viewport) {
        try {
            visible.clear();
            if (cameraXField == null) cameraXField = field(viewport.getClass(), "cameraX");
            if (cameraZField == null) cameraZField = field(viewport.getClass(), "cameraZ");
            double cameraX = cameraXField.getDouble(viewport);
            double cameraZ = cameraZField.getDouble(viewport);
            Minecraft minecraft = Minecraft.getInstance();
            float renderDistance = minecraft == null ? 0.0F
                    : minecraft.options.getEffectiveRenderDistance() * 16.0F;
            float blendStart = RoxyBlendConfig.transitionStartBlocks(renderDistance);
            boolean smoothing = RoxyBlendConfig.enabled()
                    && RoxyBlendUniforms.ready()
                    && blendStart < renderDistance;
            if (managerField == null) managerField = field(SodiumWorldRenderer.class, "renderSectionManager");
            SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
            RenderSectionManager manager = sodium == null ? null : (RenderSectionManager) managerField.get(sodium);
            if (manager != null) {
                var lists = manager.getRenderLists().iterator(false);
                while (lists.hasNext()) {
                    var list = lists.next();
                    var sections = list.sectionsWithGeometryIterator(false);
                    if (sections == null) continue;
                    var region = list.getRegion();
                    while (sections.hasNext()) {
                        var section = region.getSection(sections.nextByteAsInt());
                        if (section != null && section.isBuilt() && !section.isDisposed()) {
                            int chunkX = section.getChunkX();
                            int chunkZ = section.getChunkZ();
                            if (!smoothing
                                    || hasTranslucentGeometry(section)
                                    || fullyInsideBlendStart(chunkX, chunkZ, cameraX, cameraZ, blendStart)) {
                                visible.add(SectionPos.asLong(chunkX, section.getChunkY(), chunkZ));
                            }
                        }
                    }
                }
            }
            applyVisible(viewport);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to synchronize Voxy's visible chunk boundary", exception);
        }
    }

    private static boolean hasTranslucentGeometry(
            net.caffeinemc.mods.sodium.client.render.chunk.RenderSection section
    ) {
        var storage = section.getRegion().getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (storage == null) return false;
        long pointer = storage.getDataPointer(section.getSectionIndex());
        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            if (SectionRenderDataUnsafe.getVertexCount(pointer, facing) != 0L) return true;
        }
        return false;
    }

    private static boolean fullyInsideBlendStart(
            int chunkX,
            int chunkZ,
            double cameraX,
            double cameraZ,
            float blendStart
    ) {
        double farX = Math.abs(chunkX * 16.0 + 8.0 - cameraX) + 8.0;
        double farZ = Math.abs(chunkZ * 16.0 + 8.0 - cameraZ) + 8.0;
        return Math.hypot(farX, farZ) <= blendStart;
    }

    private void applyVisible(Object viewport) throws ReflectiveOperationException {
        obsolete.clear();
        var current = positions.keySet().iterator();
        while (current.hasNext()) {
            long position = current.nextLong();
            if (!visible.contains(position)) obsolete.add(position);
        }
        var pending = additions.iterator();
        while (pending.hasNext()) {
            long position = pending.nextLong();
            if (!visible.contains(position)) obsolete.add(position);
        }
        var removed = obsolete.iterator();
        while (removed.hasNext()) removeSection.invoke(renderer, removed.nextLong());
        var added = visible.iterator();
        while (added.hasNext()) {
            long position = added.nextLong();
            if (!positions.containsKey(position) || removals.contains(position)) {
                addSection.invoke(renderer, position);
            }
        }
    }


    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalStateException("Missing chunk boundary field " + type.getName() + "." + name);
    }
}
