package net.rasanovum.roxy.bridge;

import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoxySurfaceSamplesBridge {
    private static final String SURFACE_SAMPLES_NAMESPACE = "surfacesamples";
    private static final String FLAVOUR_PROPERTY = "flavour";
    private static final String UNKNOWN_FLAVOUR = "unknown";
    private static final String STONY_FLAVOUR = "stony";
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile Boolean installed;
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> stateClass) {
            try {
                ClassLoader loader = stateClass.getClassLoader();
                Class<?> propertyClass = Class.forName(
                        "net.minecraft.world.level.block.state.properties.Property",
                        false,
                        loader
                );
                Class<?> registryClass = Class.forName(
                        "net.minecraft.core.Registry",
                        false,
                        loader
                );
                Class<?> resourceLocationClass = Class.forName(
                        "net.minecraft.resources.ResourceLocation",
                        false,
                        loader
                );
                Class<?> registriesClass = Class.forName(
                        "net.minecraft.core.registries.BuiltInRegistries",
                        false,
                        loader
                );
                return new Access(
                        registriesClass.getField("BLOCK").get(null),
                        registryClass.getMethod("getKey", Object.class),
                        resourceLocationClass.getMethod("getNamespace"),
                        stateClass.getMethod("getBlock"),
                        stateClass.getMethod("getProperties"),
                        stateClass.getMethod("getValue", propertyClass),
                        stateClass.getMethod("setValue", propertyClass, Comparable.class),
                        propertyClass.getMethod("getName"),
                        propertyClass.getMethod("getName", Comparable.class),
                        propertyClass.getMethod("getValue", String.class)
                );
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                warnOnce(exception);
                return Access.UNAVAILABLE;
            }
        }
    };

    private RoxySurfaceSamplesBridge() {
    }

    public static Object normalizeForModel(Object state) {
        if (state == null || !isInstalled()) return state;

        Access access;
        try {
            access = ACCESS.get(state.getClass());
        } catch (RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return state;
        }
        if (access == Access.UNAVAILABLE) return state;

        try {
            Object block = access.getBlock.invoke(state);
            if (block == null) return state;
            Object blockId = access.getRegistryKey.invoke(access.blockRegistry, block);
            if (blockId == null || !SURFACE_SAMPLES_NAMESPACE.equals(access.getNamespace.invoke(blockId))) {
                return state;
            }

            Object properties = access.getProperties.invoke(state);
            if (!(properties instanceof Iterable<?> iterable)) return state;
            for (Object property : iterable) {
                if (!FLAVOUR_PROPERTY.equals(access.getPropertyName.invoke(property))) continue;

                Object current = access.getValue.invoke(state, property);
                if (!UNKNOWN_FLAVOUR.equals(access.getPropertyValueName.invoke(property, current))) return state;

                Optional<?> stony = (Optional<?>) access.parsePropertyValue.invoke(property, STONY_FLAVOUR);
                return stony.map(value -> invokeSetValue(access, state, property, value)).orElse(state);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
        }
        return state;
    }

    private static boolean isInstalled() {
        Boolean cached = installed;
        if (cached != null) return cached;
        boolean present = false;
        try {
            LoadingModList mods = LoadingModList.get();
            present = mods != null && mods.getModFileById(SURFACE_SAMPLES_NAMESPACE) != null;
        } catch (RuntimeException | LinkageError ignored) {
        }
        installed = present;
        return present;
    }

    private static Object invokeSetValue(Access access, Object state, Object property, Object value) {
        try {
            return access.setValue.invoke(state, property, value);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            warnOnce(exception);
            return state;
        }
    }

    private static void warnOnce(Throwable exception) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Unable to normalize unknown Surface Samples flavours for Voxy model baking", exception);
        }
    }

    private record Access(
            Object blockRegistry,
            Method getRegistryKey,
            Method getNamespace,
            Method getBlock,
            Method getProperties,
            Method getValue,
            Method setValue,
            Method getPropertyName,
            Method getPropertyValueName,
            Method parsePropertyValue
    ) {
        private static final Access UNAVAILABLE = new Access(
                null, null, null, null, null, null, null, null, null, null
        );
    }
}
