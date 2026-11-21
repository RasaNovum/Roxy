package net.rasanovum.roxyhost;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

public final class RoxyPalettedContainerFactory {
    private final Codec<?> biomeContainerCodec;
    private final Codec<?> blockStatesContainerCodec;

    private RoxyPalettedContainerFactory(
            Codec<?> biomeContainerCodec,
            Codec<?> blockStatesContainerCodec
    ) {
        this.biomeContainerCodec = biomeContainerCodec;
        this.blockStatesContainerCodec = blockStatesContainerCodec;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static RoxyPalettedContainerFactory create(RegistryAccess registryAccess) {
        Registry<Biome> biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        Codec<PalettedContainer<BlockState>> blockStatesCodec = PalettedContainer.codecRW(
                (net.minecraft.core.IdMap) Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState()
        );
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                defaultBiome
        );
        return new RoxyPalettedContainerFactory(biomeCodec, blockStatesCodec);
    }

    public static LevelChunkSection snapshotSection(LevelChunkSection section) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PalettedContainer<BlockState> states = snapshotPalette(section.getStates(), buffer);
            PalettedContainer<Holder<Biome>> biomes = snapshotBiomes(section.getBiomes(), buffer);
            return new LevelChunkSection(states, biomes);
        } finally {
            buffer.release();
        }
    }

    private static <T> PalettedContainer<T> snapshotPalette(PalettedContainer<T> source, FriendlyByteBuf buffer) {
        PalettedContainer<T> staging = source.copy();
        if (staging == null || staging == source || staging.getClass() != PalettedContainer.class) {
            throw new IllegalStateException("Unable to stage palette " + source.getClass().getName());
        }
        PalettedContainer<T> snapshot = staging.recreate();
        buffer.clear();
        staging.write(buffer);
        snapshot.read(buffer);
        return snapshot;
    }

    private static PalettedContainer<Holder<Biome>> snapshotBiomes(
            PalettedContainerRO<Holder<Biome>> source, FriendlyByteBuf buffer) {
        if (source instanceof PalettedContainer<Holder<Biome>> palette) return snapshotPalette(palette, buffer);
        PalettedContainer<Holder<Biome>> template = source.recreate();
        if (template == null || template.getClass() != PalettedContainer.class) {
            throw new IllegalStateException("Unable to recreate biome palette " + source.getClass().getName());
        }
        PalettedContainer<Holder<Biome>> snapshot = template.recreate();
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    snapshot.set(x, y, z, source.get(x, y, z));
                }
            }
        }
        return snapshot;
    }

    public Codec<?> biomeContainerCodec() {
        return this.biomeContainerCodec;
    }

    public Codec<?> blockStatesContainerCodec() {
        return this.blockStatesContainerCodec;
    }
}
