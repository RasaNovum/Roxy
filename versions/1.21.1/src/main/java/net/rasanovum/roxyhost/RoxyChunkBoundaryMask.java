package net.rasanovum.roxyhost;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RoxyChunkBoundaryMask {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String NVIDIUM_CLASS = "me.cortex.nvidium.Nvidium";
    private static final String NVIDIUM_RENDERER_GETTER = "me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter";
    private static final String NVIDIUM_WORLD_RENDERER = "me.cortex.nvidium.NvidiumWorldRenderer";
    private static final String NVIDIUM_SECTION_MANAGER = "me.cortex.nvidium.managers.SectionManager";

    private static volatile Field managerField;
    private static volatile boolean acediumChecked;
    private static volatile boolean acediumAvailable;
    private static volatile boolean acediumActiveLogged;
    private static volatile boolean acediumFailureLogged;
    private static volatile Field acediumEnabledField;
    private static volatile Class<?> acediumGetterClass;
    private static volatile Method acediumGetRenderer;
    private static volatile Method acediumGetSectionManager;
    private static volatile Field acediumSection2IdField;
    private static volatile Field acediumHiddenSectionsField;

    private final Object renderer;
    private final Long2IntOpenHashMap positions;
    private final LongOpenHashSet additions;
    private final LongOpenHashSet removals;
    private final LongOpenHashSet visible = new LongOpenHashSet();
    private final LongArrayList obsolete = new LongArrayList();
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
            if (managerField == null) managerField = field(SodiumWorldRenderer.class, "renderSectionManager");
            SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
            RenderSectionManager manager = sodium == null ? null : (RenderSectionManager) managerField.get(sodium);
            syncFromManager(manager, viewport);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to synchronize Voxy's visible chunk boundary", exception);
        }
    }

    void syncFromManager(Object manager, Object viewport) throws ReflectiveOperationException {
        visible.clear();
        if (manager != null && !collectAcediumVisible(manager) && manager instanceof RenderSectionManager sodiumManager) {
            collectSodiumVisible(sodiumManager);
        }
        applyVisible(viewport);
    }

    private void collectSodiumVisible(RenderSectionManager manager) {
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

    boolean collectAcediumVisible(Object manager) {
        if (!ensureAcediumHandles(manager.getClass().getClassLoader())) return false;
        try {
            if (!acediumEnabledField.getBoolean(null)) return false;
            if (!acediumGetterClass.isInstance(manager)) return false;
            Object nvidiumRenderer = acediumGetRenderer.invoke(manager);
            if (nvidiumRenderer == null) return false;
            Object sectionManager = acediumGetSectionManager.invoke(nvidiumRenderer);
            if (sectionManager == null) return false;
            Long2IntOpenHashMap section2id = (Long2IntOpenHashMap) acediumSection2IdField.get(sectionManager);
            if (section2id == null) return false;
            LongSet hiddenSections = acediumHiddenSectionsField == null
                    ? null
                    : (LongSet) acediumHiddenSectionsField.get(sectionManager);
            if (!acediumActiveLogged) {
                acediumActiveLogged = true;
                LOGGER.info("Roxy: Acedium Sodiumized renderer detected; using Nvidium SectionManager for chunk boundary mask");
            }
            visible.clear();
            var iterator = section2id.keySet().iterator();
            while (iterator.hasNext()) {
                long position = iterator.nextLong();
                if (hiddenSections == null || !hiddenSections.contains(position)) {
                    visible.add(position);
                }
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            visible.clear();
            acediumAvailable = false;
            if (!acediumFailureLogged) {
                acediumFailureLogged = true;
                LOGGER.warn("Roxy: unable to read Acedium chunk sections for boundary mask; falling back to Sodium render lists", exception);
            }
            return false;
        }
    }

    private static boolean ensureAcediumHandles(ClassLoader loader) {
        if (acediumChecked) return acediumAvailable;
        acediumChecked = true;
        try {
            Class<?> nvidiumClass = Class.forName(NVIDIUM_CLASS, false, loader);
            Class<?> getterClass = Class.forName(NVIDIUM_RENDERER_GETTER, false, loader);
            Class<?> worldRendererClass = Class.forName(NVIDIUM_WORLD_RENDERER, false, loader);
            Class<?> sectionManagerClass = Class.forName(NVIDIUM_SECTION_MANAGER, false, loader);
            acediumEnabledField = field(nvidiumClass, "IS_ENABLED");
            acediumGetterClass = getterClass;
            acediumGetRenderer = getterClass.getMethod("getRenderer");
            acediumGetRenderer.setAccessible(true);
            acediumGetSectionManager = worldRendererClass.getMethod("getSectionManager");
            acediumGetSectionManager.setAccessible(true);
            acediumSection2IdField = field(sectionManagerClass, "section2id");
            acediumHiddenSectionsField = optionalField(sectionManagerClass, "hiddenSectionKeys");
            acediumAvailable = true;
        } catch (ClassNotFoundException ignored) {
            acediumAvailable = false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            acediumAvailable = false;
            if (!acediumFailureLogged) {
                acediumFailureLogged = true;
                LOGGER.warn("Roxy: unable to initialize Acedium chunk boundary bridge; falling back to Sodium render lists", exception);
            }
        }
        return acediumAvailable;
    }

    private void applyVisible(Object viewport) throws ReflectiveOperationException {
        additions.clear();
        removals.clear();
        if (positions.size() == visible.size() && containsEveryVisiblePosition()) {
            clearDepthIfEmpty(viewport);
            return;
        }
        obsolete.clear();
        var current = positions.keySet().iterator();
        while (current.hasNext()) {
            long position = current.nextLong();
            if (!visible.contains(position)) obsolete.add(position);
        }
        for (int index = 0; index < obsolete.size(); index++) {
            removePosition.invoke(renderer, obsolete.getLong(index));
        }
        var added = visible.iterator();
        while (added.hasNext()) {
            long position = added.nextLong();
            if (!positions.containsKey(position)) addPosition.invoke(renderer, position);
        }
        clearDepthIfEmpty(viewport);
    }

    private boolean containsEveryVisiblePosition() {
        var current = visible.iterator();
        while (current.hasNext()) {
            if (!positions.containsKey(current.nextLong())) return false;
        }
        return true;
    }

    private void clearDepthIfEmpty(Object viewport) throws ReflectiveOperationException {
        if (positions.isEmpty()) {
            if (depthBuffer == null) {
                depthBuffer = field(viewport.getClass(), "depthBoundingBuffer");
                clearBuffer = depthBuffer.getType().getMethod("clear", float.class);
            }
            clearBuffer.invoke(depthBuffer.get(viewport), clearDepth);
        }
    }


    private static Field optionalField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
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
