package net.rasanovum.roxy.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "com.ethan.voxyworldgenv2.network.NetworkClientHandler", remap = false)
public final class RoxyVoxyWorldgenSectionMixin {
    @ModifyArg(
            method = "processLODData",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/ethan/voxyworldgenv2/integration/VoxyIntegration;rawIngest(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)V",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static LevelChunkSection roxy$recountDecodedSection(LevelChunkSection section) {
        section.recalcBlockCounts();
        return section;
    }
}
