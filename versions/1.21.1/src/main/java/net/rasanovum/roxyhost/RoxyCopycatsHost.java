package net.rasanovum.roxyhost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rasanovum.roxy.compat.RoxyCopycatsCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RoxyCopycatsHost implements RoxyCopycatsCompat.HostAdapter {
    private static final RoxyCopycatsHost INSTANCE = new RoxyCopycatsHost();

    private RoxyCopycatsHost() {
    }

    public static void register() {
        RoxyCopycatsCompat.registerHost(INSTANCE);
    }

    @Override
    public Object cloneSection(Object section) {
        return section instanceof LevelChunkSection levelSection
                ? RoxyPalettedContainerFactory.snapshotSection(levelSection)
                : null;
    }

    @Override
    public Object getBlockState(Object section, int x, int y, int z) {
        return ((LevelChunkSection) section).getBlockState(x, y, z);
    }

    @Override
    public void setBlockState(Object section, int x, int y, int z, Object state) {
        ((LevelChunkSection) section).setBlockState(x, y, z, (BlockState) state, false);
    }

    @Override
    public Object approximateShape(Object copycatState, Object materialState) {
        return RoxyCopycatShapeFallback.approximate((BlockState) copycatState, (BlockState) materialState);
    }

    @Override
    public Map<Integer, List<RoxyCopycatsCompat.BlockEntityAt>> indexBlockEntities(Object chunk) {
        if (!(chunk instanceof LevelChunk levelChunk)) return null;
        Map<BlockPos, BlockEntity> entities = levelChunk.getBlockEntities();
        if (entities.isEmpty()) return Map.of();
        Map<Integer, List<RoxyCopycatsCompat.BlockEntityAt>> bySection = new HashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet()) {
            BlockPos pos = entry.getKey();
            int y = pos.getY();
            bySection.computeIfAbsent(y >> 4, ignored -> new ArrayList<>())
                    .add(new RoxyCopycatsCompat.BlockEntityAt(pos.getX() & 15, y & 15, pos.getZ() & 15, entry.getValue()));
        }
        return bySection;
    }

    @Override
    public Object currentClientLevel() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.level;
    }

    @Override
    public Object loadedChunk(Object level, int chunkX, int chunkZ) {
        if (!(level instanceof ClientLevel clientLevel)) return null;
        if (!clientLevel.getChunkSource().hasChunk(chunkX, chunkZ)) return null;
        return clientLevel.getChunkSource().getChunk(chunkX, chunkZ, false);
    }

    @Override
    public Object liveChunkForSection(Object section, int sectionX, int sectionY, int sectionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) return null;
        ClientLevel level = minecraft.level;
        if (level == null || !level.getChunkSource().hasChunk(sectionX, sectionZ)) return null;
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, false);
        if (chunk == null) return null;
        int index = chunk.getSectionIndexFromSectionY(sectionY);
        LevelChunkSection[] sections = chunk.getSections();
        return index >= 0 && index < sections.length && sections[index] == section ? chunk : null;
    }

    @Override
    public RoxyCopycatsCompat.BlockEntityUpdate inspectBlockEntityUpdate(Object packet) {
        if (!(packet instanceof ClientboundBlockEntityDataPacket dataPacket)) return null;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) return null;
        BlockPos pos = dataPacket.getPos();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) return null;
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return new RoxyCopycatsCompat.BlockEntityUpdate(level, chunkX, chunkZ, state, blockEntity);
    }

    @Override
    public boolean isBlockState(Object value) {
        return value instanceof BlockState;
    }

    @Override
    public boolean isAir(Object state) {
        return state instanceof BlockState blockState && blockState.isAir();
    }

    @Override
    public Object getBlock(Object state) {
        return state instanceof BlockState blockState ? blockState.getBlock() : null;
    }

    @Override
    public String registryKey(Object state) {
        if (!(state instanceof BlockState blockState)) return null;
        return BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
    }
}
