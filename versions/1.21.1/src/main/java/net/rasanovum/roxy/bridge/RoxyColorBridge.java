package net.rasanovum.roxy.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

public final class RoxyColorBridge {
    private static final ClassValue<Field> BACKING_FIELDS = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            return findBackingField(type);
        }
    };
    private static final Map<ClassLoader, RegistryAccessors> REGISTRY_ACCESSORS = new WeakHashMap<>();

    private RoxyColorBridge() {
    }

    public static Object getBlockColor(Object colors, Object block) {
        if (colors == null || block == null) {
            return null;
        }

        try {
            Object backing = readBacking(colors);
            if (backing == null) {
                throw new IllegalStateException("BlockColors backing store is null");
            }
            if (backing instanceof Map<?, ?> map) {
                return map.get(block);
            }
            if (backing.getClass().getName().equals("net.minecraft.core.IdMapper")) {
                RegistryAccessors accessors = registryAccessors(colors);
                int id = ((Number) accessors.getId.invoke(accessors.blockRegistry, block)).intValue();
                return id >= 0 ? accessors.byId.invoke(backing, id) : null;
            }
            throw new IllegalStateException("Unsupported BlockColors backing store: "
                    + backing.getClass().getName());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve BlockColor provider", exception);
        }
    }

    public static Object getColorMapper(Object colors) {
        if (colors == null) {
            return null;
        }

        try {
            Object backing = readBacking(colors);
            if (backing == null) {
                throw new IllegalStateException("BlockColors backing store is null");
            }
            if (backing.getClass().getName().equals("net.minecraft.core.IdMapper")) {
                return backing;
            }
            if (!(backing instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Unsupported BlockColors backing store: "
                        + backing.getClass().getName());
            }

            RegistryAccessors accessors = registryAccessors(colors);
            Object result = accessors.idMapperConstructor.newInstance();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                int id = ((Number) accessors.getId.invoke(accessors.blockRegistry, entry.getKey())).intValue();
                if (id >= 0) {
                    accessors.addMapping.invoke(result, entry.getValue(), id);
                }
            }
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to adapt BlockColors backing store", exception);
        }
    }

    public static int linearToSrgbChannel(float linear) {
        if (Float.isNaN(linear)) {
            return 0;
        }

        float clamped = Math.max(0.0F, Math.min(1.0F, linear));
        float srgb = clamped <= 0.0031308F
                ? clamped * 12.92F
                : 1.055F * (float) Math.pow(clamped, 1.0 / 2.4) - 0.055F;
        return Math.max(0, Math.min(255, Math.round(srgb * 255.0F)));
    }

    private static Object readBacking(Object colors) throws IllegalAccessException {
        return BACKING_FIELDS.get(colors.getClass()).get(colors);
    }

    private static Field findBackingField(Class<?> owner) {
        Field fallback = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (!Map.class.isAssignableFrom(type)
                    && !type.getName().equals("net.minecraft.core.IdMapper")) {
                continue;
            }
            if (field.getName().equals("blockColors")) {
                if (!field.trySetAccessible()) {
                    throw new IllegalStateException("Unable to access BlockColors field " + field.getName());
                }
                return field;
            }
            if (fallback == null) {
                fallback = field;
            }
        }
        if (fallback != null) {
            if (!fallback.trySetAccessible()) {
                throw new IllegalStateException("Unable to access BlockColors field " + fallback.getName());
            }
            return fallback;
        }
        throw new IllegalStateException("Unable to locate BlockColors backing store");
    }

    private static RegistryAccessors registryAccessors(Object colors) throws ReflectiveOperationException {
        ClassLoader minecraftLoader = findMinecraftLoader(colors);
        synchronized (REGISTRY_ACCESSORS) {
            RegistryAccessors cached = REGISTRY_ACCESSORS.get(minecraftLoader);
            if (cached != null) {
                return cached;
            }
            Class<?> idMapperClass = Class.forName("net.minecraft.core.IdMapper", false, minecraftLoader);
            Constructor<?> idMapperConstructor = idMapperClass.getConstructor();
            Method addMapping = idMapperClass.getMethod("addMapping", Object.class, int.class);
            Method byId = idMapperClass.getMethod("byId", int.class);
            Class<?> registries = Class.forName(
                    "net.minecraft.core.registries.BuiltInRegistries",
                    false,
                    minecraftLoader
            );
            Object blockRegistry = registries.getField("BLOCK").get(null);
            Method getId = findGetId(blockRegistry.getClass());
            RegistryAccessors resolved = new RegistryAccessors(
                    idMapperConstructor,
                    addMapping,
                    byId,
                    blockRegistry,
                    getId
            );
            REGISTRY_ACCESSORS.put(minecraftLoader, resolved);
            return resolved;
        }
    }

    private static Method findGetId(Class<?> registryClass) {
        for (Method method : registryClass.getMethods()) {
            if (method.getName().equals("getId")
                    && method.getParameterCount() == 1
                    && method.getReturnType() == int.class) {
                return method;
            }
        }
        throw new IllegalStateException("Unable to locate registry getId method");
    }

    private static ClassLoader findMinecraftLoader(Object colors) {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                colors.getClass().getClassLoader(),
                RoxyColorBridge.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("net.minecraft.client.Minecraft", false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }

    private record RegistryAccessors(
            Constructor<?> idMapperConstructor,
            Method addMapping,
            Method byId,
            Object blockRegistry,
            Method getId
    ) {
    }
}
