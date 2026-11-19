package net.rasanovum.roxyhost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RoxyCopycatShapeFallback {
    private static final List<String> FAMILY_SUFFIXES = List.of(
            "_planks", "_log", "_wood", "_stem", "_hyphae", "_block");
    private static final Map<Block, BlockState> SLAB_TARGETS = new ConcurrentHashMap<>();
    private static final Map<Block, BlockState> STAIR_TARGETS = new ConcurrentHashMap<>();
    private static final List<BlockState> VANILLA_STAIRS = vanillaShapes(true);
    private static final List<BlockState> VANILLA_SLABS = vanillaShapes(false);

    private RoxyCopycatShapeFallback() {
    }

    public static BlockState approximate(BlockState copycatState, BlockState materialState) {
        if (copycatState.getBlock() instanceof StairBlock) {
            BlockState target = STAIR_TARGETS.computeIfAbsent(materialState.getBlock(), block -> resolve(block, true));
            return target.setValue(StairBlock.FACING, copycatState.getValue(StairBlock.FACING))
                    .setValue(StairBlock.HALF, copycatState.getValue(StairBlock.HALF))
                    .setValue(StairBlock.SHAPE, copycatState.getValue(StairBlock.SHAPE))
                    .setValue(StairBlock.WATERLOGGED, waterlogged(copycatState));
        }

        if (!copycatState.hasProperty(SlabBlock.TYPE)) return materialState;
        SlabType type = copycatState.getValue(SlabBlock.TYPE);
        if (type == SlabType.DOUBLE && ordinaryFullBlock(materialState)) return materialState;
        if (type != SlabType.DOUBLE && copycatState.hasProperty(BlockStateProperties.AXIS)
                && copycatState.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
            return materialState;
        }

        BlockState target = SLAB_TARGETS.computeIfAbsent(materialState.getBlock(), block -> resolve(block, false));
        return target.setValue(SlabBlock.TYPE, type)
                .setValue(SlabBlock.WATERLOGGED, type != SlabType.DOUBLE && waterlogged(copycatState));
    }

    private static boolean waterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static boolean ordinaryFullBlock(BlockState state) {
        return !state.hasBlockEntity() && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static BlockState resolve(Block material, boolean stairs) {
        BlockState state = material.defaultBlockState();
        if (usable(state, stairs)) return state;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(material);
        String path = key.getPath();
        BlockState target = familyState(key, path, stairs);
        if (target != null) return target;
        for (String suffix : FAMILY_SUFFIXES) {
            if (!path.endsWith(suffix)) continue;
            target = familyState(key, path.substring(0, path.length() - suffix.length()), stairs);
            if (target != null) return target;
        }
        if (path.endsWith("s")) {
            target = familyState(key, path.substring(0, path.length() - 1), stairs);
            if (target != null) return target;
        }
        return nearestColor(state, stairs);
    }

    private static BlockState familyState(ResourceLocation key, String base, boolean stairs) {
        ResourceLocation candidate = key.withPath(base + (stairs ? "_stairs" : "_slab"));
        BlockState state = BuiltInRegistries.BLOCK.get(candidate).defaultBlockState();
        return usable(state, stairs) ? state : null;
    }

    private static boolean usable(BlockState state, boolean stairs) {
        return !state.hasBlockEntity() && (stairs ? state.getBlock() instanceof StairBlock
                : state.getBlock() instanceof SlabBlock && !state.hasProperty(BlockStateProperties.AXIS));
    }

    private static BlockState nearestColor(BlockState material, boolean stairs) {
        BlockState defaultShape = (stairs ? Blocks.STONE_STAIRS : Blocks.STONE_SLAB).defaultBlockState();
        int color;
        try {
            color = mapColor(material);
        } catch (RuntimeException | LinkageError ignored) {
            return defaultShape;
        }

        List<BlockState> candidates = stairs ? VANILLA_STAIRS : VANILLA_SLABS;
        return candidates.stream().min(Comparator.comparingInt(candidate -> colorDistance(color, mapColor(candidate))))
                .orElse(defaultShape);
    }

    private static int mapColor(BlockState state) {
        return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
    }

    private static int colorDistance(int left, int right) {
        int red = ((left >> 16) & 255) - ((right >> 16) & 255);
        int green = ((left >> 8) & 255) - ((right >> 8) & 255);
        int blue = (left & 255) - (right & 255);
        return red * red + green * green + blue * blue;
    }

    private static List<BlockState> vanillaShapes(boolean stairs) {
        return BuiltInRegistries.BLOCK.keySet().stream()
                .filter(key -> key.getNamespace().equals("minecraft"))
                .sorted()
                .map(key -> BuiltInRegistries.BLOCK.get(key).defaultBlockState())
                .filter(state -> usable(state, stairs))
                .toList();
    }

}
