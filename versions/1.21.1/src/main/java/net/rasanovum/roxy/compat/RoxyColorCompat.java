package net.rasanovum.roxy.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

public final class RoxyColorCompat {
    private static final Map<Object, Object> MAPPERS = new WeakHashMap<>();

    private RoxyColorCompat() {
    }

    public static Object getColorMapper(Object colors) {
        if (colors == null) {
            return null;
        }

        synchronized (MAPPERS) {
            Object cached = MAPPERS.get(colors);
            if (cached != null) {
                return cached;
            }

            try {
                Object backing = readBacking(colors);
                if (backing == null) {
                    throw new IllegalStateException("BlockColors backing store is null");
                }
                if (backing.getClass().getName().equals("net.minecraft.core.IdMapper")) {
                    MAPPERS.put(colors, backing);
                    return backing;
                }
                if (!(backing instanceof Map<?, ?> map)) {
                    throw new IllegalStateException("Unsupported BlockColors backing store: "
                            + backing.getClass().getName());
                }

                ClassLoader minecraftLoader = findMinecraftLoader(colors);
                Class<?> idMapperClass = Class.forName("net.minecraft.core.IdMapper", false, minecraftLoader);
                Object result = idMapperClass.getConstructor().newInstance();
                Method addMapping = idMapperClass.getMethod("addMapping", Object.class, int.class);

                Class<?> registries = Class.forName(
                        "net.minecraft.core.registries.BuiltInRegistries",
                        false,
                        minecraftLoader
                );
                Object blockRegistry = registries.getField("BLOCK").get(null);
                Method getId = findGetId(blockRegistry.getClass());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    int id = ((Number) getId.invoke(blockRegistry, entry.getKey())).intValue();
                    if (id >= 0) {
                        addMapping.invoke(result, entry.getValue(), id);
                    }
                }

                MAPPERS.put(colors, result);
                return result;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to adapt BlockColors backing store", exception);
            }
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
        for (Field field : colors.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (Map.class.isAssignableFrom(type)
                    || type.getName().equals("net.minecraft.core.IdMapper")) {
                if (!field.trySetAccessible()) {
                    throw new IllegalStateException("Unable to access BlockColors field " + field.getName());
                }
                return field.get(colors);
            }
        }
        throw new IllegalStateException("Unable to locate BlockColors backing store");
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
                RoxyColorCompat.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("net.minecraft.client.Minecraft", false, candidate);
                return candidate;
            } catch (ClassNotFoundException ignored) {
                // Try the next loader.
            }
        }
        throw new IllegalStateException("Unable to locate Minecraft's client class loader");
    }
}
