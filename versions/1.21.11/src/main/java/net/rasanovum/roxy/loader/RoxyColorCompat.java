package net.rasanovum.roxy.loader;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

public final class RoxyColorCompat {
    private static final Map<BlockColors, IdMapper<BlockColor>> MAPPERS = new WeakHashMap<>();
    private static final Field BACKING_FIELD = findBackingField();

    private RoxyColorCompat() {
    }

    @SuppressWarnings("unchecked")
    public static IdMapper<BlockColor> getColorMapper(BlockColors colors) {
        if (colors == null) {
            return null;
        }

        synchronized (MAPPERS) {
            IdMapper<BlockColor> cached = MAPPERS.get(colors);
            if (cached != null) {
                return cached;
            }

            Object backing = readBacking(colors);
            if (backing instanceof IdMapper<?> mapper) {
                IdMapper<BlockColor> result = (IdMapper<BlockColor>) mapper;
                MAPPERS.put(colors, result);
                return result;
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
            MAPPERS.put(colors, result);
            return result;
        }
    }

    private static Object readBacking(BlockColors colors) {
        try {
            return BACKING_FIELD.get(colors);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read BlockColors backing store", exception);
        }
    }

    private static Field findBackingField() {
        for (Field field : BlockColors.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (IdMapper.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
                if (!field.trySetAccessible()) {
                    throw new IllegalStateException("Unable to access BlockColors field " + field.getName());
                }
                return field;
            }
        }
        throw new IllegalStateException("Unable to locate BlockColors backing store");
    }
}
