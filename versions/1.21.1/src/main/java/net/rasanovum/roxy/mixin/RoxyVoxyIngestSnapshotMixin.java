package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rasanovum.roxy.compat.RoxyCopycatsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public final class RoxyVoxyIngestSnapshotMixin {
    private static final String ENQUEUE_CHUNK =
            "enqueueIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)Z";
    private static final String SHOULD_INGEST_SECTION =
            "Lme/cortex/voxy/common/world/service/VoxelIngestService;" +
                    "shouldIngestSection(Lnet/minecraft/world/level/chunk/LevelChunkSection;III)Z";

    @WrapMethod(method = ENQUEUE_CHUNK, remap = false)
    private boolean roxy$wrapChunkSnapshot(@Coerce Object worldEngine, LevelChunk chunk,
                                            Operation<Boolean> original) {
        RoxyCopycatsCompat.beginChunkIngest(chunk);
        try {
            return original.call(worldEngine, chunk);
        } finally {
            RoxyCopycatsCompat.endChunkIngest();
        }
    }

    @WrapOperation(
            method = ENQUEUE_CHUNK,
            at = {
                    @At(value = "INVOKE", target = SHOULD_INGEST_SECTION, ordinal = 1, remap = false),
                    @At(value = "INVOKE", target = SHOULD_INGEST_SECTION, ordinal = 2, remap = false)
            },
            require = 2,
            allow = 2,
            remap = false
    )
    private static boolean roxy$snapshotChunkSection(LevelChunkSection section, int sectionX, int sectionY,
                                                     int sectionZ, Operation<Boolean> original,
                                                     @Local(argsOnly = true) LevelChunk chunk,
                                                     @Local(ordinal = 0) LocalRef<LevelChunkSection> queuedSection) {
        if (!original.call(section, sectionX, sectionY, sectionZ)) return false;
        LevelChunkSection snapshot = (LevelChunkSection) RoxyCopycatsCompat.snapshotSection(section, chunk, sectionY);
        if (snapshot == null) return false;
        queuedSection.set(snapshot);
        return true;
    }

    @WrapMethod(
            method = "rawIngest0(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
            require = 1,
            remap = false
    )
    private boolean roxy$enqueueRawSnapshot(@Coerce Object worldEngine, LevelChunkSection section,
                                           int sectionX, int sectionY, int sectionZ,
                                           DataLayer blockLight, DataLayer skyLight, Operation<Boolean> original) {
        LevelChunkSection snapshot = (LevelChunkSection) RoxyCopycatsCompat.snapshotRawSection(section, sectionX, sectionY, sectionZ);
        return snapshot != null && original.call(worldEngine, snapshot, sectionX, sectionY, sectionZ, blockLight, skyLight);
    }
}
