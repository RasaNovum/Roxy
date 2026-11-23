package net.rasanovum.roxy.bridge;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

public final class RoxyColorBridge {
    private static final Field BACKING_FIELD = findBackingField();

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
        for (Field field : BlockColors.class.getDeclaredFields()) {
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
}
