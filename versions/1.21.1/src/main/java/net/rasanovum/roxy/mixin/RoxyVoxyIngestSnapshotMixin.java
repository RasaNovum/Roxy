package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rasanovum.roxy.compat.RoxyCopycatsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public final class RoxyVoxyIngestSnapshotMixin {
    private static final String INGEST_SECTION_CONSTRUCTOR =
            "Lme/cortex/voxy/common/world/service/VoxelIngestService$IngestSection;" +
                    "<init>(IIILme/cortex/voxy/common/world/WorldEngine;" +
                    "Lnet/minecraft/world/level/chunk/LevelChunkSection;" +
                    "Lnet/minecraft/world/level/chunk/DataLayer;" +
                    "Lnet/minecraft/world/level/chunk/DataLayer;)V";

    @WrapMethod(
            method = "enqueueIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)Z",
            remap = false
    )
    private boolean roxy$wrapChunkSnapshot(@Coerce Object worldEngine, LevelChunk chunk,
                                            Operation<Boolean> original) {
        RoxyCopycatsCompat.beginChunkIngest(chunk);
        try {
            return original.call(worldEngine, chunk);
        } finally {
            RoxyCopycatsCompat.endChunkIngest();
        }
    }

    @ModifyArg(
            method = "enqueueIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)Z",
            at = @At(value = "INVOKE", target = INGEST_SECTION_CONSTRUCTOR, remap = false),
            index = 4,
            require = 2,
            remap = false
    )
    private static LevelChunkSection roxy$snapshotChunkSection(LevelChunkSection section,
                                                                @Local(argsOnly = true) LevelChunk chunk,
                                                                @Local(index = 5) int sectionY) {
        return (LevelChunkSection) RoxyCopycatsCompat.snapshotSection(section, chunk, sectionY);
    }

    @ModifyArg(
            method = "rawIngest0(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
            at = @At(value = "INVOKE", target = INGEST_SECTION_CONSTRUCTOR, remap = false),
            index = 4,
            require = 1,
            remap = false
    )
    private static LevelChunkSection roxy$snapshotRawSection(LevelChunkSection section,
                                                              @Local(argsOnly = true, ordinal = 0) int sectionX,
                                                              @Local(argsOnly = true, ordinal = 1) int sectionY,
                                                              @Local(argsOnly = true, ordinal = 2) int sectionZ) {
        return (LevelChunkSection) RoxyCopycatsCompat.snapshotRawSection(section, sectionX, sectionY, sectionZ);
    }
}
