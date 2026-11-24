package net.rasanovum.roxyhost;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Holder<Biome> defaultBiome = biomeRegistry.getHolder(Biomes.PLAINS)
                .map(holder -> (Holder<Biome>) holder)
                .or(() -> biomeRegistry.holders().findFirst().map(holder -> (Holder<Biome>) holder))
                .orElseThrow(() -> new IllegalStateException("Biome registry is empty"));
        Codec<PalettedContainer<BlockState>> blockStatesCodec = PalettedContainer.codecRW(
                (IdMap) Block.BLOCK_STATE_REGISTRY,
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
        PalettedContainer<BlockState> states = snapshotPalette(section.getStates());
        PalettedContainer<Holder<Biome>> biomes = snapshotBiomes(section.getBiomes());
        return new LevelChunkSection(states, biomes);
    }

    private static <T> PalettedContainer<T> snapshotPalette(PalettedContainer<T> source) {
        PalettedContainer<T> staging = source.copy();
        if (staging == null || staging == source || staging.getClass() != PalettedContainer.class) {
            throw new IllegalStateException("Unable to stage palette " + source.getClass().getName());
        }
        return staging;
    }

    private static PalettedContainer<Holder<Biome>> snapshotBiomes(PalettedContainerRO<Holder<Biome>> source) {
        if (source instanceof PalettedContainer<Holder<Biome>> palette) return snapshotPalette(palette);
        PalettedContainer<Holder<Biome>> snapshot = source.recreate();
        if (snapshot == null) {
            return createStandaloneBiomeContainer(source);
        }
        if (snapshot.getClass() != PalettedContainer.class) {
            throw new IllegalStateException("Unable to recreate biome palette " + source.getClass().getName());
        }
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    snapshot.set(x, y, z, source.get(x, y, z));
                }
            }
        }
        return snapshot;
    }

    private static PalettedContainer<Holder<Biome>> createStandaloneBiomeContainer(
            PalettedContainerRO<Holder<Biome>> source
    ) {
        List<Holder<Biome>> values = new ArrayList<>();
        Map<Holder<Biome>, Integer> ids = new LinkedHashMap<>();
        Holder<Biome>[][][] grid = allocateBiomeGrid();
        Holder<Biome> first = null;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> biome = source.get(x, y, z);
                    grid[y][z][x] = biome;
                    if (biome != null) {
                        if (first == null) first = biome;
                        ids.computeIfAbsent(biome, key -> {
                            int id = values.size();
                            values.add(key);
                            return id;
                        });
                    }
                }
            }
        }
        if (first == null) {
            throw new IllegalStateException("Unable to snapshot biome palette with no biome values");
        }
        IdMap<Holder<Biome>> idMap = new SnapshotIdMap<>(values, ids);
        PalettedContainer<Holder<Biome>> snapshot = new PalettedContainer<>(
                idMap,
                first,
                PalettedContainer.Strategy.SECTION_BIOMES
        );
        if (values.size() > 1) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        Holder<Biome> biome = grid[y][z][x];
                        if (biome != null && biome != first) {
                            snapshot.set(x, y, z, biome);
                        }
                    }
                }
            }
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[][][] allocateBiomeGrid() {
        return new Holder[4][4][4];
    }

    public Codec<?> biomeContainerCodec() {
        return this.biomeContainerCodec;
    }

    public Codec<?> blockStatesContainerCodec() {
        return this.blockStatesContainerCodec;
    }

    private static final class SnapshotIdMap<T> implements IdMap<T> {
        private final List<T> values;
        private final Map<T, Integer> ids;

        private SnapshotIdMap(List<T> values, Map<T, Integer> ids) {
            this.values = values;
            this.ids = ids;
        }

        @Override
        public int getId(T value) {
            Integer id = ids.get(value);
            return id == null ? DEFAULT : id;
        }

        @Override
        public T byId(int id) {
            return id >= 0 && id < values.size() ? values.get(id) : null;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Iterator<T> iterator() {
            return values.iterator();
        }
    }
}
