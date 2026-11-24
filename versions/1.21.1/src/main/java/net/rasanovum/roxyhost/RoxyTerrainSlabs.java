package net.rasanovum.roxyhost;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.fml.loading.LoadingModList;
import net.rasanovum.roxy.compat.RoxyTerrainSlabsCompat;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class RoxyTerrainSlabs {
    private static final String MOD_ID = "terrain_slabs";
    private static final boolean LOADED = isLoaded();
    private static final ThreadLocal<RoxyTerrainSlabs> LOCAL = ThreadLocal.withInitial(RoxyTerrainSlabs::new);
    private static final TagKey<Block> ON_TOP = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "on_top_blocks"));

    private WeakReference<Object> mapper = new WeakReference<>(null);
    private BlockState[] states = new BlockState[256];
    private Method lookup;

    private RoxyTerrainSlabs() {
    }

    private static boolean isLoaded() {
        try {
            LoadingModList mods = LoadingModList.get();
            return mods != null && mods.getModFileById(MOD_ID) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static void adjustSnow(Object mapper, long[] raw, long[] neighbors, int neighborMask,
                                  long[] mesh, int[] opaque, int[] nonOpaque) {
        if (!LOADED || mapper == null) return;
        RoxyTerrainSlabs cache = LOCAL.get();
        cache.bind(mapper);
        RoxyTerrainSlabsCompat.markSnow(raw, neighbors, neighborMask, mesh, opaque, nonOpaque,
                id -> cache.isSnow(mapper, id), id -> cache.isBottomSlab(mapper, id));
    }

    private void bind(Object currentMapper) {
        if (mapper.get() == currentMapper) return;
        try {
            lookup = currentMapper.getClass().getMethod("getBlockStateFromBlockId", int.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Unable to resolve Voxy's terrain block states", exception);
        }
        mapper = new WeakReference<>(currentMapper);
        Arrays.fill(states, null);
    }

    private boolean isSnow(Object mapper, int id) {
        if (id <= 0) return false;
        BlockState state = state(mapper, id);
        return state != null && state.getBlock() instanceof SnowLayerBlock && state.is(ON_TOP);
    }

    private boolean isBottomSlab(Object mapper, int id) {
        if (id <= 0) return false;
        BlockState state = state(mapper, id);
        return state != null && state.is(BlockTags.SLABS) && state.hasProperty(SlabBlock.TYPE)
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    private BlockState state(Object mapper, int id) {
        if (id < 0) return null;
        if (id >= states.length) {
            states = Arrays.copyOf(states, Math.max(states.length * 2, id + 1));
        }
        BlockState state = states[id];
        if (state != null) return state;
        try {
            return states[id] = (BlockState) lookup.invoke(mapper, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Voxy terrain block state " + id, exception);
        }
    }
}
