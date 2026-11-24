package net.rasanovum.roxy.bridge;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

public final class RoxyColorBridge {
    private static final Field BACKING_FIELD = findBackingField();
    private static volatile CachedMapper cachedMapper;

    private RoxyColorBridge() {
    }

    public static BlockColor getBlockColor(BlockColors colors, Block block) {
        if (colors == null || block == null) {
            return null;
        }

        Object backing = readBacking(colors);
        if (backing == null) {
            throw new IllegalStateException("BlockColors backing store is null");
        }
        if (backing instanceof Map<?, ?> map) {
            Object color = map.get(block);
            return color instanceof BlockColor blockColor ? blockColor : null;
        }
        if (backing instanceof IdMapper<?> mapper) {
            int id = BuiltInRegistries.BLOCK.getId(block);
            if (id < 0) {
                return null;
            }
            Object color = mapper.byId(id);
            return color instanceof BlockColor blockColor ? blockColor : null;
        }
        throw new IllegalStateException("Unsupported BlockColors backing store: "
                + backing.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    public static IdMapper<BlockColor> getColorMapper(BlockColors colors) {
        if (colors == null) {
            return null;
        }

        Object backing = readBacking(colors);
        if (backing == null) {
            throw new IllegalStateException("BlockColors backing store is null");
        }
        if (backing instanceof IdMapper<?> mapper) {
            return (IdMapper<BlockColor>) mapper;
        }
        if (!(backing instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Unsupported BlockColors backing store: "
                    + backing.getClass().getName());
        }

        int size = map.size();
        CachedMapper existing = cachedMapper;
        if (existing != null && existing.colors.get() == colors && existing.size == size) {
            return existing.mapper;
        }

        IdMapper<BlockColor> result = new IdMapper<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Block block)
                    || !(entry.getValue() instanceof BlockColor color)) {
                continue;
            }
            int id = BuiltInRegistries.BLOCK.getId(block);
            if (id >= 0) {
                result.addMapping(color, id);
            }
        }
        cachedMapper = new CachedMapper(new WeakReference<>(colors), size, result);
        return result;
    }

    private static Object readBacking(BlockColors colors) {
        try {
            return BACKING_FIELD.get(colors);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read BlockColors backing store", exception);
        }
    }

    private static Field findBackingField() {
        Field fallback = null;
        for (Class<?> current = BlockColors.class; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (!IdMapper.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) {
                    continue;
                }
                if (field.getName().equals("blockColors")) {
                    if (!field.trySetAccessible()) {
                        throw new IllegalStateException("Unable to access BlockColors field " + field.getName());
                    }
                    return field;
                }
                if (fallback == null && !field.getName().equals("coloringStates") && matchesBlockColorMap(field)) {
                    fallback = field;
                }
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

    private static boolean matchesBlockColorMap(Field field) {
        if (IdMapper.class.isAssignableFrom(field.getType())) return true;
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 2 && args[1] == BlockColor.class) return true;
            return false;
        }
        return true;
    }

    private record CachedMapper(WeakReference<BlockColors> colors, int size, IdMapper<BlockColor> mapper) {
    }
}
