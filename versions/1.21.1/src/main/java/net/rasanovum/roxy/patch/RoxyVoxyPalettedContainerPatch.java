package net.rasanovum.roxy.patch;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

public final class RoxyVoxyPalettedContainerPatch {
    private final Codec<?> biomeContainerCodec;
    private final Codec<?> blockStatesContainerCodec;

    private RoxyVoxyPalettedContainerPatch(
            Codec<?> biomeContainerCodec,
            Codec<?> blockStatesContainerCodec
    ) {
        this.biomeContainerCodec = biomeContainerCodec;
        this.blockStatesContainerCodec = blockStatesContainerCodec;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static RoxyVoxyPalettedContainerPatch create(RegistryAccess registryAccess) {
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
        return new RoxyVoxyPalettedContainerPatch(biomeCodec, blockStatesCodec);
    }

    public Codec<?> biomeContainerCodec() {
        return this.biomeContainerCodec;
    }

    public Codec<?> blockStatesContainerCodec() {
        return this.blockStatesContainerCodec;
    }
}
