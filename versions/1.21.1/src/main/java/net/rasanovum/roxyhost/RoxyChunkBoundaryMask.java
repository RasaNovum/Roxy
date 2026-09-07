package net.rasanovum.roxyhost;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.core.SectionPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyChunkBoundaryMask {
    private static Field managerField;
    private final Object renderer;
    private final Long2IntOpenHashMap positions;
    private final LongOpenHashSet additions;
    private final LongOpenHashSet removals;
    private final LongOpenHashSet visible = new LongOpenHashSet();
    private final LongOpenHashSet obsolete = new LongOpenHashSet();
    private final Method addPosition;
    private final Method removePosition;
    private final float clearDepth;
    private Field depthBuffer;
    private Method clearBuffer;

    public RoxyChunkBoundaryMask(Object renderer) {
        this.renderer = renderer;
        try {
            Class<?> type = renderer.getClass();
            positions = (Long2IntOpenHashMap) field(type, "chunk2idx").get(renderer);
            additions = (LongOpenHashSet) field(type, "addQueue").get(renderer);
            removals = (LongOpenHashSet) field(type, "remQueue").get(renderer);
            addPosition = type.getDeclaredMethod("_addPos", long.class);
            removePosition = type.getDeclaredMethod("_remPos", long.class);
            addPosition.setAccessible(true);
            removePosition.setAccessible(true);
            Object properties = field(type, "properties").get(renderer);
            clearDepth = (float) properties.getClass().getMethod("inverseClearDepth").invoke(properties);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to initialize Voxy's visible chunk boundary", exception);
        }
    }

    public void sync(Object viewport) {
        try {
            visible.clear();
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
                            visible.add(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
                        }
                    }
                }
            }
            applyVisible(viewport);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to synchronize Voxy's visible chunk boundary", exception);
        }
    }

    private void applyVisible(Object viewport) throws ReflectiveOperationException {
        additions.clear();
        removals.clear();
        obsolete.clear();
        var current = positions.keySet().iterator();
        while (current.hasNext()) {
            long position = current.nextLong();
            if (!visible.contains(position)) obsolete.add(position);
        }
        var removed = obsolete.iterator();
        while (removed.hasNext()) removePosition.invoke(renderer, removed.nextLong());
        var added = visible.iterator();
        while (added.hasNext()) {
            long position = added.nextLong();
            if (!positions.containsKey(position)) addPosition.invoke(renderer, position);
        }
        if (positions.isEmpty()) {
            if (depthBuffer == null) {
                depthBuffer = field(viewport.getClass(), "depthBoundingBuffer");
                clearBuffer = depthBuffer.getType().getMethod("clear", float.class);
            }
            clearBuffer.invoke(depthBuffer.get(viewport), clearDepth);
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
